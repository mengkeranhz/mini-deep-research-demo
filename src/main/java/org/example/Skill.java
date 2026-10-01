package org.example;

import java.util.List;

/**
 * 技能：skills/&lt;dir&gt;/SKILL.md 加载而来（标准技能目录布局）。
 * name/description 取自 frontmatter，进入系统提示词尾部（供模型判断是否匹配）；
 * instructions 为 SKILL.md 正文（含自动注入的数据结构契约）；dir/files 描述技能目录
 * （路径相对 Config.rootDir，read_file 可直接读取），附属文件按需读取实现渐进式披露。
 * schemas 是 schemas/*.json 的领域数据契约，基座只做通用加载与引用识别。
 */
public record Skill(String name, String description, String instructions, String dir,
                    List<String> files, List<SkillSchema> schemas) {
}
