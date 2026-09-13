package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Agent loop（对应方案 7 步）：
 * 轮次上限 → 注入任务进度与事实账本快照 → LLM（人格 + 工具元信息 + 对话与思考）→
 * 无工具调用时走终答闸门（约束校验 + 账本核对 + 覆盖度检查）→
 * 依次执行工具收集结果（正文写入对话稿）→ 超阈值压缩上下文（重建时携带事实账本）。
 */
public class Agent {
    static final int MAX_ROUNDS = 120;
    /** 上一次响应 inputTokens 超过该值即触发上下文压缩（演示时可调小）。 */
    static final int CONTEXT_TOKEN_THRESHOLD = 60_000;
    /** 最终校验未通过时最多触发的重规划次数（防死循环）。 */
    static final int MAX_REPLANS = 3;
    /** 最终校验判定为纯表述问题时，最多直接修订的次数（防死循环）。 */
    static final int MAX_REVISES = 3;
    /** 对话稿中每条工具结果正文的最大保留长度（足够保住数值与链接，又不让稿子膨胀）。 */
    static final int TRANSCRIPT_TOOL_BODY_LIMIT = 1_500;

    /** 最终校验器提示词：对照约束、事实账本与覆盖目标检查草稿，按首行标签分流（PASS / RETRIEVE / REVISE）。 */
    private static final String VERIFY_PROMPT = """
            你是答案质量校验器。对照「校验依据」检查「草稿回答」：
            1. 所有硬约束是否满足；2. 信息缺口是否已说明或给出合理假设；3. 关键事实是否有来源、是否可信；4. 是否仍有未完成、未核实或答非所问之处。
            5. 与「事实账本」逐条核对：草稿中每个数据、时期与结论是否与已入账事实一致；草稿声称「未找到/未检索到 X」而账本中 X 为 found/proxy，或账本中 X 为 found 而草稿遗漏 X，均为缺陷。
            6. 账本中 status=not_found 的条目：草稿是否如实说明该缺口；其 note 是否写明已尝试的检索方式（未写明视为放弃过早）。
            输出格式：第一行只能是下列三选一，其后为逐条缺陷（每行一条，具体、可执行）：
            - PASS：已满足约束且质量足够（只输出这一行，不要附其他内容）。
            - RETRIEVE：存在需进一步检索/核实才能补足的信息缺口（缺失数据、关键事实无来源、需查证）。
            - REVISE：仅存在无需再检索即可修正的表述问题（措辞不清、口径/假设/起始时期等说明不完整、引用格式等）。
            两者皆有 → 输出 RETRIEVE，并把表述问题一并列在其后。
            """;

    private final Config.Data cfg;
    private final LlmClient llm;
    private final LlmClient quietLlm; // 无工具、非流式：最终校验等嵌套调用用
    private final ToolRegistry registry;
    private final TaskStore tasks;
    private final FactsStore facts;

    public Agent() {
        this.cfg = Config.load();
        this.llm = LlmClient.create(cfg.llm());
        this.quietLlm = LlmClient.create(new Config.Llm(cfg.llm().provider(), cfg.llm().baseUrl(),
                cfg.llm().model(), cfg.llm().apiKey(), cfg.llm().maxTokens(), cfg.llm().temperature(), false));
        this.tasks = new TaskStore();
        this.facts = new FactsStore();
        this.registry = new ToolRegistry(cfg, tasks, facts);
    }

    public String run(String request) {
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.system(SystemPrompt.PERSONA));
        messages.add(Msg.user(request));
        // 6.1 用：剔除 tool_result 的纯文本对话稿（边执行边累积）
        StringBuilder transcript = new StringBuilder("用户: ").append(request).append('\n');
        int replans = 0; // 最终校验判定为信息缺口时触发的重规划次数
        int revises = 0; // 最终校验判定为纯表述问题时触发的直接修订次数
        String bestAnswer = null; // 当前最完整的交付草稿：校验/压缩围绕它，最终返回的是完整答案而非补丁

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            System.out.println("\n" + Console.header("======== 第 " + round + "/" + MAX_ROUNDS + " 轮 ========"));

            // LLM 看不到 TaskStore / FactsStore 外部状态 → 每轮重算任务进度与事实账本快照，
            // 作为新系统块注入（人格之后、对话之前）；只放进本次调用的副本，messages 不留旧快照，
            // 天然无陈旧堆积、压缩重建也无需处理。账本快照带覆盖缺口，逼模型补齐而非提前收工
            String snapshot = tasks.snapshot();
            String factsSnapshot = facts.snapshot();
            List<Msg> callMessages = messages;
            if (snapshot != null || factsSnapshot != null) {
                if (snapshot != null) {
                    System.out.println(Console.header("[任务快照已注入] ") + tasks.progress());
                }
                if (factsSnapshot != null) {
                    System.out.println(Console.header("[事实账本已注入] ") + facts.coverageLine());
                }
                callMessages = new ArrayList<>(messages);
                int injectAt = 1;
                if (snapshot != null) {
                    callMessages.add(injectAt++, Msg.system(snapshot));
                }
                if (factsSnapshot != null) {
                    callMessages.add(injectAt, Msg.system(factsSnapshot));
                }
            }

