package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.Config;
import org.example.SkillRegistry;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** run_code：写临时文件用 python3 执行（60 秒超时），返回 stdout+stderr（超长截断）。 */
public class RunCodeTool implements AgentTool {

    private static final int MAX_OUTPUT = 6000;

    private final Path root;

    public RunCodeTool(Config.Storage storage) {
        this.root = Config.rootDir(storage);
    }

    @Override
    public String name() {
        return "run_code";
    }

    @Override
    public ToolDef definition() {
        Map<String, Object> script = Map.of("type", "string",
                "description", "Python 3 脚本全文；与 skill_script 二选一");
        Map<String, Object> skillScript = Map.of("type", "string",
                "description", "Skill 附件脚本地址 skill://<skill-name>/<relative-path>；与 script 二选一");
        Map<String, Object> args = Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "仅 skill_script 模式使用的命令行参数");
        return new ToolDef(name(), "在本地执行 Python 3（60 秒超时），返回合并的 stdout+stderr。"
                        + "支持临时 script 或 Skill 自带 skill_script；后者用于执行技能目录 scripts/*.py，"
                        + "args 作为命令行参数传入。"
                        + "第三方库（requests 等）未预装，一律用标准库（urllib/json/re/csv 等）实现。",
                Map.of("type", "object",
                        "properties", Map.of("script", script, "skill_script", skillScript, "args", args),
                        "required", List.of()));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String script = ToolRegistry.optStr(input, "script");
        String skillScript = ToolRegistry.optStr(input, "skill_script");
        boolean hasScript = script != null && !script.isBlank();
        boolean hasSkillScript = skillScript != null && !skillScript.isBlank();
        if (hasScript == hasSkillScript) {
            throw new IllegalArgumentException("script 与 skill_script 必须二选一");
        }

        List<String> command = new ArrayList<>();
        Path file;
        if (hasSkillScript) {
            file = SkillRegistry.skillResource(skillScript);
            command.add("python3");
            command.add(file.toString());
            JsonNode args = input.get("args");
            if (args != null && !args.isNull()) {
                command.addAll(ToolRegistry.strList(input, "args"));
            }
        } else {
            file = Files.createTempDirectory("run_code").resolve("script.py");
            Files.writeString(file, script);
            command.add("python3");
            command.add(file.toString());
        }

        Process p = new ProcessBuilder(command)
                .directory(root.toFile())
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
