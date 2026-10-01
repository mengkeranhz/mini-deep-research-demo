package org.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * 单个 Agent 的磁盘归档：日志、事实账本、任务账本、检索记录、结果与运行元数据都落在
 * storage.audit-dir 下的独立 run 目录；子 Agent 目录嵌套在父 run 的 subagents/ 下。
 * 状态文件采用“整文件原子替换”，日志采用 append，便于事后离线分析。
 */
public final class RunArchive {
    private static final DateTimeFormatter RUN_DIR = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dir;
    private final String agentId;
    private final boolean root;
    private final LocalDateTime startedAt = LocalDateTime.now();

    private RunArchive(Path dir, String agentId, boolean root) {
        this.dir = dir;
        this.agentId = agentId;
        this.root = root;
    }

    /** 创建父 Agent 归档目录；同秒冲突时用随机后缀，避免覆盖历史 run。 */
    public static RunArchive createRoot(Config.Storage storage, String agentId) {
        Path base = auditRoot(storage);
        String stamp = LocalDateTime.now().format(RUN_DIR);
        Path dir = base.resolve("run-" + stamp);
        while (Files.exists(dir)) {
            dir = base.resolve("run-" + stamp + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong()));
        }
        return create(dir, agentId, true);
    }

    /** 子 Agent 直接使用 delegate_agent 创建的专属 workspace。 */
    public static RunArchive forWorkspace(Path workspace, String agentId) {
        return create(workspace, agentId, false);
    }

    private static RunArchive create(Path dir, String agentId, boolean root) {
        try {
            Files.createDirectories(dir);
            if (root) {
                Files.createDirectories(dir.resolve("subagents"));
            }
            return new RunArchive(dir, agentId, root);
        } catch (IOException e) {
            throw new IllegalStateException("创建 Agent 归档目录失败: " + dir + ": " + e.getMessage(), e);
        }
    }

    private static Path auditRoot(Config.Storage storage) {
        String configured = storage == null ? null : storage.auditDir();
        Path root = Config.rootDir(storage);
        return configured == null || configured.isBlank()
                ? root.resolve("runs")
                : root.resolve(configured.strip()).toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    public LocalDateTime startedAt() {
        return startedAt;
    }

    public Path file(String name) {
        return dir.resolve(name);
    }

    /** 为子 Agent 创建独立 workspace，并保证父归档下的 subagents 目录存在。 */
    public Path newSubAgentWorkspace(String executionId) {
        Path workspace = dir.resolve("subagents").resolve(executionId);
        try {
            Files.createDirectories(workspace);
            return workspace;
        } catch (IOException e) {
            throw new IllegalStateException("创建子Agent归档目录失败: " + workspace + ": " + e.getMessage(), e);
        }
    }

    /** 原子写入 Markdown 状态文件；空内容也写入占位，便于固定文件清单。 */
    public synchronized void write(String name, String content) {
        validateName(name);
        Path target = file(name);
        Path tmp = dir.resolve("." + target.getFileName() + ".tmp-" + Thread.currentThread().threadId());
        try {
            Files.createDirectories(dir);
            Files.writeString(tmp, content == null ? "" : content, StandardCharsets.UTF_8);
            Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 保留原始写入异常即可。
            }
            throw new IllegalStateException("写入 Agent 归档失败: " + target + ": " + e.getMessage(), e);
        }
    }

    public void writeIfPresent(String name, String content) {
        if (content != null && !content.isBlank()) {
            write(name, content);
        }
    }

    /** 归档目录中的 Markdown 清单，按文件名排序；忽略隐藏临时文件。 */
    public String markdownIndex() {
        try (Stream<Path> files = Files.list(dir)) {
            StringBuilder sb = new StringBuilder("# Markdown 归档清单\n\n");
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".md") && !n.startsWith("."))
                    .sorted(Comparator.naturalOrder())
                    .forEach(n -> sb.append("- ").append(n).append('\n'));
            if (root && Files.isDirectory(dir.resolve("subagents"))) {
                sb.append("\n## 子Agent\n");
                try (Stream<Path> children = Files.list(dir.resolve("subagents"))) {
                    children.filter(Files::isDirectory)
                            .map(p -> p.getFileName().toString())
                            .sorted()
                            .forEach(n -> sb.append("- subagents/").append(n).append("/\n"));
                }
            }
            return sb.toString();
        } catch (IOException e) {
            return "# Markdown 归档清单\n\n生成失败: " + e.getMessage() + '\n';
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
                || name.startsWith(".") || !name.endsWith(".md")) {
            throw new IllegalArgumentException("归档文件名必须是安全的 .md 名称: " + name);
        }
    }
}