            LlmResponse resp = llm.call(registry.definitions(), callMessages);
            if (cfg.llm().streaming()) {
                System.out.println(); // 结束流式文本行
            } else {
                printBlocks(resp); // 非流式时统一补打（流式已在接收中实时输出）
            }

            List<Block.ToolUse> toolCalls = new ArrayList<>();
            for (Block b : resp.blocks()) {
                if (b instanceof Block.ToolUse u) {
                    toolCalls.add(u);
                }
            }
            if (toolCalls.isEmpty()) {
                String candidate = resp.text();
                if (!candidate.isBlank()) {
                    bestAnswer = candidate; // 每次无工具响应的全文都视为最新完整草稿
                }
                // 最终回答前硬闸门：有校验依据（固定基线约束和/或事实账本）时先校验。
                // 校验依据用固定基线（原始约束），不用会随重规划变化的 live 快照；事实账本让校验器
                // 能做「结论 ↔ 过程」交叉核对——草稿说未找到而账本里有的矛盾在此拦截。
                // 校验器按首行标签分流：PASS 放行；REVISE 直接改答案（不重规划）；其余按信息缺口走重规划。
                String criteria = tasks.baseline();
                String ledger = facts.ledger();
                if (criteria != null || ledger != null) {
                    String basis = criteria == null ? "" : criteria;
                    if (ledger != null) {
                        basis = basis + (basis.isEmpty() ? "" : "\n\n") + ledger;
                    }
                    String verdict = verify(candidate, basis);
                    String head = firstLine(verdict).toUpperCase(Locale.ROOT);
                    if (head.startsWith("PASS")) {
                        System.out.println("[最终校验] 通过");
                        // 覆盖度硬闸门：声明的覆盖目标还有格子没入账，或 not_found 声明没写检索方式，
                        // 不放行——防止「5/5 任务完成」的假象掩盖数据缺口（如某年季度数据根本没查）
                        String gaps = facts.gateReport();
                        if (gaps != null) {
                            System.out.println("\n[覆盖度闸门] 未通过：\n" + gaps);
                            if (replans < MAX_REPLANS) {
                                messages.add(Msg.assistant(resp.blocks()));
                                messages.add(Msg.user("最终回答前检查发现以下数据覆盖缺口：\n" + gaps
                                        + "\n\n请逐项处理后再【重新输出完整的最终回答】：\n"
                                        + "1. 继续检索（换关键词、换统计口径、换来源）并用 record_facts 入账（target 填缺口对应的 required_facts id，如 rf1）；\n"
                                        + "2. 检索不到官方值的，给代理指标（status=proxy，注明折算方法）；\n"
                                        + "3. 确认检索不到的，用 record_facts 声明 status=not_found，"
                                        + "note 写明已尝试的检索关键词与来源，并在最终答案中如实说明该缺口。"));
                                transcript.append("助手: ").append(candidate.isEmpty() ? "（草稿回答）" : candidate).append('\n');
                                replans++;
                                continue;
                            }
                            // 覆盖缺口追问次数用尽：放行当前最佳答案，避免无界循环
                        } else {
                            System.out.println("[覆盖度闸门] 通过");
                        }
                    } else if (head.startsWith("REVISE")) {
                        // 纯表述问题：直接修订，不调用 analyze_query、不重置任务
                        if (revises < MAX_REVISES) {
                            System.out.println("\n[最终校验] 表述问题，直接修订（不触发重规划）：\n" + verdict);
                            messages.add(Msg.assistant(resp.blocks()));
                            messages.add(Msg.user("你的回答存在以下表述问题，无需重新检索或重新规划，请直接修订后【重新输出完整的最终回答】：\n"
                                    + verdict));
                            transcript.append("助手: ").append(candidate.isEmpty() ? "（草稿回答）" : candidate).append('\n');
                            revises++;
                            continue;
                        }
                        // 修订次数用尽：放行当前最佳答案
                    } else {
                        // RETRIEVE 或无法识别 → 保守按信息缺口走重规划
                        if (replans < MAX_REPLANS) {
                            System.out.println("\n[最终校验] 信息缺口，触发重规划：\n" + verdict);
                            messages.add(Msg.assistant(resp.blocks()));
                            messages.add(Msg.user("你的回答未通过最终校验，存在以下信息缺口：\n" + verdict
                                    + "\n\n请先调用 analyze_query 重新规划（新增的检索目标写入 required_facts），"
                                    + "补齐缺陷后【重新输出完整的最终回答】——不要只输出补丁或缺失部分，"
                                    + "必须覆盖问题要求的全部维度与时期；新检索到的数据先 record_facts 入账再作答，"
                                    + "答案数据一律以事实账本为准。"));
                            transcript.append("助手: ").append(candidate.isEmpty() ? "（草稿回答）" : candidate).append('\n');
                            replans++;
                            continue;
                        }
                        // 重规划次数用尽：放行当前最佳答案
                    }
                }
                return candidate; // 无工具调用 → 结束返回结论
            }
            for (Block.ToolUse u : toolCalls) {
                System.out.println(Console.tool("[调用工具] " + u.name() + " " + u.input()));
            }

