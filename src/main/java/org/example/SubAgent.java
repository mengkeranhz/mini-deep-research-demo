package org.example;

import org.example.tools.CurrentTimeTool;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 子 Agent Loop：每次 execute_task 新建实例、全新上下文，独立完成一个自包含任务。
 * 函数调用模式——某轮不再调用工具即视为完成，该轮文本即任务结果；
 * 看不到父对话与计划清单（仅凭简报锚定原始述求），与父共享同一事实账本实例。
 * 轮次耗尽软着陆：不抛异常，返回部分结果，由父层决定重派或收尾。
 */
public class SubAgent {
    static final int MAX_ROUNDS = 60;
    /** 上一次响应 inputTokens 超过该值即触发上下文压缩。 */
    static final int CONTEXT_TOKEN_THRESHOLD = 60_000;

    /** 执行结果：completed=true 正常完成（text 为最终文本）；false 为部分结果（轮次耗尽或空输出）。 */
    public record Result(boolean completed, String text) {}

    private final LlmClient llm;
    private final ToolRegistry registry;
    private final FactsStore facts;
    private final boolean streaming;

    public SubAgent(LlmClient llm, ToolRegistry registry, FactsStore facts, boolean streaming) {
        this.llm = llm;
        this.registry = registry;
        this.facts = facts;
        this.streaming = streaming;
    }

    /** 执行一个子任务：brief 为自包含简报（原始述求 + 任务文本 + 提示 + 账本快照）。 */
    public Result run(String brief) {
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.system(SystemPrompt.SUB_PERSONA));
        messages.add(Msg.user(brief));
        // 压缩用：剔除 tool_result 的纯文本对话稿（边执行边累积）
        StringBuilder transcript = new StringBuilder("任务: ").append(brief).append('\n');
        String lastText = "";

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            System.out.println("\n" + Console.header("---- [子代理] 第 " + round + "/" + MAX_ROUNDS + " 轮 ----"));

            // 当前时间与账本快照每轮注入调用副本（索引 1）：时间首轮即可感知且不过期，
            // 运行中新入账的事实对后续轮可见；messages 不留旧块
            String factsSnapshot = facts.snapshot();
            List<Msg> callMessages = new ArrayList<>(messages);
            callMessages.add(1, Msg.system(CurrentTimeTool.nowText(ZoneId.systemDefault())));
            if (factsSnapshot != null) {
                System.out.println(Console.header("[子代理·事实账本已注入] ") + facts.size() + " 条");
                callMessages.add(2, Msg.system(factsSnapshot));
            }

            LlmResponse resp = llm.call(registry.definitions(), callMessages);
            if (streaming) {
                System.out.println(); // 结束流式文本行
            } else {
                Agent.printBlocks(resp); // 非流式时统一补打（流式已在接收中实时输出）
            }

            List<Block.ToolUse> toolCalls = new ArrayList<>();
            for (Block b : resp.blocks()) {
                if (b instanceof Block.ToolUse u) {
                    toolCalls.add(u);
                }
            }
            // 函数调用模式：某轮不再调用工具即任务完成，该轮文本即结果
            if (toolCalls.isEmpty()) {
                String text = resp.text();
                return text.isBlank() ? new Result(false, "（子代理无有效输出）")
                        : new Result(true, text);
            }
            for (Block.ToolUse u : toolCalls) {
                System.out.println(Console.tool("[子代理·调用工具] " + u.name() + " " + u.input()));
            }

            // 完整内容块（thinking/text/tool_use）原样追加为 assistant 消息（思考签名回传）
            messages.add(Msg.assistant(resp.blocks()));
            String text = resp.text();
            if (!text.isBlank()) {
                lastText = text;
            }
            transcript.append("助手: ").append(text.isEmpty() ? "（调用工具）" : text).append('\n');
            for (Block.ToolUse call : toolCalls) {
                transcript.append("  [调用工具 ").append(call.name())
                        .append(" 参数 ").append(call.input()).append("]\n");
            }

            // 依次执行全部工具调用，每个结果一条独立 tool 消息；正文入对话稿（截断保数值与链接）
            for (Block.ToolUse call : toolCalls) {
                ToolRegistry.ToolOutput out = registry.run(call);
                System.out.println("[子代理·工具结果] " + Agent.preview(out.content()));
                messages.add(Msg.tool(new Block.ToolResult(call.id(), out.content(), out.isError())));
                transcript.append("  [").append(call.name()).append(" 结果] ")
                        .append(Agent.body(out.content())).append('\n');
            }

            // 以上次响应 input token 判断是否压缩：丢弃全部历史（无 tool_use/tool_result 配对风险），
            // 以「子任务简报 + 进度摘要 + 账本快照」重建
            if (resp.inputTokens() > CONTEXT_TOKEN_THRESHOLD) {
                System.out.println("[子代理·上下文压缩] inputTokens=" + resp.inputTokens()
                        + " 超过阈值 " + CONTEXT_TOKEN_THRESHOLD + "，开始压缩…");
                String summary = llm.summarize(transcript.toString());
                StringBuilder rebuilt = new StringBuilder("子任务简报：\n").append(brief)
                        .append("\n\n之前的执行进度摘要：\n").append(summary);
                appendSnapshot(rebuilt, facts.snapshot());
                rebuilt.append("\n\n请基于以上进度继续完成子任务；结果数据以事实账本为准。");
                messages = new ArrayList<>(List.of(
                        Msg.system(SystemPrompt.SUB_PERSONA), Msg.user(rebuilt.toString())));
                transcript = new StringBuilder("用户: ").append(rebuilt).append('\n');
            }
        }
        // 轮次耗尽软着陆：返回最后一段非空文本（可能没有），父层决定重派或收尾
        return new Result(false, lastText.isBlank() ? "（子代理达到轮次上限，无有效文本输出）" : lastText);
    }

    /** 压缩重建时附加核心状态快照（空快照跳过，快照自带标题头）。 */
    private static void appendSnapshot(StringBuilder sb, String snapshot) {
        if (snapshot != null) {
            sb.append("\n\n").append(snapshot);
        }
    }
}
