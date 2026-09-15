package org.example;

/**
 * 子 Agent：每次 execute_task 新建实例、全新上下文，独立完成一个自包含任务。
 * 终止协议与父 Agent 对齐：调用终止工具 final_answer 提交最终答案，或某轮纯文本视为隐式提交，
 * 二者都经 LLM 终止校验，未通过则反馈缺陷继续修正（连续 3 次未通过 best-effort 返回部分结果）；
 * 子代理同样可调用 analyze_query 自主规划（独立 TaskStore，不与父共享）。
 * 看不到父对话与计划清单（仅凭简报锚定原始述求），与父共享同一事实账本实例。
 * 轮次耗尽软着陆：不抛异常，返回部分结果，由父层决定重派或收尾。
 * 轮转 / 压缩 / 校验循环骨架复用 {@link AgentLoop}。
 */
public class SubAgent extends AgentLoop<SubAgent.Result> {

    /** 子代理最大轮次。 */
    private static final int MAX_ROUNDS = 90;

    /** 执行结果：completed=true 正常完成（text 为最终文本）；false 为部分结果（轮次耗尽或空输出）。 */
    public record Result(boolean completed, String text) {}

    public SubAgent(LlmClient llm, LlmClient quietLlm, ToolRegistry registry, FactsStore facts, TaskStore tasks,
                    boolean streaming) {
        super(llm, quietLlm, registry, facts, tasks, streaming);
    }

    /** 执行一个子任务：brief 为自包含简报（原始述求 + 任务文本 + 提示 + 账本快照）。 */
    public Result run(String brief) {
        return runLoop(brief);
    }

    @Override
    protected String persona() {
        return SystemPrompt.SUB_PERSONA;
    }

    @Override
    protected String tag() {
        return "子代理·";
    }

    @Override
    protected int maxRounds() {
        return MAX_ROUNDS;
    }

    @Override
    protected String roundHeader(int round) {
        return "---- [子代理] 第 " + round + "/" + maxRounds() + " 轮 ----";
    }

    @Override
    protected String seedLabel() {
        return "子任务简报";
    }

    @Override
    protected String resumeInstruction() {
        return "请基于以上进度继续完成子任务；结果数据以事实账本为准。";
    }

    @Override
    protected String defectFeedback(boolean viaFinalAnswer, String defects) {
        return viaFinalAnswer
                ? "你的最终答案未通过最终校验，存在以下缺陷：\n" + defects
                + "\n\n请补齐证据或修正结论，然后【重新用 final_answer 提交完整的最终答案】"
                + "——不要只输出补丁，必须完整覆盖任务所问。"
                : "你的回答未通过最终校验，存在以下缺陷：\n" + defects
                + "\n\n请补齐证据或修正结论，然后【重新提交完整的最终答案】"
                + "——不要只输出补丁，必须完整覆盖任务所问。";
    }

    @Override
    protected Result pass(String candidate) {
        return candidate.isBlank() ? new Result(false, "（子代理无有效输出）") : new Result(true, candidate);
    }

    @Override
    protected Result bestEffort(String candidate, String defects) {
        return new Result(false, candidate + "\n\n（注意：本结果未通过最终校验，遗留缺陷：\n" + defects + "\n）");
    }

    @Override
    protected Result exhausted(String lastText) {
        return new Result(false, lastText.isBlank() ? "（子代理达到轮次上限，无有效文本输出）" : lastText);
    }
}
