package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.example.Config;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** fetch_url：抓取 URL 保存到本地。HTML 转简易 Markdown 存 .md，PDF/二进制原样存字节。 */
public class FetchUrlTool implements AgentTool {

    private final Path root;

    public FetchUrlTool(Config.Storage storage) {
        this.root = Config.rootDir(storage);
    }

    @Override
    public String name() {
        return "fetch_url";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "抓取 URL 内容保存到本地文件，返回保存路径。HTML 转为 Markdown（标题/段落/链接），"
                        + "PDF 与二进制文件原样保存；之后可用 read_file 分段阅读。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "url", Map.of("type", "string", "description", "要抓取的网址"),
                                "save_path", Map.of("type", "string", "description",
                                        "本地保存路径（可选）。相对路径相对于根目录解析；缺省时按 URL 自动命名保存到根目录。HTML 建议以 .md 结尾")),
                        "required", List.of("url")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String url = ToolRegistry.str(input, "url");
        String savePath = ToolRegistry.optStr(input, "save_path");
        Http.Response resp = Http.get(url);
        if (resp.status() != 200) {
            throw new IllegalStateException("HTTP " + resp.status() + " <- " + url
                    + (resp.status() == 403 ? "（目标站点拒绝访问，多为反爬/需登录，请换来源或改用带 cookie 的请求）" : ""));
        }

        Path path = savePath == null || savePath.isBlank()
                ? root.resolve(defaultFileName(url, resp.contentType()))
                : resolve(savePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        if (resp.contentType().contains("html")) {
            // charset 传 null 让 jsoup 按 meta 标签自动探测
            Document doc = Jsoup.parse(new ByteArrayInputStream(resp.body()), null, url);
            Files.writeString(path, toMarkdown(doc));
            String hint = thinBodyHint(doc);
            return "已保存 Markdown（" + Files.size(path) + " 字节）到 " + path
                    + (hint.isEmpty() ? "" : "\n" + hint);
        }
        Files.write(path, resp.body());
        return "已保存文件（" + resp.body().length + " 字节，" + resp.contentType() + "）到 " + path;
    }

    /** save_path 相对路径相对于根目录解析，绝对路径原样使用。 */
    private Path resolve(String savePath) {
        Path p = Path.of(savePath);
        return p.isAbsolute() ? p.normalize() : root.resolve(p).normalize();
    }

    /** 缺省文件名：取 URL 末段并清洗；HTML 补 .md 后缀。 */
    private String defaultFileName(String url, String contentType) {
        String name;
        try {
            String p = URI.create(url).getPath();
            name = (p == null || p.isBlank() || p.endsWith("/")) ? "page" : Path.of(p).getFileName().toString();
        } catch (Exception e) {
            name = "page";
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isBlank()) {
            name = "page";
        }
        if (contentType.contains("html") && !name.toLowerCase().endsWith(".md") && !name.toLowerCase().endsWith(".html")) {
            name += ".md";
        }
        return name;
    }

    /** 正文段落总字符低于该值视为实质性内容过少，附加能力边界提示。 */
    private static final int THIN_BODY_CHARS = 200;

    /** 静态抓取正文过少时的诚实提示：只陈述本工具的提取范围与能力边界，不给行动指令。
     *  段落统计与 toMarkdown 同过滤条件（≥20 字符），保证提示与实际保存内容自洽。 */
    private static String thinBodyHint(Document doc) {
        int paras = 0;
        int chars = 0;
        for (Element p : doc.select("p")) {
            String text = p.text().strip();
            if (text.length() >= 20) { // 与 toMarkdown 的正文过滤一致
                paras++;
                chars += text.length();
            }
        }
        if (chars >= THIN_BODY_CHARS) {
            return "";
        }
        return "注意：静态抓取到的正文段落极少（" + paras + " 段共 " + chars
                + " 字符；本工具只提取标题、段落与链接），页面主要内容可能依赖脚本渲染，本工具拿不到脚本渲染的内容。";
    }

    /** HTML → 简易 Markdown：标题 + 正文段落 + 链接列表。 */
    private String toMarkdown(Document doc) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(doc.title()).append("\n\n");
        for (Element p : doc.select("p")) {
            String text = p.text().strip();
            if (text.length() >= 20) { // 过滤导航/版权等短文本
                md.append(text).append("\n\n");
            }
        }
        md.append("## 链接\n");
        Set<String> seen = new LinkedHashSet<>();
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            String text = a.text().strip();
            if (href.startsWith("http") && !text.isEmpty() && seen.add(href)) {
                md.append("- [").append(text).append("](").append(href).append(")\n");
                if (seen.size() >= 50) break;
            }
        }
        return md.toString();
    }
}
