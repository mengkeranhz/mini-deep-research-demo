package org.example;

import java.io.PrintStream;
import java.util.regex.Pattern;

/**
 * Agent 线程输出路由：父 Agent 默认写控制台；子 Agent 在自身执行线程绑定独立 Markdown 日志流。
 * LLM 流式增量、工具警告与 Agent 轮次日志都经过这里，避免并发子 Agent 在控制台交错。
 */
public final class AgentOutput {
    private static final ThreadLocal<PrintStream> BOUND = new ThreadLocal<>();
    private static final Pattern ANSI = Pattern.compile("\033\\[[0-9;]*m");

    private AgentOutput() {}

    public static void bind(PrintStream out) {
        BOUND.set(out);
    }

    public static void unbind() {
        BOUND.remove();
    }

    public static void print(String text) {
        PrintStream out = BOUND.get();
        if (out == null) {
            System.out.print(text);
        } else {
            out.print(ANSI.matcher(text).replaceAll(""));
        }
    }

    public static void println(String text) {
        PrintStream out = BOUND.get();
        if (out == null) {
            System.out.println(text);
        } else {
            out.println(ANSI.matcher(text).replaceAll(""));
        }
    }

    public static void println() {
        PrintStream out = BOUND.get();
        if (out == null) {
            System.out.println();
        } else {
            out.println();
        }
    }
}
