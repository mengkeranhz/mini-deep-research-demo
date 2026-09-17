package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 最终校验器：用无工具、非流式的 quiet 客户端，对照任务述求（经调整时附原始述求审计）、
 * 核心述求、计划与事实账本检查提交的最终答案，给出结构化 PASS / FAIL 判定。父 Agent 与子 Agent 共用。
 */
final class FinalVerifier {

    /** 校验提示词：对照「任务述求」（可能附「原始述求」）、「核心述求」「计划」「事实账本」检查「草稿回答」，
     *  首行 PASS / FAIL，其后逐条缺陷；否定性结论与基线调整以账本证据裁决。 */
    private static final String PROMPT = """
            你是答案校验器。你的唯一职责是：严格依据【输入材料】核验【草稿回答】，不得使用外部知识补全，不得自行联网，不得替草稿补来源。若材料不足以核验，视为不通过。
            
            【核验维度】
            1. 诉求与计划完成
            - 是否直接回答课题，不答非所问。
            - 是否完成所选场景要求的核心交付物。
            - 是否按计划执行；计划未完成、跳过关键步骤或擅自改变任务目标，均不通过。
            
            2. 约束遵守
            - 是否遵守课题硬性要求：真实调用外部工具，不能仅靠大模型内置记忆作答。
            - 是否遵守用户/计划中的约束。
            - 是否明确标注数据日期、抓取日期或截止时间。
            
            3. 数据与结论是否与账本一致
            - 草稿中的每个关键数据、事实、结论、计算，必须能映射到账本中的工具调用、网页摘录、API 返回或计算记录。
            - 账本没有支持的断言，视为幻觉。
            
            4. 关键事实是否附有来源
            - 关键事实必须被引用在正确的章节或段落中，不得混淆。
            - 关键事实与数据等，必须附来源。
            - 引用链接必须与账本中的抓取记录匹配，且真实、可点击、来源权威、时效合适。
            - 引用内容必须支持对应陈述，不得歪曲、拼接或过度推断。
            - 仅有 URL 文本、无抓取记录或无法与账本核对的来源，不算通过。
            
            5. 逻辑与表达
            - 结论之间不得自相矛盾。
            - 表格、排名、计算、时间线、空间顺序必须内部一致。
            - 不确定、缺失或冲突信息应被明示，不得伪装成确定结论。
            - 各章节有中心论点。
            - 事实是否服务于论点。
            - 展示可靠推理，而不只是简单罗列事实。
            
            【硬性失败项】
            任一出现即判 FAIL：
            - 未真实调用外部工具，或结论仅来自模型记忆。
            - 关键事实、数据、标准、风险结论无来源，或来源与账本不符。
            - 引用链接不可点击、无抓取记录、与陈述不匹配。
            - 数据、日期、单位、计算与账本冲突。
            - 计算错误、基期错误、排名方向错误。
            - 场景核心交付物缺失，或日程/规划存在逻辑冲突。
            - 答非所问，或违反计划与课题约束。
            
            【判定规则】
            - 所有核验维度均通过，才输出 PASS。
            - 任一硬性失败项出现，输出 FAIL。
            - FAIL 时 defects 至少一条，每条必须是一句可执行的修改意见，格式应指出：位置 + 问题 + 应如何补证/修正。
            - 不得因为草稿语气自信、格式完整或链接看似合理而 PASS。
            
            【输出】
            只输出一个 JSON 对象，不要解释、不要 markdown 围栏、不要任何其它文字：
            - 通过：{"verdict":"PASS"}
            - 不通过：{"verdict":"FAIL","defects":["缺陷1","缺陷2"]}
            defects 至少一条，每条是一句可执行的修改意见。
            """;

    /** 解析校验器 JSON 输出复用。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmClient quiet;

    FinalVerifier(LlmClient quiet) {
        this.quiet = quiet;
    }

    /** 校验器结构化判定：pass=true 通过；pass=false 且 defects 非空则需修正。 */
    record Verdict(boolean pass, List<String> defects) {}

    /** 对照原始述求（可空，未调整时不出现）、当前述求、计划与账本检查草稿，返回结构化判定；
     *  解析失败重试一次后仍失败则放行，避免死循环。 */
    Verdict verify(String original, String ledger, String draft) {
        String q = buildQuery(original, ledger, draft);
        Verdict v = parse(quiet.call(List.of(), List.of(Msg.system(PROMPT), Msg.user(q))).text());
        if (v != null) {
            return v;
        }
        // 未按格式输出：追加更明确的格式约束重试一次，仍解析不了就放行（避免空反馈死循环）
        String raw = quiet.call(List.of(), List.of(Msg.system(PROMPT),
                Msg.user(q + "\n\n上次你没有按格式输出 JSON。请只输出一个 JSON 对象：通过为 {\"verdict\":\"PASS\"}，"
                        + "不通过为 {\"verdict\":\"FAIL\",\"defects\":[\"...\"]}，且 FAIL 时 defects 至少一条。"))).text();
        Verdict v2 = parse(raw);
        return v2 != null ? v2 : new Verdict(true, List.of());
    }

    /** 组装校验对照内容 */
    private static String buildQuery(String original, String ledger, String draft) {
        StringBuilder q = new StringBuilder("任务述求：\n").append(original);
        if (ledger != null && !ledger.isBlank()) {
            q.append("\n\n事实账本：\n").append(ledger);
        }
        q.append("\n\n草稿回答：\n").append(draft);
        return q.toString();
    }

    /** 解析校验器 JSON：取首个 { 到末个 } 片段；FAIL 但无缺陷视为无法判定（返回 null，交由调用方放行）。 */
    private static Verdict parse(String text) {
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
}
