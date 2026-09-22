# Phase 2B 上下文预算与细粒度证据读取设计

## 1. 背景与目标

Phase 2A 已实现受控的问题分类、子查询生成、服务端检索策略档位、多查询合并和检索观测。
当前 Agent 仍存在三类问题：

- `search_docs` 会把命中文本直接返回给模型，单条长度限制实际按每个 chunk 生效，多个结果可能放大总上下文。
- 会话历史只按最近 10 条截取，旧消息被直接丢弃，既没有压缩，也没有可持久化的长期记忆。
- 已有 `AgentRunBudget` 尚未接入完整运行链路，模型 Token、各类上下文消耗和截断原因不可观测。

Phase 2B 的目标是建立可落地的 search-to-read 流程：搜索只返回有界摘要，Agent 根据需要查看文档目录并精读少量 chunk；
同时对当前问题、历史、摘要、工具结果和模型调用分别设置预算，并使用持久化滚动摘要保留长期会话信息。

本期继续遵循以下兼容边界：

- 不修改固定 `POST /api/sessions/{id}/chat` 的行为。
- 不增加新的 Feature Flag；Phase 2B 随现有 `fileagent.agent.adaptive-retrieval-enabled=true` 启用。
- Adaptive Flag 关闭时，保留当前 Phase 1 Agent 行为。
- 不提前实现 Phase 2C 的文档版本、页码、坐标、quoteHash 和回答后引用验证。

## 2. 核心决策

采用“有界搜索摘要 + 按需目录 + 白名单精读 + 持久化滚动摘要”：

```text
会话摘要 + 最近原始消息 + 当前问题
  -> Agent 判断是否需要检索
  -> search_docs：元数据 + 有界摘要
  -> 信息不足时 get_document_outline
  -> read_document_context：精读白名单 chunk
  -> 知识库证据 / 合理分析 / 必要时通用知识
  -> 最终回答
```

选择该方案的原因：

- 简单问题可以直接根据搜索摘要回答，不强制每题增加一次读取调用。
- 长文档不再自动把大父块整体塞进上下文，模型只精读实际需要的片段。
- 工具权限来自当前 Run 已观察到的文件和 chunk，模型不能任意遍历知识库。
- 历史超限时压缩旧消息，而不是删除旧消息或只保留固定条数。
- 所有预算状态集中在现有 `AgentRun` 和 `AgentRunBudget`，不新增 Budget Context 或 Manager 层。

不采用以下方案：

- 不采用搜索结果完全不带正文的 metadata-only 模式，否则简单问题也必须二次读取。
- 不继续按命中数量直接拼接父块，否则长父块会挤掉更高相关证据。
- 不用“删除最旧消息”处理历史超限，否则早期约束和决策永久丢失。
- 不计算缺乏价格配置支持的虚假金额成本，先以真实 Token 作为成本代理。

## 3. 运行链路

```text
AgentRunAppServiceImpl
  -> 校验当前问题字符上限
  -> 读取已有摘要、检查点和未摘要历史
  -> 保存当前 USER 消息
  -> 创建 runId
  -> Agent Runtime 创建 AgentRun 并发送 run.started
  -> 超出历史预算时执行一次滚动摘要
  -> 摘要通过版本号 CAS 更新，旧请求不能覆盖新摘要
  -> 组装摘要、最近原始消息和当前问题
  -> 执行 search -> outline/read -> final answer
  -> 记录字符、Token 和截断原因
  -> 保存最终 ASSISTANT 消息
```

摘要模型调用必须发生在 `AgentRun` 建立之后，计入本 Run 的模型调用次数、Token 和总超时。
当前用户问题不能进入本轮滚动摘要，避免问题在模型输入中重复，也避免尚未回答的问题污染长期记忆。

## 4. 预算模型

默认预算如下，全部由服务端配置，模型不能覆盖：

