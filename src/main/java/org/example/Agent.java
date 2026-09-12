package org.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent loop（对应方案 7 步）：
 * 轮次上限 → LLM（人格 + 工具元信息 + 对话与思考）→ 无工具调用即结束 →
 * 依次执行工具收集结果 → 超阈值压缩上下文。
 */
public class Agent {
    static final int MAX_ROUNDS = 120;
    /** 上一次响应 inputTokens 超过该值即触发上下文压缩（演示时可调小）。 */
    static final int CONTEXT_TOKEN_THRESHOLD = 60_000;
    /** 最终校验未通过时最多触发的重规划次数（防死循环）。 */
    static final int MAX_REPLANS = 3;

    /** 最终校验器提示词：对照约束与质量检查草稿，通过输出 PASS，否则逐条列缺陷。 */
    private static final String VERIFY_PROMPT = """
            你是答案质量校验器。对照「校验依据」检查「草稿回答」：
            1. 所有硬约束是否满足；2. 信息缺口是否已说明或给出合理假设；3. 关键事实是否有来源、是否可信；4. 是否仍有未完成、未核实或答非所问之处。
            若已满足约束且质量足够，只输出一行 PASS；否则逐条列出缺陷（每行一条，具体、可执行，供后续补救）。
            """;

    private final Config.Data cfg;
    private final LlmClient llm;
    private final LlmClient quietLlm; // 无工具、非流式：最终校验等嵌套调用用
    private final ToolRegistry registry;
    private final TaskStore tasks;

    public Agent() {
        this.cfg = Config.load();
        this.llm = LlmClient.create(cfg.llm());
        this.quietLlm = LlmClient.create(new Config.Llm(cfg.llm().provider(), cfg.llm().baseUrl(),
                cfg.llm().model(), cfg.llm().apiKey(), cfg.llm().maxTokens(), cfg.llm().temperature(), false));
        this.tasks = new TaskStore();
        this.registry = new ToolRegistry(cfg, tasks);
    }

    public String run(String request) {
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.system(SystemPrompt.PERSONA));
        messages.add(Msg.user(request));
        // 6.1 用：剔除 tool_result 的纯文本对话稿（边执行边累积）
        StringBuilder transcript = new StringBuilder("用户: ").append(request).append('\n');
        int replans = 0; // 最终校验未通过触发的重规划次数
        String bestAnswer = null; // 当前最完整的交付草稿：校验/压缩围绕它，最终返回的是完整答案而非补丁

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            System.out.println("\n" + Console.header("======== 第 " + round + "/" + MAX_ROUNDS + " 轮 ========"));

            // LLM 看不到 TaskStore 外部状态 → 每轮重算一份任务快照，作为新系统块注入（人格之后、
            // 对话之前）；只放进本次调用的副本，messages 不留旧快照，天然无陈旧堆积、压缩重建也无需处理
            String snapshot = tasks.snapshot();
            List<Msg> callMessages = messages;
            if (snapshot != null) {
                System.out.println(Console.header("[任务快照已注入] ") + tasks.progress());
                callMessages = new ArrayList<>(messages);
                callMessages.add(1, Msg.system(snapshot));
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
                // 最终回答前硬闸门：存在活跃计划且未超重规划上限时，校验约束与质量
                // 校验依据用固定基线（原始约束），不用会随重规划变化的 live 快照
                String criteria = tasks.baseline();
                if (criteria != null && replans < MAX_REPLANS) {
                    String verdict = verify(candidate, criteria);
                    if (!verdict.strip().toUpperCase().startsWith("PASS")) {
                        System.out.println("\n[最终校验] 未通过，触发重规划：\n" + verdict);
                        messages.add(Msg.assistant(resp.blocks()));
                        messages.add(Msg.user("你的回答未通过最终校验，存在以下缺陷：\n" + verdict
                                + "\n\n请先调用 analyze_query 重新规划，并在补齐缺陷后【重新输出完整的最终回答】"
                                + "——不要只输出补丁或缺失部分，必须覆盖全部行程（含吃住）。"));
                        transcript.append("助手: ").append(candidate.isEmpty() ? "（草稿回答）" : candidate).append('\n');
                        replans++;
                        continue;
                    }
                    System.out.println("[最终校验] 通过");
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

            // 依次执行全部工具调用，每个结果作为一条独立的 tool 消息回传
            for (Block.ToolUse call : toolCalls) {
                ToolRegistry.ToolOutput out = registry.run(call);
                // analyze_query 的规划 JSON 必须完整可见；其余工具结果仍按预览截断，避免刷屏
                String printed = "analyze_query".equals(call.name()) ? out.content() : preview(out.content());
                System.out.println("[工具结果] " + printed);
                messages.add(Msg.tool(new Block.ToolResult(call.id(), out.content(), out.isError())));
            }
            transcript.append("  [结果] 已回传 ").append(toolCalls.size()).append(" 个工具结果\n");

            // 步骤 6：以上次响应输入 token 判断是否压缩（零额外调用）
            if (resp.inputTokens() > CONTEXT_TOKEN_THRESHOLD) {
                System.out.println("\n[上下文压缩] inputTokens=" + resp.inputTokens()
                        + " 超过阈值 " + CONTEXT_TOKEN_THRESHOLD + "，开始压缩…");
                // 6.2 无工具单次调用总结（直接发原 messages 会因 tool_use 缺 tool_result 报错）
                String summary = llm.summarize(transcript.toString());
                System.out.println("[上下文压缩] 摘要:\n" + summary);
                // 6.3 重建：旧对话与思考全部清除，保留原始述求 + 摘要 + 已完成完整草稿 + 继续指令
                StringBuilder rebuilt = new StringBuilder("原始任务述求：\n").append(request)
                        .append("\n\n之前的执行进度摘要：\n").append(summary);
                if (bestAnswer != null && !bestAnswer.isBlank()) {
                    rebuilt.append("\n\n【当前已完成但尚未通过最终校验的完整答案草稿，"
                            + "请在其基础上修订补全，最终必须重新输出完整的最终答案】\n").append(bestAnswer);
                }
                rebuilt.append("\n\n请基于以上进度继续完成任务。");
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
}
