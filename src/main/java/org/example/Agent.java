package org.example;

import org.example.tools.CurrentTimeTool;
import org.example.tools.FinalAnswerTool;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

/**
 * Agent loop（对应方案 7 步）：
 * 轮次上限 → 尾注当前时间与任务进度、事实账本瘦身快照（以用户消息追加在对话末尾——快照每轮变化，插在头部会破坏
 * 前缀稳定、令整段历史缓存失效；放尾部后 system+历史逐轮只追加，配合 AnthropicClient 的 cache_control
 * 断点逐轮命中前缀缓存）→ LLM（人格 + 工具元信息 + 对话与思考，逐轮打印耗时与 token 统计）→
 * 无工具纯文本按状态分流：未规划可直接返回；任务完成后先过终答闸门；任务未完成则等待用户回复。
 * 调用 final_answer 时走终答闸门（确定性覆盖/冲突检查 + 关键依赖检查 + LLM 质量校验）。
 * 工具阶段：普通工具顺序执行，同批 delegate_agent 并发执行。
 * 轮末再按 token 阈值压缩上下文；终答被拒的当轮不压缩，避免刚回传的缺陷清单被摘要稀释。
 */
public class Agent {
    public static final int ROOT_MAX_ROUNDS = 90;
    public static final int DEFAULT_SUB_AGENT_ROUNDS = 60;
    /** 上下文压缩阈值（上次响应 inputTokens 超过即压缩）：config.yaml 的 llm.context-token-threshold，
     * 缺省 838,861 = 1M 窗口 × 80%——接近饱和前主动压缩，为当前轮输入与输出预留空间。 */
    private final int contextTokenThreshold;
    /** 对话稿中每条工具结果正文的最大保留长度（足够保住数值与链接，又不让稿子膨胀）。 */
    static final int TRANSCRIPT_TOOL_BODY_LIMIT = 1_500;
    /** 网关内容审核（Content Exists Risk）连续触发的最大恢复次数，超过则抛错退出——防账本本身带敏感内容时死循环；成功调用后计数清零。 */
    static final int MAX_CONTENT_RISK_RECOVERIES = 3;

    /** 最终校验器提示词：只有五类 BLOCKER 能打回；补充性意见归 SUGGESTION，不阻塞。 */
    private static final String VERIFY_PROMPT = """
            你是答案质量校验器，按「够用即可」原则裁决：只有以下五类 BLOCKER 才能判不通过——
            1. 事实错误：草稿的数据/结论与「事实账本」矛盾，或断言了账本与校验依据都不支撑的确定性事实；
            2. 账本矛盾/遗漏：草稿声称「未找到/未检索到 X」而账本中 X 为 found/proxy；账本 found 的关键事实被草稿写错或遗漏；
            3. 硬约束违反：「校验依据」中可判定的硬约束未满足；多版本任务缺少任务/技能定义的版本分离指标、
               指标无产物/账本证据、或未达到任务契约设定的分离要求；
               用户明示的执行方式、研究对象范围或交付形态被混合、缩小或替代；
            4. 关键缺口未声明：述求要求的核心内容缺失且草稿未如实说明原因；账本 status=not_found 的条目草稿未如实交代；
            5. 技能/关键依赖违反：未满足已加载技能的可判定输出骨架或终检要求；critical 且非 found 的依赖被用作主方案唯一支撑，
               而没有采用保守主方案并写明升级条件。
            裁决规则：
            - 交付形态合法：最终交付物为磁盘文件（如 Markdown 报告）时，「文件路径+摘要+关键结论」是合法答案形态；
              「答案里没列来源/没贴完整内容/没给样例」不构成缺陷——只要文件内容已按校验依据与账本覆盖即可。
            - 补充性/展示性意见（可以更详细、可加 Plan B、可补来源清单、措辞可优化、可再交叉验证等）一律不阻塞，最多写入 SUGGESTION。
            - 没有 BLOCKER 就判 PASS，不追求完美、不主动加码要求。
            输出格式（严格遵守）：
            第一行只写 PASS 或 FAIL；FAIL 时另起一行逐条列出 BLOCKER（每行一条：缺陷+依据+可执行的修复动作）；
            若有非阻塞建议，最后以「SUGGESTION:」起一段列出（无则省略该段）。
            """;

    private final Config.Data cfg;
    private final String agentId;
    private final boolean rootAgent;
    private final int maxRounds;
    private final String systemPrompt;
    private final LlmClient llm;
    private final LlmClient quietLlm; // 无工具、流式但静默（不打增量）：最终校验等嵌套调用用
    private final ToolRegistry registry;
    private final TaskStore tasks;
    private final FactsStore facts;
    private final SearchLog searchLog;
    private final SkillState skillState;
    private final SubAgentStore subAgentReports;
    private final RunArchive archive;
    /** 终答最近一次被拒的缺陷清单：非空时随上下文压缩重建以最高优先级注入（防重建后靠猜补缺陷）。 */
    private String pendingFinalDefects;
    /** 当前运行的对话状态引用：仅用于磁盘归档，不参与模型上下文。 */
    private RunState activeState;
    /** 运行中的用户回复通道：任务未完成时模型向用户提问，从这里读回答。 */
    private final Scanner console;

    public Agent() {
        this(Config.load(), "root", true, ROOT_MAX_ROUNDS);
    }