| 类别 | 默认上限 | 语义 |
|---|---:|---|
| 当前问题 | 8000 字符 | 硬上限；超限时在保存消息前拒绝 |
| 历史上下文 | 8000 字符 | 摘要与最近原始消息的总预算 |
| 滚动摘要 | 2000 字符 | 历史预算中的保留额度 |
| 最近原始消息 | 6000 字符 | 按消息完整保留，不在消息中间截断 |
| 单条搜索摘要 | 500 字符 | 每个命中最多返回的证据预览 |
| 单次工具结果 | 4000 字符 | 一次工具调用的所有结果共享该上限 |
| Run 工具结果累计 | 12000 字符 | search、outline、read 共同消耗 |
| 单次精读 chunk 数 | 3 | `read_document_context` 单次上限 |
| 单次目录项数 | 50 | 支持从指定 chunkIndex 继续读取 |
| 模型输出 | 2048 Token | 每次模型调用的输出上限 |
| Run Token | 60000 Token | 工具扩展软上限，使用供应商真实 usage 累计 |
| Adaptive 模型调用 | 6 次 | 包含滚动摘要调用，并为最终回答保留最后一次调用 |
| 非 Adaptive 模型调用 | 4 次 | 保持 Phase 1 现有行为 |
| Agent Step | 8 步 | 沿用现有默认值 |

建议在 `AgentProperties` 中增加或补齐以下配置：

```text
maxPromptCharacters=8000
maxHistoryCharacters=8000
maxSummaryCharacters=2000
maxRecentHistoryCharacters=6000
searchSnippetCharacters=500
outlineMaxEntries=50
readMaxChunks=3
maxTotalTokens=60000
```

`AdaptiveRetrievalProperties` 增加：

```text
maxModelCalls=6
```

沿用现有：

```text
maxSteps=8
maxModelCalls=4
maxOutputTokens=2048
singleToolResultCharacters=4000
maxToolResultCharacters=12000
runTimeout
toolTimeout
```

启动时校验各分项关系，例如摘要预算与最近历史预算之和不得超过历史总预算，
单次工具结果不得超过 Run 工具结果累计预算。

### 4.1 Token 上限语义

不同供应商和模型的 tokenizer 不同，调用前无法使用统一算法得到百分百准确的 Token 数。
因此字符预算负责限制单次上下文规模，`ModelCallEndEvent` 返回的真实 `inputTokens`、`outputTokens`
负责统计 Run 实际消耗。

`maxTotalTokens` 是工具扩展软上限：达到后所有知识工具返回受控的预算耗尽结果，Prompt 要求模型停止扩展并根据已有证据立即回答。
最后一次回答允许使 Run 总 Token 略微超过该值，否则无法同时保证“达到预算后停止扩展”和“仍然生成最终回答”。
模型调用次数、步骤数、字符数和超时仍是硬上限。

Adaptive 模式最多允许 6 次模型调用，覆盖以下最坏链路：滚动摘要、第一次搜索、第二次搜索、查看目录、精读和最终回答。
摘要和最终回答不消耗 Agent Step；搜索、目录、精读等工具调用才消耗 Step，因此 `maxSteps=8` 无需提高。
在发起第 6 次模型调用前，运行时中间件必须移除全部工具 Schema，使该调用只能生成最终回答；
Token 软上限已经触发时，下一次模型调用也使用相同的无工具模式。这样既不会产生第 7 次调用，也不会在最后一次调用中再次请求工具。

本期不计算金额。模型价格未进入配置之前，金额无法可靠计算；真实 Token 会完整记录，为后续按模型版本配置价格打基础。

## 5. 持久化滚动摘要

### 5.1 数据模型

在 `chat_session` 增加：

```text
summary                     CLOB
summary_through_message_id  BIGINT
summary_updated_at          TIMESTAMP
summary_version             BIGINT DEFAULT 0
summary_source_hash         VARCHAR
```

字段语义：

- `summary`：结构化滚动摘要 JSON。
- `summary_through_message_id`：摘要已覆盖的最后一条消息 ID。
- `summary_updated_at`：最近成功更新时间。
- `summary_version`：并发 CAS 版本号，不复用会话的普通更新时间。
- `summary_source_hash`：旧摘要与新增消息来源的稳定摘要，用于幂等诊断。

原始消息始终保留在 `message` 表中；摘要只改变模型上下文组装方式，不删除或改写历史消息。

