package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.Config;
import org.example.Console;
import org.example.FactsStore;
import org.example.LlmClient;
import org.example.SubAgent;
import org.example.TaskStore;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * execute_task：把一个自包含任务派发给新建的子 Agent Loop 执行（每次全新上下文）。
 * 子代理只能看到原始述求、本任务文本与事实账本（同一 FactsStore 实例，父子共享），
 * 看不到父对话与计划清单；子代理与父对齐——调用 final_answer 提交结果并做 LLM 终止校验。
 */
public class ExecuteTaskTool implements AgentTool {

    /** 工具名（父代理单代理模式下据此禁用）。 */
    public static final String NAME = "execute_task";

    /** 回传父上下文的结果上限：保住数据与链接，又不让父上下文膨胀。 */
    private static final int RESULT_LIMIT = 24_000;
    /** 任务完成备注的缩写上限。 */
    private static final int NOTE_LIMIT = 200;

    private final Config.Data cfg;
    private final TaskStore tasks;
    private final FactsStore facts;
    private final AmapClient amap;

    private LlmClient subLlm;      // 懒加载一次（cfg.llm()，流式，子代理过程实时可见）
    private LlmClient subQuietLlm; // 懒加载一次（无工具、非流式，子代理终止校验用）

    /** 构造器只赋字段不执行——子注册表场景 tasks 为 null，也能安全实例化后被排除。 */
    public ExecuteTaskTool(Config.Data cfg, TaskStore tasks, FactsStore facts, AmapClient amap) {
        this.cfg = cfg;
        this.tasks = tasks;
        this.facts = facts;
        this.amap = amap;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "把一个任务派发给独立子代理执行（检索、取数、计算、入账均由子代理完成）。"
                        + "子代理看不到计划与任务清单，task 必须自包含：写明背景、范围、时期、口径、单位与期望产出；"
                        + "带 task_id 时状态自动维护（进行中/完成）。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "task", Map.of("type", "string",
                                        "description", "自包含的任务描述：目标、背景、范围、时期、口径、单位，以及期望产出的数据形态"),
                                "task_id", Map.of("type", "string",
                                        "description", "任务清单中的任务 id（如 t1），可选；提供后自动维护状态"),
                                "context", Map.of("type", "string",
                                        "description", "补充提示（已知信息、注意事项），可选")),
                        "required", List.of("task")));
    }

    @Override
    public String execute(JsonNode input) {
        String task = ToolRegistry.str(input, "task");
        String taskId = ToolRegistry.optStr(input, "task_id");
        String context = ToolRegistry.optStr(input, "context");

        if (tasks != null && taskId != null && tasks.update(taskId, "in_progress", null) == null) {
            System.out.println(Console.warn("[子代理] 未知任务 id: " + taskId + "（当前任务: "
                    + tasks.ids() + "），仅跳过状态维护"));
            taskId = null;
        }

        String brief = buildBrief(task, context);
        System.out.println("\n" + Console.header("[子代理] 开始任务" + (taskId == null ? "" : " " + taskId) + "：")
                + abbreviate(task));
        // 每次调用新建 SubAgent 实例：全新上下文，只共享事实账本（同一实例注入子注册表）
        SubAgent.Result result = subAgent().run(brief);
        System.out.println(Console.header("[子代理] 任务结束" + (taskId == null ? "" : " " + taskId)
                + (result.completed() ? "（完成）" : "（未完成）")));

        if (taskId != null && result.completed()) {
            tasks.update(taskId, "done", abbreviate(result.text()));
        }

        String text = result.text();
        String body = text.length() <= RESULT_LIMIT ? text : text.substring(0, RESULT_LIMIT) + "…（截断）";
        return result.completed() ? body
                : "（子代理未在轮次上限内完成，以下为部分结果，任务保持进行中）\n" + body;
    }

    /** 每次调用新建独立子代理与独立 TaskStore（子代理只执行不规划，不与父共享）；
     *  LLM 客户端（流式 + 校验用非流式）懒加载一次复用，Amap 节流全局唯一。 */
    private SubAgent subAgent() {
        if (subLlm == null) {
            subLlm = LlmClient.create(cfg.llm());
            subQuietLlm = LlmClient.create(new Config.Llm(cfg.llm().provider(), cfg.llm().baseUrl(),
                    cfg.llm().model(), cfg.llm().apiKey(), cfg.llm().maxTokens(), cfg.llm().temperature(), false, 0));
        }
        // 子代理只做执行：排除 execute_task 防嵌套展开，排除 analyze_query / update_task 禁止任务规划，
        // 仅保留检索/取数/计算/入账/final_answer
        TaskStore subTasks = new TaskStore();
        ToolRegistry subRegistry = new ToolRegistry(cfg, subTasks, facts, amap,
                Set.of("execute_task", "analyze_query", "update_task"));
        return new SubAgent(subLlm, subQuietLlm, subRegistry, facts, subTasks, cfg.llm().streaming());
    }

    /** 组装子代理简报：只共享事实账本，不共享计划——子代理以原始述求 + 自包含任务文本锚定工作，
     *  不给看父草稿与任务清单。 */
    private String buildBrief(String task, String context) {
        StringBuilder brief = new StringBuilder();
        String original = tasks != null ? tasks.original() : null;
        if (original == null || original.isBlank()) {
            original = tasks != null ? tasks.goal() : null; // 未走 Agent.run 的兜底
        }
        if (original != null && !original.isBlank()) {
            brief.append("原始述求：\n").append(original).append("\n\n");
        }
        brief.append("本次任务（自包含，请独立完成）：\n").append(task);
        if (context != null && !context.isBlank()) {
            brief.append("\n\n补充提示：\n").append(context);
        }
        String ledger = facts.snapshot();
        brief.append("\n\n已知事实（事实账本，父子共享；直接复用勿重复检索，新数据继续 record_facts 入账）：\n")
                .append(ledger == null ? "（暂无）" : ledger);
        return brief.toString();
    }

    /** 压成一行并截断（横幅与任务备注用）。 */
    private static String abbreviate(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() <= NOTE_LIMIT ? one : one.substring(0, NOTE_LIMIT) + "…";
    }
}
