package org.example;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 检索日志外部存储：web_search 每次调用记录 query+domains+命中数，与 TaskStore/FactsStore 同理——
 * LLM 看不到本类状态，近似提示随工具回执给出、全量清单随上下文压缩重建注入。
 * 账本记「事实」，本类记「检索行为」：模型据此判断某目标是否已检索过，避免语义级重复检索
 * （同一对象换措辞的多轮检索是搜索额度的最大浪费源）。
 */
public class SearchLog {

    /** 单次检索：查询词、限定域名与命中条数。 */
    public record Entry(String query, List<String> domains, int hits) {}

    /** 关键词集合近似阈值：Jaccard ≥ 0.5 视为同一检索目标（如只差年份/限定词的变体）。 */
    private static final double JACCARD_THRESHOLD = 0.5;
    /** 最多保留的检索条数，超出丢最旧。 */
    private static final int MAX_ENTRIES = 200;

    private final List<Entry> entries = new ArrayList<>();

    /** 记录一次已执行的检索（hits 为实际命中条数；调用时机在真实请求成功之后）。 */
    public void record(String query, List<String> domains, int hits) {
        if (query == null || query.isBlank()) {
            return;
        }
        entries.add(new Entry(query.strip(), List.copyOf(domains), hits));
        if (entries.size() > MAX_ENTRIES) {
            entries.subList(0, entries.size() - MAX_ENTRIES).clear();
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 与 query 近似的历史检索（应在记录本次之前调用，只比已入库条目），
     * 按时间倒序最多 limit 条，格式「query（限定 …）×命中 n」。
     */
    public List<String> similarTo(String query, int limit) {
        List<String> hits = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && hits.size() < limit; i--) {
            if (similar(entries.get(i).query(), query)) {
                hits.add(format(entries.get(i)));
            }
        }
        return hits;
    }

    /** 压缩重建注入用：全部已检索查询清单（每条一行），空日志返回 null。 */
    public String render() {
        if (entries.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            sb.append(i + 1).append(". ").append(format(entries.get(i))).append('\n');
        }
        return sb.toString();
    }

    private static String format(Entry e) {
        return e.query() + (e.domains().isEmpty() ? "" : "（限定 " + String.join("、", e.domains()) + "）")
                + " ×命中 " + e.hits();
    }

    /** 近似判定：归一化后整串互相包含（只差限定词的变体），或按空白分词的集合 Jaccard ≥ 0.5。 */
    static boolean similar(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        if (na.contains(nb) || nb.contains(na)) {
            return true;
        }
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return false;
        }
        Set<String> inter = new LinkedHashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new LinkedHashSet<>(ta);
        union.addAll(tb);
        return !inter.isEmpty() && (double) inter.size() / union.size() >= JACCARD_THRESHOLD;
    }

    /** 归一基形：小写、去所有空白——「茶卡盐湖 门票」与「茶卡盐湖门票」同名。 */
    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** 关键词分词：按空白切（web_search 的 keywords 本就以空格拼接）。 */
    private static Set<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }
}