当前项目没有 Flyway/Liquibase，并使用 `spring.jpa.hibernate.ddl-auto=update`，因此 Phase 2B 不额外引入迁移框架。
本地 H2 由 Hibernate 增加字段。生产部署阶段必须用正式数据库迁移替代 `ddl-auto=update`，但不在本期顺手扩大范围。

### 5.2 摘要触发与组装

- 摘要与最近原始消息未超过 8000 字符时，不调用摘要模型。
- 超限时，从新到旧选择能够完整放入 6000 字符预算的最近消息，最终仍按时间正序传给模型。
- 放不下的旧消息进入本次滚动摘要，不进行消息中间截断。
- 摘要输入是“已有摘要 + 新进入旧历史区的消息”，不是每次重新总结全部会话。
- 一次摘要输入也受 8000 字符历史预算约束：已有摘要最多 2000 字符，新进入摘要的连续旧消息最多使用剩余 6000 字符。
- 摘要输出最多 2000 字符，并替换旧摘要；检查点推进到本次已摘要的最后一条消息。
- 当前用户问题不参与本轮摘要。

首次启用时，如果已有会话在摘要检查点与最近消息之间存在超过单次摘要预算的大量历史，本轮只压缩从检查点开始、能够完整放入预算的连续消息，
并记录 `HISTORY_BACKLOG_PARTIAL`。中间消息不会被删除或伪装为已摘要，后续 Run 继续从检查点顺序推进；
当前回答只使用已成功摘要的内容和最近原始消息，并明确受控降级，不能无界调用摘要模型。

摘要输出使用固定 JSON 结构：

```json
{
  "confirmedFacts": [],
  "userConstraints": [],
  "decisions": [],
  "openQuestions": [],
  "references": []
}
```

摘要模型温度为 0，使用固定 Schema 和输出上限。摘要只能提取消息中明确出现的信息，必须保留否定词、数字、单位、日期、文件名和 ID；
不得补充常识、猜测动机、推导结论或把助手曾经的不确定回答改写为确定事实。

该限制只约束历史摘要，不限制最终回答使用模型通用知识。

### 5.3 并发与失败

摘要写入使用 `sessionId + expectedSummaryVersion` 条件更新，并同时推进检查点和版本号：

- 更新成功后，新摘要成为后续 Run 的基线。
- 更新失败表示已有更新版本，当前 Run 不覆盖新摘要。
- 检查点只能向前推进，不能被旧请求回退。
- 摘要与检查点在同一事务中写入。

摘要模型失败、JSON 不合法或写入冲突时：

- 不清空已有摘要。
- 继续使用已有摘要和预算内最近原始消息回答。
- 在 `AgentRun` 记录 `SUMMARY_FAILED_FALLBACK` 或并发更新原因。
- 不把摘要失败升级为整个 Agent Run 失败。

## 6. 最终回答的知识边界

历史摘要和最终回答使用不同规则：

```text
滚动摘要：只压缩已明确说过的信息，禁止推断和补充。
最终回答：知识库证据 + 合理分析 + 必要时模型通用知识。
```

最终回答遵循：

- 知识库检索到证据时，优先根据知识库回答并使用真实来源。
- 可以组合多段证据进行归纳和合理推理，但推导结论不能伪装成文档原文。
- 知识库没有答案时，允许基于通用知识回答，并明确说明“以下内容基于通用知识”。
- 通用知识回答不得生成知识库来源标记。
- 公司制度、内部数据、特定文件内容等私有事实没有证据时必须说明无法确认，不能依靠通用知识猜测。

本期只约束来源使用规则，不实现 Phase 2C 的逐项事实支持度验证。

## 7. Search-to-read 工具

### 7.1 `search_docs`

Adaptive 模式下，Phase 2A 各策略档位默认关闭父块自动展开。搜索结果只返回：

```text
chunkId
parentId（存在时）
fileId
filename
location
score
snippet（最多 500 字符）
```

`location` 只使用索引中真实存在的 sheet、section、row 或 chunkIndex 信息，不构造不存在的页码和标题层级。