    private Agent(Config.Data cfg, String agentId, boolean rootAgent, int maxRounds) {
        // 基本身份与模型客户端：root/child 只影响人格、工具白名单与交互通道，不改变研究纪律。
        this.cfg = cfg;
        this.agentId = agentId;
        this.rootAgent = rootAgent;
        this.maxRounds = maxRounds;
        this.systemPrompt = rootAgent
                ? SystemPrompt.withSkills()
                : SystemPrompt.subAgentWithSkills();
        this.llm = LlmClient.create(cfg.llm());
        // 静默客户端改为流式：非流式下 300s 请求超时覆盖整包生成（含 thinking 长思考），
        // 校验大终稿（20KB+ 全量账本）时曾 4 次全量重试超时打崩任务；流式（ofLines）超时只计到
        // 响应头，思考再长也不撞上限。silent=true 保持安静不打增量；thinking 档位与主客户端一致。
        Config.Llm quietCfg = new Config.Llm(cfg.llm().provider(), cfg.llm().baseUrl(),
                cfg.llm().model(), cfg.llm().apiKey(), cfg.llm().maxTokens(), cfg.llm().temperature(),
                cfg.llm().topP(), true, cfg.llm().thinking(), cfg.llm().contextTokenThreshold());
        this.quietLlm = LlmClient.create(quietCfg, true);
        this.contextTokenThreshold = cfg.llm().contextTokenThreshold();

        // 外部状态是“LLM 看不到的账本”：每轮由 Agent 重新注入快照，避免依赖对话记忆。
        this.tasks = new TaskStore();
        this.facts = new FactsStore(agentId);
        this.searchLog = new SearchLog(agentId);
        this.skillState = new SkillState(); // 会话级生效技能：load_skill 写入，规划注入与压缩重建读取
        this.subAgentReports = new SubAgentStore();
        this.archive = rootAgent
                ? RunArchive.createRoot(cfg.storage(), agentId)
                : RunArchive.forWorkspace(Config.rootDir(cfg.storage()), agentId);

        // 工具按父/子白名单注册：父可 delegate_agent，子不可递归委派。
        this.registry = new ToolRegistry(cfg, tasks, facts, skillState, searchLog, archive,
                subAgentReports, rootAgent ? ToolRegistry.ROOT_TOOL_NAMES : ToolRegistry.SUB_AGENT_TOOL_NAMES);
        this.console = rootAgent ? new Scanner(System.in, StandardCharsets.UTF_8) : null;
    }

    /**
     * 创建子 Agent：复用模型与工具配置，但拥有独立状态与文件目录；
     * 继承父检索历史与当前技能，接收父任务指定的精确覆盖目标。
     */
    public static Agent createSubAgent(Config.Data parentCfg, String agentId, Path workspace,
                                       List<SearchLog.Entry> seedSearches, List<FactsStore.Fact> parentFacts,
                                       Skill inheritedSkill,
                                       Map<String, List<String>> requiredFacts, int maxRounds) {
        // 子 Agent 的 storage root 指向独立目录，fetch/read/report 都落在自己的 workspace。
        Config.Data childCfg = new Config.Data(
                parentCfg.llm(), parentCfg.webSearch(), parentCfg.lbs(),
                new Config.Storage(workspace.toAbsolutePath().normalize().toString(),
                        parentCfg.storage().auditDir()),
                parentCfg.readFile(), parentCfg.renderCard());

        Agent child = new Agent(childCfg, agentId, false, maxRounds);

        // 只继承“只读上下文”：检索历史、父事实与技能；任务状态仍由子 Agent 自己规划。
        child.searchLog.merge(seedSearches);
        child.facts.inherit(parentFacts);
        if (inheritedSkill != null) {
            child.skillState.set(inheritedSkill); // 只初始化子 Agent 自己的 SkillState，不反向修改父 Agent
        }
        requiredFacts.forEach(child.facts::require);
        return child;
    }

    /**
     * Agent 主循环（整体流程对应方案 7 步）：每轮轮内四阶段——
     * ① 尾注快照 + LLM 调用 → ② 纯文本/工具分流 → ③ 工具执行（含终答闸门）→ ④ 超阈值压缩。
     * 三条退出路径：任务全完成的纯文本直接作为结论返回（不触发校验）、final_answer 过终答闸门返回、
     * 超轮次上限抛错。各阶段的设计取舍与细节见对应私有方法的注释。
     */
    public String run(String request) {
        archive.write("request.md", "# 原始述求\n\n" + request + '\n');
        archive.write("system-prompt.md", "# 系统提示词\n\n" + systemPrompt + '\n');
        writeRunSummary("running", null, null);

        // 父 Agent 同时写控制台与归档日志；子 Agent 只写自身 Markdown 日志。
        Path logFile = archive.file("agent.log.md");
        // append 而不是 overwrite：同一个子 Agent 目录中多次运行可保留历史轨迹。
        try (PrintStream fileOut = new PrintStream(Files.newOutputStream(logFile,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND), true, StandardCharsets.UTF_8)) {
            PrintStream routed = rootAgent ? new TeePrintStream(System.out, fileOut) : fileOut;
            AgentOutput.bind(routed);
            Console.plain(!rootAgent);
            routed.println("\n\n# " + (rootAgent ? "父Agent日志 " : "子Agent日志 ")
                    + agentId + " · 归档 " + archive.dir() + " · " + LocalDateTime.now() + "\n");
            try {
                String result = runLoop(request);
                archive.write("final-result.md", "# 最终结果\n\n" + result + '\n');
                writeCoreState(activeState, "complete", null);
                writeRunSummary("complete", result, null);
                routed.println("\n" + Console.header("[归档完成] " + archive.dir()));
                return result;
            } catch (Exception e) {
                routed.println(Console.error("[" + (rootAgent ? "父Agent异常" : "子Agent异常") + "] " + e.getMessage()));
                writeCoreState(activeState, "failed", e.getMessage());
                writeRunSummary("failed", null, e.getMessage());
                throw e;
            }
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime : new RuntimeException(e);
        } finally {
            Console.plain(false);
            AgentOutput.unbind();
        }
    }

    /** 写入核心状态账本；running 阶段写轻量状态，complete/failed 再写完整对话。 */
    private void writeCoreState(RunState state, String status, String error) {
        archive.writeIfPresent("task-ledger.md", tasks.ledger());
        archive.writeIfPresent("fact-ledger.md", facts.ledger());
        archive.writeIfPresent("search-ledger.md", searchLog.ledger());
        if (state != null) {
            archive.write("transcript.md", "# 对话稿（不含工具结果正文）\n\n"
                    + state.transcript + '\n');
            if (state.bestAnswer != null && !state.bestAnswer.isBlank()) {
                archive.write("best-answer-draft.md", "# 最完整答案草稿\n\n" + state.bestAnswer + '\n');
            }
            if ("complete".equals(status) || "failed".equals(status)) {
                archive.write("conversation.md", renderConversation(state.messages));
            }
        }
        Skill skill = skillState.get();
        if (skill != null) {
            archive.write("skill.md", "# 已加载技能: " + skill.name() + "\n\n"
                    + "- 描述: " + skill.description() + "\n"
                    + "- 目录: " + skill.dir() + "\n\n" + skill.instructions() + '\n');
        }
        if (rootAgent) {
            archive.writeIfPresent("subagents.md", subAgentReports.snapshot());
        }
        if (error != null && !error.isBlank()) {
            archive.write("last-error.md", "# 最近错误\n\n" + error + '\n');
        }
        archive.write("archive-index.md", archive.markdownIndex());
    }

