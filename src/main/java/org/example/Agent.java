package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Agent loop：
 * 轮次上限 → 注入任务进度、事实账本与工程备忘快照 → LLM（人格 + 工具元信息 + 对话与思考）→
 * 依次执行工具收集结果（正文写入对话稿）→ 无工具调用时对照任务述求与事实账本做最终校验，通过即返回 →
 * 超阈值压缩上下文（重建时显式携带原始述求、进度摘要与三份核心状态快照）。
 */
public class Agent {
    static final int MAX_ROUNDS = 120;
    /** 上一次响应 inputTokens 超过该值即触发上下文压缩（演示时可调小）。 */
    static final int CONTEXT_TOKEN_THRESHOLD = 60_000;
    /** 对话稿中每条工具结果正文的最大保留长度（足够保住数值与链接，又不让稿子膨胀）。 */
    static final int TRANSCRIPT_TOOL_BODY_LIMIT = 1_500;

    /** 最终校验提示词：对照任务述求与事实账本检查草稿，首行 PASS / FAIL，其后逐条缺陷。 */
    private static final String VERIFY_PROMPT = """
            你是答案校验器。对照「任务述求」与「事实账本」检查「草稿回答」：
            1. 是否回答了述求所问：不答非所问，不遗漏要查的关键数据。
            2. 草稿中每个数据、时期与结论是否与事实账本一致：账本已有而草稿遗漏、草稿数值与账本不符、
               草稿声称未找到而账本中已有，均为缺陷。
            3. 关键事实是否附有来源。
            输出格式：第一行只能是 PASS（通过则只输出这一行）或 FAIL，其后逐行列出缺陷（具体、可执行）。
            """;

    private final Config.Data cfg;
    private final LlmClient llm;
    private final LlmClient quietLlm; // 无工具、非流式：最终校验等嵌套调用用
    private final ToolRegistry registry;
    private final TaskStore tasks;
    private final FactsStore facts;
    private final NotesStore notes;

    public Agent() {
        this.cfg = Config.load();
        this.llm = LlmClient.create(cfg.llm());
        this.quietLlm = LlmClient.create(new Config.Llm(cfg.llm().provider(), cfg.llm().baseUrl(),
                cfg.llm().model(), cfg.llm().apiKey(), cfg.llm().maxTokens(), cfg.llm().temperature(), false));
        this.tasks = new TaskStore();
        this.facts = new FactsStore();
        this.notes = new NotesStore();
        this.registry = new ToolRegistry(cfg, tasks, facts, notes);
    }

    public String run(String request) {
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.system(SystemPrompt.PERSONA));
        messages.add(Msg.user(request));
        // 压缩用：剔除 tool_result 的纯文本对话稿（边执行边累积）
        StringBuilder transcript = new StringBuilder("用户: ").append(request).append('\n');

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            System.out.println("\n" + Console.header("======== 第 " + round + "/" + MAX_ROUNDS + " 轮 ========"));

