package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** fetch_url：抓取 URL 保存到本地。HTML 转简易 Markdown 存 .md，PDF/二进制原样存字节。 */
public class FetchUrlTool implements AgentTool {

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
                                "save_path", Map.of("type", "string", "description", "本地保存路径，HTML 建议以 .md 结尾")),
                        "required", List.of("url", "save_path")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String url = ToolRegistry.str(input, "url");
        String savePath = ToolRegistry.str(input, "save_path");
        Http.Response resp = Http.get(url);
        if (resp.status() != 200) {
            throw new IllegalStateException("HTTP " + resp.status() + " <- " + url);
        }

        Path path = Path.of(savePath);
        if (path.toAbsolutePath().getParent() != null) {
            Files.createDirectories(path.toAbsolutePath().getParent());
        }
        if (resp.contentType().contains("html")) {
            // charset 传 null 让 jsoup 按 meta 标签自动探测
            Document doc = Jsoup.parse(new ByteArrayInputStream(resp.body()), null, url);
            Files.writeString(path, toMarkdown(doc));
            return "已保存 Markdown（" + Files.size(path) + " 字节）到 " + path;
        }
        Files.write(path, resp.body());
        return "已保存文件（" + resp.body().length + " 字节，" + resp.contentType() + "）到 " + path;
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
