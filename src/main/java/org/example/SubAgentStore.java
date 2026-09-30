package org.example;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 子 Agent 报告的外部状态：报告全文落盘，父上下文只依赖可追溯路径与摘要。
 * 报告是过程材料；数值与结论仍以 FactsStore 为最终权威，防止汇总时凭记忆改写。
 */
public final class SubAgentStore {

    public record Report(String agentId, String variant, Path path, List<String> conflicts) {}

    private final List<Report> reports = new ArrayList<>();

    public synchronized void add(String agentId, String variant, Path path, List<String> conflicts) {
        reports.add(new Report(agentId, variant, path, List.copyOf(conflicts)));
    }

    public synchronized String snapshot() {
        if (reports.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("# 子Agent报告快照（每轮自动追加的状态块，非用户消息，不要回复它）\n");
        for (Report r : reports) {
            sb.append("- ").append(r.agentId())
                    .append(" | 报告: ").append(r.path())
                    .append(" | 日志: ").append(r.path().getParent().resolve("agent.log.md"))
                    .append(r.conflicts().isEmpty() ? "" : " | 事实冲突待复核")
                    .append('\n');
        }
        sb.append("汇总前可按需 read_file 报告文件；但写进最终答案的数据必须能在事实账本找到。\n");
        if (hasMultipleVariants()) {
            sb.append("检测到多个并列版本：汇总前必须逐版 read_file/核对报告，执行任务/技能定义的版本分离指标，")
                    .append("不能只按版本名概括差异。\n");
        }
        return sb.toString();
    }

    public synchronized String verificationSection() {
        if (reports.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("# 子Agent报告核对\n")
                .append("以下报告只能作为结构和叙述来源；数值、状态与结论必须与事实账本一致，")
                .append("不得采纳账本不支撑的新增确定性事实：\n");
        for (Report r : reports) {
            sb.append("- ").append(r.agentId()).append(" 报告文件: ").append(r.path()).append('\n');
        }
        return sb.toString();
    }

    /** 是否已有多个并列版本报告，父 Agent 终检需要做跨版本分离核验。 */
    public synchronized boolean hasMultipleVariants() {
        return reports.stream()
                .map(Report::variant)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .count() > 1;
    }

    /** 多版本终检的通用核验提示；领域指标由任务基线与已加载技能提供。 */
    public synchronized String multiVersionVerificationSection() {
        if (!hasMultipleVariants()) {
            return null;
        }
        return """
                # 多版本分离核验（必须逐项复核）
                版本报告：%s
                终稿必须执行「版本分离核验」，按任务基线与已加载技能列出的指标逐项给出数值、
                PASS/FAIL 或可比证据；若任务未定义领域指标，至少比较各版本目标、契约标明的决策变量、
                产物结构和用户可感知差异。
                缺项、只有自述无证据、或未达到任务契约设定，均为 BLOCKER；先回炉重排版本决策变量。
                """.formatted(reports.stream()
                .map(Report::variant)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .toList());
    }
}
