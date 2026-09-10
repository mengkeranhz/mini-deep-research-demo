package org.example;

import com.fasterxml.jackson.databind.JsonNode;

/** 对话内容块（协议无关的中立模型，LlmClient 负责与各协议的 JSON 互转）。 */
public sealed interface Block {

    /** LLM 思考内容；signature 为 Anthropic 协议回传思考块所需的签名。 */
    record Thinking(String thinking, String signature) implements Block {}

    /** 文本内容（LLM 结论或用户输入）。 */
    record Text(String text) implements Block {}

    /** LLM 发起的工具调用；input 为参数 JSON 对象。 */
    record ToolUse(String id, String name, JsonNode input) implements Block {}

    /** 工具执行结果，回传给 LLM。 */
    record ToolResult(String toolUseId, String content, boolean isError) implements Block {}
}
