package org.example;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事实账本外部存储：record_facts 写入结构化事实（维度×时期×口径 → 值/来源/状态），
 * analyze_query 声明覆盖目标（required_facts）。
 * 与 TaskStore 同理，LLM 看不到本类状态——Agent 每轮注入账本快照；
 * 上下文压缩与最终校验都以账本为准，防止「过程找到、结论遗漏」的矛盾：
 * 结论是账本的结算，不是对话记忆的复述。
 */
public class FactsStore {

    /** 单条事实：status 为 found（官方/已核验）、proxy（代理指标或第三方折算）、not_found（检索未得的缺口声明）。 */
    public record Fact(String dimension, String period, String metric, String value,
                       String source, String status, String note) {}

    /** 覆盖目标：dimension → 需覆盖的时期列表（analyze_query 声明，重规划可追加）。 */
    private final Map<String, List<String>> targets = new LinkedHashMap<>();
    /** 已入账事实：key = dimension|period|metric，同 key 重复入账覆盖旧值。 */
    private final Map<String, Fact> facts = new LinkedHashMap<>();

    /** 声明（或追加）某维度需覆盖的时期；空白与重复时期忽略。 */
    public void require(String dimension, List<String> periods) {
        if (dimension == null || dimension.isBlank()) {
            return;
        }
        List<String> merged = new ArrayList<>(targets.getOrDefault(dimension.strip(), List.of()));
        for (String p : periods) {
            if (p != null && !p.isBlank() && !merged.contains(p.strip())) {
                merged.add(p.strip());
            }
        }
        targets.put(dimension.strip(), merged);
    }

    /** 入账一条事实（同 key 覆盖），返回是否新增或更新了内容。 */
    public boolean record(Fact f) {
        Fact old = facts.put(key(f.dimension(), f.period(), f.metric()), f);
        return !f.equals(old);
    }

    public boolean isEmpty() {
        return facts.isEmpty() && targets.isEmpty();
    }

    /** 每轮注入的紧凑快照：账本条目 + 覆盖缺口 + 入账提醒。 */
    public String snapshot() {
        if (isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("# 事实账本快照（系统每轮自动注入，非用户消息）\n");
        sb.append(counts()).append('\n');
        for (Fact f : facts.values()) {
            sb.append("- [").append(f.dimension()).append("] ").append(f.period());
            if (!f.metric().isBlank()) {
                sb.append(" | ").append(f.metric());
            }
            sb.append(" = ").append(f.value().isBlank() ? "（未获得）" : f.value())
                    .append(" (").append(f.status()).append(")");
            if (!f.source().isBlank()) {
                sb.append(" 来源: ").append(f.source());
            }
            if (!f.note().isBlank()) {
                sb.append("；").append(f.note());
            }
            sb.append('\n');
        }
        List<String> missing = missingPeriods();
        if (!missing.isEmpty()) {
            sb.append("覆盖缺口（目标要求但尚未入账）: ").append(String.join("、", missing)).append('\n');
        }
        sb.append("提醒: 新检索到的数据立即用 record_facts 入账；最终答案的全部数据必须与账本一致。\n");
        return sb.toString();
    }

    /** 完整账本文本：覆盖目标 + 全部事实明细。用于最终校验对照与上下文压缩重建。 */
    public String ledger() {
        if (isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("# 事实账本（record_facts 累积的结构化数据）\n");
        if (!targets.isEmpty()) {
            sb.append("## 覆盖目标\n");
            targets.forEach((d, ps) -> sb.append("- ").append(d).append(": ")
                    .append(String.join("、", ps)).append('\n'));
        }
        sb.append("## 已入账事实（").append(counts()).append("）\n");
        int i = 1;
        for (Fact f : facts.values()) {
            sb.append(i++).append(". [").append(f.dimension()).append("] ").append(f.period());
            if (!f.metric().isBlank()) {
                sb.append(" | ").append(f.metric());
            }
            sb.append(" = ").append(f.value().isBlank() ? "（未获得）" : f.value())
                    .append(" | ").append(f.status());
            if (!f.source().isBlank()) {
                sb.append(" | 来源: ").append(f.source());
            }
            if (!f.note().isBlank()) {
                sb.append(" | ").append(f.note());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 终答硬闸门：返回缺口清单（null 表示通过，无目标或有目标且全覆盖均算通过）。
     * 缺口两类：覆盖目标要求但账本无任何记录的时期；not_found 声明未写明检索方式（放弃过早）。
     */
    public String gateReport() {
        List<String> gaps = new ArrayList<>();
        for (String m : missingPeriods()) {
            gaps.add(m + "：覆盖目标要求但账本中无任何记录");
        }
        for (Fact f : facts.values()) {
            if ("not_found".equals(f.status()) && f.note().isBlank()) {
                gaps.add("[" + f.dimension() + "@" + f.period() + "]"
                        + "：声明未检索到，但未说明已尝试的检索关键词与来源（放弃过早）");
            }
        }
        return gaps.isEmpty() ? null : String.join("\n", gaps);
    }

    /** 一行覆盖度摘要，如「覆盖 10/14 个目标时期；缺口: CPI@2022-Q2、CPI@2022-Q3」。 */
    public String coverageLine() {
        int total = targets.values().stream().mapToInt(List::size).sum();
        if (total == 0) {
            return "未声明覆盖目标（analyze_query 的 required_facts 可声明）";
        }
        List<String> missing = missingPeriods();
        return "覆盖 " + (total - missing.size()) + "/" + total + " 个目标时期"
                + (missing.isEmpty() ? "" : "；缺口: " + String.join("、", missing));
    }

    /** 覆盖目标中尚无任何事实入账的「维度@时期」列表。 */
    private List<String> missingPeriods() {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : targets.entrySet()) {
            for (String p : e.getValue()) {
                boolean hit = facts.values().stream()
                        .anyMatch(f -> e.getKey().equals(f.dimension()) && p.equals(f.period()));
                if (!hit) {
                    missing.add(e.getKey() + "@" + p);
                }
            }
        }
        return missing;
    }

    private String counts() {
        long found = facts.values().stream().filter(f -> "found".equals(f.status())).count();
        long proxy = facts.values().stream().filter(f -> "proxy".equals(f.status())).count();
        long nf = facts.values().stream().filter(f -> "not_found".equals(f.status())).count();
        long other = facts.size() - found - proxy - nf;
        return "已入账 " + facts.size() + " 条（found " + found + "、proxy " + proxy
                + "、not_found " + nf + (other > 0 ? "、其他 " + other : "") + "）";
    }

    private static String key(String dimension, String period, String metric) {
        return nz(dimension) + "|" + nz(period) + "|" + nz(metric);
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }
}
