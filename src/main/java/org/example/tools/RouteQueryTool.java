package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** route_query：高德路线（驾车/步行/骑行 v4/公交）；打车 = 驾车路线 + 本地粗略估价。 */
public class RouteQueryTool implements AgentTool {

    private final AmapClient amap;

    public RouteQueryTool(AmapClient amap) {
        this.amap = amap;
    }

    @Override
    public String name() {
        return "route_query";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "查询两地路线。起终点均为经纬度坐标「经度,纬度」，先用 search_place 获取。"
                        + "mode: driving 驾车 / walking 步行 / bicycling 骑行 / transit 公交地铁（需提供 city）"
                        + "/ taxi 打车（返回驾车路线 + 粗略估价）。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "time", Map.of("type", "string", "description", "出发时间，如 9:00（公交模式使用）"),
                                "origin", Map.of("type", "string", "description", "起点坐标「经度,纬度」"),
                                "destination", Map.of("type", "string", "description", "终点坐标「经度,纬度」"),
                                "mode", Map.of("type", "string",
                                        "enum", List.of("driving", "walking", "bicycling", "transit", "taxi"),
                                        "description", "出行方式"),
                                "city", Map.of("type", "string", "description", "公交模式必填，城市名或城市编码，如「杭州」"),
                                "date", Map.of("type", "string", "description", "出发日期（公交模式使用）")),
                        "required", List.of("origin", "destination", "mode")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String origin = ToolRegistry.str(input, "origin");
        String destination = ToolRegistry.str(input, "destination");
        String mode = ToolRegistry.str(input, "mode");
        return switch (mode) {
            case "driving" -> driving(origin, destination, false);
            case "taxi" -> driving(origin, destination, true);
            case "walking" -> simpleRoute("/v3/direction/walking", origin, destination);
            case "bicycling" -> simpleRoute("/v4/direction/bicycling", origin, destination);
            case "transit" -> transit(origin, destination,
                    ToolRegistry.optStr(input, "time"), ToolRegistry.optStr(input, "date"),
                    ToolRegistry.optStr(input, "city"));
            default -> throw new IllegalArgumentException(
                    "不支持的出行方式: " + mode + "（可选 driving/walking/bicycling/transit/taxi）");
        };
    }

    /** v3 驾车；taxi=true 时追加本地粗略估价（高德无公开打车估价 API）。 */
    private String driving(String origin, String destination, boolean taxi) throws Exception {
        JsonNode path = amap.get("/v3/direction/driving", Map.of(
                        "origin", origin, "destination", destination, "strategy", "0"))
                .path("route").path("paths").path(0);
        if (path.isMissingNode()) return "未查询到驾车路线";
        long distance = path.path("distance").asLong(0);
        StringBuilder sb = new StringBuilder()
                .append("距离 ").append(km(distance)).append(" 公里，驾车约 ")
                .append(min(path.path("duration").asLong(0))).append("。\n")
                .append(steps(path.path("steps")));
        if (taxi) {
            double fare = 11 + Math.max(0, distance / 1000.0 - 3) * 2.5; // 起步价 11 元含 3 公里
            sb.append("\n打车粗略估价: 约 ").append(String.format("%.0f", fare))
                    .append(" 元（起步价11元/3公里 + 2.5元/公里，仅供参考）");
        }
        return sb.toString();
    }

    /** v3 walking / v4 bicycling，返回结构同构：paths[0].{distance,duration,steps[].instruction}。 */
    private String simpleRoute(String api, String origin, String destination) throws Exception {
        JsonNode path = api.startsWith("/v4")
                ? amap.get(api, Map.of("origin", origin, "destination", destination))
                        .path("data").path("paths").path(0)
                : amap.get(api, Map.of("origin", origin, "destination", destination))
                        .path("route").path("paths").path(0);
        if (path.isMissingNode()) return "未查询到路线";
        return "距离 " + km(path.path("distance").asLong(0)) + " 公里，约 "
                + min(path.path("duration").asLong(0)) + "。\n" + steps(path.path("steps"));
    }

    /** v3 公交换乘，结果映射为 segments 结构：步行段 + 公共交通段。 */
    private String transit(String origin, String destination, String time, String date, String city)
            throws Exception {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("公交换乘(transit)必须提供 city 参数（城市名或城市编码，如「杭州」）");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("origin", origin);
        params.put("destination", destination);
        params.put("city", city);
        params.put("strategy", "0"); // 最快捷
        if (time != null) params.put("time", time);
        if (date != null) params.put("date", date);
        JsonNode transits = amap.get("/v3/direction/transit/integrated", params)
                .path("route").path("transits");
        if (!transits.isArray() || transits.isEmpty()) return "未查询到公交方案";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(transits.size(), 3); i++) {
            JsonNode t = transits.get(i);
            sb.append("方案").append(i + 1).append(": 约 ").append(min(t.path("duration").asLong(0)))
                    .append(" | 步行共 ").append(km(t.path("walking_distance").asLong(0))).append(" 公里")
                    .append(" | 票价 ").append(AmapClient.text(t.path("cost"))).append('\n');
            for (JsonNode seg : t.path("segments")) {
                JsonNode walking = seg.path("walking");
                if (!walking.isMissingNode() && walking.path("distance").asLong(0) > 0) {
                    sb.append("  - 步行 ").append(walking.path("distance").asLong()).append(" 米（约 ")
                            .append(min(walking.path("duration").asLong(0))).append("）\n");
                }
                JsonNode busline = seg.path("bus").path("buslines").path(0);
                if (!busline.isMissingNode()) {
                    sb.append("  - 在「")
                            .append(AmapClient.text(busline.path("departure_stop").path("name")))
                            .append("」乘坐「").append(AmapClient.text(busline.path("name")))
                            .append("」（").append(AmapClient.text(busline.path("type")))
                            .append("）到「").append(AmapClient.text(busline.path("arrival_stop").path("name")))
                            .append("」");
                    String viaNum = AmapClient.text(busline.path("via_num"));
                    if (!viaNum.isEmpty()) sb.append("，途经 ").append(viaNum).append(" 站");
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String steps(JsonNode steps) {
        if (!steps.isArray() || steps.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(steps.size(), 12);
        for (int i = 0; i < shown; i++) {
            sb.append(i + 1).append(". ")
                    .append(AmapClient.text(steps.get(i).path("instruction")).replaceAll("\\s+", " "))
                    .append('\n');
        }
        if (steps.size() > shown) sb.append("…（共 ").append(steps.size()).append(" 步，已省略）\n");
        return sb.toString();
    }

    private static String km(long meters) {
        return String.format("%.1f", meters / 1000.0);
    }

    private static String min(long seconds) {
        return Math.round(seconds / 60.0) + " 分钟";
    }
}
