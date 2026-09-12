package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.Config;
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
            - tasks: 3-6 个可执行任务，每项 {"content": "一次工具调用可完成的动作", "depends_on": [前置任务的序号，从 1 起]}
            示例（输入「国庆想去敦煌和德令哈玩 5 天，重点抢莫高窟票」）：
            {"constraints":["国庆假期","共 5 天"],"unknowns":["出发城市","人数"],"plan":"先查门票与交通的预订规则，再排定行程","tasks":[{"content":"查莫高窟门票预订规则与放票时间","depends_on":[]},{"content":"查敦煌到德令哈的交通方式","depends_on":[]},{"content":"排定逐日行程","depends_on":[1,2]}]}
            """;

    private final Config.Llm llmCfg;
    private final TaskStore tasks;

    public AnalyzeQueryTool(Config.Llm llmCfg, TaskStore tasks) {
        this.llmCfg = llmCfg;
        this.tasks = tasks;
    }

    @Override
    public String name() {
        return "analyze_query";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "分析用户述求：识别硬约束与信息缺口，给出总体计划并拆解为带依赖的任务清单（JSON）。"
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
            planMsgs.add(Msg.system("当前进度（供重规划参考，尽量复用已完成结论，只补齐缺口）：\n" + snapshot));
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

        // 归一化后的 JSON 回给模型（含分配的 id 与初始状态）
        ObjectNode out = M.createObjectNode();
        out.set("constraints", M.valueToTree(strList(root.path("constraints"))));
        out.set("unknowns", M.valueToTree(strList(root.path("unknowns"))));
        out.put("plan", root.path("plan").asText(""));
        ArrayNode ts = out.putArray("tasks");
        for (TaskStore.Task t : parsed) {
            ObjectNode o = ts.addObject();
            o.put("id", t.id()).put("content", t.content());
            o.set("depends_on", M.valueToTree(t.dependsOn()));
            o.put("status", t.status());
        }
        return M.writerWithDefaultPrettyPrinter().writeValueAsString(out)
                + "\n任务清单已保存。请从「可执行」的任务开始执行；开始或完成时调用 update_task 更新状态，"
                + "发现新信息需要调整计划时可再次调用 analyze_query 重新规划。";
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
