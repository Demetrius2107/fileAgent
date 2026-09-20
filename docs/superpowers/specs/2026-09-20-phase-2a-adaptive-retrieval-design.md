# Phase 2A Adaptive Retrieval Core 设计

## 1. 背景与目标

Phase 1 已完成受控的 AgentScope ReAct Runtime、只读 `search_docs`、
`list_knowledge_files`、`read_document_context`、运行预算、取消、失败终态和专项评测。
当前 `search_docs` 仍只接收一个查询词，Document 域始终使用全局固定的 BM25、KNN、
RRF、rerank、TopK 和父块展开参数。复杂问题能否正确拆解、查询多少次以及如何选择检索策略，
主要依赖模型临场发挥，无法稳定评测和治理。

Phase 2A 的目标是增加一个受控的 Adaptive Retrieval Core：Agent 负责选择问题类型和生成有限子查询，
服务端根据问题类型选择固定策略档位并执行检索。模型不得直接控制底层检索参数、索引、身份或知识范围。

本期不改变固定 `/api/sessions/{id}/chat` 的默认 RAG 行为，不提前实现 Phase 2B 的完整上下文预算器，
也不提前实现 Phase 2C 的结构化引用和索引迁移。

## 2. 核心决策

采用“结构化检索工具 + 服务端策略档位”，不增加独立 Planner 模型调用：

```text
用户问题
  -> Agent 判断是否需要检索
  -> search_docs(queryType, queries)
  -> AdaptiveRetrievalPolicy
  -> 服务端生成 SearchOptions
  -> KnowledgeSearchPort.searchDetailed
  -> BM25 / KNN / 加权 RRF / 可选 rerank / 可选父块展开
  -> 多查询结果去重合并
  -> Agent 基于证据回答
```

选择该方案的原因：

- 复用当前 ReAct Runtime，不为每次请求增加一次 Planner 模型调用和新的失败点。
- 模型只表达意图，检索参数仍由服务端可信代码控制。
- 查询类型、子查询、策略档位和降级行为都可以记录、测试和评测。
- `KnowledgeSearchPort` 仍是 Chat 与 Agent 共用的唯一检索边界，不复制 Elasticsearch 实现。

不采用以下方案：

- 不使用独立 Planner + Executor；当前阶段额外延迟、费用和运行状态复杂度大于收益。
- 不采用只改 Prompt 的方案；它不能稳定验证问题分类、查询数量和实际检索参数。

## 3. 模块边界

### 3.1 `fileagent-api`

增加跨域稳定枚举 `RetrievalQueryType`：

```text
NONE
SINGLE_HOP
MULTI_HOP
COMPARISON
AGGREGATION
TIME_SENSITIVE
```

扩展 `KnowledgeSearchPort.SearchQuery`，在现有 `text`、`ragName`、`knowledgeTag`、`fileId`
之后增加可选 `SearchOptions`。保留四参数构造方法；未提供 options 时表示使用 Document 域当前全局配置。

`SearchOptions` 只承载服务端生成的检索参数：

```text
strategyId
bm25TopK
knnTopK
knnCandidates
bm25Weight
knnWeight
finalTopK
rerankEnabled
parentExpansionEnabled
```

增加 `searchDetailed(SearchQuery)` 和 `SearchResult`。`SearchResult` 包含有界的融合候选、最终命中、
实际应用参数、是否实际执行 rerank 以及受控降级码。原有 `search(...)` 继续只返回最终命中，
默认实现允许旧适配器把同一结果同时视为候选和最终命中。

### 3.2 `fileagent-agent`

`search_docs` 在 Adaptive 模式下接收：

```json
{
  "queryType": "COMPARISON",
  "queries": [
    "A 制度的年假规则",
    "B 制度的年假规则"
  ]
}
```

工具不接收 TopK、权重、rerank 开关、父块开关、fileId、索引名或 Elasticsearch DSL。
`AdaptiveRetrievalPolicy` 根据 `RetrievalQueryType` 返回固定策略档位及查询数量约束。

