package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.Config;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** web_search：Tavily 搜索 API（key 来自 config.yaml 的 tools.web-search；max-results 由 LLM 传参，缺省 30）。
 *  可选 domains 限定检索域名（映射 include_domains，裸域名匹配自身及全部子域），配合 locate_sources 使用。 */
public class WebSearchTool implements AgentTool {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String API = "https://api.tavily.com/search";
    /** 控制台预览中每条摘要整行的最大长度（含「   摘要: 」前缀）。 */
    private static final int SNIPPET_WIDTH = 80;

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
        return new ToolDef(name(), "联网搜索，返回结果列表（标题/链接/内容摘要）。用于查找资料来源；"
                        + "可用 domains 限定到 locate_sources 定位的权威域名（限定后无结果可去掉重试）。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "keywords", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "搜索关键词，中英文均可"),
                                "max-results", Map.of(
                                        "type", "integer",
                                        "description", "返回结果数，默认 30"),
                                "domains", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "限定检索的域名，可选；传裸域名（如 stats.gov.cn），匹配自身及全部子域名")),
                        "required", List.of("keywords")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        if (cfg.tavilyApiKey().isBlank()) {
            throw new IllegalStateException("未配置 tavily-api-key（config.yaml 的 tools.web-search，对应环境变量 TAVILY_API_KEY）");
        }
        String query = String.join(" ", ToolRegistry.strList(input, "keywords"));
        int maxResults = ToolRegistry.optInt(input, "max-results", 30);
        List<String> domains = normalizeDomains(input.get("domains"));
        ObjectNode body = M.createObjectNode();
        body.put("query", query).put("max_results", maxResults);
        if (!domains.isEmpty()) {
            ArrayNode arr = body.putArray("include_domains");
            domains.forEach(arr::add);
        }

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
        if (n == 0) {
            return domains.isEmpty()
                    ? "未搜到结果，建议更换关键词"
                    : "未搜到结果（本次限定 domains=" + domains + "）：域名可能过窄或有误，可去掉 domains 重试";
        }
        return "搜索「" + query + "」" + (domains.isEmpty() ? "" : "（限定 " + domains + "）")
                + "得到 " + n + " 条结果:\n" + sb;
    }

    /** 控制台精简预览：标题与链接原样，每条摘要截成一行短摘——完整结果仍回传模型，不影响检索质量。 */
    @Override
    public String consolePreview(String content) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (line.startsWith("   摘要: ") && line.length() > SNIPPET_WIDTH) {
                line = line.substring(0, SNIPPET_WIDTH) + "…";
            }
            sb.append(line).append('\n');
        }
        return sb.append("（控制台摘要已截短，完整结果已回传模型）").toString();
    }

    /** domains 归一化：小写、去协议、截首个 /、去 www. 前缀（否则只剩精确 host 匹配，静默漏掉子域名），去空去重。 */
    private static List<String> normalizeDomains(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String d = n.asText("").strip().toLowerCase()
                    .replaceFirst("^https?://", "")
                    .replaceFirst("^www\\.", "");
            int slash = d.indexOf('/');
            if (slash >= 0) {
                d = d.substring(0, slash);
            }
            if (!d.isEmpty() && !out.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }
}
