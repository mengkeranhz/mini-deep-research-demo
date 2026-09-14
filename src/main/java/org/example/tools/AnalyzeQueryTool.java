package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.Config;
import org.example.FactsStore;
import org.example.LlmClient;
import org.example.Msg;
import org.example.TaskStore;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * analyze_query：把用户述求解析为结构化任务规划 JSON。
 * 内部复用主模型（关流式）做一次无工具调用，产出约束/信息缺口/总体计划/任务清单；
 * 任务归一化（稳定 id + 依赖校验）后写入 TaskStore，Agent 每轮注入进度快照。
 */
public class AnalyzeQueryTool implements AgentTool {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String PROMPT = """
            你是查询分析器。把用户述求解析为结构化 JSON，只输出 JSON 本身，不要解释、不要 markdown 围栏。
            字段：
            - constraints: 硬约束数组（时间、预算、方式等明确限制）
            - unknowns: 用户未说清、需澄清或需做假设的信息数组
            - plan: 一句话总体执行策略
            - required_facts: 需要检索的数据覆盖目标数组，每项 {"dimension": "指标维度", "periods": ["时期", ...]}。
              每项会被系统分配稳定 id（rf1、rf2…），record_facts 用 target 填对应 id 完成覆盖匹配。
              时期逐项枚举全（这会成为最终答案的覆盖度检查表，漏枚举即漏答）；粒度跟随数据实际发布口径——
              季度指标逐年逐季列出（如 ["2024","2024-Q1","2024-Q2","2024-Q3","2024-Q4",...]），
              年度指标只列年份；非时间序列数据用自然时期（如 ["国庆假期"]）。
              每项可带 "tier": "official"——官方例行发布的核心指标（统计公报、月报等）建议声明：
              该目标只认官方一手来源（record_facts 需 status=found 且 tier=official 才覆盖），
              第三方聚合/转载不覆盖；官方确实无此口径/未发布的，用 not_found 声明后在 note 写代理指标即可。
              非官方口径的维度（代理指标本身）省略 tier。
            - tasks: 3-6 个可执行任务，每项 {"content": "一个可独立推进的执行步骤", "depends_on": [前置任务的序号，从 1 起]}。
              任务按数据获取方式组织：检索能解决的写检索任务，检索拿不到的写工程任务（run_code），不要都规划成换关键词的搜索。
            示例（输入「查比亚迪最近2年每季度的营业收入和员工人数」）：
            {"constraints":["主体：比亚迪","时间范围：最近2年","频率：季度"],"unknowns":["员工人数是否只在年报披露"],"plan":"先确认两项指标的披露频率与口径，再按各自粒度检索","required_facts":[{"dimension":"营业收入","periods":["2025","2025-Q1","2025-Q2","2025-Q3","2025-Q4","2026","2026-Q1","2026-Q2","2026-Q3","2026-Q4"],"tier":"official"},{"dimension":"员工人数","periods":["2025","2026"]}],"tasks":[{"content":"查比亚迪季报中的营业收入","depends_on":[]},{"content":"查比亚迪年报中的员工人数","depends_on":[]},{"content":"汇总对比两项指标变化","depends_on":[1,2]}]}
            """;

    private final Config.Llm llmCfg;
    private final TaskStore tasks;
    private final FactsStore facts;

    public AnalyzeQueryTool(Config.Llm llmCfg, TaskStore tasks, FactsStore facts) {
        this.llmCfg = llmCfg;
        this.tasks = tasks;
        this.facts = facts;
    }

