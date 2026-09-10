package org.example;

import java.util.List;

/**
 * LLM 客户端中立接口；按配置 provider 创建 anthropic / openai 协议实现。
 * messages 含四种角色（system/user/assistant/tool），协议差异由各实现负责映射。
 * 流式时文本增量实时打到 stdout、思考增量打到 stderr；非流式由调用方统一打印。
 */
public interface LlmClient {

    /** 主调用：工具元信息 + 全部消息（system 人格、对话、历史工具结果与思考块回传）。 */
    LlmResponse call(List<ToolDef> tools, List<Msg> messages);

    /** 上下文压缩用：无工具单次调用，把对话稿总结为进度摘要。 */
    default String summarize(String transcript) {
        LlmResponse resp = call(List.of(), List.of(
                Msg.system("你是对话压缩助手。把研究过程对话稿压缩为要点摘要，保留：任务目标、已完成的步骤、"
                        + "每一步得到的核心数据与结论、信息来源链接、尚未完成的事项。直接输出摘要本身。"),
                Msg.user("对话稿如下：\n\n" + transcript)));
        return resp.text();
    }

    static LlmClient create(Config.Llm cfg) {
        if (cfg.apiKey().isBlank()) {
            throw new IllegalStateException("config.yaml 中 llm.api-key 为空（检查对应环境变量是否设置）");
        }
        return switch (cfg.provider()) {
            case "anthropic" -> new AnthropicClient(cfg);
            case "openai" -> new OpenAiClient(cfg);
            default -> throw new IllegalStateException(
                    "不支持的 provider: " + cfg.provider() + "（可选 anthropic / openai）");
        };
    }
}
