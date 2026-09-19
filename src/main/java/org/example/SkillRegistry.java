package org.example;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 技能注册表：扫描根目录下 skills/ 中的标准技能目录（skills/&lt;name&gt;/SKILL.md，
 * 可选 scripts/、reference/ 等附属文件）。新技能只需新建目录，无需改代码——
 * name/description 自动进入系统提示词尾部，正文与附属文件经 load_skill 按需加载。
 * 根目录解析与 read_file 一致（Config.rootDir），保证技能内文件可被 read_file 读取。
 */
public final class SkillRegistry {

    /** 技能根目录名（根目录 = Config.rootDir，与 read_file 的路径解析一致）。 */
    private static final String SKILLS_DIR = "skills";

    private static final List<Skill> SKILLS = scan();

    /** 系统提示词尾部的技能清单段（只含 name/description）；无技能时返回空串。 */
    public static String promptSection() {
        if (SKILLS.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("""

                # 可用技能（Skills）
                以下技能针对特定类型述求提供经过设计的执行流程。用户述求匹配某个技能的适用范围时，\
                必须先调用 load_skill 加载该技能的完整工作流程，并严格按流程执行：
                """);
        for (Skill s : SKILLS) {
            sb.append("- ").append(s.name()).append("：").append(s.description()).append('\n');
        }
        return sb.toString();
    }

    /** 按名称查找技能（load_skill 用）。 */
    public static Optional<Skill> find(String name) {
        return SKILLS.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    /** 全部技能名（未知名称时的提示用）。 */
    public static List<String> names() {
        return SKILLS.stream().map(Skill::name).toList();
    }

    // ---- 扫描与解析 ----

    /** 扫描 skills/ 下的技能目录；目录不存在时得到空列表（技能体系整体可选）。 */
    private static List<Skill> scan() {
        Path root = Config.rootDir(null).resolve(SKILLS_DIR);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                    .map(SkillRegistry::load)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(Skill::name))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描技能目录 " + root + " 失败: " + e.getMessage(), e);
        }
    }

    /** 加载单个技能目录：SKILL.md 必需；无 SKILL.md 的目录跳过（null）。 */
    private static Skill load(Path dir) {
        Path md = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(md)) {
            return null;
        }
        String text;
        try {
            text = Files.readString(md, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + md + " 失败: " + e.getMessage(), e);
        }
        String[] front = splitFrontmatter(md, text);
        @SuppressWarnings("unchecked")
        Map<String, Object> fm = new Yaml().load(front[0]);
        String name = fm == null || fm.get("name") == null ? null : String.valueOf(fm.get("name")).strip();
        if (name == null || name.isEmpty()) {
            throw new IllegalStateException(md + " 的 frontmatter 缺少 name");
        }
        String description = fm.get("description") == null ? "" : String.valueOf(fm.get("description")).strip();
        return new Skill(name, description, front[1].strip(),
                SKILLS_DIR + "/" + dir.getFileName(), bundledFiles(dir));
    }

    /** 拆分 frontmatter 与正文：[0]=frontmatter 文本，[1]=正文。 */
    private static String[] splitFrontmatter(Path md, String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[0].strip().equals("---")) {
            throw new IllegalStateException(md + " 缺少 frontmatter（首行应为 ---，随后 name/description，再以 --- 结束）");
        }
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals("---")) {
                return new String[]{String.join("\n", List.of(lines).subList(1, i)),
                        String.join("\n", List.of(lines).subList(i + 1, lines.length))};
            }
        }
        throw new IllegalStateException(md + " 的 frontmatter 未闭合（缺少结束的 ---）");
    }

    /** 技能目录内 SKILL.md 之外的附属文件（相对技能目录，排序稳定），供按需读取。 */
    private static List<String> bundledFiles(Path dir) {
        try (Stream<Path> all = Files.walk(dir)) {
            List<String> files = new ArrayList<>();
            all.filter(Files::isRegularFile)
                    .map(dir::relativize)
                    .map(Path::toString)
                    .filter(p -> !"SKILL.md".equals(p))
                    .sorted()
                    .forEach(files::add);
            return files;
        } catch (IOException e) {
            throw new IllegalStateException("遍历技能目录 " + dir + " 失败: " + e.getMessage(), e);
        }
    }

    private SkillRegistry() {
    }
}
