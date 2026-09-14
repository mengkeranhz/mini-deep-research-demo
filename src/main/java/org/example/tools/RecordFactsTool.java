package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.FactsStore;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.util.List;
import java.util.Map;

/**
 * record_facts：把检索到的关键数据写入事实账本（自由文本 + 来源）。
 * 账本跨上下文压缩保留，是最终答案的结算依据——未入账的数据随时可能随上下文丢失。
 */
public class RecordFactsTool implements AgentTool {

    private final FactsStore facts;

    public RecordFactsTool(FactsStore facts) {
        this.facts = facts;
    }

    @Override
    public String name() {
        return "record_facts";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "把检索到的关键数据写入事实账本。每得到一条可用数据就立即入账（不要攒到最后批量补）；最终答案的数据以账本为准。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "facts", Map.of("type", "array", "description", "本次入账的事实数组",
                                        "items", Map.of("type", "object",
                                                "properties", Map.of(
                                                        "fact", Map.of("type", "string",
                                                                "description", "一条写清指标、时期、数值、口径的数据描述，如「杭州 2024 年 GDP 22062 亿元（初步核算）」"),
                                                        "source", Map.of("type", "string",
                                                                "description", "来源链接或来源名"),
                                                        "note", Map.of("type", "string",
                                                                "description", "补充说明，可选")),
                                                "required", List.of("fact")))),
                        "required", List.of("facts")));
    }

    @Override
    public String execute(JsonNode input) {
        JsonNode arr = input.path("facts");
        if (!arr.isArray() || arr.isEmpty()) {
            return "参数 facts 必须是非空数组，每项含 fact（可选 source/note）";
        }
        int accepted = 0;
        for (JsonNode n : arr) {
            String fact = ToolRegistry.optStr(n, "fact");
            if (fact == null || fact.isBlank()) {
                continue;
            }
            facts.record(new FactsStore.Fact(fact,
                    orEmpty(ToolRegistry.optStr(n, "source")),
                    orEmpty(ToolRegistry.optStr(n, "note"))));
            accepted++;
        }
        return "已入账 " + accepted + " 条（账本共 " + facts.size() + " 条）";
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s.strip();
    }
}
