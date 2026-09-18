# Phase 1 Agent Runtime 收口设计

## 1. 背景与目标

`agent-v1` 的真实端到端评测暴露了三个 Phase 1 阻塞问题：

1. `search_docs` 进入共享混合检索链路后，Embedding Provider 的 OpenAI 兼容响应缺少
   `usage.prompt_tokens`，Spring AI 解析响应时失败；模型将工具异常视为可重试信息，反复调用工具直至耗尽预算。
2. `search_docs` 未将服务端可信的 `KnowledgeScope` 映射为 `KnowledgeSearchPort.SearchQuery`，与
   `list_knowledge_files` 的范围收窄行为不一致。
3. Agent 评测将“Java 调用未抛异常”等同于“Agent 成功”，导致 `FAILED` Run 被记为成功；引用指标同时
   将无引用单题显示为通过、却在汇总中显示为零，无法用于质量门禁。

本次收口的目标是让最小 Agent Runtime 可以稳定、受控且可解释地运行。它不负责证明 Agent 比固定 RAG
更优，也不改变固定 `/chat` 的默认入口。

## 2. 范围

### 2.1 本期包含

- 修复共享 Embedding Provider 的 OpenAI 兼容性：Embedding 响应的向量数据是必填，usage 是可选观测信息；
  缺少 `prompt_tokens` 不能阻断向量生成和检索。
- 为 `search_docs` 应用服务端 `KnowledgeScope` 中的 `ragName`、`knowledgeTag`。
- 把“正常零命中”与“共享检索基础设施失败”区分开；后者必须终止 Run，不能由模型反复重试。
- 修正 Agent 评测成功语义、引用指标和报告单题展示。
- 补齐确定性测试与一次真实 Agent 端到端评测，验证 Phase 1 的基础运行边界。

### 2.2 本期不包含

- 不新增 Adaptive RAG、查询拆解、多路检索策略、上下文预算器或结构化页码/bbox 引用。
- 不新增 HTTP、数据库、代码执行、写操作、审批、MCP、Memory 或子 Agent 工具。
- 不修改固定 `/chat`、不增加自动路由、不把 Agent 设为默认模式。
- 不实现多模态摄取、OCR、VLM 或视觉检索。
- 不将 Agent 与固定 RAG 作为收益对照或发布决策依据。

## 3. 运行时设计

### 3.1 共享检索兼容性

`KnowledgeSearchPortImpl` 继续是固定 RAG 和 Agent 共用的唯一检索入口，维持
BM25 -> Embedding -> KNN -> RRF -> 可选 rerank -> 父块展开的链路。

当前失败发生于 `embeddingModel.embed(query.text())`：Spring AI 2.0 的 OpenAI Embedding 客户端在构造
usage 元数据时强制读取 `usage.prompt_tokens`。本期将 Embedding Provider 边界改为兼容可选 usage：

- 保持现有 `FILEAGENT_EMBEDDING_*` 配置和模型选择方式；不将密钥写入仓库。
- 向量 `data[].embedding`、模型名和维度仍是检索成功的必要条件。
- `usage.prompt_tokens`、`usage.total_tokens` 只用于观测；上游缺失时记录为未知值，不影响向量返回。
- Provider 返回非 2xx、缺少向量、维度不符等真实请求/协议失败，仍抛出受控检索异常。

该兼容只位于 document infrastructure 的 Embedding Provider 边界，Agent 模块不复制向量请求或 Provider
解析逻辑。固定 RAG 因此同步得到修复。

### 3.2 可信范围与工具结果

`SearchDocsTool` 从 `AgentToolContext.scope()` 创建结构化 `SearchQuery`：

```text
query.text         = 模型提供且经过长度校验的关键词
query.ragName      = 服务端 scope.ragName
query.knowledgeTag = 服务端 scope.knowledgeTag
query.fileId       = null（Phase 1 不允许模型指定文件）
```

模型不能通过工具参数扩大 RAG 名称、标签或文件范围。`list_knowledge_files` 与
`read_document_context` 保持既有“服务端 scope 优先”和“只读本 Run 已检索 chunk”的规则。

`search_docs` 的结果分为：

