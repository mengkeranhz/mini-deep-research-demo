# mini-deep-research-demo

极简 Deep Research 演示：Java 21 实现的两层 Agent 架构。父编排器负责规划、派发与综合校验，每个任务由独立子 Agent Loop 检索取数，多轮迭代后输出研究结论。

## 架构

- **父 Agent（编排器）**：`analyze_query` 拆解计划与任务清单 → 逐个 `execute_task` 派发 → 综合事实账本与子代理结果输出最终答案；答案对照述求与账本做 LLM 校验，未通过则自动重新规划（缺陷注入、进度继承），连续 3 次未通过 best-effort 返回
- **子 Agent Loop**：每次 `execute_task` 新建、全新上下文，只执行单个自包含任务（搜索、抓取、计算、入账）；某轮不再调用工具即完成，结果回传父上下文；看不到父对话与计划清单
- **事实账本**：关键数据（含 run_code 计算结果）随查随入账，父子共享同一账本，跨上下文压缩与重规划保留；最终答案以账本为准
- **上下文压缩**：输入超 6 万 token 自动压缩，重建时显式携带原始述求、进度摘要、任务快照、账本快照与草稿答案

## 特性

- 父循环最多 120 轮、子循环 40 轮；无工具调用即结束
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
