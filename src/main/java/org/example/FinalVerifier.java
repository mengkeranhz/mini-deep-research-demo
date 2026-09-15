package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 最终校验器：用无工具、非流式的 quiet 客户端，对照任务述求、核心述求、计划与事实账本
 * 检查提交的最终答案，给出结构化 PASS / FAIL 判定。父 Agent 与子 Agent 共用。
 */
final class FinalVerifier {

    /** 校验提示词：对照「任务述求」「核心述求」「计划」「事实账本」检查「草稿回答」，首行 PASS / FAIL，其后逐条缺陷。 */
    private static final String PROMPT = """
            你是答案校验器。对照「任务述求」「核心述求」「计划」「事实账本」检查「草稿回答」：
            1. 述求与计划是否完成：不答非所问；核心述求逐条核对，草稿未回答到位的算缺陷；计划中未完成的任务、未解决的未知，草稿未处理且未说明原因的算缺陷；如实说明经充分尝试仍不可得并给出原因或替代方案的，不算缺陷。
            2. 是否遵守计划中的约束。
            3. 数据与结论是否与账本一致：账本已有而草稿遗漏、数值与账本不符、草稿声称未找到而账本已有，均算缺陷；账本中与本次述求无关的事实（如其他任务的数据）不算缺陷。
            4. 关键事实是否附有来源。
            账本自身对同一指标、同一时期存在矛盾数值时不算草稿缺陷；草稿点出该矛盾并说明取舍更佳。
            只输出一个 JSON 对象，不要解释、不要 markdown 围栏、不要任何其它文字：
            - 通过：{"verdict":"PASS"}
            - 不通过：{"verdict":"FAIL","defects":["缺陷1","缺陷2"]}，defects 至少一条，每条是一句可执行的修改意见。
            """;

    /** 解析校验器 JSON 输出复用。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmClient quiet;

    FinalVerifier(LlmClient quiet) {
        this.quiet = quiet;
    }

    /** 校验器结构化判定：pass=true 通过；pass=false 且 defects 非空则需修正。 */
    record Verdict(boolean pass, List<String> defects) {}

    /** 对照述求、计划与账本检查草稿，返回结构化判定；解析失败重试一次后仍失败则放行，避免死循环。 */
    Verdict verify(String goal, List<String> coreNeeds, String plan, String ledger, String draft) {
        String q = buildQuery(goal, coreNeeds, plan, ledger, draft);
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

    /** 组装校验对照内容：述求 + 核心述求 + 计划 + 账本 + 草稿。 */
    private static String buildQuery(String goal, List<String> coreNeeds, String plan, String ledger, String draft) {
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
