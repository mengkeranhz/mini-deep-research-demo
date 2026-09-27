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

    public void add(String agentId, String variant, Path path, List<String> conflicts) {
        reports.add(new Report(agentId, variant, path, List.copyOf(conflicts)));
    }

    public String snapshot() {
        if (reports.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("# 子Agent报告快照（每轮自动追加的状态块，非用户消息，不要回复它）\n");
        for (Report r : reports) {
            sb.append("- ").append(r.agentId())
                    .append(" | 报告: ").append(r.path())
                    .append(r.conflicts().isEmpty() ? "" : " | 事实冲突待复核")
                    .append('\n');
        }
        sb.append("汇总前可按需 read_file 报告文件；但写进最终答案的数据必须能在事实账本找到。\n");
        return sb.toString();
    }

    public String verificationSection() {
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
}
