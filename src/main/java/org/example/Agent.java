package org.example;

import org.example.tools.CurrentTimeTool;
import org.example.tools.FinalAnswerTool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Agent loop（对应方案 7 步）：
 * 轮次上限 → 尾注当前时间与任务进度、事实账本瘦身快照（以用户消息追加在对话末尾——快照每轮变化，插在头部会破坏
 * 前缀稳定、令整段历史缓存失效；放尾部后 system+历史逐轮只追加，配合 AnthropicClient 的 cache_control
 * 断点逐轮命中前缀缓存）→ LLM（人格 + 工具元信息 + 对话与思考，逐轮打印耗时与 token 统计）→
 * 无工具纯文本：任务已全部完成（或未规划）时作为结论返回（不触发校验）、任务未完成时视为面向用户的
 * 陈述、等待 stdin 回复后继续；调用 final_answer 提交答案时走终答闸门（BLOCKER 级约束校验 + 账本核对 + 覆盖度检查，
 * 补充性意见不阻塞）→ 依次执行工具收集结果（正文写入对话稿）→ 超阈值压缩上下文（重建时携带事实账本与
 * 已检索清单；终答被拒的当轮不压缩，缺陷清单随后续重建以最高优先级注入，防靠猜补缺陷）。
 */
public class Agent {
    static final int MAX_ROUNDS = 90;
    /** 上下文压缩阈值（上次响应 inputTokens 超过即压缩）：config.yaml 的 llm.context-token-threshold，
     * 缺省 838,861 = 1M 窗口 × 80%——接近饱和前主动压缩，为当前轮输入与输出预留空间。 */
    private final int contextTokenThreshold;
    /** 对话稿中每条工具结果正文的最大保留长度（足够保住数值与链接，又不让稿子膨胀）。 */
    static final int TRANSCRIPT_TOOL_BODY_LIMIT = 1_500;
    /** 网关内容审核（Content Exists Risk）连续触发的最大恢复次数，超过则抛错退出——防账本本身带敏感内容时死循环；成功调用后计数清零。 */
    static final int MAX_CONTENT_RISK_RECOVERIES = 3;

    /** 最终校验器提示词：按「够用即可」裁决，只有四类 BLOCKER 能打回；补充性意见归 SUGGESTION 不阻塞。 */
    private static final String VERIFY_PROMPT = """
            你是答案质量校验器，按「够用即可」原则裁决：只有以下四类 BLOCKER 才能判不通过——
            1. 事实错误：草稿的数据/结论与「事实账本」矛盾，或断言了账本与校验依据都不支撑的确定性事实；
            2. 账本矛盾/遗漏：草稿声称「未找到/未检索到 X」而账本中 X 为 found/proxy；账本 found 的关键事实被草稿写错或遗漏；
            3. 硬约束违反：「校验依据」中可判定的硬约束未满足；
            4. 关键缺口未声明：述求要求的核心内容缺失且草稿未如实说明原因；账本 status=not_found 的条目草稿未如实交代。
            裁决规则：
            - 交付形态合法：最终交付物为磁盘文件（如 Markdown 路书）时，「文件路径+摘要+关键结论」是合法答案形态；
              「答案里没列来源/没贴完整内容/没给样例」不构成缺陷——只要文件内容已按校验依据与账本覆盖即可。
            - 补充性/展示性意见（可以更详细、可加 Plan B、可补来源清单、措辞可优化、可再交叉验证等）一律不阻塞，最多写入 SUGGESTION。
            - 没有 BLOCKER 就判 PASS，不追求完美、不主动加码要求。
            输出格式（严格遵守）：
            第一行只写 PASS 或 FAIL；FAIL 时另起一行逐条列出 BLOCKER（每行一条：缺陷+依据+可执行的修复动作）；
            若有非阻塞建议，最后以「SUGGESTION:」起一段列出（无则省略该段）。
            """;