每个最终 CHILD chunk、其真实关联 parentId 以及所属 fileId 加入当前 `AgentRun` 的读取授权集合。
搜索命中的完整内容仍可保留在有界的离线评测观察中，但不能直接拼入模型工具结果。

### 7.2 `get_document_outline`

新增只读工具 `get_document_outline`：

- 只允许读取当前 Run 已授权的 fileId。
- XLSX 优先按 sheet、section 和 row range 组织。
- 有可靠 section 元数据时按 section 分组。
- PDF、Word、Markdown、CSV 等缺少可靠标题元数据时，退化为 `chunkIndex + 短预览`，不得推测章节层级。
- 单次最多 50 项，支持从指定 `chunkIndex` 继续。
- 只有本次目录实际返回的 chunkId 才加入精读白名单。
- 目录正文计入单次及累计工具结果预算。

`list_knowledge_files` 返回的 fileId 也可加入当前 Run 的文件授权集合，使 Agent 可以先列文件再查看目录。

### 7.3 `read_document_context`

沿用现有工具名，把它作为 `get_chunk` 能力，不再增加语义重复的新工具：

- 只允许读取当前 Run 已授权的 chunkId。
- 单次最多读取 3 个 chunk。
- 严格按照请求中的 chunkId 顺序返回。
- 一次调用的所有 chunk 共享 4000 字符上限，不是每个 chunk 各 4000 字符。
- 返回成功读取数、被截断数和因预算跳过数。
- 内容计入 Run 工具结果累计预算。

## 8. 确定性选择与截断

所有预算选择使用固定顺序：

1. 搜索结果按 `chunkId` 去重。
2. 保持 Phase 2A 的相关性顺序；同分时按 `fileId -> chunkIndex -> chunkId` 排序。
3. 预算截断时先保留不同文件的最高分结果，再按原相关性排名补齐，避免单个文件占满证据。
4. 目录按 `chunkIndex` 升序返回。
5. 精读按模型请求顺序返回。
6. 使用 Unicode code point 安全截断，并显式标记结果已截断。
7. 达到累计预算后不再追加内容，返回受控预算状态，不抛出基础设施异常。

相同输入、相同索引状态和相同策略必须生成相同的上下文选择结果。

Elasticsearch、Embedding、reranker 或存储读取失败仍属于基础设施失败，不能伪装成“没有内容”或“预算耗尽”。

## 9. 运行态与可观测性

`AgentToolContext` 保持单一上下文对象，结构为：

```text
AgentToolContext
  - AgentRun：实际消费、授权集合和运行状态
  - AgentRunBudget：不可变预算上限
  - Knowledge ports
  - KnowledgeScope
```

`AgentRun` 增加：

```text
allowedFileIds
historyCharacters
summaryCharacters
searchSnippetCharacters
documentReadCharacters
toolResultCharacters
inputTokens
outputTokens
totalTokens
historyCompressed
budgetReasons
```

受控原因至少包括：

```text
HISTORY_SUMMARIZED
HISTORY_BACKLOG_PARTIAL
SUMMARY_FAILED_FALLBACK
SEARCH_RESULT_TRUNCATED
DOCUMENT_READ_TRUNCATED
TOOL_BUDGET_EXHAUSTED
TOKEN_BUDGET_EXHAUSTED
```

预算判断方法集中在 `AgentRun`，工具不得各自维护一份互相不一致的计数器。
`AgentRunBudget` 只保存不可变配置，不再创建 `AgentContextBudget`、Budget Manager 或第二套运行时 Context。

`AgentRunSnapshot` 直接增加上述受控计数和原因，不返回摘要正文、工具正文、模型原始响应、思维链或异常堆栈。
普通日志只记录 runId、sessionId、数值和受控原因，不记录用户问题、历史摘要、文档内容或完整 Prompt。

普通 SSE 不新增高频预算事件，继续使用现有 `run.started`、工具状态、回答增量和终态事件；
预算明细通过 Run 查询和离线评测查看。

## 10. 错误与降级语义

