package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.Console;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 高德 Web 服务 API：GET + status/errcode 检查 + Jackson 解析共用。key 与限流参数来自 config.yaml 的 lbs-service。
 * 内置两级防线防 QPS 超限（CUQPS_HAS_EXCEEDED_THE_LIMIT）：请求节流 + 超限退避重试。
 */
public final class AmapClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** QPS 超限重试次数（退避 1s、2s、4s），仍失败才把错误抛给上层。 */
    private static final int QPS_RETRIES = 3;

    private final String key;
    private final long minIntervalMs;
    private long lastRequestAt;

    public AmapClient(String key, long minIntervalMs) {
        this.key = key;
        this.minIntervalMs = minIntervalMs;
    }

    public JsonNode get(String path, Map<String, String> params) throws Exception {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("未配置 amap-api-key（config.yaml 的 tools.lbs-service，对应环境变量 AMAP_API_KEY）");
        }
        StringBuilder url = new StringBuilder("https://restapi.amap.com").append(path)
                .append("?key=").append(URLEncoder.encode(key, StandardCharsets.UTF_8));
        params.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                url.append('&').append(k).append('=')
                        .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        });
        for (int attempt = 0; ; attempt++) {
            throttle();
            JsonNode root = MAPPER.readTree(Http.getString(url.toString()));
            // v3 接口失败时 status="0" + info，v4 接口失败时 errcode!=0 + errmsg
            String err = "0".equals(root.path("status").asText(null))
                    ? root.path("info").asText("")
                    : root.has("errcode") && root.path("errcode").asInt(-1) != 0
                            ? root.path("errmsg").asText("") : null;
            if (err == null) {
                return root;
            }
            if (err.contains("QPS_HAS_EXCEEDED") && attempt < QPS_RETRIES) {
                System.out.println(Console.warn("[amap] QPS 超限，" + (1000L << attempt) + "ms 后第 "
                        + (attempt + 2) + " 次尝试…"));
                Thread.sleep(1000L << attempt);
                continue;
            }
            throw new IllegalStateException("高德 API 错误: " + err);
        }
    }

    /** 简单节流：相邻两次请求间隔不小于 minIntervalMs（默认 350ms ≈ 3 QPS 以内）。 */
    private synchronized void throttle() throws InterruptedException {
        long wait = lastRequestAt + minIntervalMs - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    /** 高德字段可能是 ""、[] 或文本（如 POI 的 address），统一取文本。 */
    public static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    /** POI 节点 →「名称 | 地址 | 经纬度」一行。 */
    public static String poiLine(JsonNode poi) {
        return text(poi.path("name"))
                + " | 地址: " + text(poi.path("address"))
                + " | 经纬度: " + text(poi.path("location"));
    }
}
