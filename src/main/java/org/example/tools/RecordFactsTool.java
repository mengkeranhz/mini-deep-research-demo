package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.FactsStore;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * record_facts：把检索到的关键数据写入事实账本（维度×时期×口径 → 值/来源/状态）。
 * 账本是最终答案的结算依据——压缩与校验都以它为准，未入账的数据随时可能随上下文丢失。
 */
public class RecordFactsTool implements AgentTool {

    private static final Set<String> STATUSES = Set.of("found", "proxy", "not_found");

    private final FactsStore facts;

    public RecordFactsTool(FactsStore facts) {
        this.facts = facts;
    }

    @Override
    public String name() {
        return "record_facts";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "把检索到的关键数据写入事实账本。每得到一条可用数据就立即入账"
                        + "（不要攒到最后批量补）；最终答案的全部数据必须来自账本。"
                        + "status: found=官方或已交叉核验；proxy=代理指标/第三方折算（note 写折算方法）；"
                        + "not_found=确认检索不到（note 必须写明已尝试的检索关键词与来源，否则视为放弃过早）。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "facts", Map.of("type", "array", "description", "本次入账的事实数组",
                                        "items", Map.of("type", "object",
                                                "properties", Map.of(
                                                        "dimension", Map.of("type", "string",
                                                                "description", "指标维度，如 GDP增速、常住人口"),
                                                        "period", Map.of("type", "string",
                                                                "description", "时期，如 2024、2024-Q1、2024-H1、2024-Q1-3（前三季度）"),
                                                        "metric", Map.of("type", "string",
                                                                "description", "统计口径，如 规模以上工业；单一口径可留空"),
                                                        "value", Map.of("type", "string",
                                                                "description", "数值含单位，如 34606亿元、+5.1%；not_found 留空"),
                                                        "source", Map.of("type", "string",
                                                                "description", "来源链接或来源名；not_found 可留空"),
                                                        "status", Map.of("type", "string",
                                                                "enum", List.of("found", "proxy", "not_found"),
                                                                "description", "数据状态"),
                                                        "note", Map.of("type", "string",
                                                                "description", "口径说明/折算方法；not_found 时写已尝试的检索关键词与来源")),
                                                "required", List.of("dimension", "period", "status")))),
                        "required", List.of("facts")));
    }

    @Override
    public String execute(JsonNode input) {
        JsonNode arr = input.path("facts");
        if (!arr.isArray() || arr.isEmpty()) {
            return "参数 facts 必须是非空数组，每项含 dimension、period、status（可选 metric/value/source/note）";
        }
        List<String> rejected = new ArrayList<>();
        List<String> offTarget = new ArrayList<>();
        List<String> offDimension = new ArrayList<>();
        int accepted = 0;
        for (JsonNode n : arr) {
            String dimension = ToolRegistry.optStr(n, "dimension");
            String period = ToolRegistry.optStr(n, "period");
            String status = normalize(ToolRegistry.optStr(n, "status"));
            String note = ToolRegistry.optStr(n, "note");
            if (dimension == null || period == null) {
                rejected.add("缺 dimension 或 period: " + abbreviate(n));
                continue;
            }
            if (status == null) {
                rejected.add("status 无效（可选 found/proxy/not_found）: " + abbreviate(n));
                continue;
            }
            if ("not_found".equals(status) && (note == null || note.isBlank())) {
                rejected.add("[" + dimension + "@" + period + "] not_found 必须在 note 写明已尝试的检索关键词与来源");
                continue;
            }
            facts.record(new FactsStore.Fact(dimension, period,
                    orEmpty(ToolRegistry.optStr(n, "metric")),
                    orEmpty(ToolRegistry.optStr(n, "value")),
                    orEmpty(ToolRegistry.optStr(n, "source")),
                    status, orEmpty(note)));
            accepted++;
            // 两类标签错位提示互斥：dimension 精确命中才校验 period；未命中则查近似目标（对称提示）
            if (!facts.hitsTarget(dimension, period)) {
                offTarget.add(dimension + "@" + period);
            } else {
                List<String> near = facts.nearTargets(dimension);
                if (!near.isEmpty()) {
                    offDimension.add(dimension + "（疑似应为 " + String.join("、", near) + "）");
                }
            }
        }
        StringBuilder sb = new StringBuilder("已入账 ").append(accepted).append(" 条");
        if (!rejected.isEmpty()) {
            sb.append("；被拒绝 ").append(rejected.size()).append(" 条:\n").append(String.join("\n", rejected));
        }
        if (!offTarget.isEmpty()) {
            sb.append("\n注意: ").append(offTarget.size())
                    .append(" 条的 dimension 已有覆盖目标、但 period 不在目标时期内（")
                    .append(String.join("、", offTarget))
                    .append("）——若这正是目标数据，请照抄覆盖目标的 period 字符串重新入账（同 key 覆盖旧值），否则终答覆盖闸门将报缺口");
        }
        if (!offDimension.isEmpty()) {
            sb.append("\n注意: ").append(offDimension.size())
                    .append(" 条的 dimension 未精确命中覆盖目标、但与其近似（")
                    .append(String.join("、", offDimension))
                    .append("）——若这正是目标数据，请照抄覆盖目标的 dimension 字符串重新入账（同 key 覆盖旧值），否则终答覆盖闸门将报缺口");
        }
        return sb.append('\n').append(facts.coverageLine()).toString();
    }

    /** 状态归一：空白默认 found（大多数入账是刚检索到的可用数据）。 */
    private static String normalize(String status) {
        if (status == null || status.isBlank()) {
            return "found";
        }
        String s = status.strip();
        return STATUSES.contains(s) ? s : null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s.strip();
    }

    private static String abbreviate(JsonNode n) {
        String one = n.toString().replaceAll("\\s+", " ");
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }
}
