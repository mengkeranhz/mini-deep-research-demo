# mini-deep-research-demo

极简 Deep Research 演示：Java 21 实现的 Agent 循环。LLM 通过工具自主规划、检索、核验、入账，多轮迭代后经「终答闸门」校验输出研究结论。

## 核心机制

### Agent 循环（`Agent`）

每轮执行：**轮次上限（90）→ 注入任务进度与事实账本快照 → LLM 调用（人格 + 工具元信息 + 对话与 thinking 回传）→ 终答闸门或依次执行工具 → 超阈值压缩上下文**。

- **外部状态每轮注入**：任务清单（`TaskStore`）与事实账本（`FactsStore`）存放在 LLM 上下文之外，每轮重算紧凑快照作为系统块注入，不留旧快照、天然无陈旧堆积
- **上下文压缩**：上次响应 inputTokens 超 60,000 时，用无工具单次调用把对话稿总结为摘要，重建上下文 = 原始述求 + 摘要 + 事实账本（声明为最终权威）+ 旧草稿（仅供结构参考）+ 继续指令——账本跨压缩保留，防止被陈旧草稿锚定

### 任务规划与覆盖目标

- `analyze_query` 把述求解析为任务清单与**数据覆盖目标**（`required_facts`：维度 × 时期逐格枚举，成为最终答案的覆盖度检查表）；`update_task` 标记进度；发现新信息可再次 `analyze_query` 重新规划（首次规划固化为校验基线，重规划不改变判断基准）

### 事实账本

- 每检索到一条关键数据（数值、时期、口径、来源）立即 `record_facts` 入账，状态分 `found`（已核验）/ `proxy`（代理折算）/ `not_found`（缺口声明，须注明已尝试的检索方式）
- 不入账的数据等于没查到：上下文压缩与最终校验都以账本为准，结论是账本的结算，不是对话记忆的复述

### 终答硬闸门

纯文本收尾与 `final_answer` 提交共用同一道闸门，未通过则缺陷清单回传模型，补齐后须重新提交**完整**答案：

1. **约束校验**：独立校验器对照固化基线与账本逐条核对草稿——数据矛盾、遗漏入账事实、假称「未找到」均拦截
2. **覆盖度闸门**：覆盖目标还有格子未入账，或 `not_found` 未写明检索方式，不放行——防止「5/5 任务完成」的假象掩盖数据缺口

## 内置工具

`org.example.tools` 包下的实现类自动扫描注册，新增工具只需实现 `AgentTool` 接口，无需改动注册逻辑。

| 工具 | 说明 |
| --- | --- |
| `analyze_query` | 拆解述求：硬约束 / 信息缺口 / 总体计划 / 带依赖任务清单 / 覆盖目标（JSON） |
| `update_task` | 更新任务状态（in_progress / done），可附关键结果备注 |
| `locate_sources` | 检索前定位权威来源域名：先发现式搜索观测真实结果，再判定权威域名，供 `web_search` 限定 |
| `web_search` | Tavily 联网搜索，支持 `domains` 限定到权威域名（含子域名） |
| `fetch_url` | 抓取 URL 保存到本地：HTML 转 Markdown（标题/段落/链接），PDF/二进制原样保存 |
| `read_file` | 按行读取本地文件，支持关键词命中段落检索 |
| `record_facts` | 关键数据写入事实账本，回执含覆盖度 |
| `run_code` | 本地执行 Python 3 脚本（60 秒超时），返回 stdout+stderr |
| `current_time` | 当前日期时间（含时区参数） |
| `search_place` / `nearby_search` / `route_query` | 高德 LBS：地点查询 / 周边 POI / 路线（驾车/步行/骑行/公交/出租车） |
| `final_answer` | 提交最终答案，过闸门后任务结束 |

## 快速开始

依赖：JDK 21、Maven；`run_code` 需本地 `python3`。

```bash
export LLM_API_KEY=...      # LLM 密钥
export TAVILY_API_KEY=...   # 网页搜索
export AMAP_API_KEY=...     # 高德 LBS

mvn compile exec:java -Dexec.args="你的研究问题"
```

不带参数则进入交互式输入。运行时控制台实时输出轮次进度、思考（青色）、工具调用与结果预览，最终结论单独打印。

## 配置

`config.yaml` 优先读工作目录同名文件，没有则读项目内 classpath 版本；支持 `${ENV_VAR}` 环境变量占位符。

```yaml
llm:
  provider: anthropic            # anthropic / openai 两种协议
  base-url: https://open.bigmodel.cn/api/anthropic
  model: glm-5.3
  api-key: "${LLM_API_KEY}"
  max-tokens: 16384
  temperature: 0
  streaming: true                # 流式输出与 thinking 增量实时打印

storage:
  root-dir: ""                   # fetch_url 保存 / read_file 读取的根目录；
                                 # 留空回退：工作目录 → 项目目录

tools:
  web-search:
    tavily-api-key: "${TAVILY_API_KEY}"
    max-results: 30              # 默认返回结果数
  lbs-service:
    amap-api-key: "${AMAP_API_KEY}"
    min-request-interval-ms: 350 # 相邻高德请求最小间隔，防 QPS 超限
  read-file:
    max-results: 30              # 关键词检索默认返回段落数
```

Anthropic 协议实现支持流式 SSE 与 thinking 块回传，可替换任意兼容的 base-url / 模型。

## 项目结构

```
src/main/java/org/example/
├── Main.java            # 入口：读入述求，运行 Agent，打印结论
├── Agent.java           # Agent 循环 + 终答闸门 + 上下文压缩
├── SystemPrompt.java    # 人格提示词（目标 / 准则 / 输出要求 / 边界）
├── TaskStore.java       # 任务清单与校验基线（外部状态）
├── FactsStore.java      # 事实账本与覆盖目标（外部状态）
├── ToolRegistry.java    # 工具自动扫描、注册、分发
├── LlmClient.java       # LLM 抽象接口（anthropic / openai）
├── AnthropicClient.java # Anthropic 协议：流式 SSE、thinking、tool_use
├── OpenAiClient.java    # OpenAI 协议实现
└── tools/               # 全部内置工具（自动扫描注册）
```
