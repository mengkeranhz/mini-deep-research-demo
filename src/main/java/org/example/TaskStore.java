package org.example;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务状态外部存储：analyze_query 写入任务清单，update_task 更新进度。
 * LLM 看不到本类状态——Agent 每轮用 snapshot() 重算一份紧凑快照注入上下文。
 */
public class TaskStore {

    /** 单个任务：dependsOn 为前置任务 id；status 为 pending / in_progress / done。 */
    public record Task(String id, String content, List<String> dependsOn, String status, String note) {}

    /** analyze_query 的分析头信息，进入每轮快照头部。 */
    public record Header(String intent, String plan, List<String> unknowns) {}

    private Header header;
    private final List<Task> tasks = new ArrayList<>();

    /** 写入新任务清单（重复调用即重新规划，旧清单作废）。 */
    public void reset(Header header, List<Task> newTasks) {
        this.header = header;
        tasks.clear();
        tasks.addAll(newTasks);
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    /** 更新状态与备注；id 不存在返回 null。note 为空时保留原备注。 */
    public Task update(String id, String status, String note) {
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            if (t.id().equals(id)) {
                tasks.set(i, new Task(t.id(), t.content(), t.dependsOn(), status,
                        note == null || note.isBlank() ? t.note() : note));
                return tasks.get(i);
            }
        }
        return null;
    }

    /** 全部任务 id，用于未知 id 时的提示。 */
    public List<String> ids() {
        return tasks.stream().map(Task::id).toList();
    }

    /** 进度统计，如「2/5 完成」。 */
    public String progress() {
        return tasks.stream().filter(t -> "done".equals(t.status())).count()
                + "/" + tasks.size() + " 完成";
    }

    /**
     * 每轮重算的紧凑进度快照：分析头信息 + 进度统计 + 逐任务行。
     * 就绪/阻塞不存储、每次现算：非完成任务且依赖全部完成即可执行，否则阻塞。
     */
    public String snapshot() {
        if (tasks.isEmpty()) {
            return null;
        }
        Map<String, String> statusById = new LinkedHashMap<>();
        tasks.forEach(t -> statusById.put(t.id(), t.status()));
        Map<String, List<String>> waitingById = new LinkedHashMap<>(); // 未完成任务 → 未完成依赖
        List<String> ready = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        for (Task t : tasks) {
            if ("done".equals(t.status())) {
                continue;
            }
            List<String> waiting = t.dependsOn().stream()
                    .filter(d -> !"done".equals(statusById.get(d))).toList();
            waitingById.put(t.id(), waiting);
            (waiting.isEmpty() ? ready : blocked).add(t.id());
        }

        StringBuilder sb = new StringBuilder("# 任务进度快照（系统每轮自动注入，非用户消息）\n");
        if (header != null) {
            if (!header.intent().isBlank()) {
                sb.append("意图: ").append(header.intent()).append('\n');
            }
            if (!header.plan().isBlank()) {
                sb.append("计划: ").append(header.plan()).append('\n');
            }
            if (!header.unknowns().isEmpty()) {
                sb.append("信息缺口: ").append(String.join("、", header.unknowns())).append('\n');
            }
        }
        sb.append("进度: ").append(progress())
                .append("，可执行: ").append(ready.isEmpty() ? "无" : String.join("、", ready))
                .append("，阻塞: ").append(blocked.isEmpty() ? "无" : String.join("、", blocked))
                .append('\n');
        for (Task t : tasks) {
            sb.append(t.id()).append(" [").append(label(t.status())).append("] ").append(t.content());
            if (t.note() != null && !t.note().isBlank()) {
                sb.append(" —— ").append(t.note());
            }
            List<String> waiting = waitingById.get(t.id());
            if (waiting != null && !waiting.isEmpty()) {
                sb.append("（等待 ").append(String.join("、", waiting)).append("）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String label(String status) {
        return switch (status) {
            case "done" -> "完成";
            case "in_progress" -> "进行中";
            default -> "待办";
        };
    }
}
