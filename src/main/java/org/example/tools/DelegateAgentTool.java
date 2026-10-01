package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.Agent;
import org.example.AgentOutput;
import org.example.Config;
import org.example.Console;
import org.example.FactsStore;
import org.example.RunArchive;
import org.example.SearchLog;
import org.example.Skill;
import org.example.SkillState;
import org.example.SubAgentStore;
import org.example.ToolDef;
import org.example.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * delegate_agent：子 Agent 委派工具。单个调用等待该子 Agent 完成；
 * Agent Loop 会把同一批 delegate_agent 提交到线程池并发执行。
 */
public class DelegateAgentTool implements ToolRegistry.AgentTool {

    private static final int MIN_SUB_AGENT_ROUNDS = 5;
    private static final int MAX_SUB_AGENT_ROUNDS = 60;

    private final Config.Data cfg;
    private final FactsStore parentFacts;
    private final SearchLog parentSearchLog;
    private final SkillState parentSkills;
    private final SubAgentStore reports;
    private final RunArchive parentArchive;

    public DelegateAgentTool(Config.Data cfg, FactsStore parentFacts,
                             SearchLog parentSearchLog, SkillState parentSkills,
                             RunArchive parentArchive, SubAgentStore reports) {
        this.cfg = cfg;
        this.parentFacts = parentFacts;
        this.parentSearchLog = parentSearchLog;
        this.parentSkills = parentSkills;
        this.parentArchive = parentArchive;
        this.reports = reports;
    }

    @Override
    public String name() {
        return "delegate_agent";
    }

