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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** read_file：文本文件按行切片阅读；PDF 用 PDFBox 提取全文后同样按行切片。 */
public class ReadFileTool implements AgentTool {

    /** 控制台预览保留的最大行数与每行宽度。 */
    private static final int PREVIEW_LINES = 30;
    private static final int PREVIEW_LINE_WIDTH = 120;

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
                        + "文件较长时可多次调用翻页阅读；提供 keywords 时改为按段落检索，返回命中关键词的完整段落。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "文件路径（相对路径相对于根目录解析）"),
                                "offset", Map.of("type", "integer", "description", "起始行号（0 起），默认 0"),
                                "limit", Map.of("type", "integer", "description", "返回行数，默认 200"),
                                "max-results", Map.of("type", "integer",
                                        "description", "关键词检索时最多返回的段落数，默认 30"),
                                "keywords", Map.of("type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "关键词列表，返回命中这些关键词的完整段落（命中关键词更多的段落优先）")),
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
            int maxResults = Math.max(1, ToolRegistry.optInt(input, "max-results", 30));
            return searchByKeywords(path, lines, keywords, maxResults);
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

    /** 按空行分段检索：返回命中关键词的完整段落，命中关键词更多的段落优先，最多返回 maxResults 段。 */
    private String searchByKeywords(String path, List<String> lines, List<String> keywords, int maxResults) {
        List<String> lowerKeywords = keywords.stream().map(String::toLowerCase).toList();
        List<Hit> hits = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            while (i < lines.size() && lines.get(i).isBlank()) {
                i++;
            }
            if (i >= lines.size()) {
                break;
            }
            int start = i;
            StringBuilder text = new StringBuilder();
            while (i < lines.size() && !lines.get(i).isBlank()) {
                text.append(lines.get(i)).append('\n');
                i++;
            }
            int end = i - 1;
            String lower = text.toString().toLowerCase();
            int count = 0;
            for (String kw : lowerKeywords) {
                if (lower.contains(kw)) {
                    count++;
                }
            }
            if (count > 0) {
                hits.add(new Hit(start, end, text.toString(), count));
            }
        }
        if (hits.isEmpty()) {
            return "文件 " + path + " 中没有命中关键词 " + keywords + " 的内容。";
        }
        hits.sort(Comparator.comparingInt(Hit::keywordCount).reversed()
                .thenComparingInt(Hit::start));
        int show = Math.min(maxResults, hits.size());
        StringBuilder sb = new StringBuilder("文件 ").append(path).append(" 命中关键词 ").append(keywords)
                .append(" 的段落共 ").append(hits.size()).append(" 段，返回前 ").append(show).append(" 段:\n\n");
        for (int k = 0; k < show; k++) {
            Hit h = hits.get(k);
            sb.append("[第 ").append(h.start()).append("-").append(h.end()).append(" 行，命中 ")
                    .append(h.keywordCount()).append(" 个关键词]\n").append(h.text()).append('\n');
        }
        return sb.toString();
    }

    /** 一个命中段落：起始行、结束行、文本与命中的不同关键词数。 */
    private record Hit(int start, int end, String text, int keywordCount) {}

    /** 控制台精简预览：保留前若干行、超长行截断——fetch_url 抓回的网页段落动辄整段一行，完整内容仍回传模型。 */
    @Override
    public String consolePreview(String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(lines.length, PREVIEW_LINES);
        for (int i = 0; i < shown; i++) {
            String line = lines[i];
            sb.append(line.length() > PREVIEW_LINE_WIDTH ? line.substring(0, PREVIEW_LINE_WIDTH) + "…" : line)
                    .append('\n');
        }
        if (lines.length > shown) {
            sb.append("…（共 ").append(lines.length).append(" 行，控制台已截断，完整内容已回传模型）");
        }
        return sb.toString();
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
