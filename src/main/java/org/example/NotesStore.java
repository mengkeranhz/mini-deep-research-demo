package org.example;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工程备忘外部存储：record_note 写入（标题 key 归一后覆盖同项）。
 * LLM 看不到本类状态——Agent 每轮注入快照、压缩重建时随账本保留：
 * 接口与参数格式、已下载文件路径、解析方法、失败尝试等工程状态不随上下文压缩丢失。
 */
public class NotesStore {

    /** title 为简短标题（接口/文件/方法/失败尝试），content 为具体内容。 */
    public record Note(String title, String content) {}

    /** key = title 压空白，同标题覆盖。 */
    private final Map<String, Note> notes = new LinkedHashMap<>();

    public void put(String title, String content) {
        notes.put(key(title), new Note(title.strip(), content == null ? "" : content.strip()));
    }

    public int size() {
        return notes.size();
    }

    /** 每轮注入与压缩重建共用的快照；空时 null。 */
    public String snapshot() {
        if (notes.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("# 工程备忘（record_note 累积，跨压缩保留）\n");
        for (Note n : notes.values()) {
            sb.append("- ").append(n.title()).append(": ").append(n.content()).append('\n');
        }
        return sb.toString();
    }

    private static String key(String title) {
        return title == null ? "" : title.replaceAll("\\s+", "");
    }
}
