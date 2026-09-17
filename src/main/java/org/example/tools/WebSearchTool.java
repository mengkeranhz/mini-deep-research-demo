package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.Config;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** web_search：Tavily 搜索 API。key 来自 config.yaml 的 tools.web-search；
 * 返回条数可由 LLM 传 max_results 指定，不传用 config 的 max-results（默认 30）。 */
public class WebSearchTool implements AgentTool {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String API = "https://api.tavily.com/search";

    private final Config.WebSearch cfg;

    public WebSearchTool(Config.WebSearch cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "联网搜索，返回结果列表（标题/链接/内容摘要）。用于查找资料来源。",
                Map.of("type", "object",
                        "properties", Map.of("keywords", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "搜索关键词，中英文均可"),
                                "max_results", Map.of("type", "integer",
                                        "description", "返回结果条数上限，默认 30；只需快速摸底时可传更小值（如 5）节省上下文")),
                        "required", List.of("keywords")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        if (cfg.tavilyApiKey().isBlank()) {
            throw new IllegalStateException("未配置 tavily-api-key（config.yaml 的 tools.web-search，对应环境变量 TAVILY_API_KEY）");
        }
        String query = String.join(" ", ToolRegistry.strList(input, "keywords"));
        int max = clamp(ToolRegistry.optInt(input, "max_results", cfg.maxResults()));
        ObjectNode body = M.createObjectNode();
        body.put("query", query).put("max_results", max);

        Http.Response resp = Http.post(API, Map.of("Authorization", "Bearer " + cfg.tavilyApiKey()),
                M.writeValueAsString(body));
        if (resp.status() != 200) {
            throw new IllegalStateException("HTTP " + resp.status() + " <- Tavily: "
                    + new String(resp.body(), StandardCharsets.UTF_8));
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JsonNode r : M.readTree(resp.body()).path("results")) {
            sb.append(++n).append(". ").append(r.path("title").asText())
                    .append("\n   链接: ").append(r.path("url").asText())
                    .append("\n   摘要: ").append(r.path("content").asText()).append('\n');
        }
        return n == 0
                ? "未搜到结果，建议更换关键词"
                : "搜索「" + query + "」得到 " + n + " 条结果:\n" + sb;
    }

    /** 条数钳制到 [1, 50]：LLM 传参异常时不打爆请求与上下文。 */
    private static int clamp(int v) {
        return Math.max(1, Math.min(50, v));
    }
}