`SearchDocsTool` 保留原有单查询执行入口用于兼容测试和关闭 Adaptive Flag 时的 Phase 1 行为。
开启 Adaptive Flag 后，工具 Schema 和 Prompt 切换为结构化输入；工具名称仍为 `search_docs`，
同一次 Toolkit 中只注册一个同名工具。

多查询按输入顺序确定性执行。第一版不增加并行检索调度，避免在同步
`KnowledgeSearchPort` 之上引入额外取消、线程池和供应商限流复杂度。所有子查询共享现有工具超时和 Run 预算。

### 3.3 `fileagent-document`

`KnowledgeSearchPortImpl` 负责：

- 未提供 `SearchOptions` 时完全使用当前全局配置。
- 提供 `SearchOptions` 时按单次请求覆盖召回数、权重、最终 TopK、rerank 和父块展开。
- 所有请求级参数再次进行上下限校验，不能只依赖 Agent 域校验。
- `RrfFusion` 增加 BM25/KNN 权重，但保留等权重重载供固定 RAG 使用。
- rerank 关闭时直接使用加权 RRF；rerank 失败时降级到加权 RRF并返回受控降级码。
- 父块展开关闭时返回命中的 CHILD chunk；开启时维持当前父块读取、去重和限流行为。

Document 域只负责一次查询的检索，不承担问题分类、query decomposition 或多查询编排。

### 3.4 `fileagent-evaluation`

保留 `agent-v1` 作为 Phase 1 回归集，新增 `adaptive-v1`。评测 Observation 增加可选的查询类型、
策略 ID、计划/实际查询数、每查询命中数、候选和最终 chunkId、rerank 实际状态及降级码。
新增字段不包含思维链、完整工具正文、密钥或供应商原始异常。

## 4. 策略档位与硬上限

初始策略是待真实评测校准的服务端默认值，不是模型参数：

| 类型 | 子查询数 | BM25/KNN 权重 | BM25/KNN TopK | KNN Candidates | rerank | 父块展开 | 合并后最终上限 |
|---|---:|---|---|---:|---|---|---:|
| `NONE` | 0 | 不检索 | 0 / 0 | 0 | 否 | 否 | 0 |
| `SINGLE_HOP` | 1 | 1.0 / 1.0 | 20 / 20 | 100 | 是 | 是 | 5 |
| `MULTI_HOP` | 2～3 | 1.0 / 1.0 | 20 / 20 | 100 | 是 | 否 | 8 |
| `COMPARISON` | 2～3 | 1.0 / 1.0 | 20 / 20 | 100 | 是 | 否 | 8 |
| `AGGREGATION` | 1～3 | 1.3 / 0.7 | 50 / 20 | 100 | 否 | 否 | 12 |
| `TIME_SENSITIVE` | 1～2 | 1.3 / 0.7 | 30 / 20 | 100 | 是 | 否 | 8 |

通用限制：

- 单个子查询去首尾空格后为 1～200 字。
- 每次工具调用最多 3 个子查询，去重后仍须满足对应类型的最小数量。
- 每个 Run 最多 2 轮 `search_docs`；第二轮只允许针对上一轮零命中的部分改写。
- 同一 Run 中规范化后相同的子查询不得重复执行。
- 多查询结果按 `chunkId` 去重，记录每个命中来自哪些子查询。
- `COMPARISON` 合并时先为每个子查询保留至少一个最高分命中，再按综合分补齐，避免单侧证据占满结果。
- `AGGREGATION` 仅表示有限证据聚合；证据不足时不得声称完成知识库全量统计。
- `ragName`、`knowledgeTag`、`fileId` 等检索范围仍只能来自服务端可信上下文，不能由模型在工具参数中指定。
- 本期不新增模型可控的时间、版本或任意 metadata filter；这些能力在索引字段和权限边界明确后再实现。

策略由 `AdaptiveRetrievalProperties` 提供服务端配置，并在应用启动时完成范围校验。模型不能覆盖这些值。

