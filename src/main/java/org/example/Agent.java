package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 父 Agent（编排器）：规划（analyze_query）→ 派发（execute_task，每次新建子代理执行）→
 * 综合事实账本与子代理结果，调用终止工具 final_answer 提交最终答案。
 * 提交后仍做 LLM 终止校验（对照述求与账本）：未通过则程序化重新规划（缺陷注入、进度继承），
 * 连续 3 次未通过 best-effort 返回；纯文本回复不结束任务（视为隐式提交，同样走校验）。
 * 轮转 / 压缩 / 校验循环骨架复用 {@link AgentLoop}。
 */
public class Agent extends AgentLoop<String> {

    /** 父代理最大轮次。 */
    private static final int MAX_ROUNDS = 90;

    /** 解析强制重规划参数复用。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    public Agent() {
        this(Deps.build(Config.load()));
    }

    private Agent(Deps d) {
        super(d.llm(), d.quietLlm(), d.registry(), d.facts(), d.tasks(), d.streaming());
    }

    /** 一次性组装依赖再传给基类（super() 必须先执行，用静态工厂先建好 tasks/facts/registry 以复用同一实例）。 */
    private record Deps(LlmClient llm, LlmClient quietLlm, ToolRegistry registry,
                        FactsStore facts, TaskStore tasks, boolean streaming) {
        static Deps build(Config.Data cfg) {
            LlmClient llm = LlmClient.create(cfg.llm());
            // 校验用 quiet 客户端：无工具、非流式——嵌套调用的增量输出不应打进主循环控制台
            LlmClient quietLlm = LlmClient.create(new Config.Llm(cfg.llm().provider(), cfg.llm().baseUrl(),
                    cfg.llm().model(), cfg.llm().apiKey(), cfg.llm().maxTokens(), cfg.llm().temperature(), false, 0));
            TaskStore tasks = new TaskStore();
            FactsStore facts = new FactsStore();
            // 工具集由首轮 analyze_query 判定的执行模式决定：编排模式=纯编排器，单代理模式=全量工具
            ModeControl mode = new ModeControl();
            ToolRegistry registry = new ToolRegistry(cfg, tasks, facts, mode);
            return new Deps(llm, quietLlm, registry, facts, tasks, cfg.llm().streaming());
        }
    }

    /** 入口：原始述求常驻（重规划不清），随后进入共享循环。 */
    public String run(String request) {
        tasks.original(request); // 子代理简报锚点：无论是否规划，原始述求常驻
        return runLoop(request);
    }

    @Override
    protected String persona() {
        return SystemPrompt.PERSONA;
    }

    @Override
    protected String tag() {
        return "";
    }

    @Override
    protected int maxRounds() {
        return MAX_ROUNDS;
    }

    @Override
    protected String roundHeader(int round) {
        return "======== 第 " + round + "/" + maxRounds() + " 轮 ========";
    }

    @Override
    protected String seedLabel() {
        return "原始任务述求";
    }

    @Override
    protected String resumeInstruction() {
        return "请基于以上进度继续完成任务；最终答案的数据以事实账本为准。";
    }

    /** 确定性重规划：程序化调用 analyze_query（缺陷注入，进度与账本继承，只补剩余工作）；
     *  失败则降级为仅反馈缺陷，不阻断。 */
    @Override
    protected void replan(String baseline, String defects) {
        try {
            ToolRegistry.AgentTool analyzer = registry.tool("analyze_query");
            if (analyzer != null) {
                String replanned = analyzer.execute(JSON.createObjectNode().put("query",
                        baseline + "\n上一版回答未通过最终校验，缺陷：\n" + defects
                                + "\n请结合缺陷与事实账本重新规划，只补剩余工作"));
                System.out.println("[强制重规划]\n" + replanned);
            }
        } catch (Exception e) {
            System.out.println(Console.warn("[强制重规划失败，仅反馈缺陷] " + e));
        }
    }

    @Override
    protected String defectFeedback(boolean viaFinalAnswer, String defects) {
        return viaFinalAnswer
                ? "你的最终答案未通过最终校验，存在以下缺陷：\n" + defects
                + "\n\n已按缺陷重新规划任务清单（见任务进度快照）。请用 execute_task 执行剩余待办任务"
                + "（子代理检索取数并入账），修正后【重新用 final_answer 提交完整的最终答案】"
                + "——不要只输出补丁，必须完整覆盖述求所问。"
                : "你的回答未通过最终校验，存在以下缺陷：\n" + defects
                + "\n\n已按缺陷重新规划任务清单（见任务进度快照）。请用 execute_task 执行剩余待办任务"
                + "（子代理检索取数并入账），然后【重新提交完整的最终答案】——不要只输出补丁，"
                + "必须完整覆盖述求所问。";
    }

    @Override
    protected String pass(String candidate) {
        return candidate;
    }

    @Override
    protected String bestEffort(String candidate, String defects) {
        return candidate + "\n\n（注意：本回答未通过最终校验，遗留缺陷：\n" + defects + "\n）";
    }

    @Override
    protected String exhausted(String lastText) {
        throw new IllegalStateException("超过最大轮次 " + maxRounds() + "，任务未完成");
    }
}
