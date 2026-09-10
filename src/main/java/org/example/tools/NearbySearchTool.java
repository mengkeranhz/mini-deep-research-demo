package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.util.List;
import java.util.Map;

/** search_nearby：高德周边 POI 查询（v3/place/around）。 */
public class NearbySearchTool implements AgentTool {

    private final AmapClient amap;

    public NearbySearchTool(AmapClient amap) {
        this.amap = amap;
    }

    @Override
    public String name() {
        return "search_nearby";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "查询某地点周边的 POI（如「地铁站」「咖啡店」），返回名称/地址/经纬度列表。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "要查找的目标类型或名称，如「地铁站」"),
                                "location", Map.of("type", "string",
                                        "description", "中心点经纬度，格式「经度,纬度」（可先用 search_place 获取）"),
                                "keyword", Map.of("type", "string", "description", "附加关键词，与 name 联合检索，可省略"),
                                "radius", Map.of("type", "integer", "description", "检索半径（米），默认 1000")),
                        "required", List.of("name", "location")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String name = ToolRegistry.str(input, "name");
        String location = ToolRegistry.str(input, "location");
        String keyword = ToolRegistry.optStr(input, "keyword");
        int radius = ToolRegistry.optInt(input, "radius", 1000);

        JsonNode pois = amap.get("/v3/place/around", Map.of(
                        "keywords", keyword == null ? name : name + "|" + keyword,
                        "location", location,
                        "radius", String.valueOf(radius),
                        "offset", "5", "page", "1"))
                .path("pois");
        if (!pois.isArray() || pois.isEmpty()) {
            return "在 " + location + " 周边未找到: " + name;
        }
        StringBuilder sb = new StringBuilder("周边 ").append(pois.size()).append(" 个结果:\n");
        for (int i = 0; i < pois.size(); i++) {
            sb.append(i + 1).append(". ").append(AmapClient.poiLine(pois.get(i))).append('\n');
        }
        return sb.toString();
    }
}
