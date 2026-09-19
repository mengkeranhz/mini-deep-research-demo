package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.Skill;
import org.example.SkillRegistry;
import org.example.ToolDef;
import org.example.ToolRegistry;
import org.example.ToolRegistry.AgentTool;

import java.util.List;
import java.util.Map;

/** load_skill：按名称加载技能的完整工作流程（系统提示词只带 name/description，此处取全文）。 */
public class LoadSkillTool implements AgentTool {

    @Override
    public String name() {
        return "load_skill";
    }

    @Override
    public ToolDef definition() {
        return new ToolDef(name(), "加载技能的完整工作流程。用户述求匹配系统提示词「可用技能」列表中的某个技能时，"
                        + "必须先调用本工具获取该技能的详细执行流程，再严格按流程执行。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "skill", Map.of("type", "string",
                                        "description", "技能名称，如「travel_route_planning」")),
                        "required", List.of("skill")));
    }

    @Override
    public String execute(JsonNode input) {
        String name = ToolRegistry.str(input, "skill");
        Skill skill = SkillRegistry.find(name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知技能: " + name + "，可用技能: " + String.join("、", SkillRegistry.names())));
        StringBuilder out = new StringBuilder(skill.instructions());
        if (!skill.files().isEmpty()) {
            out.append("\n\n本技能附带的文件（不必全部读取，需要时用 read_file 按需读取，路径相对根目录）：\n");
            for (String f : skill.files()) {
                out.append("- ").append(skill.dir()).append('/').append(f).append('\n');
            }
        }
        out.append("\n技能 ").append(skill.name()).append(" 的工作流程已加载，请严格按上述流程执行本次任务；")
                .append("若尚未规划任务清单，先调用 analyze_query 按本流程拆解任务再开始执行。");
        return out.toString();
    }
}
