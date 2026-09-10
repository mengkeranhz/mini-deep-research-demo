package org.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent loop（对应方案 7 步）：
 * 轮次上限 → LLM（人格 + 工具元信息 + 对话与思考）→ 无工具调用即结束 →
 * 依次执行工具收集结果 → 超阈值压缩上下文。
 */
public class Agent {
    static final int MAX_ROUNDS = 60;
    /** 上一次响应 inputTokens 超过该值即触发上下文压缩（演示时可调小）。 */
    static final int CONTEXT_TOKEN_THRESHOLD = 60_000;

    private final Config.Data cfg;
    private final LlmClient llm;
    private final ToolRegistry registry;

    public Agent() {
        this.cfg = Config.load();
        this.llm = LlmClient.create(cfg.llm());
        this.registry = new ToolRegistry(cfg);
    }

    public String run(String request) {
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.system(SystemPrompt.PERSONA));
        messages.add(Msg.user(request));
        // 6.1 用：剔除 tool_result 的纯文本对话稿（边执行边累积）
        StringBuilder transcript = new StringBuilder("用户: ").append(request).append('\n');

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            System.out.println("\n" + Console.header("======== 第 " + round + "/" + MAX_ROUNDS + " 轮 ========"));

            LlmResponse resp = llm.call(registry.definitions(), messages);
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
                return resp.text(); // 无工具调用 → 结束返回结论
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
                System.out.println("[工具结果] " + preview(out.content()));
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
                // 6.3 重建：旧对话与思考全部清除，仅保留原始述求 + 摘要 + 继续指令
                String rebuilt = "原始任务述求：\n" + request
                        + "\n\n之前的执行进度摘要：\n" + summary
                        + "\n\n请基于以上进度继续完成任务。";
                messages = new ArrayList<>(List.of(
                        Msg.system(SystemPrompt.PERSONA), Msg.user(rebuilt)));
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

    /** 控制台预览工具结果前 200 字符。 */
    private static String preview(String content) {
        String oneLine = content.replaceAll("\\s+", " ");
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }
}