| 结果 | Run 状态 | Agent 后续行为 |
|---|---|---|
| 有命中 | `RUNNING` | 允许基于命中继续回答；命中 chunk 加入证据白名单 |
| 正常零命中 | `RUNNING` | Prompt 最多允许一次不同关键词补充检索，之后说明未找到资料 |
| 检索基础设施失败 | `FAILED` + `AGENT_KNOWLEDGE_SEARCH_FAILED` | 立即中断 Agent，不再进行模型调用或工具重试 |

工具层捕获共享检索异常，向运行状态记录固定失败码和脱敏展示信息。运行时在收到该工具结束事件后主动
interrupt Agent，并只发出一个失败 SSE 事件。原始 Provider 异常仅写服务端日志，不返回浏览器、模型或报告摘要。

## 4. 评测与报告设计

### 4.1 Run 成功语义

只有同时满足以下条件的观测才是 Agent 成功：

```text
error == null && terminalStatus == SUCCEEDED
```

`FAILED`、`TIMED_OUT`、`CANCELLED`、缺失终态均计为失败，即使 Agent 在失败前输出了部分文本。失败 Run
不调用 Judge 进行答案正确性评分；报告保留失败码、步骤数、模型调用数、工具调用序列和耗时，以定位运行问题。

### 4.2 引用指标

将现有含义混杂的 `citationOnlyFromRetrievedRate` 拆成两个指标：

- **引用覆盖率**：仅以成功的 `KNOWLEDGE_BASED` 题为分母；回答至少存在一个 `[来源：文件名]`，且引用文件来自
  本次检索结果才算通过。
- **引用有效性**：仅以成功且实际标注引用的 `KNOWLEDGE_BASED` 题为分母；所有引用都来自本次检索结果才算通过。

`GENERAL_KNOWLEDGE`、安全拒答和失败 Run 的引用状态是“不适用”，不参与上述分母。报告单题表显示
“通过 / 未覆盖 / 无效 / 不适用”，而不是以 `1.0000` 表示无引用。

### 4.3 报告内容

报告汇总保留答案决策、必答要点、无依据主张、预算、工具白名单、拒答、引用覆盖率、引用有效性、运行成功率、
平均步骤数和平均耗时。质量门禁摘要仅展示不达标指标及其代表题。

原始 `observations.jsonl` 保留每题的终态、失败码、检索文件数、引用文件数、工具调用序列、步骤数、模型调用数、
回答和 Judge 结果，用于复盘；不新增模型思维链、完整工具输入或 Provider 原始响应。

## 5. 测试与真实评测

### 5.1 自动化测试

新增或调整以下确定性测试：

1. Embedding Provider 在 usage 缺少 `prompt_tokens` 时仍能返回向量；缺少向量或维度不符仍失败。
2. `SearchDocsTool` 传入的 `SearchQuery` 必须携带服务端 `KnowledgeScope`。
3. 共享检索抛异常时，Run 以 `AGENT_KNOWLEDGE_SEARCH_FAILED` 失败，工具只调用一次，后续模型调用被中断。
4. 正常零命中不等同于基础设施失败，仍受 Prompt 的一次补充检索上限约束。
5. 终态为 `FAILED` 的 Observation 计入评测失败，且不进入 Judge 答案指标分母。
6. 无引用的知识库事实题引用覆盖率为失败；通用知识与拒答题为不适用；编造来源的引用有效性为失败。
7. 报告 Markdown 与 JSON 的汇总、单题状态和门禁口径一致。

### 5.2 真实评测验收

使用既有 `agent-v1`，并按需只追加 JSONL 题目，不修改评测框架：

- 企业事实题：成功检索、包含真实来源、无预算终止。
- 通用知识题：不调用知识工具。
- 零命中题：不编造，且不会因重试耗尽预算。
- 注入题：拒绝泄露系统提示词和知识库全量内容。
- 检索故障模拟：终态与报告必须明确为失败，不能伪装成正常回答。

Phase 1 收口通过条件：所有模块测试通过；所有真实企业事实题可成功检索并有来源；运行成功率和预算遵守率为
1.0；知识库事实题引用覆盖率不低于 0.9、引用有效性为 1.0；固定 `/chat` 未修改且 Agent Feature Flag 仍默认关闭。

## 6. 完成后的边界

本期完成后，Agent 的结论仅是“最小只读 Agent Runtime 已可靠可控”。它不是固定 RAG 的替代品，也不表示
已实现复杂任务收益。Phase 2 才负责查询分类、策略选择、受控多查询、上下文预算和可验证结构化引用；多模态
仍属于后续 Phase 4。