- 当前问题超过 8000 字符：返回请求级错误 `AGENT_PROMPT_TOO_LARGE`，不保存 USER 消息、不启动 Run，因此该错误不进入 Run Snapshot。
- 历史超限：生成滚动摘要，不删除原始消息。
- 摘要失败：保留旧摘要和最近历史，记录 `SUMMARY_FAILED_FALLBACK`，继续回答。
- 单次工具结果超限：确定性截断并返回截断标记，Run 继续。
- 累计工具预算耗尽：工具返回受控提示，Agent 使用已有证据回答。
- Token 软上限达到：停止工具扩展，允许最后一次回答调用。
- Adaptive 第 6 次模型调用：运行时移除工具 Schema，强制进入最终回答，不允许产生第 7 次调用。
- 用户当前问题本身超过硬上限以外的预算不足：不直接把 Run 标记失败。
- 基础设施失败：沿用稳定错误码终止，不能用通用知识掩盖知识库故障。
- 知识库正常零命中：允许回答通用知识；若问题属于私有事实，则说明无法确认。

## 11. 模块改动范围

### 11.1 `fileagent-api`

- 扩展 `SessionQueryPort`，读取摘要状态和检查点后的消息。
- 扩展 `SessionMessagePort`，提供摘要 CAS 更新。
- 扩展 `AgentRunCommand`，传递已有摘要、检查点和版本信息。
- 扩展 `AgentRunSnapshot`，返回受控预算消耗和原因。
- 扩展 `KnowledgeContextPort`，提供按授权文件生成有界目录的能力。

跨模块数据继续使用 record；必要的摘要状态可以作为 Port 内嵌 record，避免创建一组 Context 类。

### 11.2 `fileagent-session`

- 扩展 `SessionEntity` 的摘要字段。
- 增加摘要查询、检查点后消息查询和条件更新。
- 摘要、检查点、版本、更新时间和 sourceHash 原子写入。

### 11.3 `fileagent-agent`

- 扩展 `AgentProperties`、`AgentRunBudget` 和 `AgentRun`。
- 增加历史摘要模型适配和历史组装逻辑。
- 调整 `AgentScopeRuntimeAdapter`，使摘要和正式推理共享同一 Run 预算。
- 增加最小的模型调用预算中间件；达到 Token 软上限或进入 Adaptive 最后一次调用时移除工具 Schema。
- 调整 `AgentScopeModelFactory`，实际传递 `maxOutputTokens`。
- 监听 `ModelCallEndEvent` 并记录真实 usage。
- 调整 `SearchDocsTool` 和 `ReadDocumentContextTool`。
- 新增 `GetDocumentOutlineTool`。
- 调整 Adaptive 策略默认值，关闭自动父块展开；固定 RAG 默认参数不变。

### 11.4 `fileagent-document`

- 通过 `KnowledgeContextPort` 返回文件内稳定排序的有界目录数据。
- 只使用真实解析元数据；缺少结构信息时使用 chunkIndex 和短预览降级。
- 保留固定 RAG 当前父块读取行为。

### 11.5 `fileagent-evaluation`

- 新增 `context-v1` 数据集和对应语料。
- 复用现有 `run-agent-evaluation.sh` 的数据集版本参数，不再增加功能重复的启动脚本。
- Observation 和报告增加历史、摘要、工具和 Token 消耗。
- 主报告只展示汇总、门禁和不合格指标的典型问题，完整明细保留在结构化结果中。

## 12. 评测设计

`context-v1` 至少覆盖：

- 长会话第一次触发摘要。
- 后续会话基于旧摘要增量更新。
- 摘要后仍能回答早期明确事实。
- 摘要不得生成历史中不存在的事实。
- 长文档先搜索、再查看目录、再精读。
- 单次和累计工具结果均遵守预算。
- 预算耗尽后仍能受控回答。
- 知识库无答案时使用通用知识。
- 企业内部问题无证据时不得猜测。
- 通用知识回答不得伪造知识库引用。
- 摘要调用失败时降级继续回答。

新增门禁：

```text
promptPreservationRate = 1.0
toolBudgetComplianceRate = 1.0
budgetExhaustionCompletionRate = 1.0
summaryUnsupportedClaimRate = 0
fakeCitationRate = 0
historyRequiredFactCoverage >= 0.90
```

`fakeCitationRate` 在本期只检查通用知识回答是否伪造知识库来源，不替代 Phase 2C 的事实级 citation verification。

