package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 高德 Web 服务 API：GET + status/errcode 检查 + Jackson 解析共用。key 来自 config.yaml 的 lbs-service。 */
public final class AmapClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String key;

    public AmapClient(String key) {
        this.key = key;
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
        JsonNode root = MAPPER.readTree(Http.getString(url.toString()));
        // v3 接口失败时 status="0" + info，v4 接口失败时 errcode!=0 + errmsg
        if ("0".equals(root.path("status").asText(null))) {
            throw new IllegalStateException("高德 API 错误: " + root.path("info").asText(""));
        }
        if (root.has("errcode") && root.path("errcode").asInt(-1) != 0) {
            throw new IllegalStateException("高德 API 错误: " + root.path("errmsg").asText(""));
        }
        return root;
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
