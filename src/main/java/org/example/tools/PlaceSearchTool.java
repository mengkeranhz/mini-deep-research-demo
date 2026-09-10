package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.util.List;
import java.util.Map;

/** search_place：高德关键词 POI 查询（v3/place/text）。 */
public class PlaceSearchTool implements AgentTool {

    private final AmapClient amap;

    public PlaceSearchTool(AmapClient amap) {
        this.amap = amap;
    }

    @Override
    public String name() {
        return "search_place";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "按名称查询地点（POI），返回名称/地址/经纬度列表。"
                        + "查询到的经纬度可用于 search_nearby 或 route_query。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "地点名称关键词，如「良渚文化村」")),
                        "required", List.of("name")));
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String name = ToolRegistry.str(input, "name");
        JsonNode pois = amap.get("/v3/place/text", Map.of(
                        "keywords", name, "offset", "5", "page", "1"))
                .path("pois");
        if (!pois.isArray() || pois.isEmpty()) {
            return "未找到地点: " + name;
        }
        StringBuilder sb = new StringBuilder("找到 ").append(pois.size()).append(" 个地点:\n");
        for (int i = 0; i < pois.size(); i++) {
            sb.append(i + 1).append(". ").append(AmapClient.poiLine(pois.get(i))).append('\n');
        }
        return sb.toString();
    }
}
