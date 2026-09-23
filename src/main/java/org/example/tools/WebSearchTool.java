package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.Config;
import org.example.SearchLog;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** web_search：Tavily 搜索 API（key 来自 config.yaml 的 tools.web-search；max-results 由 LLM 传参，
 *  缺省取 tools.web-search.max-results，代码缺省 30——多拉结果供素材筛选，Tavily 按查询实际可用数动态返回）。
 *  可选 domains 限定检索域名（映射 include_domains，裸域名匹配自身及全部子域），配合 locate_sources 使用。
 *  可选 include-images（映射 include_images，并固定开 include_image_descriptions 取图片描述）：Tavily 无独立
 *  图片搜索端点，开启后主搜索响应附带查询相关图片，渲染为「图片」清单附在结果列表之后。
 *  可选 include-raw（映射 include_raw_content）：索引收录的正文存档到 search/<域名>/ 下，回执逐条附路径
 *  与字数（不回传正文，避免长文灌爆上下文），配合 read_file 分段阅读——百科/知乎等 fetch_url 抓不到的
 *  反爬站正文获取通道，配合 site: 或 domains 限定域名。
 *  每次执行记入 SearchLog（检索行为账本），回执末尾附「与已检索查询语义相近」提示，抑制换措辞的重复检索。 */
public class WebSearchTool implements AgentTool {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String API = "https://api.tavily.com/search";
    /** 摘要行的前缀与控制台预览中摘要正文的截短长度（字符）。 */
    private static final String ABSTRACT_PREFIX = "   摘要: ";
    /** 回执近似提示最多列出的历史查询条数。 */
    private static final int SIMILAR_LIMIT = 5;

    private final Config.WebSearch cfg;
    private final SearchLog searchLog;
    /** include-raw 正文存档根目录（search/ 子目录）。 */
    private final Path root;

    public WebSearchTool(Config.WebSearch cfg, Config.Storage storage, SearchLog searchLog) {
        this.cfg = cfg;
        this.searchLog = searchLog;
        this.root = Config.rootDir(storage);
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "联网搜索，返回结果列表（标题/链接/内容摘要）。用于查找资料来源；"
                + "可用 domains 限定到 locate_sources 定位的权威域名（限定后无结果可去掉重试）；"
                + "需要图片时传 include-images: true，响应末尾附带查询相关图片（描述+URL）清单；"
                + "需要正文时传 include-raw: true，索引收录的全文存档到 search/ 目录并逐条给出路径"
                + "（fetch_url 被反爬拦截的站点如百科/知乎常用此路，可在关键词加 site:域名 限定）",
                Map.of("type", "object",
                        "properties", Map.of(
                                "keywords", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "搜索关键词，中英文均可"),
                                "max-results", Map.of(
                                        "type", "integer",
                                        "description", "返回结果数，默认 30（实际条数随查询可用结果浮动）"),
                                "domains", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "限定检索的域名，可选；传裸域名（如 stats.gov.cn），匹配自身及全部子域名"),
                                "include-images", Map.of(
                                        "type", "boolean",
                                        "description", "是否在搜索结果中附带查询相关图片，默认 false；开启后响应末尾列出图片（描述+URL）"),
                                "include-raw", Map.of(
                                        "type", "boolean",
                                        "description", "是否把索引收录的正文存档到 search/ 目录并在结果中附路径与字数，默认 false；"
                                                + "适合 fetch_url 抓不到的反爬站点（配合 site: 或 domains），存档用 read_file 分段阅读")),
                        "required", List.of("keywords")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        if (cfg.tavilyApiKey().isBlank()) {
            throw new IllegalStateException("未配置 tavily-api-key（config.yaml 的 tools.web-search，对应环境变量 TAVILY_API_KEY）");
        }
        String query = String.join(" ", ToolRegistry.strList(input, "keywords"));
        int maxResults = ToolRegistry.optInt(input, "max-results", cfg.maxResults());
        List<String> domains = normalizeDomains(input.get("domains"));
        List<String> similar = searchLog.similarTo(query, SIMILAR_LIMIT); // 先比对（不含本次），再记录
        boolean includeImages = input.path("include-images").asBoolean(false);
        boolean includeRaw = input.path("include-raw").asBoolean(false);
        ObjectNode body = M.createObjectNode();
        body.put("query", query).put("max_results", maxResults);
        if (!domains.isEmpty()) {
            ArrayNode arr = body.putArray("include_domains");
            domains.forEach(arr::add);
        }
        if (includeRaw) {
            // 索引收录的正文随结果返回；只存档不回传，避免长文灌爆上下文
            body.put("include_raw_content", true);
        }
        if (includeImages) {
            // Tavily 无独立图片端点：主搜索请求带 include_images，响应即附查询相关图片；descriptions 拿图片描述便于识别内容
            body.put("include_images", true).put("include_image_descriptions", true);
        }