            // 完整内容块（thinking/text/tool_use）原样追加为 assistant 消息（含思考回传）
            messages.add(Msg.assistant(resp.blocks()));
            String text = resp.text();
            transcript.append("助手: ").append(text.isEmpty() ? "（调用工具）" : text).append('\n');
            for (Block.ToolUse call : toolCalls) {
                transcript.append("  [调用工具 ").append(call.name())
                        .append(" 参数 ").append(call.input()).append("]\n");
            }

            // 依次执行全部工具调用，每个结果作为一条独立的 tool 消息回传。
            // 对话稿写入结果正文（截断保数值与链接）：压缩摘要才有数据可保，不再只记「已回传 N 个」
            for (Block.ToolUse call : toolCalls) {
                ToolRegistry.ToolOutput out = registry.run(call);
                // analyze_query 的规划 JSON 与 record_facts 的回执（含覆盖度）完整可见；其余按预览截断
                boolean full = "analyze_query".equals(call.name()) || "record_facts".equals(call.name());
                String printed = full ? out.content() : preview(out.content());
                System.out.println("[工具结果] " + printed);
                messages.add(Msg.tool(new Block.ToolResult(call.id(), out.content(), out.isError())));
                transcript.append("  [").append(call.name()).append(" 结果] ")
                        .append(body(out.content())).append('\n');
            }

            // 步骤 6：以上次响应输入 token 判断是否压缩（零额外调用）
            if (resp.inputTokens() > CONTEXT_TOKEN_THRESHOLD) {
                System.out.println("\n[上下文压缩] inputTokens=" + resp.inputTokens()
                        + " 超过阈值 " + CONTEXT_TOKEN_THRESHOLD + "，开始压缩…");
                // 6.2 无工具单次调用总结（直接发原 messages 会因 tool_use 缺 tool_result 报错）
                String summary = llm.summarize(transcript.toString());
                System.out.println("[上下文压缩] 摘要:\n" + summary);
                // 6.3 重建：旧对话与思考全部清除，保留原始述求 + 摘要 + 事实账本 + 旧草稿 + 继续指令。
                // 账本在草稿之前且声明为最终权威——旧草稿只是结构参考，与账本冲突的数据一律以账本为准重写，
                // 防止压缩后被陈旧草稿锚定（草稿只在无工具轮更新，连续检索期它必然落后于账本）
                StringBuilder rebuilt = new StringBuilder("原始任务述求：\n").append(request)
                        .append("\n\n之前的执行进度摘要：\n").append(summary);
                String ledger = facts.ledger();
                if (ledger != null) {
                    rebuilt.append("\n\n【事实账本：已检索入账的结构化数据，最终权威依据】\n")
                            .append(ledger)
                            .append("最终答案的全部数据必须与账本一致；摘要或旧草稿与账本冲突时，以账本为准。");
                }
                if (bestAnswer != null && !bestAnswer.isBlank()) {
                    rebuilt.append("\n\n【旧答案草稿：仅供结构参考，其中与事实账本冲突或账本已更新的数据必须重写】\n")
                            .append(bestAnswer);
                }
                rebuilt.append("\n\n请基于以上进度继续完成任务；后续作答数据一律以事实账本为准。");
                messages = new ArrayList<>(List.of(
                        Msg.system(SystemPrompt.PERSONA), Msg.user(rebuilt.toString())));
                transcript = new StringBuilder("用户: ").append(rebuilt).append('\n');
            }
        }
        throw new IllegalStateException("超过最大轮次 " + MAX_ROUNDS + "，任务未完成");
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

    /** 取校验器输出首行作为分类标签（PASS / RETRIEVE / REVISE）。 */
    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int i = text.indexOf('\n');
        return (i < 0 ? text : text.substring(0, i)).strip();
    }

    /** 最终校验：quiet 客户端判断草稿是否满足约束与质量，返回 PASS 或缺陷清单。 */
    private String verify(String draft, String criteria) {
        return quietLlm.call(List.of(),
                List.of(Msg.system(VERIFY_PROMPT),
                        Msg.user("校验依据：\n" + criteria + "\n\n草稿回答：\n" + draft))).text();
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
}
