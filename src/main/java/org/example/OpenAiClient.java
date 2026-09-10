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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenAI 协议实现：POST {base-url}/chat/completions（base-url 需含 /v1 等前缀）。
 * 思考内容读取 delta.reasoning_content（GLM/DeepSeek 风格）；OpenAI 协议无思考块回传，发送侧跳过。
 */
final class OpenAiClient implements LlmClient {

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final Config.Llm cfg;

    OpenAiClient(Config.Llm cfg) {
        this.cfg = cfg;
    }

    @Override
    public LlmResponse call(List<ToolDef> tools, List<Msg> messages) {
        try {
            ObjectNode body = M.createObjectNode();
            body.put("model", cfg.model())
                    .put("max_tokens", cfg.maxTokens())
                    .put("temperature", cfg.temperature())
                    .put("stream", cfg.streaming());
            if (cfg.streaming()) {
                body.putObject("stream_options").put("include_usage", true); // 末尾带 usage
            }
            ArrayNode msgs = body.putArray("messages");
            for (Msg m : messages) {
                addMessages(msgs, m);
            }
            if (!tools.isEmpty()) {
                ArrayNode toolDefs = body.putArray("tools");
                for (ToolDef t : tools) {
                    ObjectNode tool = toolDefs.addObject();
                    tool.put("type", "function");
                    ObjectNode fn = tool.putObject("function");
                    fn.put("name", t.name()).put("description", t.description());
                    fn.set("parameters", M.valueToTree(t.inputSchema()));
                }
            }

            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(300))
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)))
                    .build();
            return cfg.streaming() ? stream(req) : blocking(req);
        } catch (Exception e) {
            throw new RuntimeException("OpenAI 调用失败: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /** 非流式：整包 JSON 直接解析。 */
    private LlmResponse blocking(HttpRequest req) throws Exception {
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = AnthropicClient.parse(resp.body());
        JsonNode message = root.path("choices").path(0).path("message");
        return assemble(message.path("reasoning_content").asText(""),
                message.path("content").asText(""),
                message.path("tool_calls"),
                root.path("usage").path("prompt_tokens").asInt(),
                root.path("usage").path("completion_tokens").asInt());
    }

    /** 流式：消费 SSE 分片，边接收边打印增量，边累积文本/思考/工具调用。 */
    private LlmResponse stream(HttpRequest req) throws Exception {
        HttpResponse<Stream<String>> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofLines());
        if (resp.statusCode() != 200) {
            String err = resp.body().collect(Collectors.joining("\n"));
            throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + err);
        }
        StringBuilder thinking = new StringBuilder();
        StringBuilder text = new StringBuilder();
        Map<Integer, ObjectNode> calls = new TreeMap<>(); // tool_calls 分片按 index 累积
        int[] usage = {0, 0};
        try (Stream<String> lines = resp.body()) {
            lines.filter(line -> line.startsWith("data:")).forEach(line -> {
                String payload = line.substring(5).strip();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    return;
                }
                JsonNode d = AnthropicClient.parse(payload);
                if (!d.path("usage").isMissingNode() && !d.path("usage").isNull()) {
                    usage[0] = d.path("usage").path("prompt_tokens").asInt();
                    usage[1] = d.path("usage").path("completion_tokens").asInt();
                }
                JsonNode delta = d.path("choices").path(0).path("delta");
                String think = delta.path("reasoning_content").asText("");
                if (!think.isEmpty()) {
                    thinking.append(think);
                    System.out.print(Console.thinking(think));
                }
                String chunk = delta.path("content").asText("");
                if (!chunk.isEmpty()) {
                    text.append(chunk);
                    System.out.print(chunk);
                }
                for (JsonNode tc : delta.path("tool_calls")) {
                    ObjectNode call = calls.computeIfAbsent(tc.path("index").asInt(0), i -> M.createObjectNode());
                    if (tc.has("id")) {
                        call.put("id", tc.path("id").asText());
                    }
                    if (tc.path("function").has("name")) {
                        call.put("name", tc.path("function").path("name").asText());
                    }
                    call.put("args", call.path("args").asText("")
                            + tc.path("function").path("arguments").asText(""));
                }
            });
        }
        ArrayNode toolCalls = M.createArrayNode();
        calls.values().forEach(c -> {
            ObjectNode tc = toolCalls.addObject();
            tc.put("id", c.path("id").asText());
            ObjectNode fn = tc.putObject("function");
            fn.put("name", c.path("name").asText());
            fn.put("arguments", c.path("args").asText());
        });
        return assemble(thinking.toString(), text.toString(), toolCalls, usage[0], usage[1]);
    }

    /** 累积结果 → LlmResponse；toolCalls 形如 {id, function:{name, arguments(json 字符串)}}。 */
    private static LlmResponse assemble(String thinking, String text, JsonNode toolCalls, int inTok, int outTok) {
        List<Block> blocks = new ArrayList<>();
        if (!thinking.isBlank()) {
            blocks.add(new Block.Thinking(thinking, ""));
        }
        if (!text.isBlank()) {
            blocks.add(new Block.Text(text));
        }
        for (JsonNode tc : toolCalls) {
            String args = tc.path("function").path("arguments").asText("");
            blocks.add(new Block.ToolUse(tc.path("id").asText(), tc.path("function").path("name").asText(),
                    args.isBlank() ? M.createObjectNode() : AnthropicClient.parse(args)));
        }
        return new LlmResponse(blocks, inTok, outTok);
    }

    /** 四种角色 → chat/completions 消息：system/user/assistant/tool（tool 消息带 tool_call_id）。 */
    private static void addMessages(ArrayNode msgs, Msg m) {
        switch (m.role()) {
            case SYSTEM -> msgs.addObject().put("role", "system").put("content", text(m));
            case USER -> msgs.addObject().put("role", "user").put("content", text(m));
            case TOOL -> {
                for (Block b : m.blocks()) {
                    if (b instanceof Block.ToolResult r) {
                        msgs.addObject().put("role", "tool")
                                .put("tool_call_id", r.toolUseId())
                                .put("content", r.content());
                    }
                }
            }
            case ASSISTANT -> addAssistant(msgs, m);
        }
    }

    /** assistant 消息：文本入 content，工具调用入 tool_calls；思考块无回传方式，跳过。 */
    private static void addAssistant(ArrayNode msgs, Msg m) {
        ObjectNode o = msgs.addObject();
        o.put("role", "assistant");
        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = null;
        for (Block b : m.blocks()) {
            if (b instanceof Block.Text t) {
                text.append(t.text());
            } else if (b instanceof Block.ToolUse u) {
                if (toolCalls == null) {
                    toolCalls = o.putArray("tool_calls");
                }
                ObjectNode tc = toolCalls.addObject();
                tc.put("id", u.id()).put("type", "function");
                tc.putObject("function").put("name", u.name())
                        .put("arguments", u.input() == null ? "{}" : u.input().toString());
            }
        }
        if (text.length() > 0) {
            o.put("content", text.toString());
        } else {
            o.putNull("content");
        }
    }

    /** 拼接一条消息的全部文本块。 */
    private static String text(Msg m) {
        StringBuilder sb = new StringBuilder();
        for (Block b : m.blocks()) {
            if (b instanceof Block.Text t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