报告同时展示：

- 历史原文字符数和摘要字符数。
- 摘要调用次数。
- search、outline、read 的字符消耗。
- input/output/total Token。
- 截断和降级原因。
- 不合格指标对应的有限典型问题。

## 13. 自动化测试

至少覆盖：

1. 当前问题硬上限和保存消息前拒绝。
2. 历史未超限时不调用摘要模型。
3. 历史超限时完整保留最近消息并滚动摘要旧消息。
4. 首次超长历史分批推进检查点，未摘要消息不删除且记录受控降级。
5. 摘要 JSON 校验、禁止新增事实、失败降级和旧摘要保留。
6. 摘要版本 CAS、检查点只前进和并发旧请求不能覆盖新摘要。
7. 搜索摘要 500 字符、跨文件优先、去重和稳定排序。
8. 目录授权、50 项分页、真实元数据和无结构元数据降级。
9. 精读白名单、单次 3 个 chunk 和共享 4000 字符上限。
10. 三类工具共同遵守 12000 字符累计预算。
11. 模型输出上限实际传给 Provider。
12. `ModelCallEndEvent` 的真实 Token 正确累计。
13. Adaptive 前 5 次模型调用可使用工具，第 6 次调用移除工具 Schema，并拒绝第 7 次调用。
14. Token 软上限停止扩展但仍生成最终回答。
15. 固定 RAG 和 Adaptive Flag 关闭时行为不变，非 Adaptive 模型调用上限仍为 4。
16. `context-v1` 指标、Markdown 报告和质量门禁口径一致。
17. `adaptive-v2` 回归、受影响模块测试和完整 Maven Reactor 测试。

真实验收必须使用重启后的 FileAgent 服务、正式模型、Embedding、Elasticsearch 和已上传语料；
不能用单元测试、旧进程或模拟模型声称真实效果通过。

## 14. 实施顺序

1. 会话摘要字段、查询 Port 和并发条件更新。
2. `AgentRunBudget`、`AgentRun` 和配置校验。
3. 滚动摘要及历史上下文组装。
4. 搜索摘要、文档目录和精读工具。
5. Token 采集、软上限降级和 Run Snapshot。
6. `context-v1` 数据集、Runner 指标和报告。
7. 同步更新 API、路线图、配置和测试文档。
8. 完成模块测试、全量构建和真实评测。

## 15. 明确不做

- 不新增 Budget Context、Budget Manager 或重复的 Agent Context。
- 不修改固定 RAG 接口和默认检索行为。
- 不增加独立 Planner 模型调用。
- 不允许模型控制预算、索引、文件授权或底层检索参数。
- 不删除会话原始消息。
- 不计算缺少价格配置支持的金额费用。
- 不实现 Phase 2C 的结构化引用字段、索引版本迁移和事实级引用验证。
- 不实现 Phase 3 的 AgentRun/AgentStep 持久化、HITL 和写工具。
- 不引入 Flyway、异步任务系统、分布式锁或新的缓存中间件。

## 16. 完成定义

Phase 2B 完成必须同时满足：

- Adaptive 模式搜索不再自动返回完整父块，固定 RAG 行为不变。
- search、outline、read 均有白名单和独立/累计预算。
- 单次工具结果 4000 字符上限按整次调用生效，而非按每个 chunk 生效。
- 历史超限后使用持久化滚动摘要，最近消息完整保留，原始消息不删除。
- 摘要只保留明确事实，不补充推断；最终回答仍可受控使用通用知识。
- 同会话并发摘要不能覆盖更新版本，检查点只能前进。
- 模型调用、字符、真实 Token 和截断原因可在 Run Snapshot 与评测中查看。
- 达到工具或 Token 预算后停止扩展，并根据已有证据完成回答或明确证据不足。
- Adaptive 最多 6 次模型调用且最后一次不可调用工具；非 Adaptive 继续保持 4 次上限。
- `context-v1` 强制门禁通过，`adaptive-v2` 不发生回归。
- 所有受影响模块测试和完整 Maven 构建通过，并由重启后的真实服务完成一次评测。
