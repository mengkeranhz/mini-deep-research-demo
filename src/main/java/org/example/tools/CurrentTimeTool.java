package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** current_time：返回当前日期时间（含星期、时区与 Unix 时间戳）。 */
public class CurrentTimeTool implements AgentTool {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final List<String> WEEKDAYS = List.of("一", "二", "三", "四", "五", "六", "日");

    @Override
    public String name() {
        return "current_time";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "返回当前日期时间，含星期、时区与 Unix 时间戳。"
                        + "每轮上下文末尾已自动注入本机时区的当前时间，无需为此调用本工具；"
                        + "仅在需要换算其他时区或 Unix 时间戳时调用，其他时区传 timezone 参数。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "timezone", Map.of("type", "string",
                                        "description", "IANA 时区名，如 Asia/Shanghai、America/New_York；缺省用系统时区")),
                        "required", List.of()));
    }

    /** 本机时区当前时间的一行文本，供 Agent 每轮尾注注入——时效判断（今天/本周/营业中/节假日）
     * 免调工具即有依据；工具本身保留给时区换算与时间戳场景。 */
    public static String nowText() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        return "当前时间: " + format(now) + " 时区: " + now.getZone().getId();
    }

    /** 日期时间 + 星期的统一格式。 */
    private static String format(ZonedDateTime now) {
        return now.format(DATE_TIME) + "（星期" + WEEKDAYS.get(now.getDayOfWeek().getValue() - 1) + "）";
    }

    @Override
    public String execute(JsonNode input) throws Exception {
        String tzName = ToolRegistry.optStr(input, "timezone");
        ZoneId zone = ZoneId.systemDefault();
        if (tzName != null && !tzName.isBlank()) {
            try {
                zone = ZoneId.of(tzName.trim());
            } catch (Exception e) {
                return "无法识别的时区: " + tzName + "，请使用 IANA 时区名，如 Asia/Shanghai";
            }
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        return "当前时间: " + format(now) + " 时区: " + now.getZone().getId()
                + " Unix时间戳: " + now.toEpochSecond() + "s";
    }
}
