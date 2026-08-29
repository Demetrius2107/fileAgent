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

- 同步处理：解析 → 分块（`fileagent.chunk-size` / `chunk-overlap`）→ embedding → 写入向量库（`fileagent.vector-store-path`），并落库 `rag_file` 记录
- chunk 元数据：`knowledge`=tag、`ragName`=name、`fileId`、`filename`、`chunkIndex`；检索为全局范围，不按标签过滤

Response:
```json
{ "code": 0, "message": "ok", "data": true }
```

失败时返回 `code=400` + 失败原因（如 `不支持的文件格式: virus.exe（当前支持 TXT/MD/PDF/DOCX/XLSX/CSV）`）；单文件失败时该文件在 `rag_file` 表中状态为 `FAILED`。

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

---

## 3.5 模型 Provider 配置（前端模型设置）

聊天模型的多套配置管理：新增/列表/启用（热切换，无需重启）/删除/连通性测试。所有厂商走 OpenAI 兼容协议。
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

### 3.5.3 启用配置（热切换）
`PUT /api/model-providers/{id}/activate`

原启用配置自动停用，聊天模型立即切换（不影响进行中的请求）。Response `data`: `true`

### 3.5.4 连通性测试
`POST /api/model-providers/{id}/test`

用存储的 key 发起一次最小真实调用。成功 Response `data`: `"连通正常，耗时 1234ms，模型回复: 正常"`；失败返回 `code=400` + 原因（如 key 无效/网络不通）。

### 3.5.5 删除配置
`DELETE /api/model-providers/{id}`

删除启用中的配置时，聊天模型自动回落默认（环境变量）。Response `data`: `true`

---

## 4. 对话（SSE 流式）

### 4.1 流式问答
`POST /api/sessions/{id}/chat`

- 请求头 `Accept: text/event-stream`，响应 `Content-Type: text/event-stream`
- 主流程：读取最近 `fileagent.chat-history-limit` 条历史 → 保存本次 USER → 全局知识检索 → 组装 Prompt（System 规则 + 历史 + 带来源标记的知识上下文）→ 模型流式调用 → 事件下发 → 完整 ASSISTANT 落库
- 前置校验失败（`sessionId` 为空 / `prompt` 空白 / 会话不存在）在流建立前返回 HTTP 400/404 JSON；流建立后的错误以 `error` 事件传递，HTTP 仍为 200

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
- `answerSource=KNOWLEDGE`：命中知识，`files` 为去重后的来源文件名（保持首次出现顺序）
- `answerSource=MODEL_GENERAL`：未命中知识，`files` 为空数组

**`done`：流结束（最后一条）**
```
event:done
data:{"type":"done","messageId":20}
```

**`error`：流式过程中的错误（HTTP 仍为 200）**
```
event:error
data:{"type":"error","code":"MODEL_STREAM_FAILED","message":"模型调用失败，请稍后重试"}
```
- `code=KNOWLEDGE_SEARCH_FAILED`：知识检索失败
- `code=MODEL_STREAM_FAILED`：模型调用失败或模型未返回任何内容

> 未命中知识时，服务端提示语「未检索到相关知识库内容，以下回答来自模型通用知识。」会作为首条 `message` 事件下发，并计入最终落库的 ASSISTANT 正文。

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