    private final Config.Data cfg;
    private final LlmClient llm;
    private final LlmClient quietLlm; // 无工具、非流式：最终校验等嵌套调用用
    private final ToolRegistry registry;
    private final TaskStore tasks;
    private final FactsStore facts;
    private final SearchLog searchLog;
    private final SkillState skillState;
    /** 终答最近一次被拒的缺陷清单：非空时随上下文压缩重建以最高优先级注入（防重建后靠猜补缺陷）。 */
    private String pendingFinalDefects;
    /** 运行中的用户回复通道：任务未完成时模型向用户提问，从这里读回答。 */
    private final Scanner console = new Scanner(System.in, StandardCharsets.UTF_8);

    public Agent() {
        this.cfg = Config.load();
        this.llm = LlmClient.create(cfg.llm());
        this.quietLlm = LlmClient.create(new Config.Llm(cfg.llm().provider(), cfg.llm().baseUrl(),
                cfg.llm().model(), cfg.llm().apiKey(), cfg.llm().maxTokens(), cfg.llm().temperature(),
                cfg.llm().topP(), false, cfg.llm().thinking(), cfg.llm().contextTokenThreshold()));
        this.contextTokenThreshold = cfg.llm().contextTokenThreshold();
        this.tasks = new TaskStore();
        this.facts = new FactsStore();
        this.searchLog = new SearchLog();
        this.skillState = new SkillState(); // 会话级生效技能：load_skill 写入，规划注入与压缩重建读取
        this.registry = new ToolRegistry(cfg, tasks, facts, skillState, searchLog);
    }