            // LLM 看不到 TaskStore / FactsStore / NotesStore 外部状态 → 每轮重算三个快照，
            // 作为新系统块注入（人格之后、对话之前）；只放进本次调用的副本，messages 不留旧快照，
            // 天然无陈旧堆积、压缩重建也无需处理
            String snapshot = tasks.snapshot();
            String factsSnapshot = facts.snapshot();
            String notesSnapshot = notes.snapshot();
            List<Msg> callMessages = messages;
            if (snapshot != null || factsSnapshot != null || notesSnapshot != null) {
                if (snapshot != null) {
                    System.out.println(Console.header("[任务快照已注入] ") + tasks.progress());
                }
                if (factsSnapshot != null) {
                    System.out.println(Console.header("[事实账本已注入] ") + facts.size() + " 条");
                }
                if (notesSnapshot != null) {
                    System.out.println(Console.header("[工程备忘已注入] ") + notes.size() + " 条");
                }
                callMessages = new ArrayList<>(messages);
                int injectAt = 1;
                if (snapshot != null) {
                    callMessages.add(injectAt++, Msg.system(snapshot));
                }
                if (factsSnapshot != null) {
                    callMessages.add(injectAt++, Msg.system(factsSnapshot));
                }
                if (notesSnapshot != null) {
                    callMessages.add(injectAt, Msg.system(notesSnapshot));
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
                // 最终校验：有账本时对照检查「结论 ↔ 过程」矛盾——
                // 草稿说未找到而账本里有、账本有而草稿遗漏等在此拦截
                String ledger = facts.snapshot();
                if (!candidate.isBlank() && ledger != null) {
                    // 校验对照当前基线：未重规划过即原始述求，重规划调整后以最新目标为准
                    String baseline = tasks.goal() != null ? tasks.goal() : request;
                    String verdict = verify(baseline, ledger, candidate);
                    if (!firstLine(verdict).toUpperCase(Locale.ROOT).startsWith("PASS")) {
                        System.out.println("\n[最终校验] 未通过：\n" + verdict);
                        messages.add(Msg.assistant(resp.blocks()));
                        messages.add(Msg.user("你的回答未通过最终校验，存在以下缺陷：\n" + verdict
                                + "\n\n请修正后【重新输出完整的最终回答】——不要只输出补丁，"
                                + "必须完整覆盖述求所问；必要时继续检索，新数据先 record_facts 入账。"));
                        transcript.append("助手: ").append(candidate).append('\n');
                        continue;
                    }
                    System.out.println("[最终校验] 通过");
                }
                return candidate; // 无工具调用 → 校验通过（或无账本可对照），即最终答案
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
            // 对话稿写入结果正文（截断保数值与链接）：压缩摘要才有数据可保
            for (Block.ToolUse call : toolCalls) {
                ToolRegistry.ToolOutput out = registry.run(call);
                // analyze_query 的规划 JSON 完整可见；其余按预览截断
                boolean full = "analyze_query".equals(call.name());
                String printed = full ? out.content() : preview(out.content());
                System.out.println("[工具结果] " + printed);
                messages.add(Msg.tool(new Block.ToolResult(call.id(), out.content(), out.isError())));
                transcript.append("  [").append(call.name()).append(" 结果] ")
                        .append(body(out.content())).append('\n');
            }

            // 以上次响应 input token 判断是否压缩（零额外调用）。
            // 任务进度、事实、工程备忘重建时显式内联三份快照（见下），不再只依赖下一轮注入
            if (resp.inputTokens() > CONTEXT_TOKEN_THRESHOLD) {
                System.out.println("\n[上下文压缩] inputTokens=" + resp.inputTokens()
                        + " 超过阈值 " + CONTEXT_TOKEN_THRESHOLD + "，开始压缩…");
                // 无工具单次调用总结（直接发原 messages 会因 tool_use 缺 tool_result 报错）
                String summary = llm.summarize(transcript.toString());
                System.out.println("[上下文压缩] 摘要:\n" + summary);
                // 重建显式保留核心信息：原始述求（重规划调整过再附当前目标）+ 进度摘要 + 三份状态快照。
                // 快照压缩时现算——本轮工具调用可能刚更新过 Store，不能用轮首旧值
                StringBuilder rebuilt = new StringBuilder("原始任务述求：\n").append(request);
                String goal = tasks.goal();
                if (goal != null && !goal.equals(request)) {
                    rebuilt.append("\n\n当前任务目标（经重新规划调整）：\n").append(goal);
                }
                rebuilt.append("\n\n之前的执行进度摘要：\n").append(summary);
                appendSnapshot(rebuilt, tasks.snapshot());
                appendSnapshot(rebuilt, facts.snapshot());
                appendSnapshot(rebuilt, notes.snapshot());
                rebuilt.append("\n\n请基于以上进度继续完成任务；最终答案的数据以事实账本为准。");
                messages = new ArrayList<>(List.of(
                        Msg.system(SystemPrompt.PERSONA), Msg.user(rebuilt.toString())));
                transcript = new StringBuilder("用户: ").append(rebuilt).append('\n');
            }
        }
        throw new IllegalStateException("超过最大轮次 " + MAX_ROUNDS + "，任务未完成");
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

    /** 取校验器输出首行作为分类标签（PASS / FAIL）。 */
    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int i = text.indexOf('\n');
        return (i < 0 ? text : text.substring(0, i)).strip();
    }

    /** 最终校验：quiet 客户端对照述求与账本检查草稿，返回 PASS 或缺陷清单。 */
    private String verify(String goal, String ledger, String draft) {
        return quietLlm.call(List.of(),
                List.of(Msg.system(VERIFY_PROMPT),
                        Msg.user("任务述求：\n" + goal
                                + "\n\n事实账本：\n" + ledger
                                + "\n\n草稿回答：\n" + draft))).text();
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