## 5. 运行时状态与观测

`AgentRun` 增加一个有界的 `RetrievalExecution` 列表，每次合法的 `search_docs` 调用记录：

```text
queryType
strategyId
plannedQueryCount
executedQueries
perQueryHitCount
candidateChunkIds
finalChunkIds
rerankRequested
rerankApplied
fallbackCode
```

约束如下：

- 如果整个 Run 未调用 `search_docs`，运行结束时派生实际查询类型为 `NONE`。
- 查询原文只进入受控的内存运行态和离线评测 Observation，不进入普通 SSE、普通 trace 或日志。
- SSE 继续只返回工具名、步骤、结果数量、耗时、最终来源和稳定错误码。
- `GET /api/agent-runs/{runId}` 本期不扩展为返回完整检索计划。
- `candidateChunkIds` 表示单查询融合后的有界候选集合；`finalChunkIds` 表示实际渲染进工具结果的集合。
- Phase 2A 不把“最终命中”冒充“最终引用”；最终引用仍按现有答案中的来源标记提取，Phase 2C 再升级。

## 6. 多查询合并

每个子查询独立调用 `searchDetailed`。工具层使用以下确定性流程合并：

1. 按子查询顺序收集最终命中并记录来源查询序号。
2. `COMPARISON` 先从每个非空子查询取第一条，其他类型直接进入统一候选集。
3. 按归一化分数降序、子查询序号、单查询排名、chunkId 排序。
4. 按 chunkId 去重；重复命中合并来源查询序号，保留最高分版本。
5. 截断到策略的合并后最终上限。
6. 将合并结果写入本 Run 的允许读取白名单和检索观测。

本期不在不同子查询的原始 Provider 分数之间声称绝对可比；归一化只用于有界合并排序。
评测若证明该排序不稳定，再引入跨查询统一 rerank，不能在没有收益证据时增加一次远程模型调用。

## 7. 错误与降级语义

- JSON Schema 或语义校验失败时，递增当前 Run 的非法计划计数并返回受控工具校验信息，允许模型修正一次。
- 非法工具调用消耗一个 Agent Step，但不计入成功检索轮次；非法计划计数达到 2 时，Run 立即以
  `AGENT_RETRIEVAL_PLAN_INVALID` 失败，不能循环到最大预算。
- `NONE` 不允许作为 `search_docs` 参数；不检索应通过不调用工具表达。
- 任一子查询发生 Elasticsearch、Embedding 等基础设施异常时，取消后续子查询，沿用
  `AGENT_KNOWLEDGE_SEARCH_FAILED` 终止 Run，禁止使用残缺结果回答。
- 某个子查询正常零命中不是基础设施失败。工具返回缺失查询摘要，第二轮只能改写这些查询。
- 比较或多跳问题重试后仍缺少必要一侧证据时，Agent 必须说明证据不完整，不能补写企业事实。
- reranker 未启用或失败时允许降级到加权 RRF，分别记录“配置未启用”和“运行失败”降级码。
- 策略配置越界在应用启动时失败，不进行静默截断。

`TIME_SENSITIVE` 在 Phase 2A 只完成分类和保守回答策略。当前索引没有可靠的文档版本与时间字段，
因此没有明确日期证据时必须拒绝“最新”“当前”等结论。真正的版本、时间过滤与追溯归 Phase 2C/Phase 5。

## 8. 评测设计

`adaptive-v1` 覆盖：无需检索、单跳、多跳、比较、聚合、时间敏感、部分零命中、全部零命中、
重复子查询、非法计划、reranker 降级和检索基础设施失败。

新增指标：

- `queryTypeAccuracy`：实际问题类型与人工标注预期是否一致。
- `unnecessaryRetrievalRate`：无需检索题调用知识工具的比例，越低越好。
- `queryCountComplianceRate`：子查询数量和检索轮次是否满足策略限制。
- `strategyComplianceRate`：实际参数是否来自允许的服务端策略。
- `subQuestionCoverage`：多跳和比较题的必要子问题是否被查询覆盖。

