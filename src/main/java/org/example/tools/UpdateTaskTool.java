package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.TaskStore;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** update_task：更新任务状态与备注，更新后的进度每轮以系统快照注入上下文。 */
public class UpdateTaskTool implements AgentTool {

    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "done");

    private final TaskStore tasks;

    public UpdateTaskTool(TaskStore tasks) {
        this.tasks = tasks;
    }

    @Override
    public String name() {
        return "update_task";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "更新任务清单中某任务的状态：开始执行标 in_progress，完成标 done，可附一句关键结果备注。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "task", Map.of("type", "string", "description", "任务 id（如 t1）"),
                                "status", Map.of("type", "string",
                                        "enum", List.of("pending", "in_progress", "done"),
                                        "description", "新状态"),
                                "note", Map.of("type", "string", "description", "关键结果备注，可选")),
                        "required", List.of("task", "status")));
    }

    @Override
    public String execute(JsonNode input) {
        String id = ToolRegistry.str(input, "task");
        String status = ToolRegistry.str(input, "status");
        if (!STATUSES.contains(status)) {
            return "无效状态: " + status + "，可选: " + STATUSES;
        }
        TaskStore.Task t = tasks.update(id, status, ToolRegistry.optStr(input, "note"));
        if (t == null) {
            return "未知任务: " + id + "，当前任务: " + tasks.ids();
        }
        return "已更新 " + id + " → " + status + "，进度: " + tasks.progress();
    }
}