    /** 运行摘要：路径、模型、进度、状态与错误；不写 API key。 */
    private void writeRunSummary(String status, String result, String error) {
        StringBuilder sb = new StringBuilder("# Run Summary\n\n")
                .append("- Agent ID: ").append(agentId).append('\n')
                .append("- Role: ").append(rootAgent ? "parent" : "child").append('\n')
                .append("- Archive: ").append(archive.dir()).append('\n')
                .append("- StartedAt: ").append(archive.startedAt()).append('\n')
                .append("- UpdatedAt: ").append(LocalDateTime.now()).append('\n')
                .append("- Status: ").append(status).append('\n')
                .append("- ModelProvider: ").append(cfg.llm().provider()).append('\n')
                .append("- Model: ").append(cfg.llm().model()).append('\n')
                .append("- MaxRounds: ").append(maxRounds).append('\n')
                .append("- TaskProgress: ").append(tasks.progress()).append('\n')
                .append("- FactLedger: ").append(facts.coverageLine()).append('\n')
                .append("- SearchEntries: ").append(searchLog.entries().size()).append('\n');
        if (rootAgent) {
            sb.append("- SubAgentArchiveRoot: ").append(archive.dir().resolve("subagents")).append('\n');
        }
        if (error != null && !error.isBlank()) {
            sb.append("\n## Error\n\n").append(error).append('\n');
        }
        if (result != null && !result.isBlank()) {
            sb.append("\n## ResultPreview\n\n").append(excerpt(result, 1600)).append('\n');
        }
        archive.write("run-summary.md", sb.toString());
    }

