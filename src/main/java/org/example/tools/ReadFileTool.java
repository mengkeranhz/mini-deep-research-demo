package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** read_file：文本文件按行切片阅读；PDF 用 PDFBox 提取全文后同样按行切片。 */
public class ReadFileTool implements AgentTool {

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "按行读取本地文件（fetch_url 保存的 Markdown、PDF 等），返回指定范围的行。"
                        + "文件较长时可多次调用翻页阅读。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "文件路径"),
                                "offset", Map.of("type", "integer", "description", "起始行号（0 起），默认 0"),
                                "limit", Map.of("type", "integer", "description", "返回行数，默认 200")),
                        "required", List.of("path")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String path = ToolRegistry.str(input, "path");
        int offset = ToolRegistry.optInt(input, "offset", 0);
        int limit = ToolRegistry.optInt(input, "limit", 200);

        List<String> lines = readAllLines(path);
        int from = Math.min(offset, lines.size());
        int to = Math.min(from + limit, lines.size());
        StringBuilder sb = new StringBuilder("文件 ").append(path).append(" 共 ").append(lines.size())
                .append(" 行，返回第 ").append(from).append("-").append(to - 1).append(" 行:\n");
        for (int i = from; i < to; i++) {
            sb.append(i).append(": ").append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    private List<String> readAllLines(String path) throws Exception {
        if (path.toLowerCase().endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(new File(path))) {
                return new PDFTextStripper().getText(doc).lines().toList();
            }
        }
        return Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
    }
}