继续保留运行成功率、预算遵守率、工具白名单、检索 Recall/MRR/nDCG、回答决策、必答事实、
无依据主张、拒答、引用覆盖率和引用有效性。

初始强制门禁：

```text
queryCountComplianceRate = 1.0
strategyComplianceRate = 1.0
toolWhitelistPassRate = 1.0
budgetComplianceRate = 1.0
```

`queryTypeAccuracy`、`subQuestionCoverage` 和多跳/比较收益阈值必须在首份真实 baseline 经人工确认后设定，
不能先填一个容易通过的阈值。无答案错误作答率不得比 `agent-v1` 已认可基线恶化。

## 9. 自动化测试

至少覆盖：

1. 六种查询类型到策略档位的确定性映射。
2. 子查询数量、长度、去重、类型匹配和第二次非法计划终止。
3. `SearchOptions` 按请求覆盖 TopK、加权 RRF、rerank 和父块展开。
4. 无 options 的固定 RAG 仍生成与当前相同的 Elasticsearch 请求和结果上限。
5. 多查询合并、比较题来源均衡、chunk 去重和确定性排序。
6. 正常零命中、部分零命中、基础设施失败和 reranker 降级。
7. 无需检索题不调用工具，多跳/比较题在一次工具调用内执行有限子查询。
8. Adaptive Observation、指标、Markdown 和门禁口径一致。
9. document、agent、evaluation 模块回归测试及完整 Maven 构建。

真实验收使用同一部署实例的正式模型、Embedding、Elasticsearch 和可选 reranker 配置，
不得用单元测试或旧进程推断真实收益。

## 10. 灰度与兼容

新增 `fileagent.agent.adaptive-retrieval-enabled=false`，默认关闭：

- 关闭时维持 Phase 1 的单查询 `search_docs` Schema、Prompt 和检索参数。
- 开启时使用 Adaptive Schema、策略档位、运行记录和 `adaptive-v1` 评测。
- Agent 总开关仍保持默认关闭；Adaptive 子开关不改变固定 `/chat`。
- 内部评测允许在前端 Agent 总开关关闭时执行，但是否使用 Adaptive 行为由 Adaptive 子开关决定。

上线顺序是：离线单元/模块回归 -> `adaptive-v1` 真实 baseline -> 人工核验计划和答案 -> 测试环境开启
Adaptive Flag -> 小流量灰度。任一步骤失败都可关闭子开关回到 Phase 1，不修改数据或索引即可回滚。

## 11. 明确不做

- 不增加独立 Planner 模型调用。
- 不实现 Phase 2B 的完整上下文预算器、`get_chunk` 或 `get_document_outline`。
- 不实现 Phase 2C 的 `documentVersion/page/bbox/quoteHash`、索引迁移或 citation verification。
- 不修改公开 HTTP/SSE 契约。
- 不新增写工具、HITL、持久化 Agent Step、MCP、GraphRAG、RAPTOR 或多 Agent。
- 不声称已支持真正的时间版本检索或全库精确聚合。

## 12. 完成定义

Phase 2A 完成必须同时满足：

- Adaptive Flag 默认关闭，固定 RAG 和 Phase 1 Agent 行为可回退。
- 所有检索参数由服务端策略生成并经过双层校验，模型不能扩大可信知识范围。
- 无需检索、单跳、多跳、比较、聚合和时间敏感行为都有确定性自动化场景覆盖。
- 子查询、检索轮次、候选数、最终命中数、步骤、工具超时和 Run 超时均有硬上限。
- `adaptive-v1` 报告能区分分类、计划、策略、检索、回答和引用问题。
- 多跳和比较题相对 Phase 0/Phase 1 基线获得经人工确认的稳定收益，无答案错误作答率不恶化。
- 所有受影响模块测试和完整构建通过，并使用重启后的部署实例完成一次真实评测。