    /** 完整对话归档：含 assistant thinking、tool_use 参数与 tool_result 正文。 */
    private static String renderConversation(List<Msg> messages) {
        StringBuilder sb = new StringBuilder("# 完整对话记录\n");
        for (Msg msg : messages) {
            sb.append("\n## ").append(msg.role()).append('\n');
            for (Block block : msg.blocks()) {
                if (block instanceof Block.Thinking t) {
                    sb.append("\n### Thinking\n\n").append(t.thinking()).append('\n');
                } else if (block instanceof Block.Text t) {
                    sb.append("\n### Text\n\n").append(t.text()).append('\n');
                } else if (block instanceof Block.ToolUse u) {
                    sb.append("\n### ToolUse ").append(u.name()).append(" / ").append(u.id()).append('\n')
                            .append(u.input()).append('\n');
                } else if (block instanceof Block.ToolResult r) {
                    sb.append("\n### ToolResult ").append(r.toolUseId())
                            .append(r.isError() ? "（ERROR）" : "").append('\n')
                            .append(r.content()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String excerpt(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n…（已截断，全文见 final-result.md）";
    }

    private String runLoop(String request) {
        // 跨轮可变状态：对话消息、纯文本对话稿（供压缩摘要）、最完整草稿、内容审核恢复计数；
        // 内容审核恢复与压缩重建时整体 reset 重开对话，而非在旧历史上追加
        RunState state = RunState.initial(request, systemPrompt);
        this.activeState = state;
        for (int round = 1; round <= maxRounds; round++) {
            // 每轮都显式打印边界，日志中能清楚区分父/子和轮次。
            AgentOutput.println("\n" + Console.header("======== " + (rootAgent ? "" : "[子Agent " + agentId + "] ")
                    + "第 " + round + "/" + maxRounds + " 轮 ========"));

            // ① LLM 调用（带尾注快照）：任务进度与事实账本以用户消息追加在本次调用副本的末尾——
            //    快照每轮变化，放头部会破坏前缀缓存，放尾部则 system+历史逐轮只追加（详见 withTailSnapshots）。
            //    返回 null = 输入触发网关内容审核：历史已丢弃并按账本重建，本轮到此为止
            LlmResponse resp = callLlm(state, request, withTailSnapshots(state.messages));
            if (resp == null) {
                continue; // 网关内容审核已恢复：历史已丢弃并按账本重建，进入下一轮
            }

            // ② 分流：响应里没有 tool_use 就是纯文本轮——空响应催促重试 / 即最终结论 / 等待用户 stdin 回复，
            //    三种去向见 handlePlainText
            List<Block.ToolUse> toolCalls = toolUses(resp);
            if (toolCalls.isEmpty()) {
                String conclusion = handlePlainText(state, resp);
                if (conclusion != null) {
                    return conclusion; // 只有“无任何校验依据”或“纯文本已过闸门”才会到这里
                }
                continue; // 空响应催促已注入或用户回复已入对话，进入下一轮
            }

            // ③ 工具轮：普通工具顺序执行，同批 delegate_agent 并发执行；
            //    结果逐条回传；final_answer 先过终答闸门（BLOCKER 校验 + 账本核对 + 覆盖度），通过即任务完成
            ToolOutcome outcome = executeToolCalls(state, resp, toolCalls);
            if (outcome.answer() != null) {
                return outcome.answer(); // final_answer 终答闸门通过
            }
            // ④ 超阈值压缩：用上轮响应的 inputTokens 判断（零额外调用）；终答被拒的当轮不压缩——
            //    缺陷清单刚以 tool 结果进入对话，立即压缩会把它降级进摘要、模型只能靠猜补缺陷
            compressIfNeeded(state, request, resp, outcome.finalRejected());
            writeCoreState(state, "running", null);
        }
        throw new IllegalStateException((rootAgent ? "Agent" : "子Agent " + agentId)
                + " 超过最大轮次 " + maxRounds + "，任务未完成");
    }

    /** 子 Agent 运行结果：最终报告与待合并的外部状态快照。 */
    public record SubAgentResult(String answer, List<FactsStore.Fact> facts,
                                 List<SearchLog.Entry> searches) {}

    /** 以子 Agent 身份运行；纯文本不会结束，必须通过 final_answer 闸门。 */
    public SubAgentResult runAsSubAgent(String request) {
        if (rootAgent) {
            throw new IllegalStateException("runAsSubAgent 只允许子 Agent 调用");
        }
        String answer = run(request);
        return new SubAgentResult(answer, facts.facts(), searchLog.entries());
    }

    /**
     * LLM 看不到 TaskStore / FactsStore 外部状态 → 每轮重算任务进度与事实账本快照，
     * 以用户消息追加在对话末尾（只放进本次调用的副本，messages 不留旧快照，天然无陈旧堆积、
     * 压缩重建也无需处理）。当前时间同样每轮尾注注入：时效判断（今天/本周/营业中/节假日）从
     * 第一轮起就有依据，无需模型自发调用 current_time——无祈使句槽位的工具在 temperature=0 下
     * 不会被主动调用，且每次调用耗一整个轮次；时间消息放在尾注最前，倒数第二条仍是任务快照，
     * cache_control 断点目标不变。位置决定缓存命运：快照每轮变化，此前插在历史头部会把其后全部
     * 内容变成缓存 miss；放尾部后 system+历史是逐轮只追加的稳定前缀，断点逐轮增量命中。
     * 账本快照带覆盖缺口，逼模型补齐而非提前收工。
     */
    private List<Msg> withTailSnapshots(List<Msg> messages) {
        // 先重算外部状态，再复制 messages：本方法只影响本次 LLM 调用，不污染 state.messages。
        String snapshot = tasks.snapshot();
        String factsSnapshot = facts.snapshot();
        List<Msg> callMessages = new ArrayList<>(messages);

        // 注入顺序：时间 → 任务 → 账本 → 子报告；都是 user 尾注，模型无需主动调用状态工具。
        callMessages.add(Msg.user("[当前时间·每轮自动追加的状态块，非用户消息，不要回复它] "
                + CurrentTimeTool.nowText()));
        if (snapshot != null) {
            AgentOutput.println(Console.header("[任务快照已注入·尾注] ") + tasks.progress());
            callMessages.add(Msg.user(snapshot));
        }
        if (factsSnapshot != null) {
            AgentOutput.println(Console.header("[事实账本已注入·尾注] ") + facts.coverageLine());
            callMessages.add(Msg.user(factsSnapshot));
        }
        if (rootAgent) {
            // 子报告只放路径与核对提示；需要正文时再 read_file，避免重建上下文过大。
            String reports = subAgentReports.snapshot();
            if (reports != null) {
                callMessages.add(Msg.user(reports));
            }
        }
        return callMessages;
    }

    /**
     * LLM 调用（人格 + 工具元信息 + 对话与思考），打印本轮耗时与 token 统计。
     * 触发网关内容审核且未超恢复上限时丢弃历史重建上下文，返回 null 表示调用方应直接进入下一轮。
     */
    private LlmResponse callLlm(RunState state, String request, List<Msg> callMessages) {
        long llmStart = System.nanoTime();
        LlmResponse resp;
        try {
            resp = llm.call(registry.definitions(), callMessages);
            state.contentRiskRecoveries = 0; // 调用成功即清零：只有连续触发才受上限约束
        } catch (RuntimeException e) {
            // DeepSeek 系网关输入审核：请求体（累积历史）里某段被判「Content Exists Risk」整次 400。
            // 重试无意义（内容仍在请求体里），改为丢弃历史、以事实账本重建上下文后进入下一轮。
            if (state.contentRiskRecoveries < MAX_CONTENT_RISK_RECOVERIES && isContentRisk(e)) {
                recoverFromContentRisk(state, request);
                return null;
            }
            throw e;
        }
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000;
        AgentOutput.println(Console.stat(String.format(
                "[本轮统计] LLM %.1fs · 输入 %,d tok（另缓存命中 %,d） · 输出 %,d tok",
                llmMs / 1000.0, resp.inputTokens(), resp.cacheReadTokens(), resp.outputTokens())));
        if (cfg.llm().streaming()) {
            AgentOutput.println(); // 结束流式文本行
        } else {
            printBlocks(resp); // 非流式时统一补打（流式已在接收中实时输出）
        }
        return resp;
    }

    /** 网关内容审核恢复：丢弃触发审核的累积历史，以任务原始述求与事实账本重建上下文（不复述被标记的原文）。 */
    private void recoverFromContentRisk(RunState state, String request) {
        state.contentRiskRecoveries++;
        AgentOutput.println(Console.error("\n[内容风险] 输入触发网关内容审核（Content Exists Risk），"
                + "第 " + state.contentRiskRecoveries + "/" + MAX_CONTENT_RISK_RECOVERIES
                + " 次丢弃历史、以账本重建后继续…"));
        String rebuilt = "此前累积的对话历史触发网关内容审核被拦截，已全部丢弃；"
                + "以下依据任务原始述求与事实账本继续执行。"
                + "\n\n" + rebuild(request, state.bestAnswer, null);
        state.reset(rebuilt, systemPrompt);
    }

    /**
     * 无工具纯文本轮的分流规则：
     * 1. 空响应：催促重试；
     * 2. 子 Agent：不能纯文本结束，要求 final_answer；
     * 3. 根 Agent 无校验依据：直接返回；
     * 4. 根 Agent 任务完成：先过终答闸门；
     * 5. 根 Agent 任务未完成：作为中间陈述并等待用户。
     * 返回非 null 表示最终结论；null 表示本轮已处理完，进入下一轮。
     */
    private String handlePlainText(RunState state, LlmResponse resp) {
        String candidate = resp.text();
        // 情况 1：空响应不能当结论，也不能当提问，只注入催促后重试。
        if (candidate.isBlank()) {
            nudgeAfterEmptyResponse(state, resp);
            return null;
        }
        // 情况 2：子 Agent 的合法出口只有 final_answer；纯文本一律回催指令。
        if (!rootAgent) {
            state.messages.add(Msg.assistant(resp.blocks()));
            state.transcript.append("助手: ").append(candidate).append('\n');
            String instruction = """
                    子 Agent 不能以纯文本结束任务，也不能向用户提问。
                    请基于事实账本继续执行；需要假设时在最终答案中明确说明。
                    任务完成后必须调用 final_answer 提交完整子任务报告。
                    """;
            state.messages.add(Msg.user(instruction));
            state.transcript.append("用户: ").append(instruction).append('\n');
            return null;
        }
        // 情况 3：根 Agent 尚无任务/事实/子报告，说明没有可校验依据，纯文本可直接结束。
        if (tasks.isEmpty() && facts.isEmpty() && subAgentReports.snapshot() == null) {
            state.messages.add(Msg.assistant(resp.blocks()));
            state.transcript.append("助手: ").append(candidate).append('\n');
            return candidate;
        }
        // 情况 4：任务已结束或无需任务清单但有校验依据，纯文本也必须过终答闸门。
        if (tasks.isEmpty() || tasks.allDone()) {
            state.messages.add(Msg.assistant(resp.blocks()));
            state.bestAnswer = candidate;
            String defects = finalGate(candidate);
            if (defects == null) {
                return candidate;
            }
            pendingFinalDefects = defects;
            state.messages.add(Msg.user(defects));
            state.transcript.append("助手: ").append(candidate).append('\n')
                    .append("用户: ").append(defects).append('\n');
            return null;
        }
        // 情况 5：任务未完成时视为中间陈述/提问，等待用户补充后继续。
        awaitUserReply(state, resp, candidate);
        return null;
    }

    /** 空响应兜底（网关偶发 200 空流：无文本、无工具、无用量）：既不当作结论也不当作提问，
     * 注入催促消息进入下一轮——否则任务全完成时空串会被当「最终结论」静默结束。 */
    private void nudgeAfterEmptyResponse(RunState state, LlmResponse resp) {
        if (!resp.blocks().isEmpty()) {
            state.messages.add(Msg.assistant(resp.blocks())); // 保住思考块原样回传
        }
        String nudge = "（上一轮没有文本输出也没有工具调用——请继续执行任务清单："
                + "任务已全部完成时立即输出完整最终答案或调用 final_answer 提交，不要停）";
        state.messages.add(Msg.user(nudge));
        state.transcript.append("助手: （空响应）\n用户: ").append(nudge).append('\n');
    }

    /** 任务未完成 → 纯文本视为面向用户的中间陈述：打印并等待用户 stdin 回复（直接回车=按已入账假设继续），
     * 回复进入对话后继续执行，不结束运行——「提问」与「终答」不再共用同一条退出路径。 */
    private void awaitUserReply(RunState state, LlmResponse resp, String statement) {
        state.messages.add(Msg.assistant(resp.blocks()));
        state.transcript.append("助手: ").append(statement).append('\n');
        AgentOutput.println(Console.header("\n[等待用户回复]") + "（直接回车 = 按默认假设继续执行）");
        AgentOutput.print("> ");
        System.out.flush();
        String line = console.nextLine();
        String reply = line.isBlank()
                ? "（用户未回复。不要再询问，基于事实账本中已入账的假设按默认方案继续执行任务清单。）"
                : line;
        state.messages.add(Msg.user(reply));
        state.transcript.append("用户: ").append(reply).append('\n');
    }

    /** 从响应内容块中收集全部 tool_use 调用。 */
    private static List<Block.ToolUse> toolUses(LlmResponse resp) {
        List<Block.ToolUse> toolCalls = new ArrayList<>();
        for (Block b : resp.blocks()) {
            if (b instanceof Block.ToolUse u) {
                toolCalls.add(u);
            }
        }
        return toolCalls;
    }

    /**
     * 工具轮主体，分六步：
     * 1. 记录 assistant tool_use；2. 普通工具顺序执行；
     * 3. 普通工具顺序执行；4. 同批 delegate_agent 并发执行；
     * 5. 有效 final_answer 最后过闸门并按原顺序回传结果；6. 输出耗时统计。
     * 并发只改变执行等待关系，不改变 LLM 看到的结果顺序。
     */
    private ToolOutcome executeToolCalls(RunState state, LlmResponse resp, List<Block.ToolUse> toolCalls) {
        // 第 1 步：先把 assistant 的完整响应块入对话稿；后续 tool_result 必须能对应这些 tool_use id。
        for (Block.ToolUse u : toolCalls) {
            AgentOutput.println(Console.tool("[调用工具] " + u.name() + " " + u.input()));
        }
        state.messages.add(Msg.assistant(resp.blocks()));
        appendToolTurn(state.transcript, resp, toolCalls);

        // 第 2 步：准备执行统计和结果表。outputs 按 tool_use id 暂存，最后再按原顺序回传。
        boolean finalRejectedThisRound = false;
        long toolsMs = 0;
        long gateMs = 0;
        int ran = 0;
        Map<String, ToolRegistry.ToolOutput> outputs = new LinkedHashMap<>();
        List<Block.ToolUse> delegates = toolCalls.stream()
                .filter(c -> "delegate_agent".equals(c.name()))
                .toList();

        // 第 3 步：普通工具按模型给定顺序执行，避免共享 TaskStore/FactsStore 产生并发写。
        for (Block.ToolUse call : toolCalls) {
            // final_answer 不在这里执行；空参数例外，交给 FinalAnswerTool 返回缺参错误。
            if (FinalAnswerTool.NAME.equals(call.name())) {
                String submitted = ToolRegistry.optStr(call.input(), "answer");
                if (submitted == null || submitted.isBlank()) {
                    long toolStart = System.nanoTime();
                    ToolRegistry.ToolOutput out = registry.run(call);
                    toolsMs += (System.nanoTime() - toolStart) / 1_000_000;
                    ran++;
                    outputs.put(call.id(), out);
                }
            }
            if (FinalAnswerTool.NAME.equals(call.name()) || delegates.contains(call)) {
                continue;
            }
            long toolStart = System.nanoTime();
            ToolRegistry.ToolOutput out = registry.run(call);
            toolsMs += (System.nanoTime() - toolStart) / 1_000_000;
            ran++;
            outputs.put(call.id(), out);
        }

        // 第 4 步：同一批 delegate_agent 并发执行，最多 4 个；父状态由工具内部加锁合并。
        if (!delegates.isEmpty()) {
            long delegatesStart = System.nanoTime();
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, delegates.size()));
            try {
                List<Future<ToolRegistry.ToolOutput>> futures = delegates.stream()
                        .map(call -> executor.submit(() -> registry.run(call)))
                        .toList();
                for (int i = 0; i < delegates.size(); i++) {
                    outputs.put(delegates.get(i).id(), futures.get(i).get());
                    ran++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并发子Agent执行被中断", e);
            } catch (Exception e) {
                throw new IllegalStateException("并发子Agent执行失败", e);
            } finally {
                executor.shutdownNow();
            }
            toolsMs += (System.nanoTime() - delegatesStart) / 1_000_000;
        }

        // 第 5 步：按原始 tool_use 顺序写回结果，避免并发完成顺序影响模型理解。
        for (Block.ToolUse call : toolCalls) {
            // 有效 final_answer 最后处理，确保同轮子 Agent 结果都已进入 state.messages。
            if (FinalAnswerTool.NAME.equals(call.name())) {
                String answer = ToolRegistry.optStr(call.input(), "answer");
                if (answer != null && !answer.isBlank()) {
                    state.bestAnswer = answer;
                    long gateStart = System.nanoTime();
                    String defects = finalGate(answer);
                    gateMs += (System.nanoTime() - gateStart) / 1_000_000;
                    pendingFinalDefects = defects;
                    if (defects == null) {
                        AgentOutput.println("[final_answer] 校验通过，任务完成");
                        state.messages.add(Msg.tool(new Block.ToolResult(call.id(),
                                "最终校验通过，答案已采纳，任务完成。", false)));
                        return new ToolOutcome(answer, false);
                    }
                    finalRejectedThisRound = true;
                    state.messages.add(Msg.tool(new Block.ToolResult(call.id(), defects, true)));
                    state.transcript.append("  [final_answer 未通过最终校验，缺陷清单已回传]\n");
                    continue;
                }
            }

            ToolRegistry.ToolOutput out = outputs.get(call.id());
            if (out == null) {
                continue;
            }
            boolean full = "analyze_query".equals(call.name()) || "record_facts".equals(call.name())
                    || "load_skill".equals(call.name()) || "delegate_agent".equals(call.name());
            AgentOutput.println("[工具结果] " + (full ? out.content() : preview(out.content())));

            // LLM message 保留完整正文；transcript 只留截断稿，用于后续摘要并控制体积。
            state.messages.add(Msg.tool(new Block.ToolResult(call.id(), out.content(), out.isError())));
            state.transcript.append("  [").append(call.name()).append(" 结果] ")
                    .append(body(out.content())).append('\n');
        }

        // 第 6 步：输出工具/闸门耗时；gateMs 单列，避免把终答 LLM 校验误算成工具执行。
        StringBuilder toolStat = new StringBuilder(String.format(
                "[本轮统计] 工具 %.1fs（%d 次调用）", toolsMs / 1000.0, ran));
        if (gateMs > 0) {
            toolStat.append(String.format(" · 终答校验 %.1fs", gateMs / 1000.0));
        }
        AgentOutput.println(Console.stat(toolStat.toString()));
        return new ToolOutcome(null, finalRejectedThisRound);
    }

    /** 对话稿记录本轮助手文本与工具调用参数（无文本时记「（调用工具）」占位）。 */
    private static void appendToolTurn(StringBuilder transcript, LlmResponse resp,
            List<Block.ToolUse> toolCalls) {
        String text = resp.text();
        transcript.append("助手: ").append(text.isEmpty() ? "（调用工具）" : text).append('\n');
        for (Block.ToolUse call : toolCalls) {
            transcript.append("  [调用工具 ").append(call.name())
                    .append(" 参数 ").append(call.input()).append("]\n");
        }
    }

    /**
     * 步骤 6：以上次响应输入 token 判断是否压缩（零额外调用）。
     * 终答被拒的当轮不压缩：缺陷清单刚以 tool 结果进入对话，立即压缩会把它降级进摘要、
     * 重建后模型看不到原始清单只能靠猜（后续轮次再压缩时 rebuild 会带上缺陷清单兜底）。
     */
    private void compressIfNeeded(RunState state, String request, LlmResponse resp,
            boolean finalRejectedThisRound) {
        // 两个不压缩条件：未超阈值，或终答刚被拒（缺陷清单必须先原样留在对话里）。
        if (resp.inputTokens() <= contextTokenThreshold || finalRejectedThisRound) {
            return;
        }
        AgentOutput.println("\n[上下文压缩] inputTokens=" + resp.inputTokens()
                + " 超过阈值 " + contextTokenThreshold + "，开始压缩…");
        // 6.2 无工具单次调用总结（直接发原 messages 会因 tool_use 缺 tool_result 报错）
        String summary = llm.summarize(state.transcript.toString());
        AgentOutput.println("[上下文压缩] 摘要:\n" + summary);
        // 6.3 重建：旧对话与思考全部清除，保留原始述求 + 摘要 + 事实账本 + 旧草稿 + 继续指令（拼装逻辑见 rebuild）。
        state.reset(rebuild(request, state.bestAnswer, summary), systemPrompt);
    }

    /** 非流式模式下的统一打印：思考与结论文本。 */
    private static void printBlocks(LlmResponse resp) {
        for (Block b : resp.blocks()) {
            if (b instanceof Block.Thinking t) {
                AgentOutput.println(Console.thinking("[思考] " + t.thinking()));
            } else if (b instanceof Block.Text t) {
                AgentOutput.println("[输出] " + t.text());
            }
        }
    }

    /** 最终校验：quiet 客户端判断草稿是否满足约束与质量，返回 PASS 或缺陷清单。 */
    private LlmResponse verify(String draft, String criteria) {
        return quietLlm.call(List.of(),
                List.of(Msg.system(VERIFY_PROMPT),
                        Msg.user("校验依据：\n" + criteria + "\n\n草稿回答：\n" + draft)));
    }

    /** 生成校验器基础设施异常回执：明确原因与调整方向，避免主模型把空 verdict 误当内容 BLOCKER。 */
    private static String verifierFailureReceipt(LlmResponse verification, String verdict) {
        String stop = verification.stopReason();
        String cause = verification.maxTokensStopped()
                ? "校验器在输出 PASS/FAIL 前因 stop_reason=" + stop + " 截断"
                : "校验器返回空 verdict" + (stop.isBlank() ? "" : "（stop_reason=" + stop + "）");

        return """
                最终校验器未产出内容裁决：%s。%s

                这不是答案内容 FAIL，也没有任何内容 BLOCKER；不要据此修改事实、删除关键结论或重新检索。
                请针对“校验输入/终稿体积”进行调整：保留技能要求的全部章节、事实账本数据、关键计算过程、保守主方案、升级条件、待核实项和来源完整性，压缩重复叙述、合并同类说明、精简表格备注与冗余转写，降低一次性终校验负担，然后重新提交完整的 final_answer。
                校验器 thinking=max 保持不变；本回执只要求你压缩交付文本体积，不要求降低校验严谨性。
                """.formatted(cause, verdict.isBlank() ? "" : "已收到截断片段，但不能作为完整裁决采用。");
    }

    /**
     * 终答硬闸门（纯文本与 final_answer 共用），执行顺序：
     * 1. 覆盖度 / 事实冲突；
     * 2. critical 依赖是否已降级；
     * 3. quiet LLM 做事实、约束、技能格式复核。
     * 无任何校验依据时直接放行；返回 null 表示通过，非 null 是要回传模型的缺陷清单。
     * 校验器空 verdict / max_tokens 属于基础设施异常，返回明确回执而不包装成内容 BLOCKER。
     * 任务依据使用首次规划的固定基线，事实依据使用全量账本，避免 live 重规划放宽原始要求。
     */
    private String finalGate(String draft) {
        // 收集四类校验依据：固定任务基线、事实账本、技能终检、子 Agent 报告核对信息。
        String criteria = tasks.baseline();
        String ledger = facts.ledger();
        Skill skill = skillState.get();
        String reports = subAgentReports.verificationSection();
        String multiVersion = subAgentReports.multiVersionVerificationSection();
        if (criteria == null && ledger == null && skill == null && reports == null) {
            return null;
        }

        // 闸门 1：确定性覆盖度/冲突检查。先跑便宜规则，未过就不调用昂贵 LLM。
        String gaps = facts.gateReport();
        if (gaps != null) {
            AgentOutput.println("\n[覆盖度/冲突闸门] 未通过：\n" + gaps);
            return "最终回答前检查发现以下数据覆盖或冲突缺口：\n" + gaps
                    + "\n\n请逐项处理后再重新提交【完整的最终回答】，先判断缺口类型再选动作：\n"
                    + "1. 缺口清单已点名「账本中已有疑似条目」的，属标签错位——照抄覆盖目标的 "
                    + "dimension/period 字符串重新入账即可，不重新检索；\n"
                    + "2. 确实未检索的，继续检索（换关键词、统计口径、来源）并 record_facts 入账；\n"
                    + "3. 检索不到官方值的，给代理指标（status=proxy，注明折算方法）；关键依赖必须同时写保守主方案与升级条件；\n"
                    + "4. 确认检索不到的，用 record_facts 声明 status=not_found，"
                    + "note 写明已尝试的检索关键词与来源，并给不依赖它的保守主方案。";
        }

        // 闸门 2：关键依赖降级。critical 且非 found 的事实不能直接支撑主方案。
        String critical = facts.criticalAssumptions();
        if (critical != null && (!draft.contains("保守主方案") || !draft.contains("升级条件"))) {
            String defects = "最终回答存在未降级的关键依赖：以下 critical 事实不是 found，"
                    + "但答案没有同时给出「保守主方案」与「升级条件」，不能让它直接支撑主方案：\n"
                    + critical;
            AgentOutput.println("\n[关键依赖闸门] 未通过：\n" + critical);
            return defects;
        }

        // 闸门 3：组装校验依据，交给 quiet LLM 做事实、约束、技能格式与关键依赖复核。
        String basis = criteria == null ? "" : criteria;
        if (ledger != null) {
            basis = basis + (basis.isEmpty() ? "" : "\n\n") + ledger;
        }
        if (skill != null) {
            basis = basis + (basis.isEmpty() ? "" : "\n\n")
                    + "# 已加载技能终检与输出格式要求（必须执行）\n" + skill.instructions();
        }
        if (critical != null) {
            basis = basis + "\n\n# 关键依赖降级核对\n以下关键依赖不是 found，"
                    + "最终答案不得把它们作为主方案唯一支撑；必须采用保守主方案，并写明升级条件：\n"
                    + critical;
        }
        if (reports != null) {
            basis = basis + "\n\n" + reports;
        }
        if (multiVersion != null) {
            basis = basis + "\n\n" + multiVersion;
        }
        LlmResponse verification = verify(draft, basis);
        String verdict = verification.text();
        // 校验器没有产出可用 verdict 时，这是基础设施异常，不是答案内容缺陷。
        // max_tokens 可能发生在长思考后、Text 块尚未生成时；此时绝不能把空串包装成 BLOCKER。
        if (verification.maxTokensStopped() || verdict.isBlank()) {
            String receipt = verifierFailureReceipt(verification, verdict);
            AgentOutput.println("\n[最终校验] 校验器未产出 verdict（非内容 FAIL）：\n" + receipt);
            return receipt;
        }
        // LLM 校验失败时，附上“按缺陷类型修复”的操作指引，避免模型盲目重查。
        if (!verdict.strip().toUpperCase().startsWith("PASS")) {
            AgentOutput.println("\n[最终校验] 未通过：\n" + verdict);
            return "回答未通过最终校验，存在以下 BLOCKER（逐条修复后重新提交【完整的最终回答】，"
                    + "不要只输出补丁或缺失部分）：\n" + verdict
                    + "\n\n修复分流——先判断每条缺陷的类型再动手，不要一律重新检索或重新规划："
                    + "\n1. 数据类（缺数据/数据与账本矛盾）→ 只补检索缺失的数据并 record_facts 入账；"
                    + "已在账本的数据直接引用，禁止重复检索已入账条目；"
                    + "\n2. 陈述类（表述/结构/遗漏说明）→ 直接修改答案文本，不需要任何检索；"
                    + "\n3. 标签错位（覆盖闸门报缺口但数据已检索过）→ 照抄覆盖目标的 dimension/period "
                    + "字符串重新入账（同 key 覆盖旧值），不重新检索。"
                    + "\n答案数据一律以事实账本为准；只对修复涉及的部分与账本重新对账（改了哪些就核对哪些），"
                    + "不必对全文重跑双向对账。";
        }
        AgentOutput.println("[最终校验] 通过");
        AgentOutput.println("[覆盖度/冲突闸门] 通过");
        return null;
    }

    /** 控制台预览工具结果前 200 字符。 */
    private static String preview(String content) {
        String oneLine = content.replaceAll("\\s+", " ");
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    /** 对话稿中的工具结果正文：保住数值与来源链接所需的长度，超出截断。 */
    private static String body(String content) {
        String s = content.strip();
        return s.length() <= TRANSCRIPT_TOOL_BODY_LIMIT ? s
                : s.substring(0, TRANSCRIPT_TOOL_BODY_LIMIT) + "…（截断）";
    }

    /**
     * 压缩 / 内容风险恢复共用：以「原始述求 + 可选进度摘要 + 待修缺陷清单 + 技能正文 + 事实账本
     * + 已检索清单 + 旧草稿」拼出重建后的用户消息文本。
     * summary 传 null 表示无摘要——内容风险恢复不得复述被标记的原文，数据连续性由事实账本保证。
     * 缺陷清单置于最前（最高优先级），防重建后模型看不到原始清单只能靠猜；检索清单随后，
     * 模型据此避免对已检索目标换措辞重查。账本置于草稿之前并声明为最终权威，防止重建后被陈旧草稿锚定。
     */
    private String rebuild(String request, String bestAnswer, String summary) {
        // 重建上下文的优先级：原始任务 > 执行摘要 > 未修复缺陷 > 技能/事实/检索/报告 > 旧草稿。
        StringBuilder rebuilt = new StringBuilder("原始任务述求：\n").append(request);
        if (summary != null && !summary.isBlank()) {
            rebuilt.append("\n\n之前的执行进度摘要：\n").append(summary);
        }
        if (pendingFinalDefects != null && !pendingFinalDefects.isBlank()) {
            rebuilt.append("\n\n【上次终答被拒的缺陷清单——最高优先级：逐条修复后重新提交完整答案，不要猜测缺陷】\n")
                    .append(pendingFinalDefects);
        }
        // 已加载技能正文确定性重注入（重水化）：压缩重建的白名单只含技能 name/description，
        // 正文不补回则技能在压缩后失忆——技能是流程状态，不是聊天记录
        Skill activeSkill = skillState.get();
        if (activeSkill != null) {
            rebuilt.append("\n\n【已加载技能：").append(activeSkill.name())
                    .append("，本次任务按其完整工作流程继续严格执行】\n")
                    .append(activeSkill.instructions());
        }
        String ledger = facts.ledger();
        if (ledger != null) {
            rebuilt.append("\n\n【事实账本：已检索入账的结构化数据，最终权威依据】\n")
                    .append(ledger)
                    .append("最终答案的全部数据必须与账本一致；摘要或旧草稿与账本冲突时，以账本为准。");
        }
        String searches = searchLog.render();
        if (searches != null) {
            rebuilt.append("\n\n【已检索清单：本次任务执行过的全部 web_search——语义相同的目标不要再检索，只为空缺 facet 补检索】\n")
                    .append(searches);
        }
        if (rootAgent) {
            String reports = subAgentReports.snapshot();
            if (reports != null) {
                rebuilt.append("\n\n").append(reports);
            }
        }
        if (bestAnswer != null && !bestAnswer.isBlank()) {
            // 旧草稿只提供结构参考；与账本冲突时必须以账本重写。
            rebuilt.append("\n\n【旧答案草稿：仅供结构参考，其中与事实账本冲突或账本已更新的数据必须重写】\n")
                    .append(bestAnswer);
        }
        rebuilt.append("\n\n请基于以上进度继续完成任务；后续作答数据一律以事实账本为准。");
        return rebuilt.toString();
    }

    /** 判断异常是否为网关输入审核拦截（Content Exists Risk）：遍历异常链，任一消息命中即认定。 */
    private static boolean isContentRisk(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("Content Exists Risk")) {
                return true;
            }
        }
        return false;
    }

    /** 单次 run 的跨轮可变状态：对话消息、纯文本对话稿（供压缩摘要）、最完整草稿与内容审核恢复计数。 */
    private static final class RunState {
        /** 真正发给 LLM 的对话历史；尾注快照只复制到调用参数，不写入这里。 */
        List<Msg> messages;
        /** 无 tool_result 的纯文本执行稿，专供上下文压缩摘要使用。 */
        StringBuilder transcript;
        /** 当前最完整的交付草稿：校验/压缩围绕它，最终返回的是完整答案而非补丁。 */
        String bestAnswer;
        /** 网关内容审核恢复计数（连续触发才累加，成功调用后清零）。 */
        int contentRiskRecoveries;

        /** 起步上下文：system 人格 + 原始述求；transcript 为 6.1 用：剔除 tool_result 的纯文本对话稿（边执行边累积）。 */
        static RunState initial(String request, String systemPrompt) {
            RunState state = new RunState();
            state.messages = new ArrayList<>(List.of(
                    Msg.system(systemPrompt), Msg.user(request)));
            state.transcript = new StringBuilder("用户: ").append(request).append('\n');
            return state;
        }

        /** 丢弃全部历史与对话稿，以重建后的用户消息重开对话（内容审核恢复与上下文压缩共用）。 */
        void reset(String userMessage, String systemPrompt) {
            messages = new ArrayList<>(List.of(
                    Msg.system(systemPrompt), Msg.user(userMessage)));
            transcript = new StringBuilder("用户: ").append(userMessage).append('\n');
        }
    }

    /** 父 Agent 输出路由：控制台保留 ANSI，磁盘日志去 ANSI 后同步写入。 */
    private static final class TeePrintStream extends PrintStream {
        private static final Pattern ANSI = Pattern.compile("\033\\[[0-9;]*m");
        private final PrintStream console;
        private final PrintStream file;

        TeePrintStream(PrintStream console, PrintStream file) {
            super(console, true, StandardCharsets.UTF_8);
            this.console = console;
            this.file = file;
        }

        @Override
        public void print(String text) {
            console.print(text);
            file.print(ANSI.matcher(text).replaceAll(""));
        }

        @Override
        public void println(String text) {
            console.println(text);
            file.println(ANSI.matcher(text).replaceAll(""));
        }

        @Override
        public void println() {
            console.println();
            file.println();
        }

        @Override
        public void flush() {
            console.flush();
            file.flush();
        }
    }

    /** 工具轮执行结果：answer 非 null 表示终答闸门已通过、run 应立即返回该答案；
     * finalRejected 表示终答本轮被拒（当轮不压缩，缺陷清单留在对话里给模型看）。 */
    private record ToolOutcome(String answer, boolean finalRejected) {
    }
}
