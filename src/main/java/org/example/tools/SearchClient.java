package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.Config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 联网检索客户端：web_search 与 locate_sources 共用，按 config.yaml 的
 * tools.web-search.provider 在 Tavily 与博查（Bocha，https://open.bochaai.com）间切换，
 * 两家的响应归一化为统一的 Hit/Image 结构，调用方不感知引擎差异。
 * provider 留空时自动选已配 key 的引擎（tavily 优先）；key 未配置在使用时报错（缺省即空，交给使用处报错）。
 * 引擎能力差异：
 * - domains 限定：Tavily 走 include_domains 服务端过滤；博查无此参数，客户端按 host 后缀过滤（语义一致：裸域名匹配自身及全部子域）；
 * - include-raw（索引正文存档）：仅 Tavily 支持（include_raw_content），supportsRaw() 供工具定义与回执提示区分；
 * - 图片：Tavily 靠 include_images 附带；博查响应自带 data.images（contentUrl 取图片本体）。
 */
public final class SearchClient {

    /** 单条命中：标题 / 链接 / 摘要 / 索引正文（仅 Tavily include-raw 时非空）。 */
    public record Hit(String title, String url, String content, String raw) {}

    /** 图片：描述 + 图片本体 URL。 */
    public record Image(String desc, String url) {}

    /** 一次检索的归一化结果。 */
    public record Page(List<Hit> hits, List<Image> images) {}

    private static final ObjectMapper M = new ObjectMapper();
    private static final String TAVILY_API = "https://api.tavily.com/search";
    private static final String BOCHA_API = "https://api.bochaai.com/v1/web-search";

    private final String engine;
    private final String tavilyKey;
    private final String bochaKey;

    public SearchClient(Config.WebSearch cfg) {
        String p = cfg.provider() == null ? "" : cfg.provider().strip().toLowerCase();
        switch (p) {
            case "tavily", "bocha" -> engine = p;
            case "" -> engine = cfg.tavilyApiKey().isBlank() && !cfg.bochaApiKey().isBlank() ? "bocha" : "tavily";
            default -> throw new IllegalArgumentException(
                    "未知 web-search provider: " + p + "（可用: tavily / bocha，config.yaml 的 tools.web-search.provider）");
        }
        this.tavilyKey = cfg.tavilyApiKey();
        this.bochaKey = cfg.bochaApiKey();
    }

    /** 引擎显示名（回执标注用）。 */
    public String engineLabel() {
        return "bocha".equals(engine) ? "博查" : "Tavily";
    }

    /** 当前引擎是否支持索引正文存档（include-raw）。 */
    public boolean supportsRaw() {
        return "tavily".equals(engine);
    }

    /** 使用前检查：选定引擎的 key 未配置即抛错，附配置指引。 */
    public void requireConfigured() {
        if ("tavily".equals(engine) && tavilyKey.isBlank()) {
            throw new IllegalStateException("未配置 tavily-api-key（config.yaml 的 tools.web-search，对应环境变量 TAVILY_API_KEY）");
        }
        if ("bocha".equals(engine) && bochaKey.isBlank()) {
            throw new IllegalStateException("未配置 bocha-api-key（config.yaml 的 tools.web-search，对应环境变量 BOCHA_API_KEY）");
        }
    }

    /**
     * 联网检索：domains 限定（Tavily 服务端 / 博查客户端过滤），includeImages 附带图片，
     * includeRaw 携带索引正文（仅 Tavily 生效，博查忽略）。
     */
    public Page search(String query, int maxResults, List<String> domains,
                       boolean includeImages, boolean includeRaw) throws Exception {
        requireConfigured();
        return "tavily".equals(engine)
                ? searchTavily(query, maxResults, domains, includeImages, includeRaw)
                : searchBocha(query, maxResults, domains);
    }

