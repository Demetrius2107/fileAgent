# 接口文档 (API.md)

> 版本: v0.2 (M2 全局知识库 RAG + SSE 流式对话)
> 基础路径: `http://localhost:8080`
> 所有接口返回统一包装：`ApiResult<T>` `{ code, message, data }`，`code=0` 表示成功。
> 例外：SSE 对话接口（§4.1）直接返回 `text/event-stream` 事件流。

---

## 0. 通用约定

### 响应包装
```json
{ "code": 0, "message": "ok", "data": { ... } }
```

### 错误码
| code | 含义 |
|---|---|
| 0 | 成功 |
| 400 | 参数错误 / 业务错误 |
| 401 | 内部接口认证失败 |
| 404 | 资源不存在（会话/文档/产物） |
| 500 | 系统异常 |

### ActionType 枚举（对话返回值，定义见 `model/enums/ActionType.java`）
- `ANSWER`：直接回答（含 Markdown 表格 / 结构化答案）
- `ASK_USER`：信息不足，反问用户
- （M2+ 扩展：`EXPORT_FILE` / `DRAW_CHART` / `CALL_API` / `EXECUTE_SCRIPT`）

### ParseStatus 枚举（文档解析状态）
`PENDING` → `PARSING` → `SUCCESS` | `FAILED`

---

## 1. 会话管理

### 1.1 创建会话
`POST /api/sessions`

Request:
```json
{ "title": "Q3 销售分析" }
```

- `title` 可省略或为空白，此时默认为「新会话」

Response `data`:
```json
{ "id": 1, "title": "Q3 销售分析", "createdAt": "2026-08-22T13:30:00" }
```

### 1.2 会话列表
`GET /api/sessions`

- 按更新时间倒序（最近活跃在前）

Response `data`: `SessionDto[]`

### 1.3 会话消息历史
`GET /api/sessions/{id}/messages`

- 按创建时间正序；会话不存在返回 HTTP 404 + `code=404`

Response `data`:
```json
[
  { "id": 1, "role": "USER", "content": "帮我看下这个报表", "actionJson": null, "createdAt": "..." },
  { "id": 2, "role": "ASSISTANT", "content": "已解析报表……", "actionJson": null, "createdAt": "..." }
]
```

### 1.4 重命名会话
`PUT /api/sessions/{id}`

Request:
```json
{ "title": "Q4 销售复盘" }
```

- 标题去空白后落库；空白标题返回 `code=400`（「会话标题不能为空」），不回退默认标题
- 会话不存在返回 `code=404`

Response `data`: `SessionDto`

### 1.5 删除会话
`DELETE /api/sessions/{id}`

- 会话内的消息随会话**级联删除**（JPA cascade），不可恢复
- 会话内上传的会话文档记录（跨域值引用）本次不清理，属已知边界
- 会话不存在返回 `code=404`

Response `data`: `null`

---

## 2. 文件管理

### 2.1 上传文件到会话
`POST /api/sessions/{id}/files`

- `multipart/form-data`，字段名 `file`，支持多文件
- 上传后**同步解析并抽取内容级元数据**（标题/作者/页数/工作表数），M2 起改为异步 + 轮询状态
- MIME 按扩展名优先识别、客户端 content-type 兜底；当前支持 PDF / DOCX / XLSX / CSV / TXT / MD

Response `data`:
```json
{
  "documentId": 10,
  "filename": "销售报表.xlsx",
  "size": 20480,
  "mimeType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "parseStatus": "SUCCESS",
  "chunkCount": 12,
  "title": null,
  "author": null,
  "pageCount": null,
  "sheetCount": 3
}
```

> 内容级元数据按格式可抽取的字段填充：PDF 取 `title`/`author`/`pageCount`；DOCX 取 `pageCount`（段落数近似）；XLSX 取 `sheetCount`；CSV/TXT/MD 无结构化元数据，对应字段为 null。

### 2.2 会话内文档列表
`GET /api/sessions/{id}/documents`