    @Override
    public String name() {
        return "analyze_query";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "分析用户述求：识别硬约束与信息缺口，给出总体计划并拆解为带依赖的任务清单（JSON）。"
                        + "required_facts 每项会被分配稳定 id（rf1、rf2…），record_facts 用 target 引用；"
                        + "重规划时只声明新增的覆盖目标，已有目标不要重复声明。"
                        + "任务按数据获取方式组织：检索能解决的写检索任务，检索拿不到的可安排工程任务（run_code）。"
                        + "初始规划或执行中发现新信息、需要新任务时随时可调用；重规划会基于当前进度、复用已完成结论。"
                        + "生成的任务状态每轮以系统快照自动注入。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "要分析的目标：初始述求，或结合新发现需要重新规划的目标描述")),
                        "required", List.of("query")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String query = ToolRegistry.str(input, "query");
        // 复用主模型、关闭流式：嵌套调用的增量输出不应打进主循环控制台
        Config.Llm quiet = new Config.Llm(llmCfg.provider(), llmCfg.baseUrl(), llmCfg.model(),
                llmCfg.apiKey(), llmCfg.maxTokens(), llmCfg.temperature(), false);
        // 重规划时注入当前进度，让新计划基于已掌握信息、仅补齐剩余缺口
        List<Msg> planMsgs = new ArrayList<>();
        planMsgs.add(Msg.system(PROMPT));
        String snapshot = tasks.snapshot();
        if (snapshot != null) {
            planMsgs.add(Msg.system("当前进度（供重规划参考，尽量复用已完成结论，只补齐缺口）：\n" + snapshot
                + "\n规则：已完成任务若需保留在 tasks 中，content 必须与上版逐字一致（系统据此继承「完成」状态，"
                + "不要改写或同义替换）；也可以直接省略已完成任务，只列待办与新任务。"));
        }
        // 重规划同样只声明增量覆盖目标：注入已声明清单，防止全量重复申报使目标无限膨胀、覆盖缺口永不收敛
        String declared = facts.declaredTargets();
        if (declared != null) {
            planMsgs.add(Msg.system("已声明的数据覆盖目标（required_facts，已保存在事实账本）：\n" + declared
                + "\n规则：required_facts 只声明增量——新增维度，或已有维度补充的新时期"
                + "（dimension 必须与上表逐字一致才会并入同一目标，不要改写或同义替换）；"
                + "上表已有的维度与时期一律不要重复声明。"));
        }
        planMsgs.add(Msg.user(query));
        String raw = LlmClient.create(quiet).call(List.of(), planMsgs).text();
        JsonNode root = extractJson(raw);

        // 归一化：id 取原始序号（t1..tn，可能有空洞）；depends_on 序号越界、指向空任务或自身则丢弃
        JsonNode arr = root.path("tasks");
        Set<Integer> kept = new HashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            if (!arr.get(i).path("content").asText("").isBlank()) {
                kept.add(i + 1);
            }
        }
        List<TaskStore.Task> parsed = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            String content = arr.get(i).path("content").asText("").strip();
            if (content.isEmpty()) {
                continue;
            }
            List<String> deps = new ArrayList<>();
            for (JsonNode d : arr.get(i).path("depends_on")) {
                int no = d.asInt(-1);
                if (kept.contains(no) && no != i + 1) {
                    deps.add("t" + no);
                }
            }
            parsed.add(new TaskStore.Task("t" + (i + 1), content, deps, "pending", null));
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("规划结果中没有可执行任务，原始输出: " + abbreviate(raw));
        }
        tasks.reset(new TaskStore.Header(root.path("plan").asText(""),
                strList(root.path("constraints")), strList(root.path("unknowns"))), parsed);

        // 覆盖目标写入事实账本（追加合并，重规划只补不丢），作为最终答案的覆盖度检查表；每项分配稳定 id。
        // 只回显增量：目标已存在且无新时期（或维度为空）的声明直接忽略——重规划全量重复申报时账本与
        // 返回值都不膨胀，覆盖缺口才可能收敛归零，避免「每次重规划都长出新目标」的死循环
        ArrayNode rfs = M.createArrayNode();
        for (JsonNode rf : root.path("required_facts")) {
            FactsStore.Requirement r = facts.require(rf.path("dimension").asText(null), strList(rf.path("periods")),
                    rf.path("tier").asText(""));
            if (r == null || (!r.newTarget() && r.addedPeriods().isEmpty())) {
                continue;
            }
            ObjectNode o = rfs.addObject();
            o.put("id", r.id());
            o.put("dimension", rf.path("dimension").asText(null));
            o.set("periods", M.valueToTree(r.addedPeriods()));
            if (!r.newTarget()) {
                o.put("appended", true); // 并入已有目标：periods 仅为本次新增的时期
            }
        }

        // 归一化后的 JSON 回给模型（含分配的目标 id 与任务初始状态）
        ObjectNode out = M.createObjectNode();
        out.set("constraints", M.valueToTree(strList(root.path("constraints"))));
        out.set("unknowns", M.valueToTree(strList(root.path("unknowns"))));
        out.put("plan", root.path("plan").asText(""));
        if (!rfs.isEmpty()) {
            out.set("required_facts", rfs);
        }
        ArrayNode ts = out.putArray("tasks");
        for (TaskStore.Task t : parsed) {
            ObjectNode o = ts.addObject();
            o.put("id", t.id()).put("content", t.content());
            o.set("depends_on", M.valueToTree(t.dependsOn()));
            o.put("status", t.status());
        }
        return M.writerWithDefaultPrettyPrinter().writeValueAsString(out)
                + "\n任务清单已保存；required_facts 仅含本次新增的覆盖目标（无新增时不列出，已有目标在事实账本中保持不变）。"
                + "请从「可执行」的任务开始执行。";
    }

    /** 剥掉可能的围栏与说明文字：取首个 { 到末个 } 的片段解析。 */
    private static JsonNode extractJson(String text) {
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        if (s < 0 || e <= s) {
            throw new IllegalArgumentException("分析输出中没有 JSON: " + abbreviate(text));
        }
        try {
            return M.readTree(text.substring(s, e + 1));
        } catch (Exception ex) {
            throw new IllegalArgumentException("分析输出 JSON 解析失败: " + abbreviate(text), ex);
        }
    }

    private static List<String> strList(JsonNode arr) {
        List<String> list = new ArrayList<>();
        if (arr.isArray()) {
            arr.forEach(n -> list.add(n.asText().strip()));
        }
        return list.stream().filter(s -> !s.isEmpty()).toList();
    }

    private static String abbreviate(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() <= 300 ? one : one.substring(0, 300) + "…";
    }
}
