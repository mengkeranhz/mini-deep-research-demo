# mini-deep-research-demo

极简 Deep Research 演示：Java 21 实现的 Agent 循环。LLM 通过工具自主搜索、阅读网页、执行代码，多轮迭代后输出研究结论。

## 特性

- **Agent 循环**：最多 60 轮；无工具调用即结束；输入超 6 万 token 自动压缩上下文
- **Anthropic 协议**：支持流式输出与 thinking 回传，可替换任意兼容的 base-url / 模型
- **内置工具**：网页搜索（Tavily）、网页/PDF 抓取解析、读文件、执行代码、高德地点/周边/路线查询

## 快速开始

```bash
export LLM_API_KEY=...      # LLM 密钥
export TAVILY_API_KEY=...   # 网页搜索
export AMAP_API_KEY=...     # 高德 LBS

mvn compile exec:java -Dexec.args="你的研究问题"
```

不带参数则进入交互式输入。

## 配置

`src/main/resources/config.yaml`（优先读工作目录同名文件），支持 `${ENV_VAR}` 环境变量占位符：

```yaml
llm:
  provider: anthropic
  base-url: https://open.bigmodel.cn/api/anthropic
  model: glm-5.3
```