Response `data`:
```json
[
  { "documentId": 10, "filename": "销售报表.xlsx", "parseStatus": "SUCCESS", "createdAt": "..." }
]
```

### 2.3 批量导入历史文档（M4）
`POST /api/documents/import-batch`（预留）

---

## 3. 知识库文件

### 3.1 上传知识库文件（解析 → 分块 → 向量入库）
`POST /api/rag-files/upload`

- `multipart/form-data`，字段：`name`（知识库名称）、`tag`（知识标签）、`files`（文件列表，可多个）
- 支持格式及 MIME 映射（按扩展名固定识别，未知扩展名直接返回业务 400，不按纯文本解析）：

| 扩展名 | MIME |
|---|---|
| `.txt` | `text/plain` |
| `.md` / `.markdown` | `text/markdown` |
| `.pdf` | `application/pdf` |
| `.docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| `.xlsx` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
| `.csv` | `text/csv` |

- 同步处理：sha256 内容去重校验 → 原件落盘（`fileagent.storage-dir`，回填 `storage_path` + `sha256`）→ 解析 → 分块（`fileagent.chunk-size` / `chunk-overlap`）→ embedding → 写入 Elasticsearch 知识索引（`fileagent.elasticsearch.index-alias`），并落库 `rag_file` 记录
- 上传去重：同一 `name` + `tag` 下存在 `sha256` 相同且索引成功的文件时直接拒绝（`code=400`），不落盘不落库；如需替换内容请先删除原文件
- 文件本体持久化：原件保存到 `storage/files/日期/文件名`，`rag_file` 表回填 `storage_path`/`sha256`；解析/索引失败时记录保留为 `FAILED`（不进列表），已写入 ES 的数据被清理，原件保留便于排查
- chunk 元数据：`knowledge`=tag、`ragName`=name、`fileId`、`filename`、`chunkIndex`；检索为全局范围，不按标签过滤

Response:
```json
{ "code": 0, "message": "ok", "data": true }
```

失败时返回 `code=400` + 失败原因（如 `不支持的文件格式: virus.exe（当前支持 TXT/MD/PDF/DOCX/XLSX/CSV）`）；单文件解析/索引失败时该条记录保留为 `FAILED`（不进列表），修正问题后可重新上传同名内容。

### 3.2 知识文件列表
`GET /api/rag-files`

- 按创建时间倒序（最新上传在前）

Response `data`: `RagFileSummary[]`
```json
[
  {
    "id": 1,
    "ragName": "员工知识库",
    "knowledgeTag": "制度",
    "filename": "员工手册.pdf",
    "status": "SUCCESS",
    "chunkCount": 12,
    "createdAt": "2026-08-26T09:00:00"
  }
]
```

### 3.3 删除知识库文件
`DELETE /api/rag-files/{id}`

删除顺序：查 `rag_file` 记录 → 按 `fileId` 删除 ES 索引 → 删除原件（`storage_path`）→ 删除 H2 记录。

- **必须先删 ES**：记录先没了索引还在，残留数据会继续被召回，且再无 fileId 可清理
- ES 索引删除（delete-by-query 删不到不报错）与原件删除（文件不存在静默跳过）均幂等，中途失败可直接重试本接口
- 索引进行中（`PARSING`）的文件不允许删除，避免删除与索引写入竞态

Response:
```json
{ "code": 0, "message": "ok", "data": true }
```

### 3.4 原件预览/下载
`GET /api/rag-files/{id}/content`

- 返回**二进制内容**，不走 `ApiResult` 包装；`Content-Disposition: inline` 按正确 MIME 直出，浏览器渲染不了的格式（DOCX/XLSX 等）自行转下载
- 文件名用 RFC 5987 `filename*=UTF-8''` 编码，支持中文
- 文件不存在 / 原件记录缺失 / 原件被删时返回 JSON 错误体（`code=400` 级业务错误）

### 3.5 分块查看
`GET /api/rag-files/{id}/chunks`

- 返回该文件在 ES 知识索引中的全部分块，含 CHILD 与 PARENT，按 `chunkIndex` 升序回排（同序号下 CHILD 先于 PARENT）；不返回 embedding 向量字段
- 文件不存在返回 `code=400`

Response `data`:
```json
[
  { "chunkId": "1:0", "chunkIndex": 0, "chunkType": "CHILD", "content": "……", "metadata": { "knowledge": "制度", "filename": "员工手册.pdf" } }
]
```

记录不存在或文件正在索引中时返回 `code=400` + 原因。

---

## 3.5 模型 Provider 配置（前端模型设置）

聊天模型的多套配置管理：新增/列表/编辑/启用（热切换，无需重启）/删除/连通性测试。所有厂商走 OpenAI 兼容协议。
API Key 安全约定：提交明文 → AES-GCM 加密落 H2（主密钥在 `storage/secret.key`，不进仓库）；**接口只回掩码（`****尾4位`），明文永不出后端**。
回落语义：无任何启用配置时，聊天模型回落到 application.yml + 环境变量配置的默认模型。

### 3.5.1 配置列表
`GET /api/model-providers`

Response `data`: `ModelProviderSummary[]`
```json
[
  {
    "id": 1,
    "provider": "ZHIPU",
    "baseUrl": "https://open.bigmodel.cn/api/paas/v4",
    "chatModel": "glm-4.6",
    "temperature": null,
    "active": true,
    "apiKeyMasked": "****ab3f",
    "createdAt": "2026-08-29T22:00:00"
  }
]
```

### 3.5.2 新增配置
`POST /api/model-providers`

```json
{
  "provider": "ZHIPU",
  "baseUrl": null,
  "apiKey": "sk-明文key",
  "chatModel": "glm-4.6",
  "temperature": null
}
```

- `provider`：`DEEPSEEK` / `ZHIPU` / `DASHSCOPE` / `MOONSHOT` / `OPENAI` / `CUSTOM`
- `baseUrl` 为空时用厂商默认端点（`CUSTOM` 必填）；`temperature` 为空时用 0.2
- 库内无启用配置时，新配置自动启用

Response `data`: `ModelProviderSummary`（同 3.5.1）

### 3.5.3 编辑配置
`PUT /api/model-providers/{id}`

请求体同 3.5.2，差异：
- `apiKey` **留空（null/空串）表示保留原 key**；填写则重新加密覆盖
- `baseUrl` 为空时改用所选厂商默认端点（`CUSTOM` 必填）
- 编辑启用中的配置会热切换聊天模型（实例缓存先失效再重建）

Response `data`: `ModelProviderSummary`（同 3.5.1）

### 3.5.4 启用配置（热切换）
`PUT /api/model-providers/{id}/activate`

原启用配置自动停用，聊天模型立即切换（不影响进行中的请求）。Response `data`: `true`

### 3.5.5 连通性测试
`POST /api/model-providers/{id}/test`

用存储的 key 发起一次最小真实调用。成功 Response `data`: `"连通正常，耗时 1234ms，模型回复: 正常"`；失败返回 `code=400` + 原因（如 key 无效/网络不通）。

### 3.5.6 删除配置
`DELETE /api/model-providers/{id}`

删除启用中的配置时，聊天模型自动回落默认（环境变量）。Response `data`: `true`

---

## 3.6 内部 RAG 评测

### 3.6.1 执行固定数据集评测

`POST /internal/evaluation/rag/run`

该接口默认不存在。部署时同时配置以下环境变量后才会注册：

```bash
FILEAGENT_EVALUATION_ENABLED=true
FILEAGENT_EVALUATION_TOKEN=<随机长 Token>
```

请求头：

```text
X-FileAgent-Evaluation-Token: <FILEAGENT_EVALUATION_TOKEN>
Content-Type: application/json
```

Request（全部字段可省略）：

```json
{
  "datasetVersion": "v1",
  "kValues": [1, 3, 5, 10],
  "metadata": {
    "trigger": "manual"
  },
  "baseline": null
}
```

- 数据集从当前部署包的 `evaluation/{datasetVersion}/cases/*.jsonl` 加载，单次最多 100 题。
- 使用当前实例的正式 RAG 评测 Port，因此复用已经生效的 Chat、Embedding、Elasticsearch、RRF 和可选 reranker 配置。
- 每题执行检索、Prompt 构造和 Chat 回答，再使用 `deepseek-v4-pro` 做结构化语义评判，但不创建或写入用户会话。
- Judge 复用部署已有的 `FILEAGENT_CHAT_API_KEY` 和 DeepSeek 端点，不需要新增 API Key。
- 不接收也不返回任何模型 API Key。
- `baseline` 可传上一次的 `report.json`，用于检查核心指标回退。
- 接口返回逐题检索片段、实际回答、拒答判断、来源和汇总报告；Markdown 包含指标解释，并只展示未通过门禁指标下最多 3 道代表问题。Token 缺失或错误返回 HTTP 401。
- `observations[].judgeRawResponse` 最多保留 4000 个字符；Judge 失败时，已生成的回答、检索结果和引用仍会返回，方便区分回答失败与评判失败。
- Judge 的 `unsupportedClaimsReason` 仅在 `hasUnsupportedClaims=true` 时必填；核心决策字段、事实数组及逐项判断仍执行结构化格式校验。
- 必须在网关或防火墙限制 `/internal/evaluation/**`，不要暴露给普通用户。

Response `data`：

```json
{
  "report": {
    "datasetVersion": "v1",
    "totalCases": 30,
    "scores": {
      "recall@10": 0.8,
      "mrr": 0.7,
      "requiredFactCoverage": 0.9,
      "unsupportedClaimSafety": 1.0
    },
    "gate": {
      "passed": true,
      "violations": []
    }
  },
  "observations": [
    {
      "caseId": "fact-001",
      "answer": "员工入职第一年享有 5 天年假。",
      "refused": false,
      "citations": [{"filename": "employee-handbook.md"}],
      "judgeScores": {
        "requiredFactCoverage": 1.0,
        "answerDecisionAccuracy": 1.0,
        "unsupportedClaimSafety": 1.0
      },
      "judgeReasons": {
        "requiredFactCoverage": "年假 5 天：回答明确表达了该事实",
        "answerDecisionAccuracy": "回答给出了问题要求的信息"
      },
      "judgeRawResponse": "{\"decision\":\"ANSWERED\",\"hasUnsupportedClaims\":false,...}"
    }
  ],
  "markdown": "# RAG 端到端评测报告..."
}
```

### 3.6.2 执行真实 Agent 评测

`POST /internal/evaluation/agent/run`

与 RAG 评测共用 `FILEAGENT_EVALUATION_ENABLED=true`、`FILEAGENT_EVALUATION_TOKEN` 和请求头 `X-FileAgent-Evaluation-Token`；该接口默认不存在。

Request（字段可省略）：

```json
{
  "datasetVersion": "agent-v1",
  "baseline": null
}
```

- 调用当前部署实例中的 `AgentAnswerEvaluationPort -> AgentScopeRuntimeAdapter.evaluate()`，真实执行 Agent 模型调用、只读工具和当前 Elasticsearch 知识库；之后使用 `deepseek-v4-pro` Judge 评判答案。
- 评测不创建会话、不写入 USER/ASSISTANT 消息，也不输出思维链、工具正文或模型 API Key。
- 不要求 `fileagent.agent.enabled=true`：前端功能开关可保持关闭，内部评测仍能安全检查即将发布的 Agent 行为。
- `expected.groundingMode=KNOWLEDGE_BASED` 表示企业事实，必须从本次检索证据回答；`GENERAL_KNOWLEDGE` 表示稳定通用知识或正常创作，允许无检索、无引用直接回答；`REFUSE` 只用于提示词注入、数据泄露等安全越界请求。
- 响应的 `report`、`observations`、`markdown` 分别保存汇总指标、逐题 Agent 行为/回答/Judge 结果和人工阅读报告；`baseline` 使用上一次 Agent `report.json` 比较回退。
- 非 `SUCCEEDED` 的 Run 计入 `agent.runSuccessRate` 分母，但不调用 Judge；已生成的回答、检索文件、引用文件、终态和失败码仍保留在 `observations` 与 `report.cases` 中。
- `agent.citationCoverageRate` 只统计成功的 `KNOWLEDGE_BASED` 题中是否至少引用一个本次检索命中的文件；`agent.citationValidityRate` 只统计这些题实际写出引用时，引用文件是否全部来自本次检索结果。`GENERAL_KNOWLEDGE`、`REFUSE` 和失败 Run 的引用状态为 `NOT_APPLICABLE`。
- `answer.forbiddenFactSafety` 在没有标注禁答事实且成功完成 Judge 的样本时为 `null`（报告显示“不适用”），配置了该指标的门禁会因缺少结果而失败；`agent.refusalDecisionAccuracy` 依据 Judge 的 `REFUSED` / `ANSWERED` 判定，Judge 失败的题不参与该指标分母。
- 自适应检索评测的 `observations[].retrievals` 按执行顺序列出所有成功检索轮次，`retrieval` 保留最后一轮以兼容旧报告；查询类型准确率看首轮，检索轮数与策略合规检查全部轮次。检索类型包括 `MULTI_QUERY`（并列独立事实）和 `MULTI_HOP`（依赖前一步结果），均由服务端控制实际检索参数。
- Phase 2B 的 `observations[].details` 记录上下文预算观察：历史/摘要/检索摘要/原文读取/工具结果字符数、模型 Token 用量、摘要是否发生、受控摘要及其覆盖到的消息 ID、预算原因和预算收口结果。摘要受 `maxSummaryCharacters` 限制；该字段只出现在内部评测结果，不进入公开 Run Snapshot，也不返回思维链或完整工具正文。报告中的 `context.*` 指标分为越高越好的保留/完成/覆盖率，以及越低越好的摘要无依据主张率和通用知识假引用率；后两者通过 `gate.json.maximumScores` 配置上限。

---

## 4. 对话（SSE 流式）

### 4.1 流式问答
`POST /api/sessions/{id}/chat`

- 请求头 `Accept: text/event-stream`，响应 `Content-Type: text/event-stream`
- 支持通过 W3C `traceparent` 请求头透传上游 Trace；响应头 `X-Trace-Id` 返回本次问答的 `traceId`
- 主流程：读取最近 `fileagent.chat-history-limit` 条历史 → 保存本次 USER → 第二轮起调用模型改写独立检索问题 → BM25/KNN 混合召回 → RRF 融合 → 可选 reranker → 最低相关性过滤 → 父块展开 → 组装 Prompt（System 规则 + 历史 + 带来源标记的知识上下文）→ 模型流式调用 → 事件下发 → 完整 ASSISTANT 落库
- `fileagent.reranker.min-relevance-score` 默认 `0.2`，仅在 reranker 成功返回时过滤低分片段；reranker 关闭或降级为 RRF 时不应用该阈值
- 前置校验失败（`sessionId` 为空 / `prompt` 空白 / 会话不存在）在流建立前返回 HTTP 400/404，响应仍为 `text/event-stream`，并携带一条 `error` 事件；流建立后的错误同样以 `error` 事件传递，但 HTTP 已提交为 200

Request:
```json
{ "prompt": "年假如何申请？" }
```

事件流共四种事件，事件名即 `type` 字段，`data` 为 `ChatStreamEvent` JSON：

**`message`：模型增量正文（多条，未命中知识时首条为固定提示语）**
```
event:message
data:{"type":"message","content":"根据《员工手册》，年假申请流程为……"}

event:message
data:{"type":"message","content":"更多正文增量……"}
```

**`sources`：回答来源（模型流结束后一条）**
```
event:sources
data:{"type":"sources","answerSource":"KNOWLEDGE","files":["员工手册.pdf","制度.docx"]}
```
- `answerSource=KNOWLEDGE`：命中知识，`files` 仅包含回答正文中实际标注、且本次检索确实返回的来源文件名，去重并保持检索首次出现顺序
- `answerSource=KNOWLEDGE_INSUFFICIENT`：未命中知识，服务端不会调用模型补充通用事实，`files` 为空数组

**`done`：流结束（最后一条）**
```
event:done
data:{"type":"done","messageId":20}
```

**`error`：流式过程中的错误（HTTP 仍为 200）**
```
event:error
data:{"type":"error","code":"MODEL_STREAM_FAILED","message":"模型调用失败，请稍后重试","traceId":"0123456789abcdef0123456789abcdef"}
```
- `traceId` 与响应头 `X-Trace-Id` 相同，可用于检索本次问答的关联日志
- `code=KNOWLEDGE_SEARCH_FAILED`：知识检索失败
- `code=MODEL_STREAM_FAILED`：模型调用失败或模型未返回任何内容

流建立前的业务错误使用相同事件结构，但保留对应 HTTP 状态。例如会话不存在返回 HTTP 404：
```
event:error
data:{"type":"error","code":"404","message":"会话不存在","traceId":"0123456789abcdef0123456789abcdef"}
```

> 未命中知识时，服务端返回「未检索到可用于回答该问题的知识库资料，无法根据现有资料确认。请提供相关文件或咨询对应负责人。」并计入最终落库的 ASSISTANT 正文；不会调用模型生成通用知识回答。

### 4.2 Agent 模式流式运行
`POST /api/sessions/{id}/agent-runs`

- 请求头 `Accept: text/event-stream`，响应 `Content-Type: text/event-stream`；响应头 `X-Trace-Id` 返回本次运行的 `traceId`，`X-Agent-Run-Id` 返回已经持久化的 `runId`
- 服务端先同步完成 prepare：校验会话与请求、读取历史、保存 USER 消息和 `PENDING` Run；事务提交后才由后台受限线程池入队执行。SSE 连接只是事件订阅，浏览器刷新或断连不会取消 Run。
- 流程：`PENDING` → ReAct 循环（知识工具检索，或受控动作工具请求审批）→ 完整 ASSISTANT、FINALIZATION Step 与 `SUCCEEDED` 状态在一个事务中落库
- 默认仅注册服务端固定的只读知识工具（检索文档 / 列知识文件 / 读文档上下文）。副作用工具必须显式开启 `fileagent.agent.actions-enabled`，并由服务端策略决定 `ALLOW / DENY / ASK`；当前没有内置写入型业务工具。
- 敏感配置（模型 apiKey/baseUrl）绝不进入任何事件；工具正文、思维链不外发

Request:
```json
{ "prompt": "年假如何申请？", "knowledgeScope": { "ragName": null, "knowledgeTag": null } }
```
- `knowledgeScope` 可省略（默认全局知识范围）；两个字段均为 null 表示全局范围

事件类型（`event` 名即 `type`，`data` 为 `AgentRunEvent` JSON）：
- `run.status`：`PENDING`、`CANCELLED` 等状态快照；新建 Run 的第一个事件为 `PENDING`，前端应显示“Agent 已排队”
- `run.started`：运行开始，`status=RUNNING`
- `tool.started`：`{ "step": 1, "tool": "search_docs" }`
- `tool.completed`：`{ "step": 1, "tool": "search_docs", "resultCount": 3, "durationMs": 42 }`（只带结果数量与耗时，不带正文）
- `message.delta`：`{ "content": "模型增量正文" }`
- `sources`：`{ "files": ["员工手册.pdf"] }` 回答实际引用的文件名
- `run.completed`：`{ "status": "SUCCEEDED", "messageId": 21 }`
- `run.failed`：`{ "status": "FAILED", "code": "...", "message": "...", "traceId": "..." }`
- `approval.required`：`{ "status": "WAITING_APPROVAL", "tool": "...", "approval": { ... } }`。审批视图只包含动作摘要、资源范围、预计影响、脱敏参数预览、参数短指纹、截止时间和风险等级，不含原始工具参数或模型思维链；该事件后当前 SSE 可正常结束。
- `approval.resolved`：`{ "status": "PENDING", "tool": "...", "approval": { "status": "APPROVED|DENIED", ... } }`。表示决定已持久化并已交给后台续跑，事件不返回用户备注或原始参数。

每个 SSE 帧均带 `id: <eventId>`，`data` 也包含 `eventId` 与对应的 `runVersion`。客户端保存最后成功处理的 `eventId`，用于断线恢复和去重。

错误码（`run.failed` 事件的 `code`）：
| code | 含义 |
|---|---|
| `AGENT_RUN_TIMEOUT` | 运行超时（超 `fileagent.agent.run-timeout`） |
| `AGENT_BUDGET_EXCEEDED` | 超出步骤/模型调用/最大迭代预算 |
| `AGENT_RUN_CANCELLED` | 运行已取消 |
| `AGENT_MODEL_UNAVAILABLE` | 模型调用失败 |
| `AGENT_RUN_INTERRUPTED` | 进程中断、后台执行异常或无法安全恢复 |
| `AGENT_RECOVERY_FAILED` | PENDING Run 的命令解密、校验或重新入队失败 |

流建立前校验失败（会话不存在、prompt 空白、队列已满、功能未开启）保留对应 HTTP 状态，并返回一条 `event:error` SSE 事件；此时没有已创建的 Run。

### 4.3 Agent Run 快照查询
`GET /api/agent-runs/{runId}`

返回 `ApiResult<AgentRunSnapshot>`，含 `runId`/`status`/`stepCount`/`modelCallCount`/`assistantMessageId`/`failureCode`/`traceId`，以及 `historyCharacters`、`summaryCharacters`、`searchSnippetCharacters`、`documentReadCharacters`、`toolResultCharacters`、`inputTokens`、`outputTokens`、`totalTokens`、`historyCompressed`、`budgetReasons`。当 `status=WAITING_APPROVAL` 时，额外返回脱敏 `approval` 视图，供页面刷新或 409 后恢复审批区；其他状态为 `null`。不含思维链、Prompt、摘要正文与工具正文。Run 不存在返回 HTTP 404 + `code=404`。

Run/Step/Checkpoint 保存在 H2；进程重启后 Run 快照仍可查询。事件增量仅保留有限内存窗口，重启后不能伪造已经丢失的模型增量。

### 4.4 当前活动 Run 查询
`GET /api/sessions/{sessionId}/agent-runs/active`

返回该会话中尚未进入终态的 `ApiResult<AgentRunSnapshot>`；没有活动 Run 时 `data=null`。前端在页面刷新、切回会话或 SSE EOF/网络错误后应调用此接口：`PENDING`/`RUNNING` 保持输入禁用并恢复订阅，`WAITING_APPROVAL` 从快照恢复审批区，终态则重新读取会话消息。

### 4.5 Agent Run 事件恢复
`GET /api/agent-runs/{runId}/events`

- 请求头可带 `Last-Event-ID: <eventId>`，服务端回放该游标之后仍在内存窗口中的事件，再继续订阅新事件。
- 缺失、过期或进程重启后的游标不会补造旧增量，服务端先下发当前 `run.status` 快照；客户端据此继续轮询 `active` 接口。
- 客户端需要按 `eventId` 做有限去重，重复的 `message.delta` 不应重复拼接。

### 4.6 Agent Run 取消
`POST /api/agent-runs/{runId}/cancel`

显式结束 `PENDING`、`RUNNING` 或 `WAITING_APPROVAL` Run：先持久化 `CANCELLED`；等待审批时同时撤销尚未决议的 Approval 与 Tool Step；运行中再中断本进程的模型调用句柄。接口返回最新快照，对已结束 Run 幂等。浏览器切换会话、关闭标签或 SSE 断连不得调用该接口。

### 4.7 Agent 高风险动作审批
`POST /api/agent-runs/{runId}/approvals/{approvalId}`

当 AgentScope 为一个服务端动作工具返回 `ASK` 后，系统先保存可校验的 `ASKING` 检查点、不可变审批快照和 `WAITING_APPROVAL` Run，随后通过 `approval.required` 事件展示本次动作。客户端只能对当前审批作出一次决定，不能提交工具参数、资源范围、操作者身份或“始终允许”规则。

请求头：
- `Accept: text/event-stream`
- `X-FileAgent-Client: web`
- 浏览器自动发送的同源 `Origin` 与 `Host`；缺失、不一致或非 HTTP(S) Origin 返回 `403`
- `Last-Event-ID` 可选，用于在决定提交后从已有回放窗口继续订阅

Request：
```json
{ "decision": "APPROVE", "comment": "确认执行本次操作" }
```

- `decision` 只能是 `APPROVE` 或 `DENY`；`comment` 为可选审计备注，服务端会删除控制字符并限制为 500 字符，不会传入模型或 `ConfirmResult`。
- 服务端先以 Approval 版本条件 CAS 落库决议；只有首次决议成功时才在同一事务提交前预留后台容量。容量不足会回滚该次决议，使 Approval 保持 `PENDING`；提交成功后才发布 `approval.resolved` 并入队恢复同一个 Agent Run。
- `APPROVE` 恢复时只读取数据库中原始 `ASKING ToolCall`，再次核对工具、Schema、资源、策略、端点版本和参数 HMAC；任一绑定变化均会取消旧批准，不能执行。
- 相同审批重复提交相同决定是幂等的，不会二次恢复或二次执行；提交相反决定返回 `409`。队列容量不足返回 `429` 且 Approval 保持 `PENDING`；审批已过期返回 `409`。
- 若管理员在审批等待期间关闭 `fileagent.agent.actions-enabled`，新的 `APPROVE` 返回 `403` 且不会改变 Approval；`DENY` 仍可用于安全结束既有等待。
- 默认在启动时及每 30 秒扫描一次过期审批，将 Approval、Tool Step、Run 一并收敛为 `EXPIRED`、`EXPIRED`、`TIMED_OUT`，释放会话活动槽位并发出状态事件。超时时间和扫描间隔分别由 `fileagent.agent.approval-timeout`、`approval-expiry-scan-interval` 配置。
- 成功响应是持续 `text/event-stream`，响应头带 `X-Agent-Run-Id`；页面应继续处理 `approval.resolved`、`run.started`、工具事件和最终事件。业务错误在流建立前仍按对应 HTTP 状态返回单个 `event:error`。

---

## 5. 产物管理

### 5.1 下载产物（M2）
`GET /api/artifacts/{id}`（预留）

---

## 6. 业务库连接器（M4 预留）

- `POST /api/connectors`：配置外部业务数据库连接
- `GET  /api/connectors`

---

## 7. 系统

### 7.1 健康检查
`GET /api/health`

Response:
```json
{ "code": 0, "message": "ok", "data": { "status": "UP", "version": "0.1.0-SNAPSHOT" } }
```

---

## 8. 交互时序（M2 RAG 闭环）

```
前端                      后端
  │ POST /api/sessions        │
  │─────────────────────────▶│ 创建会话
  │◀─────────────────────────│ SessionDto
  │ POST /api/rag-files/upload
  │─────────────────────────▶│ 解析+分块+向量化入库
  │◀─────────────────────────│ data: true
  │ GET  /api/rag-files       │
  │─────────────────────────▶│ 知识文件列表
  │◀─────────────────────────│ RagFileSummary[]
  │ POST /api/sessions/1/chat (Accept: text/event-stream)
  │─────────────────────────▶│ 历史读取→知识检索→Prompt→LLM 流式
  │◀─────────────────────────│ event: message / sources / done
  │ GET  /api/sessions/1/messages（刷新后恢复）
  │─────────────────────────▶│ 消息历史
  │◀─────────────────────────│ MessageDto[]
```
