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

    /**
     * 单条事实：status 为 found（官方/已核验）、proxy（代理指标或第三方折算）、not_found（检索未得的缺口声明）。
     * origin 记录入账来源 Agent；父 / 多个子 Agent 对同一语义 key 的证据可并存，不互相覆盖。
     */
    public record Fact(String dimension, String period, String metric, String value,
                       String source, String status, String note, String origin) {
        public Fact {
            origin = origin == null || origin.isBlank() ? "parent" : origin.strip();
        }

        public Fact(String dimension, String period, String metric, String value,
                    String source, String status, String note) {
            this(dimension, period, metric, value, source, status, note, "parent");
        }
    }

    /** 覆盖目标：dimension → 需覆盖的时期列表（analyze_query 声明，重规划可追加）。 */
    private final Map<String, List<String>> targets = new LinkedHashMap<>();
    /** 已入账事实：key = dimension|period|metric|origin，同来源同 key 覆盖，不同来源并存。 */
    private final Map<String, Fact> facts = new LinkedHashMap<>();
    /** 上次快照以来新增/变更的条目（key → 最新值），snapshot() 渲染后清空。 */
    private final Map<String, Fact> recentChanges = new LinkedHashMap<>();
    private final String origin;

    public FactsStore() {
        this("parent");
    }

    public FactsStore(String origin) {
        this.origin = origin == null || origin.isBlank() ? "parent" : origin.strip();
    }

    /** 单条入账结果：NEW=新格子；UPDATED=同 key 覆盖且有内容变化；UNCHANGED=与现有条目完全相同。 */
    public enum RecordOutcome { NEW, UPDATED, UNCHANGED }

    /** 覆盖目标中尚无事实入账的一个格子（维度×时期），闸门报缺口的最小单位。 */
    private record Cell(String dimension, String period) {}

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

    /** 入账一条事实（同 key 覆盖），返回三态结果；新增/变更同时进 recentChanges 供每轮快照展示。 */
    public RecordOutcome record(Fact f) {
        f = "parent".equals(f.origin()) ? withOrigin(f, origin) : f;
        String k = storageKey(f);
        Fact old = facts.get(k);
        if (old == null) {
            facts.put(k, f);
            recentChanges.put(k, f);
            return RecordOutcome.NEW;
        }
        if (f.equals(old)) {
            return RecordOutcome.UNCHANGED;
        }
        facts.put(k, f);
        recentChanges.put(k, f);
        return RecordOutcome.UPDATED;
    }

    /** 导出已入账事实快照，供子 Agent 结果合并回父 Agent。 */
    public List<Fact> facts() {
        return List.copyOf(facts.values());
    }

    /** 子 Agent 事实合并结果：冲突与互证均保留来源条目，交由父 Agent / 最终校验裁决。 */
    public record MergeResult(int added, int updated, int unchanged, int corroborated,
                              List<String> conflicts) {}

    /**
     * 合并子 Agent 事实：先给条目打上 childOrigin 标签。
     * 同一语义 key 下不同 Agent 的条目不覆盖：数值/状态一致记为互证，不一致记为冲突。
     * 只有同一 childOrigin 重复提交时才允许覆盖，避免子 Agent A / B 彼此吞掉证据。
     */
    public MergeResult mergeChild(List<Fact> incoming, String childOrigin) {
        int added = 0;
        int updated = 0;
        int unchanged = 0;
        int corroborated = 0;
        List<String> conflicts = new ArrayList<>();

        for (Fact raw : incoming) {
            Fact f = withOrigin(raw, childOrigin);
            Fact sameOrigin = findSameOrigin(f);
            if (sameOrigin != null) {
                if (sameEvidence(sameOrigin, f)) {
                    unchanged++;
                } else {
                    record(f);
                    updated++;
                }
                continue;
            }

            List<Fact> existing = facts.values().stream()
                    .filter(old -> semanticKey(old).equals(semanticKey(f)))
                    .toList();
            record(f);
            added++;
            if (existing.isEmpty()) {
                continue;
            }
            if (existing.stream().anyMatch(old -> sameValueAndStatus(old, f))) {
                corroborated++;
            } else {
                conflicts.add(conflictMessage(f, existing));
            }
        }

        return new MergeResult(added, updated, unchanged, corroborated, conflicts);
    }

    /**
     * 入账回执提示用：该 dimension 是否未声明目标（视为额外补充，不提示），
     * 或已声明目标且 period 在其目标时期列表内（命中）；命中/未声明返回 true，
     * dimension 已有目标而 period 不在其中返回 false——典型的标签错位。
     */
    public boolean hitsTarget(String dimension, String period) {
        if (dimension == null || period == null) {
            return true;
        }
        List<String> ps = targets.get(dimension.strip());
        return ps == null || ps.contains(period.strip());
    }

    /**
     * 入账回执提示用：与 dimension 不精确相等（含首尾空白差异）但归一化后近似
     * （去空白/大小写/分隔符后同名，或单边包含）的已声明目标，最多 2 个——
     * 与 period 错位提示对称：疑似 dimension 标签错位，提示照抄目标字符串重录。
     */
    public List<String> nearTargets(String dimension) {
        if (dimension == null || dimension.isBlank() || targets.isEmpty()) {
            return List.of();
        }
        List<String> near = new ArrayList<>();
        String dim = dimension.strip();
        for (String t : targets.keySet()) {
            if (!t.equals(dim) && nearDim(t, dimension) && !near.contains(t)) {
                near.add(t);
                if (near.size() >= 2) {
                    break;
                }
            }
        }
        return near;
    }

    public boolean isEmpty() {
        return facts.isEmpty() && targets.isEmpty();
    }

    /**
     * 每轮注入的瘦身快照：计数 + 覆盖缺口 + 上次快照以来的新增/变更 + 维度索引。
     * 不再逐轮注入全部条目数值——账本越大每轮固定开销越大，且诱导模型每轮重审全账本；
     * 全量账本只在压缩重建（ledger）与终答校验时进入上下文。检索前模型对照维度索引即可
     * 判断某对象/口径是否已入账；本轮数值看 record_facts 回执与工具结果即可。
     */
    public String snapshot() {
        if (isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("# 事实账本快照（每轮自动追加在对话末尾的状态块，非用户消息，不要回复它）\n");
        sb.append(counts()).append("；").append(coverageLine()).append('\n');
        if (!recentChanges.isEmpty()) {
            sb.append("上次快照以来新增/变更:\n");
            for (Fact f : recentChanges.values()) {
                sb.append("- [").append(f.dimension()).append("] ").append(f.period());
                if (!f.metric().isBlank()) {
                    sb.append(" | ").append(f.metric());
                }
                sb.append(" = ").append(f.value().isBlank() ? "（未获得）" : f.value())
                        .append(" (").append(f.status()).append("; 来源Agent ")
                        .append(f.origin()).append(")\n");
            }
        }
        sb.append("维度索引（已入账条目按 dimension 汇总；具体数值以 record_facts 回执与终答校验的全量账本为准）:\n");
        Map<String, Long> byDim = new LinkedHashMap<>();
        for (Fact f : facts.values()) {
            byDim.merge(f.dimension(), 1L, Long::sum);
        }
        byDim.forEach((d, c) -> sb.append("- ").append(d).append(" ×").append(c).append('\n'));
        List<String> missing = missingPeriods();
        if (!missing.isEmpty()) {
            sb.append("覆盖缺口（目标要求但尚未入账）: ").append(String.join("、", missing)).append('\n');
        }
        sb.append("提醒: 检索前先对照维度索引——已入账的对象/口径直接复用，不要重复检索；")
                .append("新检索到的数据立即用 record_facts 入账；最终答案的全部数据必须与账本一致。\n");
        recentChanges.clear();
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
                    .append(" | ").append(f.status())
                    .append(" | 入账Agent: ").append(f.origin());
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
     * 缺口格若存在 dimension/period 近似的已入账条目，点名提示标签错位——
     * 防止（尤其压缩后）把「标签写歪」误读为「没查到」而反复重查。
     */
    public String gateReport() {
        List<String> gaps = new ArrayList<>();
        for (Cell c : missingCells()) {
            String gap = c.dimension() + "@" + c.period() + "：覆盖目标要求但账本中无任何记录";
            List<String> suspects = suspectsFor(c);
            if (!suspects.isEmpty()) {
                gap += "；账本中已有疑似条目（标签错位）: " + String.join("、", suspects)
                        + "——若即此数据，照抄覆盖目标的 dimension 与 period 字符串重新入账"
                        + "（同 key 覆盖旧值），不必重新检索";
            }
            gaps.add(gap);
        }
        for (Fact f : facts.values()) {
            if ("not_found".equals(f.status()) && f.note().isBlank()) {
                gaps.add("[" + f.dimension() + "@" + f.period() + "]"
                        + "：声明未检索到，但未说明已尝试的检索关键词与来源（放弃过早）");
            }
        }
        String conflicts = evidenceConflicts();
        if (conflicts != null) {
            gaps.add(conflicts);
        }
        return gaps.isEmpty() ? null : String.join("\n", gaps);
    }

    /** 同一语义事实在多个来源 Agent 间数值/状态不一致时，终答闸门显式要求先裁决。 */
    private String evidenceConflicts() {
        Map<String, List<Fact>> bySemantic = new LinkedHashMap<>();
        for (Fact f : facts.values()) {
            bySemantic.computeIfAbsent(semanticKey(f), k -> new ArrayList<>()).add(f);
        }

        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<Fact>> entry : bySemantic.entrySet()) {
            List<Fact> values = entry.getValue();
            if (values.size() < 2) {
                continue;
            }
            String value = values.get(0).value();
            String status = values.get(0).status();
            if (values.stream().allMatch(f -> f.value().equals(value) && f.status().equals(status))) {
                continue;
            }
            List<String> labels = values.stream()
                    .map(f -> f.origin() + "=" + f.value() + " (" + f.status() + ")")
                    .toList();
            conflicts.add("[" + entry.getKey() + "] 多来源 Agent 证据冲突: "
                    + String.join("；", labels)
                    + "——终答前必须复核并只采用可靠一方，不能同时采纳矛盾数值");
        }
        return conflicts.isEmpty() ? null : String.join("\n", conflicts);
    }

    /** 一行覆盖度摘要，如「覆盖 8/10 个目标时期；缺口: GDP增速@2024-Q2、GDP增速@2024-Q3」。 */
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
        return missingCells().stream().map(c -> c.dimension() + "@" + c.period()).toList();
    }

    /** 覆盖目标中尚无任何事实入账的格子（判定与 hitsTarget 同口径的精确匹配）。 */
    private List<Cell> missingCells() {
        List<Cell> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : targets.entrySet()) {
            for (String p : e.getValue()) {
                boolean hit = facts.values().stream()
                        .anyMatch(f -> e.getKey().equals(f.dimension()) && p.equals(f.period()));
                if (!hit) {
                    missing.add(new Cell(e.getKey(), p));
                }
            }
        }
        return missing;
    }

    /** 缺口格的疑似已入账条目（dimension 近似即可，含 period 错位的同维度条目），最多 2 条。 */
    private List<String> suspectsFor(Cell c) {
        List<String> suspects = new ArrayList<>();
        for (Fact f : facts.values()) {
            if (nearDim(c.dimension(), f.dimension())) {
                String s = "[" + f.dimension() + "@" + f.period() + "]";
                if (!suspects.contains(s)) {
                    suspects.add(s);
                }
                if (suspects.size() >= 2) {
                    break;
                }
            }
        }
        return suspects;
    }

    /**
     * 维度近似判定：去首尾空白相等（覆盖空白变体）、去空白/大小写/分隔符后同名
     * （「故宫-成人门票」「故宫成人门票」「故宫·成人门票」视为同名）、或归一化后单边包含
     * （「成人门票」⊂「故宫-成人门票」）。包含要求短边 ≥2 字，防单字误报。
     * 不覆盖同义改写（票价↔门票）——那类靠提示词与闸门点名兜底。
     */
    private static boolean nearDim(String target, String candidate) {
        String a = target == null ? "" : target.strip();
        String b = candidate == null ? "" : candidate.strip();
        if (a.equals(b)) {
            return true;
        }
        if (normLoose(a).equals(normLoose(b))) {
            return true;
        }
        String na = norm(a);
        String nb = norm(b);
        String shorter = na.length() <= nb.length() ? na : nb;
        String longer = shorter == na ? nb : na;
        return shorter.length() >= 2 && longer.contains(shorter);
    }

    /** 归一基形：去所有空白（含全角空格）、转小写——「GDP 增速」与「gdp增速」同名。 */
    private static String norm(String s) {
        return s.replaceAll("\\s+", "").toLowerCase();
    }

    /** 宽松基形：再去常见分隔符——连字符/箭头/斜杠/冒号/顿号等中英文变体。 */
    private static String normLoose(String s) {
        return norm(s).replaceAll("[-—–_·>/→|｜,，、:：;；]+", "");
    }

    private String counts() {
        long found = facts.values().stream().filter(f -> "found".equals(f.status())).count();
        long proxy = facts.values().stream().filter(f -> "proxy".equals(f.status())).count();
        long nf = facts.values().stream().filter(f -> "not_found".equals(f.status())).count();
        long other = facts.size() - found - proxy - nf;
        return "已入账 " + facts.size() + " 条（found " + found + "、proxy " + proxy
                + "、not_found " + nf + (other > 0 ? "、其他 " + other : "") + "）";
    }

    private static String semanticKey(Fact f) {
        return key(f.dimension(), f.period(), f.metric());
    }

    private static String storageKey(Fact f) {
        return semanticKey(f) + "|" + nz(f.origin());
    }

    private Fact findSameOrigin(Fact f) {
        String k = storageKey(f);
        Fact old = facts.get(k);
        return old != null && semanticKey(old).equals(semanticKey(f)) ? old : null;
    }

    private static Fact withOrigin(Fact f, String origin) {
        return new Fact(f.dimension(), f.period(), f.metric(), f.value(),
                f.source(), f.status(), f.note(), origin);
    }

    private static boolean sameEvidence(Fact a, Fact b) {
        return a.equals(b);
    }

    private static boolean sameValueAndStatus(Fact a, Fact b) {
        return a.value().equals(b.value()) && a.status().equals(b.status());
    }

    private static String conflictMessage(Fact incoming, List<Fact> existing) {
        String cell = incoming.dimension() + "@" + incoming.period()
                + (incoming.metric().isBlank() ? "" : "|" + incoming.metric());
        StringBuilder sb = new StringBuilder(cell + "：");
        for (int i = 0; i < existing.size(); i++) {
            Fact old = existing.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(old.origin()).append("=").append(old.value()).append(" (").append(old.status()).append(")");
        }
        return sb.append("；").append(incoming.origin()).append("=")
                .append(incoming.value()).append(" (").append(incoming.status()).append(")")
                .append("——条目已全部保留，请父 Agent 复核后择优采用").toString();
    }

    private static String key(String dimension, String period, String metric) {
        return nz(dimension) + "|" + nz(period) + "|" + nz(metric);
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }
}
