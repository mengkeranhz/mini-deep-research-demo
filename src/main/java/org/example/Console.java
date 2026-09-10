package org.example;

/**
 * 控制台输出着色工具。
 *
 * <p>全项目统一颜色约定：
 * <ul>
 *   <li>红（31）—— 仅错误，且只允许出现在 stderr</li>
 *   <li>黄（33）—— 仅警告</li>
 *   <li>青（36）—— LLM 思考内容</li>
 *   <li>蓝（34）—— 工具调用</li>
 *   <li>加粗 —— 章节标题（轮次 / 最终结论）</li>
 *   <li>默认 —— 普通正文、工具结果、摘要</li>
 * </ul>
 *
 * <p>设置 NO_COLOR 环境变量、或 TERM=dumb 时自动禁用着色。
 */
public final class Console {
    private Console() {}

    private static final boolean ENABLED = colorEnabled();

    private static final String RESET = "[0m";
    private static final String RED = "[31m";
    private static final String YELLOW = "[33m";
    private static final String CYAN = "[36m";
    private static final String BLUE = "[34m";
    private static final String BOLD = "[1m";

    private static boolean colorEnabled() {
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        return !"dumb".equals(System.getenv("TERM"));
    }

    private static String paint(String code, String text) {
        return ENABLED ? code + text + RESET : text;
    }

    /** 错误：红色，全项目唯一允许用红的场景。 */
    public static String error(String text) {
        return paint(RED, text);
    }

    /** 警告：黄色，全项目唯一允许用黄的场景。 */
    public static String warn(String text) {
        return paint(YELLOW, text);
    }

    /** LLM 思考：青色。 */
    public static String thinking(String text) {
        return paint(CYAN, text);
    }

    /** 工具调用：蓝色。 */
    public static String tool(String text) {
        return paint(BLUE, text);
    }

    /** 章节标题：加粗。 */
    public static String header(String text) {
        return paint(BOLD, text);
    }
}
