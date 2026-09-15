package org.example;

import org.example.tools.CurrentTimeTool;
import org.example.tools.FinalAnswerTool;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 父子 Agent 共用的执行循环骨架：每轮注入当前时间与任务/账本快照 → 调模型 → 收集工具调用 →
 * 执行工具（每个结果一条 tool 消息）→ 提交（final_answer 或纯文本视为隐式提交）走 LLM 终止校验，
 * 未通过反馈缺陷并计数（连续 3 次 best-effort）；超阈值压缩上下文（重建时显式保留述求/简报、
 * 进度摘要、任务与账本快照、草稿答案）。差异点（人格、日志前缀、轮次上限与横幅、缺陷反馈文案、
 * 失败后是否程序化重规划、压缩重建文案、结果组装）由子类模板方法提供。
 */
abstract class AgentLoop<T> {
    /** 上一次响应 inputTokens 超过该值即触发上下文压缩（演示时可调小）。 */
    private static final int CONTEXT_TOKEN_THRESHOLD = 60_000;
    /** 对话稿中每条工具结果正文的最大保留长度（足够保住数值与链接，又不让稿子膨胀）。 */
    private static final int TRANSCRIPT_TOOL_BODY_LIMIT = 1_500;
    /** 控制台打印工具结果正文的最大长度：保留结构化换行，仅对超长输出截断并标注总长。 */
    private static final int CONSOLE_TOOL_BODY_LIMIT = 12_000;

    protected final LlmClient llm;
    protected final ToolRegistry registry;
    protected final FactsStore facts;
    protected final TaskStore tasks;
    private final FinalVerifier verifier; // 无工具、非流式：提交的最终答案终止校验用
    /** 无工具、非流式：上下文压缩摘要等嵌套调用用，避免增量输出打进主循环控制台。 */
    private final LlmClient quietLlm;
    private final boolean streaming;
    /** 最终校验连续失败计数：任何干活（调用工具）轮次重置，达到 3 次即 best-effort 返回。 */
    private int failStreak = 0;

    protected AgentLoop(LlmClient llm, LlmClient quietLlm, ToolRegistry registry,
                        FactsStore facts, TaskStore tasks, boolean streaming) {
        this.llm = llm;
        this.registry = registry;
        this.facts = facts;
        this.tasks = tasks;
        this.verifier = new FinalVerifier(quietLlm);
        this.quietLlm = quietLlm;
        this.streaming = streaming;
    }

    // ---- 模板钩子（父子差异点） ----

    /** 系统人格提示词。 */
    protected abstract String persona();

    /** 控制台标签前缀（父 ""，子 "子代理·"），统一各环节日志文案。 */
    protected abstract String tag();

    /** 最大轮次（父/子可不同）。 */
    protected abstract int maxRounds();

    /** 轮次横幅。 */
    protected abstract String roundHeader(int round);

    /** 校验失败后的确定性动作：父程序化重规划；子默认无操作。 */
    protected void replan(String baseline, String defects) {
    }

    /** 校验失败反馈文案（隐式提交 / final_answer 两种口径）。 */
    protected abstract String defectFeedback(boolean viaFinalAnswer, String defects);

    /** 压缩重建时「原始述求 / 简报」的标题。 */
    protected abstract String seedLabel();

    /** 压缩重建结尾的续接指令。 */
    protected abstract String resumeInstruction();

    /** 校验通过：组装成功结果。 */
    protected abstract T pass(String candidate);

    /** 连续 3 次校验未通过：best-effort 组装结果。 */
    protected abstract T bestEffort(String candidate, String defects);

    /** 轮次耗尽：软着陆或抛异常由子类决定。 */
    protected abstract T exhausted(String lastText);

    /** 运行循环；seed 为首条用户消息（父=原始述求，子=子任务简报），也是压缩重建锚点。 */
    protected final T runLoop(String seed) {
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.system(persona()));
        messages.add(Msg.user(seed));
        // 压缩用：剔除 tool_result 的纯文本对话稿（边执行边累积）
        StringBuilder transcript = new StringBuilder("用户: ").append(seed).append('\n');
        String lastText = "";

