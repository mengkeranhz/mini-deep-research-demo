package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.Config;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** read_file：文本文件按行切片阅读；PDF 用 PDFBox 提取全文后同样按行切片。 */
public class ReadFileTool implements AgentTool {

    private final Path root;

    public ReadFileTool(Config.Storage storage) {
        this.root = Config.rootDir(storage);
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "按行读取本地文件（fetch_url 保存的 Markdown、PDF 等），返回指定范围的行。"
                        + "文件较长时可多次调用翻页阅读；提供 keywords 时改为返回命中关键词的行。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "文件路径（相对路径相对于根目录解析）"),
                                "offset", Map.of("type", "integer", "description", "起始行号（0 起），默认 0"),
                                "limit", Map.of("type", "integer", "description", "返回行数，默认 200"),
                                "keywords", Map.of("type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "关键词列表，命中任意一个即返回该行（忽略 offset/limit）")),
                        "required", List.of("path")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String path = ToolRegistry.str(input, "path");
        List<String> lines = readAllLines(resolve(path));

        List<String> keywords = input.hasNonNull("keywords")
                ? ToolRegistry.strList(input, "keywords").stream().filter(k -> !k.isBlank()).toList()
                : List.of();
        if (!keywords.isEmpty()) {
            return searchByKeywords(path, lines, keywords);
        }

        int offset = ToolRegistry.optInt(input, "offset", 0);
        int limit = ToolRegistry.optInt(input, "limit", 200);
        int from = Math.min(offset, lines.size());
        int to = Math.min(from + limit, lines.size());
        StringBuilder sb = new StringBuilder("文件 ").append(path).append(" 共 ").append(lines.size())
                .append(" 行，返回第 ").append(from).append("-").append(to - 1).append(" 行:\n");
        for (int i = from; i < to; i++) {
            sb.append(i).append(": ").append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    /** 返回命中任意关键词的行（不区分大小写）。 */
    private String searchByKeywords(String path, List<String> lines, List<String> keywords) {
        List<String> lowerKeywords = keywords.stream().map(String::toLowerCase).toList();
        StringBuilder sb = new StringBuilder("文件 ").append(path).append(" 命中关键词 ").append(keywords).append(" 的行:\n");
        int hit = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (containsAny(line, lowerKeywords)) {
                sb.append(i).append(": ").append(line).append('\n');
                hit++;
            }
        }
        if (hit == 0) {
            return "文件 " + path + " 中没有命中关键词 " + keywords + " 的内容。";
        }
        return sb.toString();
    }

    private static boolean containsAny(String line, List<String> lowerKeywords) {
        String lower = line.toLowerCase();
        for (String kw : lowerKeywords) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** 相对路径相对于根目录解析，绝对路径原样使用。 */
    private Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p.normalize() : root.resolve(p).normalize();
    }

    private List<String> readAllLines(Path path) throws Exception {
        if (path.toString().toLowerCase().endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(path.toFile())) {
                return new PDFTextStripper().getText(doc).lines().toList();
            }
        }
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }
}
