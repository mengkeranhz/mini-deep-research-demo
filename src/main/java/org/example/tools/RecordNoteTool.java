package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.NotesStore;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.util.List;
import java.util.Map;

/**
 * record_note：把工程探索中的关键状态写入工程备忘（接口与参数格式、已下载文件路径、解析方法、失败尝试）。
 * 备忘跨轮、跨上下文压缩保留——工程探索的关键发现随手记录，压缩后不丢。
 */
public class RecordNoteTool implements AgentTool {

    private final NotesStore notes;

    public RecordNoteTool(NotesStore notes) {
        this.notes = notes;
    }

    @Override
    public String name() {
        return "record_note";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "记录需跨轮、跨上下文压缩保留的关键工程状态：发现的接口与参数、"
                        + "文件路径、方法与结论、失败尝试等。工程探索过程中随手记录，压缩后这些状态不丢。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string",
                                        "description", "简短标题（接口/文件/方法/失败尝试），同类更新用同标题覆盖"),
                                "content", Map.of("type", "string",
                                        "description", "具体内容：接口与参数、文件路径、方法要点、失败原因与结论等")),
                        "required", List.of("title", "content")));
    }

    @Override
    public String execute(JsonNode input) {
        String title = ToolRegistry.str(input, "title");
        String content = ToolRegistry.str(input, "content");
        notes.put(title, content);
        return "已记录：" + title.strip() + "（共 " + notes.size() + " 条）";
    }
}
