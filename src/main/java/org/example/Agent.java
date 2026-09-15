package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.tools.CurrentTimeTool;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 父 Agent Loop（编排器）：规划（analyze_query）→ 派发（execute_task，每次新建子代理执行）→
 * 综合事实账本与子代理结果输出最终答案。无工具调用轮对照述求与账本做最终校验：
 * 未通过则程序化重新规划（缺陷注入、进度继承），连续 3 次未通过 best-effort 返回；
 * 超阈值压缩上下文（重建时显式携带原始述求、进度摘要、任务与账本快照、草稿答案）。
 */
public class Agent {
    static final int MAX_ROUNDS = 120;
    /** 上一次响应 inputTokens 超过该值即触发上下文压缩（演示时可调小）。 */
    static final int CONTEXT_TOKEN_THRESHOLD = 60_000;
    /** 对话稿中每条工具结果正文的最大保留长度（足够保住数值与链接，又不让稿子膨胀）。 */
    static final int TRANSCRIPT_TOOL_BODY_LIMIT = 1_500;

    /** 最终校验提示词：对照任务述求、计划与事实账本检查草稿，首行 PASS / FAIL，其后逐条缺陷。 */
    private static final String VERIFY_PROMPT = """
            你是答案校验器。对照「任务述求」「核心述求」「计划」「事实账本」检查「草稿回答」：
            1. 述求与计划是否完成：不答非所问；核心述求逐条核对，草稿未回答到位的算缺陷；计划中未完成的任务、未解决的未知，草稿未处理且未说明原因的算缺陷；如实说明经充分尝试仍不可得并给出原因或替代方案的，不算缺陷。
            2. 是否遵守计划中的约束。
            3. 数据与结论是否与账本一致：账本已有而草稿遗漏、数值与账本不符、草稿声称未找到而账本已有，均算缺陷。
            4. 关键事实是否附有来源。
            账本自身对同一指标、同一时期存在矛盾数值时不算草稿缺陷；草稿点出该矛盾并说明取舍更佳。
            只输出一个 JSON 对象，不要解释、不要 markdown 围栏、不要任何其它文字：
            - 通过：{"verdict":"PASS"}
            - 不通过：{"verdict":"FAIL","defects":["缺陷1","缺陷2"]}，defects 至少一条，每条是一句可执行的修改意见。
            """;

    /** 解析校验器 JSON 输出复用。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Config.Data cfg;
    private final LlmClient llm;
    private final LlmClient quietLlm; // 无工具、非流式：最终校验等嵌套调用用
    private final ToolRegistry registry;
    private final TaskStore tasks;
    private final FactsStore facts;
    /** 最终校验连续失败计数：任何干活（调用工具）轮次重置，达到 3 次即 best-effort 返回。 */
    private int failStreak = 0;

    public Agent() {
        this.cfg = Config.load();
        this.llm = LlmClient.create(cfg.llm());
        this.quietLlm = LlmClient.create(new Config.Llm(cfg.llm().provider(), cfg.llm().baseUrl(),
                cfg.llm().model(), cfg.llm().apiKey(), cfg.llm().maxTokens(), cfg.llm().temperature(), false));
        this.tasks = new TaskStore();
        this.facts = new FactsStore();
        // 纯编排器：检索/取数/入账全部下沉子代理；保留 run_code 与 current_time 供综合期计算与日期核对；
        this.registry = new ToolRegistry(cfg, tasks, facts, Set.of(
                "web_search", "locate_sources", "fetch_url", "read_file", "search_place", "search_nearby",
                "route_query", "record_facts"));
    }

    public String run(String request) {
        tasks.original(request); // 子代理简报锚点：无论是否规划，原始述求常驻（重规划不清）
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.system(SystemPrompt.PERSONA));
        messages.add(Msg.user(request));
        // 压缩用：剔除 tool_result 的纯文本对话稿（边执行边累积）
        StringBuilder transcript = new StringBuilder("用户: ").append(request).append('\n');

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            System.out.println("\n" + Console.header("======== 第 " + round + "/" + MAX_ROUNDS + " 轮 ========"));

