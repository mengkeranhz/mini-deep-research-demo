package org.example;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * delegate_agent：第一阶段顺序子 Agent 工具。
 * 阻塞执行一个子 Agent；子 Agent 独立规划、检索、入账并过自己的 final_answer 闸门，
 * 返回后把事实与检索日志按来源 Agent 合并回父 Agent。
 */
public class DelegateAgentTool implements ToolRegistry.AgentTool {

    private static final int MIN_SUB_AGENT_ROUNDS = 5;
    private static final int MAX_SUB_AGENT_ROUNDS = 60;

    private final Config.Data cfg;
    private final FactsStore parentFacts;
    private final SearchLog parentSearchLog;
    private final SkillState parentSkills;

    public DelegateAgentTool(Config.Data cfg, FactsStore parentFacts,
                             SearchLog parentSearchLog, SkillState parentSkills) {
        this.cfg = cfg;
        this.parentFacts = parentFacts;
        this.parentSearchLog = parentSearchLog;
        this.parentSkills = parentSkills;
    }

    @Override
    public String name() {
        return "delegate_agent";
    }

    @Override
    public ToolDef definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task", Map.of("type", "string",
                "description", "边界清晰的子任务完整描述，必须自包含"));
        properties.put("constraints", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "子任务硬约束，可选"));
        properties.put("required_facts", Map.of("type", "array", "description", """
                子任务必须覆盖的事实目标，可选但研究类任务建议提供。每项
                {"dimension":"指标维度","periods":["时期",...]}；dimension/period 会原样传给子 Agent""",
                "items", Map.of("type", "object", "properties", Map.of(
                        "dimension", Map.of("type", "string"),
                        "periods", Map.of("type", "array", "items", Map.of("type", "string"))),
                        "required", List.of("dimension", "periods"))));
        properties.put("context", Map.of("type", "string",
                "description", "父任务背景或只读事实切片，可选；不要传完整父对话"));
        properties.put("expected_output", Map.of("type", "string",
                "description", "期望的子任务输出格式与完成标准，可选"));
        properties.put("max_rounds", Map.of("type", "integer",
                "description", "子 Agent 最大轮次，默认 60，范围 5-60"));

        return new ToolDef(name(), """
                阻塞式运行一个子 Agent，用于边界清晰、需要独立检索或多轮执行的子任务。
                子 Agent 拥有独立任务计划、事实账本、检索日志和文件目录；完成后返回子任务报告，
                并把事实与检索记录按来源 Agent 合并回父 Agent。不要用于简单事实查询；
                调用前应将对应父任务标为 in_progress，收到结果并确认无冲突后再 update_task。
                """, Map.of("type", "object", "properties", properties,
                "required", List.of("task")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String task = ToolRegistry.str(input, "task");
        List<String> constraints = strings(input, "constraints");
        Map<String, List<String>> requiredFacts = requiredFacts(input);
        String context = ToolRegistry.optStr(input, "context");
        String expectedOutput = ToolRegistry.optStr(input, "expected_output");
        int maxRounds = clamp(ToolRegistry.optInt(input, "max_rounds",
                Agent.DEFAULT_SUB_AGENT_ROUNDS));

        String agentId = "child-" + UUID.randomUUID().toString().substring(0, 8);
        Path workspace = Config.rootDir(cfg.storage())
                .resolve("subagents").resolve(agentId).toAbsolutePath().normalize();
        Files.createDirectories(workspace);

        Skill inheritedSkill = parentSkills.get();
        Agent child = Agent.createSubAgent(cfg, agentId, workspace,
                parentSearchLog.entries(), inheritedSkill, requiredFacts, maxRounds);

        System.out.println(Console.tool("[delegate_agent] 启动子Agent " + agentId
                + "（最大 " + maxRounds + " 轮，目录 " + workspace + "）"));
        Agent.SubAgentResult result = child.runAsSubAgent(buildRequest(
                task, constraints, requiredFacts, context, expectedOutput, inheritedSkill));

        FactsStore.MergeResult factMerge = parentFacts.mergeChild(result.facts(), agentId);
        SearchLog.MergeResult searchMerge = parentSearchLog.merge(result.searches());
        System.out.println(Console.tool("[delegate_agent] 子Agent " + agentId + " 完成：事实新增 "
                + factMerge.added() + " / 冲突 " + factMerge.conflicts().size()
                + "，检索新增 " + searchMerge.added()));

        return report(agentId, workspace, result.answer(), factMerge, searchMerge);
    }

    private static String buildRequest(String task, List<String> constraints,
                                       Map<String, List<String>> requiredFacts, String context,
                                       String expectedOutput, Skill skill) {
        StringBuilder request = new StringBuilder("请执行以下子任务契约。\n\n## 子任务\n")
                .append(task);

        if (!constraints.isEmpty()) {
            request.append("\n\n## 硬约束\n");
            constraints.forEach(c -> request.append("- ").append(c).append('\n'));
        }
        if (context != null && !context.isBlank()) {
            request.append("\n\n## 父任务背景 / 只读输入\n").append(context.strip());
        }
        if (!requiredFacts.isEmpty()) {
            request.append("\n\n## 必须覆盖的事实目标\n")
                    .append("dimension 与 period 必须严格照抄下列字符串入账：\n");
            requiredFacts.forEach((dimension, periods) -> request.append("- dimension: ")
                    .append(dimension).append("\n  periods: ").append(String.join("、", periods)).append('\n'));
        }
        if (expectedOutput != null && !expectedOutput.isBlank()) {
            request.append("\n\n## 期望输出\n").append(expectedOutput.strip());
        }
        if (skill != null) {
            request.append("\n\n## 已继承技能「").append(skill.name())
                    .append("」（子任务规划与执行必须遵循）\n")
                    .append(skill.instructions());
        }

        request.append("""

                ## 执行要求
                1. 先分析子任务并建立自己的任务清单。
                2. 每得到关键数据立即 record_facts，含数值、时期、口径、来源与状态。
                3. 不向用户提问；信息不足时明确假设，或用 status=not_found 写明已尝试方式。
                4. 不要扩展到父任务全貌，也不要假设兄弟子任务结论。
                5. 完成时必须调用 final_answer 提交完整子任务报告。
                """);
        return request.toString();
    }

    private static String report(String agentId, Path workspace, String answer,
                                 FactsStore.MergeResult factMerge,
                                 SearchLog.MergeResult searchMerge) {
        StringBuilder sb = new StringBuilder("子 Agent ").append(agentId).append(" 执行完成。\n\n")
                .append("## 子 Agent 最终报告\n").append(answer).append('\n')
                .append("\n## 子 Agent 文件目录\n").append(workspace).append('\n')
                .append("\n## 事实合并（按来源 Agent 保留，不互相覆盖）\n")
                .append("- 新增: ").append(factMerge.added())
                .append("; 更新: ").append(factMerge.updated())
                .append("; 无变化: ").append(factMerge.unchanged())
                .append("; 互证: ").append(factMerge.corroborated())
                .append("; 冲突: ").append(factMerge.conflicts().size()).append('\n');
        if (!factMerge.conflicts().isEmpty()) {
            sb.append("冲突明细（父 Agent 必须复核，不得直接忽略）：\n");
            factMerge.conflicts().forEach(c -> sb.append("- ").append(c).append('\n'));
        }
        sb.append("\n## 检索合并（按来源 Agent 保留）\n")
                .append("- 新增: ").append(searchMerge.added())
                .append("; 已存在跳过: ").append(searchMerge.skipped()).append('\n')
                .append("""

                        ## 父 Agent 下一步
                        1. 核对子 Agent 报告与已合并事实账本；有冲突时先复核冲突。
                        2. 子任务满足要求后调用 update_task 将对应父任务标为 done。
                        3. 不要重复检索已合并且无冲突的事实。
                        """);
        return sb.toString();
    }

    private static List<String> strings(JsonNode input, String key) {
        JsonNode value = input.get(key);
        return value == null || !value.isArray() ? List.of()
                : ToolRegistry.strList(input, key);
    }

    private static Map<String, List<String>> requiredFacts(JsonNode input) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (JsonNode node : input.path("required_facts")) {
            String dimension = node.path("dimension").asText(null);
            if (dimension == null || dimension.isBlank()) {
                continue;
            }
            List<String> periods = new ArrayList<>();
            for (JsonNode period : node.path("periods")) {
                if (!period.isNull() && !period.asText("").isBlank()) {
                    periods.add(period.asText().strip());
                }
            }
            if (!periods.isEmpty()) {
                out.putIfAbsent(dimension.strip(), periods);
            }
        }
        return out;
    }

    private static int clamp(int value) {
        return Math.clamp(value, MIN_SUB_AGENT_ROUNDS, MAX_SUB_AGENT_ROUNDS);
    }
}
