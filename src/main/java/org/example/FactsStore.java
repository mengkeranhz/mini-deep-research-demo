package org.example;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事实账本外部存储：record_facts 写入结构化事实（维度×时期×口径 → 值/来源/状态），
 * analyze_query 声明覆盖目标（required_facts，每个目标分配稳定 id：rf1、rf2…）。
 * 覆盖匹配以「目标 id + 时期」为主（record_facts 的 fact.target 指向目标 id），
 * 维度字符串仅作展示标签；未填 target 时退回维度规范化（去括号限定）匹配兜底。
 * 与 TaskStore 同理，LLM 看不到本类状态——Agent 每轮注入账本快照；
 * 上下文压缩与最终校验都以账本为准，防止「过程找到、结论遗漏」的矛盾：
 * 结论是账本的结算，不是对话记忆的复述。
 */
public class FactsStore {

    /** 覆盖目标：id 稳定（rf1、rf2…），dimension 仅作展示标签，periods 可随重规划追加。 */
    public record Target(String id, String dimension, List<String> periods) {}

    /** 声明结果：id 为该维度稳定 id；newTarget 表示本次是否新建目标；addedPeriods 为本次新增的时期。 */
    public record Requirement(String id, boolean newTarget, List<String> addedPeriods) {}

    /** 单条事实：status 为 found（官方/已核验）、proxy（代理指标或第三方折算）、not_found（检索未得的缺口声明）；
     *  target 指向覆盖目标 id（可空，空则退回维度规范化匹配）。 */
    public record Fact(String dimension, String period, String metric, String value,
                       String source, String status, String note, String target) {}

    /** 覆盖目标：key = dimension.strip()，value = 目标（含稳定 id）。 */
    private final Map<String, Target> targets = new LinkedHashMap<>();
    /** 已入账事实：key = dimension|period|metric，同 key 重复入账覆盖旧值。 */
    private final Map<String, Fact> facts = new LinkedHashMap<>();
    private int nextTargetId = 1;

    /** 声明（或追加）某维度需覆盖的时期；返回稳定 id 与本次增量（新建目标/新增时期），
     *  供调用方在重规划时只回显增量目标，防止全量重复申报导致覆盖目标无限膨胀。空白与重复时期忽略。 */
    public Requirement require(String dimension, List<String> periods) {
        if (dimension == null || dimension.isBlank()) {
            return null;
        }
        String d = dimension.strip();
        Target t = targets.get(d);
        boolean newTarget = t == null;
        if (newTarget) {
            t = new Target("rf" + nextTargetId++, d, new ArrayList<>());
            targets.put(d, t);
        }
        List<String> added = new ArrayList<>();
        for (String p : periods) {
            String ps = p == null ? null : p.strip();
            if (ps != null && !ps.isBlank() && !t.periods().contains(ps)) {
                t.periods().add(ps);
                added.add(ps);
            }
        }
        return new Requirement(t.id(), newTarget, added);
    }

    /** 入账一条事实（同 key 覆盖），返回是否新增或更新了内容。target 指向未知 id 时退回维度兜底。 */
    public boolean record(Fact f) {
        String rawTarget = f.target();
        String target = (rawTarget != null && targets.values().stream().noneMatch(t -> t.id().equals(rawTarget)))
                ? null : rawTarget; // 未知 target：退回维度规范化匹配，避免错误引用导致覆盖丢失
        Fact normalized = new Fact(f.dimension(), f.period(), f.metric(), f.value(),
                f.source(), f.status(), f.note(), target);
        Fact old = facts.put(key(f.dimension(), f.period(), f.metric()), normalized);
        return !normalized.equals(old);
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
            if (f.target() != null) {
                sb.append(" → ").append(f.target());
            }
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
        sb.append("提醒: 新检索到的数据立即用 record_facts 入账（target 填对应 required_facts 的 id）；最终答案的全部数据必须与账本一致。\n");
        return sb.toString();
    }

    /** 已声明覆盖目标清单（id + 维度 + 时期）。重规划时注入分析提示词，便于只声明增量目标。 */
    public String declaredTargets() {
        if (targets.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendTargets(sb);
        return sb.toString();
    }

    /** 完整账本文本：覆盖目标（含 id）+ 全部事实明细。用于最终校验对照与上下文压缩重建。 */
    public String ledger() {
        if (isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("# 事实账本（record_facts 累积的结构化数据）\n");
        if (!targets.isEmpty()) {
            sb.append("## 覆盖目标（record_facts 用 target 填下列 id 完成覆盖匹配）\n");
            appendTargets(sb);
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
            if (f.target() != null) {
                sb.append(" | 目标: ").append(f.target());
            }
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

    /** 一行覆盖度摘要，如「覆盖 8/10 个目标时期；缺口: rf1@2024-Q2（GDP增速）、…」。 */
    public String coverageLine() {
        int total = targets.values().stream().mapToInt(t -> t.periods().size()).sum();
        if (total == 0) {
            return "未声明覆盖目标（analyze_query 的 required_facts 可声明）";
        }
        List<String> missing = missingPeriods();
        return "覆盖 " + (total - missing.size()) + "/" + total + " 个目标时期"
                + (missing.isEmpty() ? "" : "；缺口: " + String.join("、", missing));
    }

    /** 覆盖目标行（- [id] 维度: 时期），declaredTargets 与 ledger 共用。 */
    private void appendTargets(StringBuilder sb) {
        for (Target t : targets.values()) {
            sb.append("- [").append(t.id()).append("] ").append(t.dimension())
                    .append(": ").append(String.join("、", t.periods())).append('\n');
        }
    }

    /** 覆盖目标中尚无任何事实入账的「id@时期（维度）」列表。 */
    private List<String> missingPeriods() {
        List<String> missing = new ArrayList<>();
        for (Target t : targets.values()) {
            for (String p : t.periods()) {
                boolean hit = facts.values().stream().anyMatch(f -> covers(t, p, f));
                if (!hit) {
                    missing.add(t.id() + "@" + p + "（" + t.dimension() + "）");
                }
            }
        }
        return missing;
    }

    /** 判断事实 f 是否覆盖目标 t 的时期 p：优先 target id，未填 target 时退回维度规范化匹配。 */
    private static boolean covers(Target t, String p, Fact f) {
        if (!p.equals(f.period())) {
            return false;
        }
        if (f.target() != null) {
            return t.id().equals(f.target());
        }
        return canonical(t.dimension()).equals(canonical(f.dimension()));
    }

    /** 维度规范化（纯结构，不背题）：去括号限定、去空白、英文小写。覆盖匹配的兜底路径。 */
    private static String canonical(String dimension) {
        if (dimension == null) {
            return "";
        }
        return dimension.strip()
                .replaceAll("[（(][^（）()]*[）)]", "")
                .replaceAll("\\s+", "")
                .toLowerCase();
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