        for (int round = 1; round <= maxRounds(); round++) {
            System.out.println("\n" + Console.header(roundHeader(round)));

            // LLM 看不到外部时钟与 TaskStore / FactsStore 外部状态 → 每轮注入当前时间并重算两个快照，
            // 作为新系统块注入（人格之后、对话之前）；只放进本次调用的副本，messages 不留旧块
            String taskSnapshot = tasks.snapshot();
            String factsSnapshot = facts.snapshot();
            List<Msg> callMessages = new ArrayList<>(messages);
            int injectAt = 1;
            callMessages.add(injectAt++, Msg.system(CurrentTimeTool.nowText(ZoneId.systemDefault())));
            if (taskSnapshot != null) {
                System.out.println(Console.header("[" + tag() + "任务快照已注入] ") + tasks.progress());
                callMessages.add(injectAt++, Msg.system(taskSnapshot));
            }
            if (factsSnapshot != null) {
                System.out.println(Console.header("[" + tag() + "事实账本已注入] ") + facts.size() + " 条");
                callMessages.add(injectAt, Msg.system(factsSnapshot));
            }

            LlmResponse resp = llm.call(registry.definitions(), callMessages);
            if (streaming) {
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
                // 纯文本回复不结束任务：视为隐式提交，同样走 LLM 终止校验，通过才是最终答案
                String candidate = resp.text();
                String baseline = tasks.goal() != null ? tasks.goal() : seed;
                FinalVerifier.Verdict verdict = verifier.verify(baseline, tasks.coreNeeds(),
                        tasks.snapshot(), facts.snapshot(), candidate);
                if (verdict.pass()) {
                    tasks.draft(null); // 本版通过，旧草稿使命结束
                    System.out.println("[" + tag() + "最终校验] 通过");
                    return pass(candidate);
                }
                String defects = String.join("\n", verdict.defects());
                System.out.println("\n[" + tag() + "最终校验] 未通过：\n" + defects);
                tasks.draft(candidate);
                if (++failStreak >= 3) {
                    System.out.println(Console.warn("[警告] " + tag() + "最终校验连续 " + failStreak
                            + " 次未通过，best-effort 返回当前答案"));
                    return bestEffort(candidate, defects);
                }
                replan(baseline, defects);
                messages.add(Msg.assistant(resp.blocks()));
                messages.add(Msg.user(defectFeedback(false, defects)));
                if (!candidate.isBlank()) {
                    lastText = candidate;
                }
                transcript.append("助手: ").append(candidate).append('\n');
                continue;
            }
            for (Block.ToolUse u : toolCalls) {
                System.out.println(Console.tool("[" + tag() + "调用工具] " + u.name() + " " + u.input()));
            }

            // 完整内容块（thinking/text/tool_use）原样追加为 assistant 消息（含思考回传）
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

            // 依次执行全部工具调用，每个结果作为一条独立的 tool 消息回传；对话稿写入结果正文（截断保数值与链接）
            String finalAnswer = null;
            for (Block.ToolUse call : toolCalls) {
                ToolRegistry.ToolOutput out = registry.run(call);
                System.out.println("[" + tag() + "工具结果]\n" + preview(out.content()));
                messages.add(Msg.tool(new Block.ToolResult(call.id(), out.content(), out.isError())));
                transcript.append("  [").append(call.name()).append(" 结果] ")
                        .append(body(out.content())).append('\n');
                if (FinalAnswerTool.NAME.equals(call.name())) {
                    finalAnswer = out.content();
                }
            }

            // 终止工具被调用：结果已回传，仍要做 LLM 终止校验；未通过则缺陷反馈 +（父）强制重规划
            if (finalAnswer != null) {
                String baseline = tasks.goal() != null ? tasks.goal() : seed;
                FinalVerifier.Verdict verdict = verifier.verify(baseline, tasks.coreNeeds(),
                        tasks.snapshot(), facts.snapshot(), finalAnswer);
                if (verdict.pass()) {
                    tasks.draft(null); // 本版通过，旧草稿使命结束
                    System.out.println("[" + tag() + "最终校验] 通过");
                    return pass(finalAnswer);
                }
                String defects = String.join("\n", verdict.defects());
                System.out.println("\n[" + tag() + "最终校验] 未通过：\n" + defects);
                tasks.draft(finalAnswer);
                // 连续 3 次校验失败且中间无干活轮次：best-effort 返回当前最优答案，避免死循环
                if (++failStreak >= 3) {
                    System.out.println(Console.warn("[警告] " + tag() + "最终校验连续 " + failStreak
                            + " 次未通过，best-effort 返回当前答案"));
                    return bestEffort(finalAnswer, defects);
                }
                replan(baseline, defects);
                messages.add(Msg.user(defectFeedback(true, defects)));
                continue;
            }
            failStreak = 0; // 干活轮次（非提交）重置校验连败计数

            // 以上次响应 input token 判断是否压缩（零额外调用）：丢弃全部历史（无 tool_use/tool_result 配对风险），
            // 以「述求/简报 + 进度摘要 + 任务与账本快照 + 草稿答案」重建，核心信息不丢
            if (resp.inputTokens() > CONTEXT_TOKEN_THRESHOLD) {
                System.out.println("\n[" + tag() + "上下文压缩] inputTokens=" + resp.inputTokens()
                        + " 超过阈值 " + CONTEXT_TOKEN_THRESHOLD + "，开始压缩…");
                String summary = quietLlm.summarize(transcript.toString());
                System.out.println("[" + tag() + "上下文压缩] 摘要:\n" + summary);
                // 快照压缩时现算——本轮工具调用可能刚更新过 Store，不能用轮首旧值
                StringBuilder rebuilt = new StringBuilder(seedLabel()).append("：\n").append(seed);
                String goal = tasks.goal();
                if (goal != null && !goal.equals(seed)) {
                    rebuilt.append("\n\n当前任务目标（经重新规划调整）：\n").append(goal);
                }
                rebuilt.append("\n\n之前的执行进度摘要：\n").append(summary);
                appendSnapshot(rebuilt, tasks.snapshot());
                appendSnapshot(rebuilt, facts.snapshot());
                appendSnapshot(rebuilt, tasks.draft() == null ? null
                        : "上一版草稿答案（未通过校验，需修正）：\n" + tasks.draft());
                rebuilt.append("\n\n").append(resumeInstruction());
                messages = new ArrayList<>(List.of(
                        Msg.system(persona()), Msg.user(rebuilt.toString())));
                transcript = new StringBuilder("用户: ").append(rebuilt).append('\n');
            }
        }
        return exhausted(lastText);
    }

    /** 压缩重建时附加核心状态快照（空快照跳过，快照自带标题头）。 */
    private static void appendSnapshot(StringBuilder sb, String snapshot) {
        if (snapshot != null) {
            sb.append("\n\n").append(snapshot);
        }
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

    /** 控制台打印工具结果正文：保留换行与结构，仅对超长输出截断并标注总长。 */
    private static String preview(String content) {
        String s = content.strip();
        return s.length() <= CONSOLE_TOOL_BODY_LIMIT ? s
                : s.substring(0, CONSOLE_TOOL_BODY_LIMIT)
                + "\n…（工具结果共 " + s.length() + " 字符，已截断）";
    }

    /** 对话稿中的工具结果正文：保住数值与来源链接所需的长度，超出截断。 */
    private static String body(String content) {
        String s = content.strip();
        return s.length() <= TRANSCRIPT_TOOL_BODY_LIMIT ? s
                : s.substring(0, TRANSCRIPT_TOOL_BODY_LIMIT) + "…（截断）";
    }
}