    /**
     * Agent 主循环（整体流程对应方案 7 步）：每轮轮内四阶段——
     * ① 尾注快照 + LLM 调用 → ② 纯文本/工具分流 → ③ 工具执行（含终答闸门）→ ④ 超阈值压缩。
     * 三条退出路径：任务全完成的纯文本直接作为结论返回（不触发校验）、final_answer 过终答闸门返回、
     * 超轮次上限抛错。各阶段的设计取舍与细节见对应私有方法的注释。
     */
    public String run(String request) {
        // 跨轮可变状态：对话消息、纯文本对话稿（供压缩摘要）、最完整草稿、内容审核恢复计数；
        // 内容审核恢复与压缩重建时整体 reset 重开对话，而非在旧历史上追加
        RunState state = RunState.initial(request);
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            System.out.println("\n" + Console.header("======== 第 " + round + "/" + MAX_ROUNDS + " 轮 ========"));

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
                    return conclusion; // 任务已全部完成，纯文本即最终结论（不走终答闸门）
                }
                continue; // 空响应催促已注入或用户回复已入对话，进入下一轮
            }

            // ③ 工具轮：完整内容块（thinking/text/tool_use）回传为 assistant 消息后依次执行全部调用，
            //    结果逐条回传；final_answer 先过终答闸门（BLOCKER 校验 + 账本核对 + 覆盖度），通过即任务完成
            ToolOutcome outcome = executeToolCalls(state, resp, toolCalls);
            if (outcome.answer() != null) {
                return outcome.answer(); // final_answer 终答闸门通过
            }
            // ④ 超阈值压缩：用上轮响应的 inputTokens 判断（零额外调用）；终答被拒的当轮不压缩——
            //    缺陷清单刚以 tool 结果进入对话，立即压缩会把它降级进摘要、模型只能靠猜补缺陷
            compressIfNeeded(state, request, resp, outcome.finalRejected());
        }
        throw new IllegalStateException("超过最大轮次 " + MAX_ROUNDS + "，任务未完成");
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
        String snapshot = tasks.snapshot();
        String factsSnapshot = facts.snapshot();
        List<Msg> callMessages = new ArrayList<>(messages);
        callMessages.add(Msg.user("[当前时间·每轮自动追加的状态块，非用户消息，不要回复它] "
                + CurrentTimeTool.nowText()));
        if (snapshot != null) {
            System.out.println(Console.header("[任务快照已注入·尾注] ") + tasks.progress());
            callMessages.add(Msg.user(snapshot));
        }
        if (factsSnapshot != null) {
            System.out.println(Console.header("[事实账本已注入·尾注] ") + facts.coverageLine());
            callMessages.add(Msg.user(factsSnapshot));
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
        System.out.println(Console.stat(String.format(
                "[本轮统计] LLM %.1fs · 输入 %,d tok（另缓存命中 %,d） · 输出 %,d tok",
                llmMs / 1000.0, resp.inputTokens(), resp.cacheReadTokens(), resp.outputTokens())));
        if (cfg.llm().streaming()) {
            System.out.println(); // 结束流式文本行
        } else {
            printBlocks(resp); // 非流式时统一补打（流式已在接收中实时输出）
        }
        return resp;
    }

    /** 网关内容审核恢复：丢弃触发审核的累积历史，以任务原始述求与事实账本重建上下文（不复述被标记的原文）。 */
    private void recoverFromContentRisk(RunState state, String request) {
        state.contentRiskRecoveries++;
        System.out.println(Console.error("\n[内容风险] 输入触发网关内容审核（Content Exists Risk），"
                + "第 " + state.contentRiskRecoveries + "/" + MAX_CONTENT_RISK_RECOVERIES
                + " 次丢弃历史、以账本重建后继续…"));
        String rebuilt = "此前累积的对话历史触发网关内容审核被拦截，已全部丢弃；"
                + "以下依据任务原始述求与事实账本继续执行。"
                + "\n\n" + rebuild(request, state.bestAnswer, null);
        state.reset(rebuilt);
    }

    /**
     * 无工具纯文本轮的三种去向：任务已全部完成 → 最终结论，直接返回、不走终答闸门；
     * 空响应 → 注入催促后重试；未规划（空任务）或任务未完成 → 视为面向用户的中间陈述
     * （如技能第一步的集中澄清），等待 stdin 回复后继续（中途提问曾被校验当「不合格答案」打回，
     * 故不走 final_answer 闸门）。返回非 null 表示这就是最终结论；null 表示本轮已处理完毕，进入下一轮。
     */
    private String handlePlainText(RunState state, LlmResponse resp) {
        String candidate = resp.text();
        if (candidate.isBlank()) {
            nudgeAfterEmptyResponse(state, resp);
            return null;
        }
        if (!tasks.isEmpty() && tasks.allDone()) {
            state.transcript.append("助手: ").append(candidate).append('\n');
            return candidate;
        }
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
        System.out.println(Console.header("\n[等待用户回复]") + "（直接回车 = 按默认假设继续执行）");
        System.out.print("> ");
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
     * 工具轮主体：完整内容块（thinking/text/tool_use）原样追加为 assistant 消息（含思考回传）→
     * 依次执行全部工具调用，每个结果作为一条独立的 tool 消息回传。对话稿写入结果正文（截断保数值与链接）：
     * 压缩摘要才有数据可保，不再只记「已回传 N 个」。final_answer 先过终答闸门，通过则答案直接生效。
     */
    private ToolOutcome executeToolCalls(RunState state, LlmResponse resp, List<Block.ToolUse> toolCalls) {
        for (Block.ToolUse u : toolCalls) {
            System.out.println(Console.tool("[调用工具] " + u.name() + " " + u.input()));
        }
        state.messages.add(Msg.assistant(resp.blocks()));
        appendToolTurn(state.transcript, resp, toolCalls);

        boolean finalRejectedThisRound = false; // 终答被拒当轮跳过压缩，缺陷清单留在对话里给模型看
        long toolsMs = 0; // 工具执行累计耗时（不含终答校验——那是嵌套 LLM 调用，单独计）
        long gateMs = 0;
        int ran = 0;
        for (Block.ToolUse call : toolCalls) {
            // final_answer 终止闸门：提交的答案先过与纯文本终答同一套校验（VERIFY_PROMPT + 覆盖度），
            // 通过才真正结束任务；未通过则缺陷清单作为 error 结果回传，模型修正后重新提交
            if (FinalAnswerTool.NAME.equals(call.name())) {
                String answer = ToolRegistry.optStr(call.input(), "answer");
                if (answer != null && !answer.isBlank()) {
                    state.bestAnswer = answer; // 工具提交的全文同样视为最新完整草稿
                    long gateStart = System.nanoTime();
                    String defects = finalGate(answer);
                    gateMs += (System.nanoTime() - gateStart) / 1_000_000;
                    pendingFinalDefects = defects; // 通过置 null；被拒保留——压缩重建时最高优先级注入
                    if (defects == null) {
                        System.out.println("[final_answer] 校验通过，任务完成");
                        state.messages.add(Msg.tool(new Block.ToolResult(call.id(),
                                "最终校验通过，答案已采纳，任务完成。", false)));
                        return new ToolOutcome(answer, false);
                    }
                    finalRejectedThisRound = true;
                    state.messages.add(Msg.tool(new Block.ToolResult(call.id(), defects, true)));
                    state.transcript.append("  [final_answer 未通过最终校验，缺陷清单已回传]\n");
                    continue; // 同批其余工具调用照常执行
                }
                // answer 缺失或为空 → 走正常执行路径，由 registry 返回缺参错误
            }
            long toolStart = System.nanoTime();
            ToolRegistry.ToolOutput out = registry.run(call);
            toolsMs += (System.nanoTime() - toolStart) / 1_000_000;
            ran++;
            // analyze_query 的规划 JSON 与 record_facts 的回执（含覆盖度）完整可见；
            // load_skill 全文入稿——摘要才能看清流程走到哪一步（正文重注入靠 SkillState，这里只为摘要保真）
            boolean full = "analyze_query".equals(call.name()) || "record_facts".equals(call.name())
                    || "load_skill".equals(call.name());
            String printed = full ? out.content() : preview(out.content());
            System.out.println("[工具结果] " + printed);
            state.messages.add(Msg.tool(new Block.ToolResult(call.id(), out.content(), out.isError())));
            state.transcript.append("  [").append(call.name()).append(" 结果] ")
                    .append(body(out.content())).append('\n');
        }

        StringBuilder toolStat = new StringBuilder(String.format(
                "[本轮统计] 工具 %.1fs（%d 次调用）", toolsMs / 1000.0, ran));
        if (gateMs > 0) {
            toolStat.append(String.format(" · 终答校验 %.1fs", gateMs / 1000.0));
        }
        System.out.println(Console.stat(toolStat.toString()));
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
        if (resp.inputTokens() <= contextTokenThreshold || finalRejectedThisRound) {
            return;
        }
        System.out.println("\n[上下文压缩] inputTokens=" + resp.inputTokens()
                + " 超过阈值 " + contextTokenThreshold + "，开始压缩…");
        // 6.2 无工具单次调用总结（直接发原 messages 会因 tool_use 缺 tool_result 报错）
        String summary = llm.summarize(state.transcript.toString());
        System.out.println("[上下文压缩] 摘要:\n" + summary);
        // 6.3 重建：旧对话与思考全部清除，保留原始述求 + 摘要 + 事实账本 + 旧草稿 + 继续指令（拼装逻辑见 rebuild）。
        state.reset(rebuild(request, state.bestAnswer, summary));
    }

    /** 非流式模式下的统一打印：思考与结论文本。 */
    private static void printBlocks(LlmResponse resp) {
        for (Block b : resp.blocks()) {
            if (b instanceof Block.Thinking t) {
                System.out.println(Console.thinking("[思考] " + t.thinking()));
            } else if (b instanceof Block.Text t) {
                System.out.println("[输出] " + t.text());
            }
        }
    }

    /** 最终校验：quiet 客户端判断草稿是否满足约束与质量，返回 PASS 或缺陷清单。 */
    private String verify(String draft, String criteria) {
        return quietLlm.call(List.of(),
                List.of(Msg.system(VERIFY_PROMPT),
                        Msg.user("校验依据：\n" + criteria + "\n\n草稿回答：\n" + draft))).text();
    }

    /**
     * 终答硬闸门（纯文本回答与 final_answer 提交共用）：VERIFY_PROMPT 约束校验 + 账本核对 + 覆盖度检查。
     * 无校验依据（未规划且未入账）时不拦；返回 null 表示通过放行，否则返回需回传模型的
     * 缺陷清单（含补救指引），模型补齐缺陷后重新提交完整答案。
     * 校验依据用固定基线（原始约束），不用会随重规划变化的 live 快照；事实账本让校验器
     * 能做「结论 ↔ 过程」交叉核对——草稿说未找到而账本里有的矛盾在此拦截。
     */
    private String finalGate(String draft) {
        String criteria = tasks.baseline();
        String ledger = facts.ledger();
        if (criteria == null && ledger == null) {
            return null;
        }
        String basis = criteria == null ? "" : criteria;
        if (ledger != null) {
            basis = basis + (basis.isEmpty() ? "" : "\n\n") + ledger;
        }
        String verdict = verify(draft, basis);
        if (!verdict.strip().toUpperCase().startsWith("PASS")) {
            System.out.println("\n[最终校验] 未通过：\n" + verdict);
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
        System.out.println("[最终校验] 通过");
        // 覆盖度硬闸门：声明的覆盖目标还有格子没入账，或 not_found 声明没写检索方式，
        // 不放行——防止「5/5 任务完成」的假象掩盖数据缺口（如某年季度数据根本没查）
        String gaps = facts.gateReport();
        if (gaps != null) {
            System.out.println("\n[覆盖度闸门] 未通过：\n" + gaps);
            return "最终回答前检查发现以下数据覆盖缺口：\n" + gaps
                    + "\n\n请逐项处理后再重新提交【完整的最终回答】，先判断缺口类型再选动作：\n"
                    + "1. 缺口清单已点名「账本中已有疑似条目」的，属标签错位——照抄覆盖目标的 "
                    + "dimension/period 字符串重新入账即可，不重新检索；\n"
                    + "2. 确实未检索的，继续检索（换关键词、统计口径、来源）并 record_facts 入账；\n"
                    + "3. 检索不到官方值的，给代理指标（status=proxy，注明折算方法）；\n"
                    + "4. 确认检索不到的，用 record_facts 声明 status=not_found，"
                    + "note 写明已尝试的检索关键词与来源，并在最终答案中如实说明该缺口。";
        }
        System.out.println("[覆盖度闸门] 通过");
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
        if (bestAnswer != null && !bestAnswer.isBlank()) {
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
        List<Msg> messages;
        StringBuilder transcript;
        /** 当前最完整的交付草稿：校验/压缩围绕它，最终返回的是完整答案而非补丁。 */
        String bestAnswer;
        /** 网关内容审核恢复计数（连续触发才累加，成功调用后清零）。 */
        int contentRiskRecoveries;

        /** 起步上下文：system 人格 + 原始述求；transcript 为 6.1 用：剔除 tool_result 的纯文本对话稿（边执行边累积）。 */
        static RunState initial(String request) {
            RunState state = new RunState();
            state.messages = new ArrayList<>(List.of(
                    Msg.system(SystemPrompt.withSkills()), Msg.user(request)));
            state.transcript = new StringBuilder("用户: ").append(request).append('\n');
            return state;
        }

        /** 丢弃全部历史与对话稿，以重建后的用户消息重开对话（内容审核恢复与上下文压缩共用）。 */
        void reset(String userMessage) {
            messages = new ArrayList<>(List.of(
                    Msg.system(SystemPrompt.withSkills()), Msg.user(userMessage)));
            transcript = new StringBuilder("用户: ").append(userMessage).append('\n');
        }
    }

    /** 工具轮执行结果：answer 非 null 表示终答闸门已通过、run 应立即返回该答案；
     * finalRejected 表示终答本轮被拒（当轮不压缩，缺陷清单留在对话里给模型看）。 */
    private record ToolOutcome(String answer, boolean finalRejected) {
    }
}
