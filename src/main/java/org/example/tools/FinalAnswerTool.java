package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.util.List;
import java.util.Map;

/**
 * final_answer：终止工具——任务确认完成后提交最终答案并结束任务。
 * 工具本身只校验必填并回显答案；是否真正终止由 agent loop 决定：提交后仍要做 LLM 最终校验，
 * 未通过会收到缺陷清单并继续修正重交（父 Agent 与子 Agent 协议一致）。
 */
public class FinalAnswerTool implements AgentTool {

    /** 终止工具名：agent loop 据此识别提交。 */
    public static final String NAME = "final_answer";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "提交最终答案并结束任务。只有在确认任务已完成、答案完整覆盖述求时才可调用"
                        + "（纯文本回复不会结束任务）；提交后会自动做最终校验，未通过会收到缺陷清单，需修正后重新提交。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "answer", Map.of("type", "string",
                                        "description", "完整的最终答案（含数据、计算过程与来源）")),
                        "required", List.of("answer")));
    }

    @Override
    public String execute(JsonNode input) {
        return ToolRegistry.str(input, "answer"); // 校验必填后原样回显，校验与终止判定在 agent loop
    }
}