    /** Tavily Search API：include_domains 服务端过滤；raw_content/images 按需附带。 */
    private Page searchTavily(String query, int maxResults, List<String> domains,
                              boolean includeImages, boolean includeRaw) throws Exception {
        ObjectNode body = M.createObjectNode();
        body.put("query", query).put("max_results", maxResults);
        if (!domains.isEmpty()) {
            var arr = body.putArray("include_domains");
            domains.forEach(arr::add);
        }
        if (includeRaw) {
            body.put("include_raw_content", true);
        }
        if (includeImages) {
            body.put("include_images", true).put("include_image_descriptions", true);
        }
        Http.Response resp = Http.post(TAVILY_API, Map.of("Authorization", "Bearer " + tavilyKey),
                M.writeValueAsString(body));
        if (resp.status() != 200) {
            throw new IllegalStateException("HTTP " + resp.status() + " <- Tavily: "
                    + new String(resp.body(), StandardCharsets.UTF_8));
        }
        JsonNode tree = M.readTree(resp.body());
        List<Hit> hits = new ArrayList<>();
        for (JsonNode r : tree.path("results")) {
            JsonNode rawNode = r.get("raw_content");
            hits.add(new Hit(r.path("title").asText(), r.path("url").asText(),
                    r.path("content").asText(),
                    rawNode != null && rawNode.isTextual() ? rawNode.asText() : ""));
        }
        return new Page(hits, tavilyImages(tree.path("images")));
    }

    /** Tavily 顶层 images：兼容对象项（url/description，description 为 null 回退来源页标题）与旧版纯 URL 字符串项。 */
    private static List<Image> tavilyImages(JsonNode images) {
        List<Image> out = new ArrayList<>();
        if (images == null || !images.isArray()) {
            return out;
        }
        for (JsonNode img : images) {
            String url = img.isTextual() ? img.asText() : img.path("url").asText("");
            String desc = img.isTextual() ? "" : img.path("description").asText("");
            if (desc.isBlank()) {
                desc = img.path("title").asText("");
            }
            if (!url.isBlank()) {
                out.add(new Image(desc, url));
            }
        }
        return out;
    }

    /** 博查 Web Search API 请求：freshness noLimit 全时段、summary=true 取 LLM 摘要、count 上限 50；
     *  无 include_domains / raw_content 参数，domains 过滤与字段映射由 parseBocha 客户端补齐。 */
    private Page searchBocha(String query, int maxResults, List<String> domains) throws Exception {
        ObjectNode body = M.createObjectNode();
        body.put("query", query)
                .put("freshness", "noLimit")
                .put("summary", true)
                .put("count", Math.max(1, Math.min(50, maxResults)))
                .put("page", 1);
        Http.Response resp = Http.post(BOCHA_API, Map.of("Authorization", "Bearer " + bochaKey),
                M.writeValueAsString(body));
        if (resp.status() != 200) {
            throw new IllegalStateException("HTTP " + resp.status() + " <- 博查: "
                    + new String(resp.body(), StandardCharsets.UTF_8));
        }
        JsonNode tree = M.readTree(resp.body());
        // 业务错误可能仍回 HTTP 200（code!=200 + msg，如 key 无效/欠费），一并拦截
        int code = tree.path("code").asInt(200);
        if (code != 200) {
            throw new IllegalStateException("HTTP 200 但 code=" + code + " <- 博查: " + tree.path("msg").asText(""));
        }
        return parseBocha(tree, domains);
    }

    /** 博查响应（兼容 Bing 结构）→ 归一化 Page：标题←name、摘要←summary（空则 snippet）、
     *  图片←data.images（contentUrl）；domains 客户端按 host 后缀过滤。 */
    static Page parseBocha(JsonNode tree, List<String> domains) {
        List<Hit> hits = new ArrayList<>();
        for (JsonNode r : tree.path("data").path("webPages").path("value")) {
            String url = r.path("url").asText("");
            if (!domains.isEmpty() && !matchDomain(url, domains)) {
                continue;
            }
            JsonNode sum = r.get("summary");
            String content = sum != null && sum.isTextual() && !sum.asText().isBlank()
                    ? sum.asText() : r.path("snippet").asText();
            hits.add(new Hit(r.path("name").asText(), url, content, ""));
        }
        List<Image> images = new ArrayList<>();
        for (JsonNode img : tree.path("data").path("images").path("value")) {
            String url = img.path("contentUrl").asText("");
            if (!url.isBlank()) {
                images.add(new Image(img.path("name").asText(""), url));
            }
        }
        return new Page(hits, images);
    }

    /** URL host 是否命中任一限定域名（自身或其子域）；解析不出 host 视为不命中。 */
    private static boolean matchDomain(String url, List<String> domains) {
        String h;
        try {
            URI u = URI.create(url.strip());
            h = u.getHost();
        } catch (Exception e) {
            h = null;
        }
        if (h == null) {
            return false;
        }
        h = h.toLowerCase();
        if (h.startsWith("www.")) {
            h = h.substring(4);
        }
        for (String d : domains) {
            if (h.equals(d) || h.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }
}
