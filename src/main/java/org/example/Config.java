package org.example;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置加载：先读工作目录下的 config.yaml，没有则读项目内的（classpath）config.yaml。
 * 支持 ${ENV_VAR} 环境变量占位符，在解析前做纯文本替换。
 */
public final class Config {

    /** 上下文压缩阈值缺省：1,048,576（1M 窗口）× 80% ≈ 838,861。 */
    static final int DEFAULT_CONTEXT_THRESHOLD = 838_861;

    /**
     * temperature/topP：采样参数（GLM-5.3 推荐 temperature=1、top_p=0.95）；topP<0 表示未配置、不发送。
     * thinking：思考档位，原样透传给网关（off/disabled=关闭思考，其余值发顶层 reasoning_effort，
     * 档位集合以网关为准，如 low/medium/high/max）；留空不发送、走网关默认档。
     * contextTokenThreshold：上下文压缩阈值——上次响应 inputTokens 超过即压缩重建。
     * 基准：模型窗口的 ~80% 触发，为当前轮输入与输出预留空间（GLM-5.3 1M 窗口 → 1,048,576×80% ≈ 838,861）。
     */
    public record Llm(String provider, String baseUrl, String model, String apiKey,
                      int maxTokens, double temperature, double topP, boolean streaming,
                      String thinking, int contextTokenThreshold) {}

    public record WebSearch(String tavilyApiKey, int maxResults) {}

    /** minRequestIntervalMs：相邻两次高德请求的最小间隔（毫秒），防 QPS 超限。 */
    public record Lbs(String amapApiKey, int minRequestIntervalMs) {}

    /** 文件保存/读取根目录。rootDir 为空时回退：工作目录 → 项目目录。 */
    public record Storage(String rootDir) {}

    /** read-file 工具：关键词检索默认返回的段落数。 */
    public record ReadFile(int maxResults) {}

    /** render-card 工具：md2card API key（md2card.com/zh/my/api-keys 创建）；baseUrl 留空用官方主域名、失败自动换备用域名。 */
    public record RenderCard(String apiKey, String baseUrl, int timeoutMs, String defaultTheme,
                             int defaultWidth, int defaultHeight) {}

    /** 全量配置：llm + storage + tools 三段。 */
    public record Data(Llm llm, WebSearch webSearch, Lbs lbs, Storage storage, ReadFile readFile,
                       RenderCard renderCard) {}

    public static Data load() {
        Map<String, Object> root = new Yaml().load(expandEnv(readText()));
        Map<String, Object> llm = asMap(root.get("llm"));
        Map<String, Object> tools = asMap(root.get("tools"));
        Map<String, Object> webSearch = asMap(tools.get("web-search"));
        Map<String, Object> lbs = asMap(tools.get("lbs-service"));
        Map<String, Object> storage = asMap(root.get("storage"));
        Map<String, Object> readFile = asMap(tools.get("read-file"));
        Map<String, Object> renderCard = asMap(tools.get("render-card"));
        String theme = str(renderCard, "default-theme");
        return new Data(
                new Llm(
                        str(llm, "provider"), str(llm, "base-url"), str(llm, "model"), str(llm, "api-key"),
                        intVal(llm, "max-tokens", 8192),
                        dblVal(llm, "temperature", 0),
                        dblVal(llm, "top-p", -1),
                        boolVal(llm, "streaming", true),
                        strBlank(str(llm, "thinking"), "medium"),
                        intVal(llm, "context-token-threshold", DEFAULT_CONTEXT_THRESHOLD)),
                new WebSearch(
                        str(webSearch, "tavily-api-key"),
                        intVal(webSearch, "max-results", 30)),
                new Lbs(str(lbs, "amap-api-key"), intVal(lbs, "min-request-interval-ms", 350)),
                new Storage(str(storage, "root-dir")),
                new ReadFile(intVal(readFile, "max-results", 30)),
                new RenderCard(
                        str(renderCard, "md2card-api-key"),
                        str(renderCard, "base-url"),
                        intVal(renderCard, "timeout-ms", 90000),
                        theme.isBlank() ? "xiaohongshu" : theme,
                        intVal(renderCard, "default-width", 440),
                        intVal(renderCard, "default-height", 586)));
    }

    /** 文件根目录解析：显式配置 root-dir → 工作目录(user.dir) → 项目目录(code source 所在)。 */
    public static Path rootDir(Storage storage) {
        String configured = storage == null ? null : storage.rootDir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path wd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (Files.isDirectory(wd) && Files.isWritable(wd)) {
            return wd;
        }
        Path project = projectDir();
        return project != null ? project : wd;
    }

    /** 项目目录：code source 所在（IDE 下为 target/classes 上两级；jar 下为 jar 所在目录）。 */
    private static Path projectDir() {
        try {
            java.net.URL loc = Config.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null) {
                return null;
            }
            Path p = Path.of(loc.toURI()).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) { // classes 目录形态：target/classes → 项目根
                return p.getParent() != null && p.getParent().getParent() != null
                        ? p.getParent().getParent() : p;
            }
            return p.getParent(); // jar 形态：jar 所在目录
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** ${VAR} → 环境变量值；未设置则替换为空串（后续使用处会给出明确报错）。 */
    private static String expandEnv(String text) {
        return ENV_VAR.matcher(text).replaceAll(m -> {
            String v = System.getenv(m.group(1));
            return v == null ? "" : Matcher.quoteReplacement(v);
        });
    }

    private static String readText() {
        try {
            Path local = Path.of("config.yaml");
            if (Files.exists(local)) {
                return Files.readString(local);
            }
            try (InputStream in = Config.class.getResourceAsStream("/config.yaml")) {
                if (in != null) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            throw new IllegalStateException("找不到 config.yaml（先找工作目录，再找项目 classpath）");
        } catch (IOException e) {
            throw new IllegalStateException("读取 config.yaml 失败: " + e.getMessage());
        }
    }

    // ---- 取值辅助（缺省即空，交给使用处报错） ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /** 取字符串，空白或缺失时用缺省值（档位类配置用）。 */
    private static String strBlank(String v, String def) {
        return v == null || v.isBlank() ? def : v.strip().toLowerCase();
    }

    private static int intVal(Map<String, Object> m, String key, int def) {
        return m.get(key) instanceof Number n ? n.intValue() : def;
    }

    private static double dblVal(Map<String, Object> m, String key, double def) {
        return m.get(key) instanceof Number n ? n.doubleValue() : def;
    }

    private static boolean boolVal(Map<String, Object> m, String key, boolean def) {
        return m.get(key) instanceof Boolean b ? b : def;
    }

    private Config() {}
}
