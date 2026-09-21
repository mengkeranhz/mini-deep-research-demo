package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.Config;
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

/**
 * render_card：把 Markdown 渲染成知识卡片 PNG（md2card 官方 API，本地 curl 调用）。
 * POST {base}/api/generate（x-api-key 认证），网络层失败按官方容灾规则换备用域名重试；
 * 返回的图片 URL 逐张下载保存为 card-NN.png。请求体经临时文件 --data-binary 发送，无转义问题。
 */
public class RenderCardTool implements AgentTool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOMAIN_PRIMARY = "https://md2card.com";
    private static final String DOMAIN_BACKUP = "https://md2card.cn";

    private final Config.RenderCard cfg;
    private final Path root;

    public RenderCardTool(Config.RenderCard cfg, Config.Storage storage) {
        this.cfg = cfg;
        this.root = Config.rootDir(storage);
    }

    @Override
    public String name() {
        return "render_card";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "把 Markdown 渲染成精美的知识卡片 PNG 图片（调用 md2card API，消耗积分，需联网；"
                        + "服务端超时 60s，首次调用可能较慢）。"
                        + "markdown 中的图片只用可外链的开放图源（unsplash/pixabay 等直链，unsplash 已实测可渲染）；"
                        + "pngtree/alamy/shutterstock 等图库站直链会被防盗链拦截，渲染服务端抓不到图会静默丢失"
                        + "（不报错、不留占位），一律不用。"
                        + "常用主题：xiaohongshu（小红书风，旅行/探店）、apple-notes（通用笔记）、"
                        + "nature（清新自然）、darktech（技术）、business（商务）。"
                        + "用户指定风格或常用主题不合适时，先 read_file docs/render-card-themes.md "
                        + "读完整主题表（23 套主题的适用场景、theme_mode 可选值、type 尺寸预设），再选 theme 与 theme_mode。"
                        + "默认单卡输出（一份内容一张长图卡，不传 split_mode 即可）；"
                        + "仅当用户明确要求拆成多张卡片时才传 split_mode=hrSplit（按 --- 分隔线拆）。返回本地 PNG 路径清单。",
                Map.of("type", "object",
                        "properties", Map.ofEntries(
                                Map.entry("markdown", Map.of("type", "string", "description", "Markdown 全文（与 file_path 二选一）")),
                                Map.entry("file_path", Map.of("type", "string", "description",
                                        "Markdown 文件路径，相对根目录解析（与 markdown 二选一）")),
                                Map.entry("theme", Map.of("type", "string", "description",
                                        "主题 ID，默认 xiaohongshu；完整主题表与适用场景见 docs/render-card-themes.md（先 read_file 再选）")),
                                Map.entry("theme_mode", Map.of("type", "string", "description",
                                        "主题变体（可选），仅 apple-notes/coil-notebook/pop-art 支持，可选值见 docs/render-card-themes.md")),
                                Map.entry("type", Map.of("type", "string",
                                        "enum", List.of("xiaohongshu", "square", "poster", "a4"),
                                        "description", "尺寸预设，指定后忽略 width/height："
                                                + "xiaohongshu 440×586（小红书 3:4）、square 500×500、"
                                                + "poster 440×782（手机长海报）、a4 595×842（A4 打印）")),
                                Map.entry("width", Map.of("type", "integer", "description",
                                        "卡片宽度 px（200-2000），默认 440；与 type 二选一")),
                                Map.entry("height", Map.of("type", "integer", "description",
                                        "卡片高度 px（200-2000），默认 586（440×586 约 3:4，适合小红书）；与 type 二选一")),
                                Map.entry("split_mode", Map.of("type", "string", "enum", List.of("noSplit", "autoSplit", "hrSplit"),
                                        "description", "默认 noSplit 单卡（一份内容一张图，无需传此参数）；仅用户明确要多张拆分卡时才传 hrSplit（按 --- 拆）")),
                                Map.entry("mdx_mode", Map.of("type", "boolean", "description",
                                        "启用 MDX（JSX/自定义字体/公式/Mermaid 图表），默认 false")),
                                Map.entry("over_hidden_mode", Map.of("type", "boolean", "description",
                                        "溢出隐藏模式：超出卡片高度的内容裁切而不溢出，默认 false")),
                                Map.entry("output_dir", Map.of("type", "string", "description",
                                        "输出目录（可选），相对根目录解析，默认 render-cards"))),
                        "required", List.of()));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String apiKey = cfg.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 md2card API key：在 config.yaml tools.render-card.md2card-api-key 填入"
                    + "（https://md2card.com/zh/my/api-keys 登录后创建），或设置环境变量 MD2CARD_API_KEY");
        }
        String markdown = ToolRegistry.optStr(input, "markdown");
        String filePath = ToolRegistry.optStr(input, "file_path");
        boolean hasMd = markdown != null && !markdown.isBlank();
        boolean hasFile = filePath != null && !filePath.isBlank();
        if (hasMd == hasFile) {
            throw new IllegalArgumentException("markdown 与 file_path 必须二选一提供一个");
        }
        String md = hasFile ? readFile(filePath) : markdown;
        if (md.isBlank()) {
            throw new IllegalArgumentException("markdown 内容为空");
        }

        String theme = orDefault(ToolRegistry.optStr(input, "theme"), cfg.defaultTheme());
        String themeMode = ToolRegistry.optStr(input, "theme_mode");
        String splitMode = orDefault(ToolRegistry.optStr(input, "split_mode"), "noSplit");
        if (!List.of("noSplit", "autoSplit", "hrSplit").contains(splitMode)) {
            throw new IllegalArgumentException("split_mode 可选: noSplit / autoSplit / hrSplit");
        }
        String type = ToolRegistry.optStr(input, "type");
        int width;
        int height;
        if (type != null && !type.isBlank()) {
            int[] size = presetSize(type); // 尺寸预设：本地映射为 width/height，不透传（HTTP API 不认 MCP 的 type 扩展）
            width = size[0];
            height = size[1];
        } else {
            width = ToolRegistry.optInt(input, "width", cfg.defaultWidth());
            height = ToolRegistry.optInt(input, "height", cfg.defaultHeight());
        }
        if (width < 200 || width > 2000 || height < 200 || height > 2000) {
            throw new IllegalArgumentException("width/height 需在 200-2000 px（或用 type 预设）");
        }
        boolean mdxMode = optBool(input, "mdx_mode", false);
        boolean overHiddenMode = optBool(input, "over_hidden_mode", false);
        String outDirStr = ToolRegistry.optStr(input, "output_dir");

        Path outDir = outDirStr == null || outDirStr.isBlank()
                ? root.resolve("render-cards") : resolve(outDirStr);
        Files.createDirectories(outDir);
        // 清掉上次残留的编号卡，避免新旧混淆
        try (var files = Files.list(outDir)) {
            files.filter(f -> f.getFileName().toString().matches("card-\\d+\\.png"))
                    .forEach(f -> {
                        try {
                            Files.delete(f);
                        } catch (Exception ignored) {
                        }
                    });
        }

        ObjectNode payload = JSON.createObjectNode()
                .put("markdown", md)
                .put("theme", theme)
                .put("width", width)
                .put("height", height)
                .put("splitMode", splitMode);
        if (themeMode != null && !themeMode.isBlank()) {
            payload.put("themeMode", themeMode);
        }
        if (mdxMode) {
            payload.put("mdxMode", true);
        }
        if (overHiddenMode) {
            payload.put("overHiddenMode", true);
        }
        Path payloadFile = Files.createTempDirectory("render-card").resolve("payload.json");
        Files.write(payloadFile, JSON.writeValueAsBytes(payload));
        try {
            String primary = cfg.baseUrl() == null || cfg.baseUrl().isBlank()
                    ? DOMAIN_PRIMARY : cfg.baseUrl().replaceAll("/+$", "");
            String backup = DOMAIN_BACKUP.equals(primary) ? DOMAIN_PRIMARY : DOMAIN_BACKUP;
            JsonNode images = null;
            String usedDomain = null;
            NetworkError last = null;
            for (String domain : List.of(primary, backup)) {
                try {
                    images = generate(domain, apiKey, payloadFile);
                    usedDomain = domain;
                    break;
                } catch (NetworkError e) { // 仅网络层失败才换域名（官方容灾：同 key 同 body）
                    last = e;
                }
            }
            if (images == null) {
                throw new IllegalStateException("md2card API 两个域名均不可达（" + primary + " / " + backup + "）："
                        + (last == null ? "" : last.getMessage()), last);
            }

            int n = images.size();
            int digits = Math.max(2, String.valueOf(n).length());
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String url = images.get(i).path("url").asText("");
                if (url.isEmpty()) {
                    throw new IllegalStateException("API 返回的图片缺 url: " + images.get(i));
                }
                Path file = outDir.resolve("card-" + String.format("%0" + digits + "d", i + 1) + ".png");
                download(url, file);
                lines.add(file + " (" + Files.size(file) / 1024 + " KB)");
            }
            StringBuilder sb = new StringBuilder("已保存 ").append(n).append(" 张知识卡片（主题 ").append(theme)
                    .append("，").append(width).append("×").append(height).append("，").append(splitMode)
                    .append("，来源 ").append(usedDomain).append("）：\n");
            lines.forEach(l -> sb.append(l).append("\n"));
            return sb.toString().stripTrailing();
        } finally {
            try {
                Files.deleteIfExists(payloadFile);
                Files.deleteIfExists(payloadFile.getParent());
            } catch (IOException ignored) {
                // 临时文件清理失败不影响结果
            }
        }
    }

    // ---- md2card API 调用 ----

    /** 调 /api/generate 取图片清单；网络层失败抛 NetworkError（可换域名），业务错误直接抛（不重试）。 */
    private JsonNode generate(String domain, String apiKey, Path payloadFile) throws Exception {
        CurlResult r = curl(List.of(
                "curl", "-sS", "--max-time", String.valueOf(timeoutSec()),
                "-X", "POST", domain + "/api/generate",
                "-H", "x-api-key: " + apiKey,
                "-H", "Content-Type: application/json",
                "--data-binary", "@" + payloadFile,
                "-w", "\n%{http_code}"));
        if (r.exitCode() != 0 || r.httpCode() == 0 || r.httpCode() >= 500) {
            throw new NetworkError(domain + " 请求失败：exit=" + r.exitCode()
                    + " http=" + r.httpCode()
                    + (r.stderr().isBlank() ? "" : " " + truncate(r.stderr())));
        }
        JsonNode node;
        try {
            node = JSON.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("md2card 返回了非 JSON 内容（http=" + r.httpCode() + "）: "
                    + truncate(r.body()));
        }
        if (node.has("error")) {
            throw new IllegalStateException("md2card 生成失败（http=" + r.httpCode() + "）："
                    + node.path("error").asText()
                    + (node.has("message") ? " - " + node.path("message").asText() : "")
                    + "；排查：API key 是否有效、积分是否充足（https://md2card.com/zh/my/api-keys）");
        }
        JsonNode images = node.path("images");
        if (!images.isArray() || images.isEmpty()) {
            throw new IllegalStateException("md2card 未返回图片: " + truncate(node.toString()));
        }
        return images;
    }

    /** 下载单张卡片并校验 PNG 魔数。 */
    private void download(String url, Path file) throws Exception {
        CurlResult r = curl(List.of(
                "curl", "-sS", "--max-time", String.valueOf(timeoutSec()),
                "-o", file.toString(),
                "-w", "\n%{http_code}", url));
        if (r.exitCode() != 0 || r.httpCode() != 200) {
            Files.deleteIfExists(file);
            throw new NetworkError("下载卡片失败（exit=" + r.exitCode() + " http=" + r.httpCode() + "）: " + url);
        }
        if (Files.size(file) < 100 || !isPng(file)) {
            Files.deleteIfExists(file);
            throw new IllegalStateException("下载的内容不是有效 PNG: " + url);
        }
    }

    // ---- curl 执行 ----

    /** curl 结果：exitCode 进程退出码；httpCode 从 -w 尾行取（0=未拿到）；body/stderr 原文。 */
    private record CurlResult(int exitCode, int httpCode, String body, String stderr) {}

    /** 网络层失败（curl 退出非 0 / 无状态码 / 5xx），可换域名重试。 */
    private static final class NetworkError extends IllegalStateException {
        NetworkError(String message) {
            super(message);
        }
    }

    private CurlResult curl(List<String> command) throws Exception {
        Process p;
        try {
            p = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new IllegalStateException("本机无法执行 curl，请确认 curl 已安装且在 PATH 中", e);
        }
        // 虚拟线程读双流，防输出超管道缓冲卡死（截图 PNG 可达数 MB）
        AtomicReference<byte[]> outRef = new AtomicReference<>(new byte[0]);
        AtomicReference<byte[]> errRef = new AtomicReference<>(new byte[0]);
        Thread t1 = Thread.ofVirtual().start(() -> {
            try {
                outRef.set(p.getInputStream().readAllBytes());
            } catch (IOException ignored) {
            }
        });
        Thread t2 = Thread.ofVirtual().start(() -> {
            try {
                errRef.set(p.getErrorStream().readAllBytes());
            } catch (IOException ignored) {
            }
        });
        // --max-time 之外再给进程级兜底
        if (!p.waitFor(timeoutSec() + 10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new NetworkError("curl 超时被终止: " + command.get(command.size() - 1));
        }
        t1.join(3000);
        t2.join(3000);
        String out = new String(outRef.get(), StandardCharsets.UTF_8);
        String err = new String(errRef.get(), StandardCharsets.UTF_8);
        int code = 0;
        String body = out;
        int nl = out.lastIndexOf('\n');
        if (nl >= 0) {
            String tail = out.substring(nl + 1).strip();
            if (tail.matches("\\d{3}")) {
                code = Integer.parseInt(tail);
                body = out.substring(0, nl);
            }
        }
        return new CurlResult(p.exitValue(), code, body.strip(), err.strip());
    }

    /** 尺寸预设（对齐 MCP 服务器的 type 扩展）：中文别名兼容。 */
    private static int[] presetSize(String type) {
        return switch (type) {
            case "xiaohongshu", "小红书" -> new int[]{440, 586};
            case "square", "正方形" -> new int[]{500, 500};
            case "poster", "手机海报" -> new int[]{440, 782};
            case "a4", "A4纸打印" -> new int[]{595, 842};
            default -> throw new IllegalArgumentException("未知 type: " + type + "，可选 xiaohongshu / square / poster / a4");
        };
    }

    private int timeoutSec() {
        return Math.max(20, cfg.timeoutMs() / 1000);
    }

    private static boolean isPng(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] head = new byte[4];
            return in.readNBytes(head, 0, 4) == 4
                    && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
        }
    }

    // ---- 辅助 ----

    private String readFile(String filePath) throws IOException {
        Path mdPath = resolve(filePath);
        if (!Files.isRegularFile(mdPath)) {
            throw new IllegalArgumentException("文件不存在: " + mdPath);
        }
        return Files.readString(mdPath);
    }

    /** 相对路径相对根目录解析，绝对路径原样使用。 */
    private Path resolve(String p) {
        Path path = Path.of(p);
        return path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
    }

    private static String orDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private static boolean optBool(JsonNode input, String key, boolean def) {
        JsonNode v = input.get(key);
        return v == null || v.isNull() ? def : v.asBoolean(def);
    }

    private static String truncate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
