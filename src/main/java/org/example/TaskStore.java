package org.example;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务状态外部存储：analyze_query 写入当前目标（任务基线，允许随重规划调整）、计划、约束与未知、
 * 任务清单，update_task 更新进度。LLM 看不到本类状态——Agent 每轮用 snapshot() 重算一份紧凑快照注入上下文。
 */
public class TaskStore {

    /** 单个任务：status 为 pending / in_progress / done。 */
    public record Task(String id, String content, String status, String note) {}

    private String goal;
    private String plan;
    private List<String> constraints = List.of();
    private List<String> unknowns = List.of();
    private List<String> coreNeeds = List.of();
    private String originalRequest; // Agent.run 开始写入：原始述求，子代理简报锚点；reset 不清（重规划保留）
    private String draft;           // 最近一次校验未通过的草稿答案；上下文压缩重建时显式携带
    private final List<Task> tasks = new ArrayList<>();

    /** 写入新任务清单（重复调用即重新规划；goal 为本次分析的目标述求，基线随之更新，
     *  约束、未知与核心述求随本次分析整体替换）。
     *  重规划保留已完成状态：新任务按内容匹配旧任务，done 的继承 done（含备注），
     *  避免重规划把进度清零导致重复劳动。 */
    public void reset(String goal, String plan, List<String> constraints, List<String> unknowns,
                      List<String> coreNeeds, List<Task> newTasks) {
        if (goal != null && !goal.isBlank()) {
            this.goal = goal.strip();
        }
        this.plan = plan;
        this.constraints = constraints == null ? List.of() : List.copyOf(constraints);
        this.unknowns = unknowns == null ? List.of() : List.copyOf(unknowns);
        this.coreNeeds = coreNeeds == null ? List.of() : List.copyOf(coreNeeds);
        Map<String, Task> doneByContent = new LinkedHashMap<>();
        for (Task t : tasks) {
            if ("done".equals(t.status())) {
                doneByContent.put(contentKey(t.content()), t);
            }
        }
        tasks.clear();
        for (Task nt : newTasks) {
            Task old = doneByContent.get(contentKey(nt.content()));
            tasks.add(old == null ? nt : new Task(nt.id(), nt.content(), "done", old.note()));
        }
    }

    /** 更新状态与备注；id 不存在返回 null。note 为空时保留原备注。 */
    public Task update(String id, String status, String note) {
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            if (t.id().equals(id)) {
                tasks.set(i, new Task(t.id(), t.content(), status,
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

    /** 全部任务（只读视图），供 analyze_query 回显继承后的状态。 */
    public List<Task> all() {
        return List.copyOf(tasks);
    }

    /** 进度统计，如「2/5 完成」。 */
    public String progress() {
        return tasks.stream().filter(t -> "done".equals(t.status())).count()
                + "/" + tasks.size() + " 完成";
    }

    /** 当前任务基线（最近一次规划的目标述求）；未规划过为 null，届时以原始述求为准。 */
    public String goal() {
        return goal;
    }

    /** 原始述求：Agent.run 开始写入，此后不变（重规划调整的是 goal，不改写本字段）。 */
    public void original(String request) {
        this.originalRequest = request;
    }

    public String original() {
        return originalRequest;
    }

    /** 最近一次未通过校验的草稿答案；传入 null 即清除（本版通过校验时）。 */
    public void draft(String d) {
        this.draft = d;
    }

    public String draft() {
        return draft;
    }

    /** 核心述求（最近一次规划解析出的要点），最终校验逐条核对。 */
    public List<String> coreNeeds() {
        return coreNeeds;
    }

    /** 每轮重算的紧凑进度快照：计划 + 进度统计 + 逐任务行。 */
    public String snapshot() {
        if (tasks.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("# 任务进度快照（系统每轮自动注入，非用户消息）\n");
        if (goal != null) {
            sb.append("当前目标: ").append(goal).append('\n');
        }
        if (plan != null && !plan.isBlank()) {
            sb.append("计划: ").append(plan).append('\n');
        }
        if (!constraints.isEmpty()) {
            sb.append("约束: ").append(String.join("、", constraints)).append('\n');
        }
        if (!unknowns.isEmpty()) {
            sb.append("未知: ").append(String.join("、", unknowns)).append('\n');
        }
        if (!coreNeeds.isEmpty()) {
            sb.append("核心述求: ").append(String.join("、", coreNeeds)).append('\n');
        }
        sb.append("进度: ").append(progress()).append('\n');
        for (Task t : tasks) {
            sb.append(t.id()).append(" [").append(label(t.status())).append("] ").append(t.content());
            if (t.note() != null && !t.note().isBlank()) {
                sb.append(" —— ").append(t.note());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 任务内容规范化 key：去空白与常见标点，用于重规划时按内容匹配继承完成状态。 */
    private static String contentKey(String content) {
        return content == null ? "" : content.replaceAll("[\\s，。、：:；;（）()、,./\\-]+", "");
    }

    private static String label(String status) {
        return switch (status) {
            case "done" -> "完成";
            case "in_progress" -> "进行中";
            default -> "待办";
        };
    }
}