            // LLM 看不到外部时钟与 TaskStore / FactsStore 外部状态 → 每轮注入当前时间并重算两个快照，
            // 作为新系统块注入（人格之后、对话之前）；只放进本次调用的副本，messages 不留旧块，
            // 天然无陈旧堆积、压缩重建也无需处理
            String snapshot = tasks.snapshot();
            String factsSnapshot = facts.snapshot();
            List<Msg> callMessages = new ArrayList<>(messages);
            int injectAt = 1;
            callMessages.add(injectAt++, Msg.system(CurrentTimeTool.nowText(ZoneId.systemDefault())));
            if (snapshot != null) {
                System.out.println(Console.header("[任务快照已注入] ") + tasks.progress());
                callMessages.add(injectAt++, Msg.system(snapshot));
            }
            if (factsSnapshot != null) {
                System.out.println(Console.header("[事实账本已注入] ") + facts.size() + " 条");
                callMessages.add(injectAt, Msg.system(factsSnapshot));
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
                // 最终校验：有账本或计划时对照检查「结论 ↔ 过程」矛盾——
                // 草稿说未找到而账本里有、账本有而草稿遗漏、计划未完成或未知未解决等在此拦截
                String ledger = facts.snapshot();
                String plan = tasks.snapshot();
                if (!candidate.isBlank() && (ledger != null || plan != null)) {
                    // 校验对照当前基线：未重规划过即原始述求，重规划调整后以最新目标为准
                    String baseline = tasks.goal() != null ? tasks.goal() : request;
                    Verdict verdict = verify(baseline, tasks.coreNeeds(), plan, ledger, candidate);
                    if (!verdict.pass()) {
                        String defects = String.join("\n", verdict.defects());
                        System.out.println("\n[最终校验] 未通过：\n" + defects);
                        tasks.draft(candidate);
                        // 连续 3 次校验失败且中间无干活轮次：best-effort 返回当前最优答案，避免死循环
                        if (++failStreak >= 3) {
                            System.out.println(Console.warn("[警告] 最终校验连续 " + failStreak
                                    + " 次未通过，best-effort 返回当前答案"));
                            return candidate + "\n\n（注意：本回答未通过最终校验，遗留缺陷：\n" + defects + "\n）";
                        }
                        // 确定性重规划：程序化调用 analyze_query（缺陷注入，进度与账本继承，只补剩余工作）；
                        // 失败则降级为仅反馈缺陷，不阻断
                        try {
                            ToolRegistry.AgentTool analyzer = registry.tool("analyze_query");
                            if (analyzer != null) {
                                String replanned = analyzer.execute(JSON.createObjectNode().put("query",
                                        baseline + "\n上一版回答未通过最终校验，缺陷：\n" + defects
                                                + "\n请结合缺陷与事实账本重新规划，只补剩余工作"));
                                System.out.println("[强制重规划]\n" + replanned);
                            }
                        } catch (Exception e) {
                            System.out.println(Console.warn("[强制重规划失败，仅反馈缺陷] " + e));
                        }
                        messages.add(Msg.assistant(resp.blocks()));
                        messages.add(Msg.user("你的回答未通过最终校验，存在以下缺陷：\n" + defects
                                + "\n\n已按缺陷重新规划任务清单（见任务进度快照）。请用 execute_task 执行剩余待办任务"
                                + "（子代理检索取数并入账），然后【重新输出完整的最终回答】——不要只输出补丁，"
                                + "必须完整覆盖述求所问。"));
                        transcript.append("助手: ").append(candidate).append('\n');
                        continue;
                    }
                    tasks.draft(null); // 本版通过，旧草稿使命结束
                    System.out.println("[最终校验] 通过");
                }
                return candidate; // 无工具调用 → 校验通过（或无账本可对照），即最终答案
            }
            failStreak = 0; // 任何干活轮次重置校验连败计数
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
            // 任务进度、事实重建时显式内联两份快照（见下），不再只依赖下一轮注入
            if (resp.inputTokens() > CONTEXT_TOKEN_THRESHOLD) {
                System.out.println("\n[上下文压缩] inputTokens=" + resp.inputTokens()
                        + " 超过阈值 " + CONTEXT_TOKEN_THRESHOLD + "，开始压缩…");
                // 无工具单次调用总结（直接发原 messages 会因 tool_use 缺 tool_result 报错）
                String summary = llm.summarize(transcript.toString());
                System.out.println("[上下文压缩] 摘要:\n" + summary);
                // 重建显式保留核心信息：原始述求（重规划调整过再附当前目标）+ 进度摘要 + 两份状态快照。
                // 快照压缩时现算——本轮工具调用可能刚更新过 Store，不能用轮首旧值
                StringBuilder rebuilt = new StringBuilder("原始任务述求：\n").append(request);
                String goal = tasks.goal();
                if (goal != null && !goal.equals(request)) {
                    rebuilt.append("\n\n当前任务目标（经重新规划调整）：\n").append(goal);
                }
                rebuilt.append("\n\n之前的执行进度摘要：\n").append(summary);
                appendSnapshot(rebuilt, tasks.snapshot());
                appendSnapshot(rebuilt, facts.snapshot());
                appendSnapshot(rebuilt, tasks.draft() == null ? null
                        : "上一版草稿答案（未通过校验，需修正）：\n" + tasks.draft());
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

    /** 非流式模式下的统一打印：思考与结论文本（包可见，SubAgent 复用）。 */
    static void printBlocks(LlmResponse resp) {
        for (Block b : resp.blocks()) {
            if (b instanceof Block.Thinking t) {
                System.out.println(Console.thinking("[思考] " + t.thinking()));
            } else if (b instanceof Block.Text t) {
                System.out.println("[输出] " + t.text());
            }
        }
    }

    /** 校验器结构化判定：pass=true 通过；pass=false 且 defects 非空则需修正。 */
    private record Verdict(boolean pass, List<String> defects) {}

    /** 最终校验：quiet 客户端对照述求、计划与账本检查草稿，返回结构化判定；解析失败重试一次后仍失败则放行，避免死循环。 */
    private Verdict verify(String goal, List<String> coreNeeds, String plan, String ledger, String draft) {
        String q = buildVerifyQuery(goal, coreNeeds, plan, ledger, draft);
        Verdict v = parseVerdict(quietLlm.call(List.of(), List.of(Msg.system(VERIFY_PROMPT), Msg.user(q))).text());
        if (v != null) {
            return v;
        }
        // 未按格式输出：追加更明确的格式约束重试一次，仍解析不了就放行（避免空反馈死循环）
        String raw = quietLlm.call(List.of(), List.of(Msg.system(VERIFY_PROMPT),
                Msg.user(q + "\n\n上次你没有按格式输出 JSON。请只输出一个 JSON 对象：通过为 {\"verdict\":\"PASS\"}，"
                        + "不通过为 {\"verdict\":\"FAIL\",\"defects\":[\"...\"]}，且 FAIL 时 defects 至少一条。"))).text();
        Verdict v2 = parseVerdict(raw);
        return v2 != null ? v2 : new Verdict(true, List.of());
    }

    /** 组装校验对照内容：述求 + 核心述求 + 计划 + 账本 + 草稿。 */
    private static String buildVerifyQuery(String goal, List<String> coreNeeds, String plan, String ledger, String draft) {
        StringBuilder q = new StringBuilder("任务述求：\n").append(goal);
        if (coreNeeds != null && !coreNeeds.isEmpty()) {
            q.append("\n\n核心述求：\n").append(String.join("\n", coreNeeds));
        }
        if (plan != null && !plan.isBlank()) {
            q.append("\n\n计划与进度：\n").append(plan);
        }
        if (ledger != null && !ledger.isBlank()) {
            q.append("\n\n事实账本：\n").append(ledger);
        }
        q.append("\n\n草稿回答：\n").append(draft);
        return q.toString();
    }

    /** 解析校验器 JSON：取首个 { 到末个 } 片段；FAIL 但无缺陷视为无法判定（返回 null，交由调用方放行）。 */
    private static Verdict parseVerdict(String text) {
        if (text == null) {
            return null;
        }
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        if (s < 0 || e <= s) {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(text.substring(s, e + 1));
        } catch (Exception ex) {
            return null;
        }
        String verdict = root.path("verdict").asText("").strip();
        boolean pass = "PASS".equalsIgnoreCase(verdict);
        if (!pass && !"FAIL".equalsIgnoreCase(verdict)) {
            return null;
        }
        if (pass) {
            return new Verdict(true, List.of());
        }
        List<String> defects = new ArrayList<>();
        JsonNode arr = root.path("defects");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                String d = n.asText("").strip();
                if (!d.isEmpty()) {
                    defects.add(d);
                }
            }
        }
        return defects.isEmpty() ? null : new Verdict(false, defects);
    }

    /** 控制台预览工具结果前 200 字符（包可见，SubAgent 复用）。 */
    static String preview(String content) {
        String oneLine = content.replaceAll("\\s+", " ");
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    /** 对话稿中的工具结果正文：保住数值与来源链接所需的长度，超出截断（包可见，SubAgent 复用）。 */
    static String body(String content) {
        String s = content.strip();
        return s.length() <= TRANSCRIPT_TOOL_BODY_LIMIT ? s
                : s.substring(0, TRANSCRIPT_TOOL_BODY_LIMIT) + "…（截断）";
    }
}
