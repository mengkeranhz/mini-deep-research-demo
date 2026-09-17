package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Agent（编排器 {@link AgentLoop}。
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
        super(d.llm(), d.quietLlm(), d.registry(), d.facts(), d.streaming());
    }

    /** 一次性组装依赖再传给基类（super() 必须先执行，用静态工厂先建好 tasks/facts/registry 以复用同一实例）。 */
    private record Deps(LlmClient llm, LlmClient quietLlm, ToolRegistry registry,
                        FactsStore facts, boolean streaming) {
        static Deps build(Config.Data cfg) {
            LlmClient llm = LlmClient.create(cfg.llm());
            // 校验用 quiet 客户端：无工具、非流式——嵌套调用的增量输出不应打进主循环控制台
            LlmClient quietLlm = LlmClient.create(new Config.Llm(cfg.llm().provider(), cfg.llm().baseUrl(),
                    cfg.llm().model(), cfg.llm().apiKey(), cfg.llm().maxTokens(), cfg.llm().temperature(), false, 0));
            FactsStore facts = new FactsStore();
            ToolRegistry registry = new ToolRegistry(cfg, facts);
            return new Deps(llm, quietLlm, registry, facts, cfg.llm().streaming());
        }
    }

    /** 入口：原始述求常驻（重规划不清），随后进入共享循环。 */
    public String run(String request) {
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

    @Override
    protected String defectFeedback(String defects) {
        return "你的最终答案未通过最终校验，存在以下缺陷：\n" + defects;
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
