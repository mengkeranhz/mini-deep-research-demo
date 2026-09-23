package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** run_code：写临时文件用 python3 执行（60 秒超时），返回 stdout+stderr（超长截断）。 */
public class RunCodeTool implements AgentTool {

    private static final int MAX_OUTPUT = 6000;

    @Override
    public String name() {
        return "run_code";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "在本地执行一段 Python 3 脚本（60 秒超时），返回合并的 stdout+stderr。"
                        + "它是万能补位工具：不止计算与数据处理——脚本能访问网络与本地文件，"
                        + "可现场实现各种实用小工具，达成内置工具边界之外的能力："
                        + "发起 HTTP 请求（GET/POST、带 header）、下载并解析远程文件（JSON/HTML/XML/CSV）、"
                        + "读写本地已有文件（工作目录=应用根目录，相对路径由此解析）、"
                        + "正则批量提取、数据清洗、格式转换等。"
                        + "第三方库（requests 等）未预装，一律用标准库（urllib/json/re/csv 等）实现。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "script", Map.of("type", "string", "description", "Python 3 脚本全文")),
                        "required", List.of("script")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String script = ToolRegistry.str(input, "script");
        Path file = Files.createTempDirectory("run_code").resolve("script.py");
        Files.writeString(file, script);

        Process p = new ProcessBuilder("python3", file.toString())
                .redirectErrorStream(true) // stdout+stderr 合并，避免管道缓冲死锁
                .start();
        // 边执行边读取输出，防止输出超过管道缓冲导致进程卡死
        AtomicReference<byte[]> output = new AtomicReference<>(new byte[0]);
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                output.set(p.getInputStream().readAllBytes());
            } catch (IOException ignored) {
            }
        });
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("脚本执行超过 60 秒，已终止");
        }
        reader.join(5000);
        String out = new String(output.get(), StandardCharsets.UTF_8);
        if (p.exitValue() != 0) out = "退出码 " + p.exitValue() + "\n" + out;
        return truncate(out);
    }

    private static String truncate(String s) {
        if (s.length() <= MAX_OUTPUT) return s;
        return "[输出共 " + s.length() + " 字符，已截断]\n"
                + s.substring(0, 3000) + "\n…（中间省略）…\n"
                + s.substring(s.length() - 2500);
    }
}
