package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** web_search：联网搜索（引擎随 config.yaml 的 tools.web-search.provider 切换：tavily / 博查 bocha，
 *  见 SearchClient；key 与 max-results 同段配置，max-results 由 LLM 传参，缺省取配置值、代码缺省 30
 *  ——多拉结果供素材筛选，两家实际条数随查询可用结果浮动）。
 *  可选 domains 限定检索域名（裸域名匹配自身及全部子域；Tavily 服务端过滤，博查客户端过滤，语义一致），
 *  配合 locate_sources 使用。
 *  可选 include-images：附带查询相关图片（Tavily 随主搜索请求附带；博查取响应自带的 images），
 *  渲染为「图片」清单附在结果列表之后。
 *  可选 include-raw（仅 Tavily 支持，博查引擎下回执明示忽略）：索引收录的正文存档到 search/<域名>/ 下，
 *  回执逐条附路径与字数（不回传正文，避免长文灌爆上下文），配合 read_file 分段阅读——百科/知乎等
 *  fetch_url 抓不到的反爬站正文获取通道，配合 site: 或 domains 限定域名。
 *  每次执行记入 SearchLog（检索行为账本），回执末尾附「与已检索查询语义相近」提示，抑制换措辞的重复检索。 */
public class WebSearchTool implements AgentTool {

    /** 摘要行的前缀与控制台预览中摘要正文的截短长度（字符）。 */
    private static final String ABSTRACT_PREFIX = "   摘要: ";
    /** 回执近似提示最多列出的历史查询条数。 */
    private static final int SIMILAR_LIMIT = 5;

    private final Config.WebSearch cfg;
    private final SearchClient client;
    private final SearchLog searchLog;
    /** include-raw 正文存档根目录（search/ 子目录）。 */
    private final Path root;

    public WebSearchTool(Config.WebSearch cfg, Config.Storage storage, SearchLog searchLog) {
        this.cfg = cfg;
        this.client = new SearchClient(cfg);
        this.searchLog = searchLog;
        this.root = Config.rootDir(storage);
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public ToolDef definition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("keywords", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "搜索关键词，中英文均可"));
        props.put("max-results", Map.of(
                "type", "integer",
                "description", "返回结果数，默认 30（实际条数随查询可用结果浮动）"));
        props.put("domains", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "限定检索的域名，可选；传裸域名（如 stats.gov.cn），匹配自身及全部子域名"));
        props.put("include-images", Map.of(
                "type", "boolean",
                "description", "是否在搜索结果中附带查询相关图片，默认 false；开启后响应末尾列出图片（描述+URL）"));
        if (client.supportsRaw()) {
            props.put("include-raw", Map.of(
                    "type", "boolean",
                    "description", "是否把索引收录的正文存档到 search/ 目录并在结果中附路径与字数，默认 false；"
                            + "适合 fetch_url 抓不到的反爬站点（配合 site: 或 domains），存档用 read_file 分段阅读"));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("keywords"));
        String desc = "联网搜索，返回结果列表（标题/链接/内容摘要）。用于查找资料来源；"
                + "可用 domains 限定到 locate_sources 定位的权威域名（限定后无结果可去掉重试）；"
                + "需要图片时传 include-images: true，响应末尾附带查询相关图片（描述+URL）清单";
        if (client.supportsRaw()) {
            desc += "；需要正文时传 include-raw: true，索引收录的全文存档到 search/ 目录并逐条给出路径"
                    + "（fetch_url 被反爬拦截的站点如百科/知乎常用此路，可在关键词加 site:域名 限定）";
        }
        return new ToolDef(name(), desc, schema);
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        client.requireConfigured();
        String query = String.join(" ", ToolRegistry.strList(input, "keywords"));
        int maxResults = ToolRegistry.optInt(input, "max-results", cfg.maxResults());
        List<String> domains = normalizeDomains(input.get("domains"));
        List<String> similar = searchLog.similarTo(query, SIMILAR_LIMIT); // 先比对（不含本次），再记录
        boolean includeImages = input.path("include-images").asBoolean(false);
        boolean rawRequested = input.path("include-raw").asBoolean(false);
        boolean includeRaw = rawRequested && client.supportsRaw();

        SearchClient.Page page = client.search(query, maxResults, domains, includeImages, includeRaw);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        int saved = 0;
        for (SearchClient.Hit hit : page.hits()) {
            sb.append(++n).append(". ").append(hit.title())
                    .append("\n   链接: ").append(hit.url())
                    .append('\n').append(ABSTRACT_PREFIX).append(hit.content()).append('\n');
            if (includeRaw && !hit.raw().isBlank()) {
                Path file = saveRaw(hit.url(), hit.raw());
                sb.append("   正文: ").append(file).append("（").append(hit.raw().length())
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
                + "得到 " + n + " 条结果（" + client.engineLabel() + "）:\n";
        StringBuilder out = new StringBuilder(header).append(sb);
        if (includeImages) {
            out.append(renderImages(page.images()));
        }
        if (rawRequested && !client.supportsRaw()) {
            out.append("\ninclude-raw 已忽略：当前引擎（博查）不支持索引正文存档，请 fetch_url 直抓原链接；"
                    + "需要存档能力可在 config.yaml 的 tools.web-search.provider 切回 tavily\n");
        } else if (includeRaw && saved == 0) {
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

    /** include_images 开启时渲染图片清单；无描述时直接给 URL。 */
    private static String renderImages(List<SearchClient.Image> images) {
        if (images.isEmpty()) {
            return "本次响应未附带图片：可换更具体的对象名/场景词重试\n";
        }
        StringBuilder sb = new StringBuilder("图片 " + images.size() + " 张:\n");
        int i = 0;
        for (SearchClient.Image img : images) {
            sb.append(++i).append(". ").append(img.desc().isBlank() ? img.url() : img.desc())
                    .append("\n   链接: ").append(img.url()).append('\n');
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
