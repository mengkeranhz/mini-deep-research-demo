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
 * 流式：逐行读 SSE，按 index 累积 content block（text/thinking 签名/tool_use 的分片 JSON），结束时统一转 Block；
 * 流中断（残缺 JSON / 未见收尾事件 / 空流）一律按 StreamError 走 HttpRetry 重试，不把半截输出混进 Agent。
 * 思考档位（cfg.thinking）原样透传：off/disabled → thinking.type=disabled；其余值发顶层 reasoning_effort
 * 对应档位（low/medium/high 等，以网关支持为准）；留空不发送、走网关默认档。
 * 采样参数 top_p 仅在配置 ≥0 时发送（未配置用服务端默认）。
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
            if (cfg.topP() >= 0) {
                body.put("top_p", cfg.topP()); // 未配置（<0）不发送，用服务端默认
            }
            applyThinking(body);
            if (!system.isBlank()) {
                // system 用块数组形式并打 cache_control 断点（bigmodel 网关已实测支持）：
                // 人格 + 技能全文跨轮稳定，是第一个缓存段
                ArrayNode systemBlocks = body.putArray("system");
                ObjectNode sysBlock = systemBlocks.addObject();
                sysBlock.put("type", "text").put("text", system);
                sysBlock.putObject("cache_control").put("type", "ephemeral");
            }
            ArrayNode msgs = messages(conversation);
            markCacheBreakpoint(msgs);
            body.set("messages", msgs);
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
            return HttpRetry.retry(() -> cfg.streaming() ? stream(req) : blocking(req));
        } catch (Exception e) {
            throw new RuntimeException("Anthropic 调用失败: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * 思考档位直接透传：off/disabled → thinking.type=disabled；其余非空值原样写入顶层
     * reasoning_effort（档位集合由网关决定，不做白名单与换算）；留空不发送、走网关默认档。
     */
    private void applyThinking(ObjectNode body) {
        String level = cfg.thinking() == null ? "" : cfg.thinking().strip().toLowerCase();
        if ("off".equals(level) || "disabled".equals(level)) {
            body.putObject("thinking").put("type", "disabled");
        } else if (!level.isBlank()) {
            body.put("reasoning_effort", level);
        }
    }

    /** 非流式：整包 JSON 直接解析。 */
    private LlmResponse blocking(HttpRequest req) throws Exception {
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new HttpRetry.HttpError(resp.statusCode(), resp.body());
        }
        JsonNode root = parse(resp.body());
        List<Block> blocks = new ArrayList<>();
        for (JsonNode cb : root.path("content")) {
            blocks.add(block(cb));
        }
        return new LlmResponse(blocks,
                root.path("usage").path("input_tokens").asInt(),
                root.path("usage").path("output_tokens").asInt(),
                root.path("usage").path("cache_read_input_tokens").asLong());
    }

    /** 流式：消费 SSE 事件流，边接收边打印增量，边累积成完整 Block。 */
    private LlmResponse stream(HttpRequest req) throws Exception {
        HttpResponse<Stream<String>> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofLines());
        if (resp.statusCode() != 200) {
            String err = resp.body().collect(Collectors.joining("\n"));
            throw new HttpRetry.HttpError(resp.statusCode(), err);
        }
        List<Block> blocks = new ArrayList<>();
        Map<Integer, ObjectNode> open = new HashMap<>(); // index → 累积中的 content block
        int[] usage = {0, 0};
        long[] cacheRead = {0}; // 前缀缓存命中 token（message_start 与 message_delta 均可能携带）
        boolean[] completed = {false}; // 收到正常收尾信号（message_delta 的 stop_reason / message_stop）
        try (Stream<String> lines = resp.body()) {
            lines.filter(line -> line.startsWith("data:")).forEach(line -> {
                String payload = line.substring(5).strip();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    return;
                }
                JsonNode d = parse(payload);
                switch (d.path("type").asText()) {
                    case "message_start" -> {
                        usage[0] = d.path("message").path("usage").path("input_tokens").asInt();
                        cacheRead[0] = d.path("message").path("usage").path("cache_read_input_tokens").asLong();
                    }
                    case "content_block_start" ->
                            open.put(d.path("index").asInt(), (ObjectNode) d.get("content_block"));
                    case "content_block_delta" -> accumulate(open.get(d.path("index").asInt()), d.path("delta"));
                    case "content_block_stop" -> {
                        ObjectNode b = open.remove(d.path("index").asInt());
                        if (b != null) {
                            blocks.add(block(b));
                        }
                    }
                    case "message_delta" -> {
                        usage[1] = d.path("usage").path("output_tokens").asInt();
                        if (d.path("usage").path("cache_read_input_tokens").asLong() > 0) {
                            cacheRead[0] = d.path("usage").path("cache_read_input_tokens").asLong();
                        }
                        String sr = d.path("delta").path("stop_reason").asText("");
                        if (!sr.isBlank()) {
                            completed[0] = true;
                            // 异常收尾（max_tokens 截断 / refusal 拒答等）就地亮明，别让它无声滑过
                            if (!"end_turn".equals(sr) && !"tool_use".equals(sr)
                                    && !"stop_sequence".equals(sr)) {
                                System.out.println(Console.warn("[stop_reason] " + sr));
                            }
                        }
                    }
                    case "message_stop" -> completed[0] = true;
                    // 网关 200 但流内 error 事件（上游过载/审核/内部错误等）：上抛走重试——
                    // 此前走 default 被静默丢弃，整条流被当成「成功」的空响应返回
                    case "error" -> throw new HttpRetry.StreamError("网关流内 error 事件: "
                            + d.path("error").path("type").asText() + ": "
                            + d.path("error").path("message").asText());
                    default -> { }
                }
            });
        }
        // 整流未见收尾信号（连 message_delta 都没发＝网关空流或仅 message_start 即中断；内容块收到一半
        // 流被掐断＝半截文本/缺尾的工具参数）：按瞬时故障上抛重试，不让残缺输出被当「成功」轮次混进 Agent。
        // usage 输出量兜底：个别网关不发 stop_reason，但有完整用量同样视为正常收尾。
        if (!completed[0] && usage[1] == 0) {
            throw new HttpRetry.StreamError(blocks.isEmpty()
                    ? "流式响应无任何内容块与用量（疑似网关空流/仅 message_start 即中断）"
                    : "流式响应疑似中途截断：已收 " + blocks.size() + " 个内容块但未见收尾事件");
        }
        return new LlmResponse(blocks, usage[0], usage[1], cacheRead[0]);
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
                System.out.print(Console.thinking(delta.path("thinking").asText()));
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

    /**
     * 前缀缓存断点：倒数第二条消息的最后一个内容块（bigmodel 网关已实测支持 cache_control 且能命中）。
     * Agent 每轮把变化的任务/账本快照追加为最后一条消息，倒数第二条即稳定历史的末尾——
     * 断点打在这里，上一轮写入的缓存段恰好覆盖本轮的稳定前缀，实现逐轮增量命中；
     * 无尾注快照的单次调用（summarize/verify）同样适用，标记落在正文末块上，无害。
     * 加上 system 的一处共 2 个断点，远低于协议上限 4。
     */
    private static void markCacheBreakpoint(ArrayNode arr) {
        if (arr.size() < 2) {
            return;
        }
        ObjectNode target = (ObjectNode) arr.get(arr.size() - 2);
        JsonNode content = target.get("content");
        if (content == null) {
            return;
        }
        if (content.isTextual()) { // 纯文本 user 消息 → 转块数组再标记
            ArrayNode blocks = M.createArrayNode();
            ObjectNode block = blocks.addObject();
            block.put("type", "text").put("text", content.asText());
            block.putObject("cache_control").put("type", "ephemeral");
            target.set("content", blocks);
        } else if (content.isArray() && !content.isEmpty()) {
            JsonNode last = content.get(content.size() - 1);
            if (last instanceof ObjectNode block) {
                block.putObject("cache_control").put("type", "ephemeral");
            }
        }
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

    /**
     * 解析网关返回的 JSON（响应体 / SSE 数据行 / 工具参数分片累积），供 anthropic 与 openai 客户端共用。
     * 网关偶发流中断会产生残缺 JSON（Unexpected end-of-input 等，实测在 63KB 分片累积处截断）：
     * 按 StreamError 上抛走 HttpRetry 重试。此前抛普通 RuntimeException 不在重试白名单里，
     * 直穿重试层把整轮 Agent 打崩退出。
     */
    static JsonNode parse(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new HttpRetry.StreamError("JSON 解析失败（疑似网关响应截断/损坏）: " + e.getMessage()
                    + "；片段: " + (json.length() <= 120 ? json : json.substring(0, 120) + "…"));
        }
    }
}
