package org.example;

import java.util.Map;

/** 工具元信息：名称 + 描述 + JSON Schema 参数定义（Map 形式，协议序列化交给 LlmClient）。 */
public record ToolDef(String name, String description, Map<String, Object> inputSchema) {}
