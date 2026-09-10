package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Anthropic 协议实现：POST {base-url}/v1/messages。
 * 流式：逐行读 SSE，按 index 累积 content block（text/thinking 签名/tool_use 的分片 JSON），结束时统一转 Block。
 */
final class AnthropicClient implements LlmClient {

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final Config.Llm cfg;

    AnthropicClient(Config.Llm cfg) {
        this.cfg = cfg;
    }

    @Override
    public LlmResponse call(List<ToolDef> tools, List<Msg> messages) {
        try {
            // system 消息提升为顶层 system 字段（Anthropic 协议要求），不进入 messages
            String system = joinText(messages.stream().filter(m -> m.role() == Msg.Role.SYSTEM).toList());
            List<Msg> conversation = messages.stream().filter(m -> m.role() != Msg.Role.SYSTEM).toList();

            ObjectNode body = M.createObjectNode();
            body.put("model", cfg.model())
                    .put("max_tokens", cfg.maxTokens())
                    .put("temperature", cfg.temperature())
                    .put("stream", cfg.streaming());
            if (!system.isBlank()) {
                body.put("system", system);
            }
            body.set("messages", messages(conversation));
            ArrayNode toolDefs = body.putArray("tools");
            for (ToolDef t : tools) {
                ObjectNode tool = toolDefs.addObject();
                tool.put("name", t.name()).put("description", t.description());
                tool.set("input_schema", M.valueToTree(t.inputSchema()));
            }

            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/v1/messages"))
                    .timeout(Duration.ofSeconds(300))
                    .header("x-api-key", cfg.apiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)))
                    .build();
            return cfg.streaming() ? stream(req) : blocking(req);
        } catch (Exception e) {
            throw new RuntimeException("Anthropic 调用失败: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /** 非流式：整包 JSON 直接解析。 */
    private LlmResponse blocking(HttpRequest req) throws Exception {
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = parse(resp.body());
        List<Block> blocks = new ArrayList<>();
        for (JsonNode cb : root.path("content")) {
            blocks.add(block(cb));
        }
        return new LlmResponse(blocks,
                root.path("usage").path("input_tokens").asInt(),
                root.path("usage").path("output_tokens").asInt());
    }

    /** 流式：消费 SSE 事件流，边接收边打印增量，边累积成完整 Block。 */
    private LlmResponse stream(HttpRequest req) throws Exception {
        HttpResponse<Stream<String>> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofLines());
        if (resp.statusCode() != 200) {
            String err = resp.body().collect(Collectors.joining("\n"));
            throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + err);
        }
        List<Block> blocks = new ArrayList<>();
        Map<Integer, ObjectNode> open = new HashMap<>(); // index → 累积中的 content block
        int[] usage = {0, 0};
        try (Stream<String> lines = resp.body()) {
            lines.filter(line -> line.startsWith("data:")).forEach(line -> {
                String payload = line.substring(5).strip();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    return;
                }
                JsonNode d = parse(payload);
                switch (d.path("type").asText()) {
                    case "message_start" ->
                            usage[0] = d.path("message").path("usage").path("input_tokens").asInt();
                    case "content_block_start" ->
                            open.put(d.path("index").asInt(), (ObjectNode) d.get("content_block"));
                    case "content_block_delta" -> accumulate(open.get(d.path("index").asInt()), d.path("delta"));
                    case "content_block_stop" -> {
                        ObjectNode b = open.remove(d.path("index").asInt());
                        if (b != null) {
                            blocks.add(block(b));
                        }
                    }
                    case "message_delta" ->
                            usage[1] = d.path("usage").path("output_tokens").asInt();
                    default -> { }
                }
            });
        }
        return new LlmResponse(blocks, usage[0], usage[1]);
    }

    /** 按 delta 类型累积到块上，文本/思考增量同时实时打印。 */
    private static void accumulate(ObjectNode b, JsonNode delta) {
        if (b == null) {
            return;
        }
        switch (delta.path("type").asText()) {
            case "text_delta" -> {
                b.put("text", b.path("text").asText("") + delta.path("text").asText());
                System.out.print(delta.path("text").asText());
            }
            case "thinking_delta" -> {
                b.put("thinking", b.path("thinking").asText("") + delta.path("thinking").asText());
                System.err.print(delta.path("thinking").asText());
            }
            case "signature_delta" ->
                    b.put("signature", b.path("signature").asText("") + delta.path("signature").asText());
            case "input_json_delta" ->
                    b.put("input_json", b.path("input_json").asText("") + delta.path("partial_json").asText());
            default -> { }
        }
    }

    /** content block JSON → Block。tool_use 的参数优先取流式累积的 input_json 分片，其次取整包 input。 */
    static Block block(JsonNode cb) {
        return switch (cb.path("type").asText()) {
            case "thinking" -> new Block.Thinking(cb.path("thinking").asText(), cb.path("signature").asText());
            case "tool_use" -> {
                String raw = cb.path("input_json").asText(""); // 流式累积的分片
                JsonNode input = !raw.isBlank() ? parse(raw)
                        : cb.has("input") ? cb.get("input")
                        : M.createObjectNode();
                yield new Block.ToolUse(cb.path("id").asText(), cb.path("name").asText(), input);
            }
            default -> new Block.Text(cb.path("text").asText());
        };
    }

    /**
     * 消息列表 → 请求 messages。连续的 tool 消息合并为一条 user 消息
     * （Anthropic 的 tool_result 块必须放在 user 消息内）；纯文本 user 消息用字符串 content。
     */
    private static ArrayNode messages(List<Msg> messages) {
        ArrayNode arr = M.createArrayNode();
        List<Block> toolResults = new ArrayList<>();
        for (Msg m : messages) {
            if (m.role() == Msg.Role.TOOL) {
                toolResults.addAll(m.blocks());
                continue;
            }
            flushToolResults(arr, toolResults);
            arr.add(message(m));
        }
        flushToolResults(arr, toolResults);
        return arr;
    }

    private static void flushToolResults(ArrayNode arr, List<Block> toolResults) {
        if (toolResults.isEmpty()) {
            return;
        }
        ObjectNode o = arr.addObject();
        o.put("role", "user");
        ArrayNode content = o.putArray("content");
        toolResults.forEach(b -> content.add(blockJson(b)));
        toolResults.clear();
    }

    /** 单条 user/assistant 消息。 */
    private static ObjectNode message(Msg m) {
        ObjectNode o = M.createObjectNode();
        o.put("role", m.role() == Msg.Role.ASSISTANT ? "assistant" : "user");
        if (m.role() == Msg.Role.USER && m.blocks().size() == 1
                && m.blocks().get(0) instanceof Block.Text t) {
            o.put("content", t.text());
            return o;
        }
        ArrayNode content = o.putArray("content");
        for (Block b : m.blocks()) {
            content.add(blockJson(b));
        }
        return o;
    }

    /** 拼接一组消息的全部文本块。 */
    private static String joinText(List<Msg> msgs) {
        StringBuilder sb = new StringBuilder();
        for (Msg m : msgs) {
            for (Block b : m.blocks()) {
                if (b instanceof Block.Text t) {
                    if (!sb.isEmpty()) {
                        sb.append("\n\n");
                    }
                    sb.append(t.text());
                }
            }
        }
        return sb.toString();
    }

    /** Block → 请求 content block JSON（thinking 需带原签名回传）。 */
    private static ObjectNode blockJson(Block b) {
        ObjectNode o = M.createObjectNode();
        switch (b) {
            case Block.Thinking t -> {
                o.put("type", "thinking");
                o.put("thinking", t.thinking());
                o.put("signature", t.signature());
            }
            case Block.Text t -> {
                o.put("type", "text");
                o.put("text", t.text());
            }
            case Block.ToolUse u -> {
                o.put("type", "tool_use");
                o.put("id", u.id());
                o.put("name", u.name());
                o.set("input", u.input() == null ? M.createObjectNode() : u.input());
            }
            case Block.ToolResult r -> {
                o.put("type", "tool_result");
                o.put("tool_use_id", r.toolUseId());
                o.put("content", r.content());
                o.put("is_error", r.isError());
            }
        }
        return o;
    }

    static JsonNode parse(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
