package org.example;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事实账本外部存储：record_facts 写入自由文本事实（数据描述 + 来源）。
 * 与 NotesStore 同理，LLM 看不到本类状态——Agent 每轮注入账本快照，
 * 上下文压缩后账本不丢，最终答案的数据以账本为准。
 */
public class FactsStore {

    /** 单条事实：fact 为写清指标/时期/数值/口径的数据描述；source 为来源链接；note 可选补充。 */
    public record Fact(String fact, String source, String note) {}

    /** 已入账事实：key = fact 压空白，同描述覆盖（允许修正）。 */
    private final Map<String, Fact> facts = new LinkedHashMap<>();

    /** 入账一条事实（同描述覆盖），返回是否新增或更新了内容。 */
    public boolean record(Fact f) {
        Fact normalized = new Fact(nz(f.fact()), nz(f.source()), nz(f.note()));
        return !normalized.equals(facts.put(key(f.fact()), normalized));
    }

    /** 删除一条事实（按 fact 文本压空白匹配），用于纠正错误入账；返回是否确实删除。 */
    public boolean remove(String fact) {
        return facts.remove(key(fact)) != null;
    }

    public boolean isEmpty() {
        return facts.isEmpty();
    }

    public int size() {
        return facts.size();
    }

    /** 每轮注入的紧凑快照；空时 null。 */
    public String snapshot() {
        if (facts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("# 事实账本快照（系统每轮自动注入，非用户消息）\n");
        for (Fact f : facts.values()) {
            sb.append("- ").append(f.fact());
            if (!f.source().isBlank()) {
                sb.append("（来源: ").append(f.source()).append("）");
            }
            if (!f.note().isBlank()) {
                sb.append("（").append(f.note()).append("）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String key(String fact) {
        return fact == null ? "" : fact.replaceAll("\\s+", "");
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }
}