    @Override
    public ToolDef definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task", Map.of("type", "string",
                "description", """
                        边界清晰的子任务完整描述，必须自包含。指定 variant 时还必须携带版本契约、
                        本版本可独立裁决的决策变量、差异约束和版本专属研究/检索策略"""));
        properties.put("variant", Map.of("type", "string", "description", """
                子 Agent 负责的方案版本名。用户要求多个并列版本 / 方案 / 情景时必填，
                单版本任务可省略"""));
        properties.put("constraints", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "子任务硬约束，可选"));
        properties.put("required_facts", Map.of("type", "array", "description", """
                子任务必须覆盖的事实目标，可选但研究类任务建议提供。每项
                {"dimension":"指标维度","periods":["时期",...]}。传 variant 时会自动补版本前缀；
                「版本：指标」与「版本·指标」会统一归一，避免字符串手滑导致覆盖失败。""",
                "items", Map.of("type", "object", "properties", Map.of(
                        "dimension", Map.of("type", "string"),
                        "periods", Map.of("type", "array", "items", Map.of("type", "string"))),
                        "required", List.of("dimension", "periods"))));
        properties.put("context", Map.of("type", "string",
                "description", """
                        父任务背景，可选；不要手写事实数值，父事实账本会机器生成只读切片自动传给子 Agent。
                        多版本任务只允许描述公用事实、保护约束和评价口径；不得下传版本契约标明的版本决策"""));
        properties.put("expected_output", Map.of("type", "string",
                "description", "期望的子任务输出格式与完成标准，可选"));
        properties.put("max_rounds", Map.of("type", "integer",
                "description", "子 Agent 最大轮次，默认 60，范围 5-60"));

        return new ToolDef(name(), """
                运行一个子 Agent，用于一个独立交付物、方案实例或边界清晰的完整子任务。
                子 Agent 拥有独立任务计划、事实账本、检索日志和文件目录；完成后返回子任务报告，
                并把事实与检索记录按来源 Agent 合并回父 Agent。同一轮的多个 delegate_agent 会并发执行；
                子 Agent 日志写入 subagents/<agent-id>/agent.log.md。不要用于简单事实查询；
                多个并列版本 / 方案 / 情景必须一个版本一个子 Agent，并传 variant。
                多版本的公用事实可以共享；版本决策变量必须由各 variant 独立推导并在报告中给出可核验证据。
                调用前应将对应父任务标为 in_progress，收到结果并确认无冲突后再 update_task。
                """, Map.of("type", "object", "properties", properties,
                "required", List.of("task")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String task = ToolRegistry.str(input, "task");
        String variant = normalize(ToolRegistry.optStr(input, "variant"));
        List<String> constraints = strings(input, "constraints");
        Map<String, List<String>> requiredFacts = requiredFacts(input, variant);
        requiredFacts = withVariantDecisionTarget(variant, requiredFacts);
        String context = ToolRegistry.optStr(input, "context");
        String expectedOutput = ToolRegistry.optStr(input, "expected_output");
        int maxRounds = clamp(ToolRegistry.optInt(input, "max_rounds",
                Agent.DEFAULT_SUB_AGENT_ROUNDS));

        String executionId = "child-" + UUID.randomUUID().toString().substring(0, 8);
        String agentId = variant == null ? executionId : executionId + "::" + variant;
        Path workspace = parentArchive.newSubAgentWorkspace(executionId);

        Skill inheritedSkill = parentSkills.get();
        List<FactsStore.Fact> factSnapshot;
        String factSlice;
        synchronized (parentFacts) {
            factSnapshot = parentFacts.facts();
            factSlice = parentFacts.factSlice();
        }
        List<SearchLog.Entry> searchSnapshot;
        synchronized (parentSearchLog) {
            searchSnapshot = parentSearchLog.entries();
        }
        Agent child = Agent.createSubAgent(cfg, agentId, workspace,
                searchSnapshot, factSnapshot, inheritedSkill,
                requiredFacts, maxRounds);

        AgentOutput.println(Console.tool("[delegate_agent] 启动子Agent " + agentId
                + "（最大 " + maxRounds + " 轮，目录 " + workspace + "）"));
        Agent.SubAgentResult result = child.runAsSubAgent(buildRequest(
                task, variant, constraints, requiredFacts, context, expectedOutput,
                inheritedSkill, factSlice));

        FactsStore.MergeResult factMerge;
        SearchLog.MergeResult searchMerge;
        synchronized (parentFacts) {
            factMerge = parentFacts.mergeChild(result.facts(), agentId);
        }
        synchronized (parentSearchLog) {
            searchMerge = parentSearchLog.merge(result.searches());
        }
        Path reportPath = workspace.resolve("final-report.md");
        Files.writeString(reportPath, result.answer());
        RunArchive childArchive = RunArchive.forWorkspace(workspace, agentId);
        childArchive.write("archive-index.md", childArchive.markdownIndex());
        reports.add(agentId, variant, reportPath, factMerge.conflicts());
        AgentOutput.println(Console.tool("[delegate_agent] 子Agent " + agentId + " 完成：事实新增 "
                + factMerge.added() + " / 冲突 " + factMerge.conflicts().size()
                + "，检索新增 " + searchMerge.added()));

        return report(agentId, workspace, result.answer(), factMerge, searchMerge);
    }

    private static String buildRequest(String task, String variant, List<String> constraints,
                                       Map<String, List<String>> requiredFacts, String context,
                                       String expectedOutput, Skill skill, String factSlice) {
        StringBuilder request = new StringBuilder("请执行以下子任务契约。\n");
        if (variant != null) {
            request.append("\n## 唯一方案版本\n").append(variant)
                    .append("\n你负责且只负责这个版本的完整交付物；禁止规划、参考或假设其他兄弟版本。\n");
            request.append("""

                    ## 版本决策变量隔离
                    任务契约标明的版本决策变量由你独立推导，不得因常见做法、公用事实或其他版本的通常选择直接沿用。
                    公用事实只用于可行性校验和事实对账，不能替代版本决策。
                    不可交易的用户约束优先于版本差异最大化；不得为版本分离而改变用户明示的执行方式、研究对象范围或交付形态。

                    ## 版本差异约束
                    按版本契约给出可核验的差异证据，并说明已选方案和被拒绝的替代方案及理由；
                    不得只调整表面参数而保留实质相同的方案结构。

                    ## 版本专属研究/检索
                    若契约要求专属检索策略，必须使用本版本目标驱动的查询族，不能只复用所有版本相同的一般性查询。
                    检索结果应进入可比方案假设。
                    """);
        }
        request.append("\n## 子任务\n")
                .append(task);

        if (!constraints.isEmpty()) {
            request.append("\n\n## 硬约束\n");
            constraints.forEach(c -> request.append("- ").append(c).append('\n'));
        }
        if (context != null && !context.isBlank()) {
            request.append("\n\n## 父任务背景 / 只读输入\n").append(context.strip());
        }
        if (factSlice != null && !factSlice.isBlank()) {
            request.append("\n\n").append(factSlice)
                    .append("\n使用规则：以上父Agent事实切片是只读权威输入；不要重复检索已列数据，")
                    .append("新增数据必须 record_facts 入账，最终报告不得与切片或本层账本矛盾。\n");
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
                5. 若指定唯一方案版本，最终报告必须仅覆盖该版本的完整计划、数据、预算与风险。
                6. 若指定唯一方案版本，最终报告还必须包含版本决策账本：契约标明的决策变量、
                   已选方案、被拒替代方案和版本差异证据，并按 required_facts 入账。
                7. 完成时必须调用 final_answer 提交完整子任务报告。
                """);
        return request.toString();
    }

    private static String report(String agentId, Path workspace, String answer,
                                 FactsStore.MergeResult factMerge,
                                 SearchLog.MergeResult searchMerge) {
        StringBuilder sb = new StringBuilder("子 Agent ").append(agentId).append(" 执行完成。\n\n")
                .append("## 子 Agent 最终报告\n").append(answer).append('\n')
                .append("\n## 子 Agent 文件目录\n").append(workspace)
                .append("\n## 最终报告文件\n").append(workspace.resolve("final-report.md")).append('\n')
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

    private static Map<String, List<String>> requiredFacts(JsonNode input, String variant) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (JsonNode node : input.path("required_facts")) {
            String dimension = scopedDimension(variant, node.path("dimension").asText(null));
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
                out.putIfAbsent(dimension, periods);
            }
        }
        return out;
    }

    /**
     * 多版本子任务自动追加一条通用的“决策证据”覆盖目标。
     * 这里不预设任何领域决策维度；具体指标由版本契约、用户述求和已加载技能提供。
     */
    private static Map<String, List<String>> withVariantDecisionTarget(
            String variant, Map<String, List<String>> supplied) {
        if (variant == null) {
            return supplied;
        }
        Map<String, List<String>> out = new LinkedHashMap<>(supplied);
        out.putIfAbsent(scopedDimension(variant, "版本决策与差异证据"), List.of("最终方案"));
        return out;
    }

    /** 多版本目标自动加版本前缀，并容忍「版本：指标」等分隔符变体。 */
    private static String scopedDimension(String variant, String dimension) {
        String d = FactsStore.canonicalDimension(dimension);
        if (variant == null || d == null || d.isBlank()) {
            return d;
        }
        for (String sep : List.of("·", "：", ":")) {
            String prefix = variant + sep;
            if (d.startsWith(prefix)) {
                return variant + "·" + d.substring(prefix.length()).strip();
            }
        }
        return variant + "·" + d;
    }

    private static int clamp(int value) {
        return Math.clamp(value, MIN_SUB_AGENT_ROUNDS, MAX_SUB_AGENT_ROUNDS);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
