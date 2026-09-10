package org.example;

import java.util.List;

/** 一次 LLM 调用的结果：内容块（思考 / 文本 / 工具调用）+ token 用量（用于上下文压缩判断）。 */
public record LlmResponse(List<Block> blocks, int inputTokens, int outputTokens) {

    /** 拼接全部文本块，作为本轮结论文本。 */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (b instanceof Block.Text t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
