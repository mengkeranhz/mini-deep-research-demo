package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.Config;
import org.example.Console;
import org.example.FactsStore;
import org.example.LlmClient;
import org.example.ModeControl;
import org.example.Msg;
import org.example.TaskStore;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * analyze_query：把用户述求解析为计划 + 任务清单 JSON。
 * 内部复用主模型（关流式）做一次无工具调用，目标与任务写入 TaskStore，Agent 每轮注入进度快照；
 * 可随时重复调用重新规划（述求本身也允许调整）：注入任务进度与事实账本两份核心状态，
 * 已完成任务按内容匹配继承完成状态。
 */
public class AnalyzeQueryTool implements AgentTool {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String PROMPT = """
            你是查询分析器。把用户述求解析为结构化 JSON，只输出 JSON 本身，不要解释、不要 markdown 围栏。
            字段：
            - plan: 一句话总体执行策略
            - constraints: 硬约束数组（时间、范围、口径、方式等明确限制），无则空数组
            - unknowns: 未知/待定点数组（用户未说清、需澄清或需做假设之处），无则空数组
            - core_needs: 核心述求数组（用户最核心要回答的几个问题/要点，每条一句短语），无则空数组
            - mode: "orchestrator" 或 "single"，判定述求适合哪种执行模式。
              "orchestrator"（主+子代理）：述求可拆解为多路相互独立的深度检索/取数（多主题、多数据点、多子问题），检索取数下沉子代理；
              "single"（单代理）：单一、连贯、强耦合的任务（如路线/行程规划、单跳问答、需全程一个上下文连续推理），父代理亲自检索取数、不派发子任务。
            - tasks: 3-6 个可执行任务，每项为一个任务描述字符串。
              任务按数据获取方式组织：检索能解决的写检索任务，检索拿不到的写工程任务（run_code），不要都规划成换关键词的搜索。
              mode=orchestrator 时每个任务由独立子代理执行——子代理只能看到原始述求与该任务文本，任务描述必须自包含：写明背景、范围、时期、口径、单位与期望产出；
              mode=single 时任务由父代理亲自执行。
            若提供了当前进度或事实账本：结合已知信息规划，只补剩余工作；目标需要调整时按调整后的目标给出。
            """;

    private final Config.Llm llmCfg;
    private final TaskStore tasks;
    private final FactsStore facts;
    /** 可空：父代理据此切换执行模式（子代理无模式控制）。 */
    private final ModeControl mode;

    public AnalyzeQueryTool(Config.Llm llmCfg, TaskStore tasks, FactsStore facts, ModeControl mode) {
        this.llmCfg = llmCfg;
        this.tasks = tasks;
        this.facts = facts;
        this.mode = mode;
    }

    @Override
    public String name() {
        return "analyze_query";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "分析用户述求，给出总体计划并拆解为任务清单。初始规划或执行中需要调整时可随时重复调用重新规划"
                        + "（述求本身也允许调整，如范围、口径、目标变化），重新规划基于当前进度与已知信息。",
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
        // 重规划注入两份核心状态：新计划基于已知信息，只补剩余工作，不丢已完成结论
        List<Msg> planMsgs = new ArrayList<>();
        planMsgs.add(Msg.system(PROMPT));
        String progress = tasks.snapshot();
        addSnapshot(planMsgs, progress);
        addSnapshot(planMsgs, facts.snapshot());
        if (progress != null) {
            planMsgs.add(Msg.system("重规划规则：新计划基于以上已知信息，只补剩余工作；"
                    + "已完成任务在新清单中保留原文（逐字一致）即继承完成状态，也可只列剩余任务。"));
        }
        planMsgs.add(Msg.user(query));
        String raw = LlmClient.create(quiet).call(List.of(), planMsgs).text();
        JsonNode root = extractJson(raw);

        // 归一化：过滤空任务，id 按原始序号 t1..tn（容忍对象形式 {"content": ...}）
        List<TaskStore.Task> parsed = new ArrayList<>();
        JsonNode arr = root.path("tasks");
        for (int i = 0; i < arr.size(); i++) {
            JsonNode n = arr.get(i);
            String content = (n.isObject() ? n.path("content").asText("") : n.asText("")).strip();
            if (!content.isEmpty()) {
                parsed.add(new TaskStore.Task("t" + (i + 1), content, "pending", null));
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("规划结果中没有可执行任务，原始输出: " + abbreviate(raw));
        }
        List<String> constraints = strList(root.path("constraints"));
        List<String> unknowns = strList(root.path("unknowns"));
        List<String> coreNeeds = strList(root.path("core_needs"));
        tasks.reset(query, root.path("plan").asText(""), constraints, unknowns, coreNeeds, parsed);

        // 判定执行模式（首判锁定）：父代理据此切换工具集，子代理无模式控制
        String decided = ModeControl.ORCHESTRATOR;
        if (mode != null) {
            mode.set(root.path("mode").asText("").strip());
            decided = mode.get();
            System.out.println(Console.header("[执行模式] ") + decided
                    + (ModeControl.SINGLE.equals(decided)
                        ? "（单代理：禁用 execute_task，父代理亲自检索取数）"
                        : "（主+子：检索取数下沉子代理）"));
        }

        // 归一化后的 JSON 回给模型（含任务 id 与继承后的真实状态——重声明原文的任务已是 done）
        ObjectNode out = M.createObjectNode();
        out.put("plan", root.path("plan").asText(""));
        out.put("mode", decided);
        out.set("constraints", M.valueToTree(constraints));
        out.set("unknowns", M.valueToTree(unknowns));
        out.set("core_needs", M.valueToTree(coreNeeds));
        ArrayNode ts = out.putArray("tasks");
        for (TaskStore.Task t : tasks.all()) {
            ObjectNode o = ts.addObject();
            o.put("id", t.id()).put("content", t.content()).put("status", t.status());
        }
        return M.writerWithDefaultPrettyPrinter().writeValueAsString(out)
                + (ModeControl.SINGLE.equals(decided)
                    ? "\n任务清单已保存。本述求判定为单代理模式，请亲自执行待办任务（execute_task 已禁用）。"
                    : "\n任务清单已保存。请按顺序用 execute_task 执行待办任务。");
    }

    /** 字符串数组读取：非数组返回空列表，元素去空白、去空项。 */
    private static List<String> strList(JsonNode arr) {
        List<String> list = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> list.add(n.asText().strip()));
        }
        return list.stream().filter(s -> !s.isEmpty()).toList();
    }

    /** 注入一份核心状态快照（空快照跳过，快照自带标题头）。 */
    private static void addSnapshot(List<Msg> planMsgs, String snapshot) {
        if (snapshot != null) {
            planMsgs.add(Msg.system(snapshot));
        }
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

    private static String abbreviate(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() <= 300 ? one : one.substring(0, 300) + "…";
    }
}
