package org.example;

import java.util.List;

/** 一条消息：role 为四种角色之一，blocks 为具体内容块。 */
public record Msg(Role role, List<Block> blocks) {

    /** 消息角色：system 系统人格 / user 用户输入 / assistant 模型输出 / tool 工具结果。 */
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public static Msg system(String text) {
        return new Msg(Role.SYSTEM, List.of(new Block.Text(text)));
    }

    public static Msg user(String text) {
        return new Msg(Role.USER, List.of(new Block.Text(text)));
    }

    public static Msg assistant(List<Block> blocks) {
        return new Msg(Role.ASSISTANT, List.copyOf(blocks));
    }

    /** 单条工具执行结果消息。 */
    public static Msg tool(Block.ToolResult result) {
        return new Msg(Role.TOOL, List.of(result));
    }
}
