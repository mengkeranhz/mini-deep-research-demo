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

    public record Llm(String provider, String baseUrl, String model, String apiKey,
                      int maxTokens, double temperature, boolean streaming) {}

    public record WebSearch(String tavilyApiKey, int maxResults) {}

    /** minRequestIntervalMs：相邻两次高德请求的最小间隔（毫秒），防 QPS 超限。 */
    public record Lbs(String amapApiKey, int minRequestIntervalMs) {}

    /** 全量配置：llm + tools 两段。 */
    public record Data(Llm llm, WebSearch webSearch, Lbs lbs) {}

    public static Data load() {
        Map<String, Object> root = new Yaml().load(expandEnv(readText()));
        Map<String, Object> llm = asMap(root.get("llm"));
        Map<String, Object> tools = asMap(root.get("tools"));
        Map<String, Object> webSearch = asMap(tools.get("web-search"));
        Map<String, Object> lbs = asMap(tools.get("lbs-service"));
        return new Data(
                new Llm(
                        str(llm, "provider"), str(llm, "base-url"), str(llm, "model"), str(llm, "api-key"),
                        intVal(llm, "max-tokens", 8192),
                        dblVal(llm, "temperature", 0),
                        boolVal(llm, "streaming", true)),
                new WebSearch(
                        str(webSearch, "tavily-api-key"),
                        intVal(webSearch, "max-results", 5)),
                new Lbs(str(lbs, "amap-api-key"), intVal(lbs, "min-request-interval-ms", 350)));
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