        Http.Response resp = Http.post(API, Map.of("Authorization", "Bearer " + cfg.tavilyApiKey()),
                M.writeValueAsString(body));
        if (resp.status() != 200) {
            throw new IllegalStateException("HTTP " + resp.status() + " <- Tavily: "
                    + new String(resp.body(), StandardCharsets.UTF_8));
        }
        JsonNode tree = M.readTree(resp.body());
        StringBuilder sb = new StringBuilder();
        int n = 0;
        int saved = 0;
        for (JsonNode r : tree.path("results")) {
            sb.append(++n).append(". ").append(r.path("title").asText())
                    .append("\n   链接: ").append(r.path("url").asText())
                    .append('\n').append(ABSTRACT_PREFIX).append(r.path("content").asText()).append('\n');
            JsonNode rawNode = r.get("raw_content");
            if (includeRaw && rawNode != null && rawNode.isTextual() && !rawNode.asText().isBlank()) {
                String raw = rawNode.asText();
                Path file = saveRaw(r.path("url").asText(), raw);
                sb.append("   正文: ").append(file).append("（").append(raw.length())
                        .append(" 字，read_file 分段阅读）\n");
                saved++;
            }
        }
        searchLog.record(query, domains, n);
        if (n == 0) {
            return domains.isEmpty()
                    ? "未搜到结果，建议更换关键词"
                    : "未搜到结果（本次限定 domains=" + domains + "）：域名可能过窄或有误，可去掉 domains 重试";
        }
        String header = "搜索「" + query + "」" + (domains.isEmpty() ? "" : "（限定 " + domains + "）")
                + "得到 " + n + " 条结果:\n";
        StringBuilder out = new StringBuilder(header).append(sb);
        if (includeImages) {
            out.append(renderImages(tree.path("images")));
        }
        if (includeRaw && saved == 0) {
            out.append("\n本次索引未附带任何正文存档：可 fetch_url 直抓原链接，或换更具体的关键词重试\n");
        }
        if (!similar.isEmpty()) {
            out.append("\n提示: 本次查询与已检索过的 ").append(similar.size()).append(" 条语义相近:\n")
                    .append(String.join("\n", similar))
                    .append("\n——该目标已检索过：素材已够就 record_facts 入账后直接复用，")
                    .append("不要换措辞重复检索；确需新信息时才用明显不同的关键词。\n");
        }
        return out.toString();
    }

    /** 索引正文存档：search/&lt;域名&gt;/&lt;URL 路径段拼接&gt;.md，文件名保留中文便于识别；同 URL 重复存档时覆盖。 */
    private Path saveRaw(String url, String raw) throws IOException {
        String host = "page";
        String tail = "page";
        try {
            URI u = URI.create(url.strip());
            if (u.getHost() != null) {
                host = u.getHost().replaceFirst("^www\\.", "");
            }
            List<String> segs = new ArrayList<>();
            for (String s : u.getPath().split("/")) {
                if (!s.isBlank()) {
                    segs.add(URLDecoder.decode(s, StandardCharsets.UTF_8));
                }
            }
            if (!segs.isEmpty()) {
                tail = String.join("-", segs);
            }
        } catch (Exception ignored) {
            // URL 解析失败按 page 兜底
        }
        tail = tail.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (tail.length() > 80) {
            tail = tail.substring(0, 80);
        }
        if (!tail.toLowerCase().endsWith(".md")) {
            tail += ".md";
        }
        Path dir = root.resolve("search").resolve(host);
        Files.createDirectories(dir);
        Path p = dir.resolve(tail);
        Files.writeString(p, raw);
        return p;
    }

    /** include_images 开启时渲染顶层 images 清单；兼容对象项（url/description）与旧版纯 URL 字符串项。 */
    private static String renderImages(JsonNode images) {
        if (!images.isArray() || images.isEmpty()) {
            return "本次响应未附带图片：可换更具体的对象名/场景词重试\n";
        }
        StringBuilder sb = new StringBuilder("图片 " + images.size() + " 张:\n");
        int i = 0;
        for (JsonNode img : images) {
            String url = img.isTextual() ? img.asText() : img.path("url").asText("");
            String desc = img.isTextual() ? "" : img.path("description").asText("");
            if (desc.isBlank()) {
                desc = img.path("title").asText(""); // description 常为 null，回退用来源页标题
            }
            sb.append(++i).append(". ").append(desc.isBlank() ? url : desc)
                    .append("\n   链接: ").append(url).append('\n');
        }
        return sb.toString();
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
