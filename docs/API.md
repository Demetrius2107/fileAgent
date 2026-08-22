# 接口文档 (API.md)

> 版本: v0.1 (M1 骨架)
> 基础路径: `http://localhost:8080`
> 所有接口返回统一包装：`ApiResult<T>` `{ code, message, data }`，`code=0` 表示成功。

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

Response `data`:
```json
{ "id": 1, "title": "Q3 销售分析", "createdAt": "2026-08-22T13:30:00" }
```

### 1.2 会话列表
`GET /api/sessions`

Response `data`: `SessionDto[]`

### 1.3 会话详情 / 消息历史
`GET /api/sessions/{id}/messages`

Response `data`:
```json
[
  { "id": 1, "role": "USER", "content": "帮我看下这个报表", "actionJson": null, "createdAt": "..." },
  { "id": 2, "role": "ASSISTANT", "content": "已解析报表……", "actionJson": "{...}", "createdAt": "..." }
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
- 当前支持 TXT / Markdown；PDF/Office 随 M2 解析器扩展
- 同步处理：解析 → 分块（`fileagent.chunk-size` / `chunk-overlap`）→ embedding → 写入向量库（`fileagent.vector-store-path`），并落库 `rag_file` 记录
- chunk 元数据：`knowledge`=tag、`ragName`=name、`fileId`、`filename`、`chunkIndex`，供 chat 域按标签检索注入上下文

Response `data`:
```json
{ "code": 0, "message": "ok", "data": true }
```

失败时返回 `code=400` + 失败原因（如 `暂不支持的文件格式: application/pdf`）；单文件失败时该文件在 `rag_file` 表中状态为 `FAILED`。

---

## 4. 对话

### 4.1 发送消息
`POST /api/sessions/{id}/chat`

Request:
```json
{ "prompt": "汇总各区域销售额并按季度对比" }
```

Response `data`:
```json
{
  "messageId": 20,
  "action": {
    "action": "ANSWER",
    "params": {
      "answer": "| 区域 | Q1 | Q2 | ... |"
    },
    "reasoning": "根据上传的销售报表，按区域聚合了季度销售额",
    "summary": "已按区域汇总季度销售额，见上方表格"
  }
}
```

`action=ASK_USER` 示例：
```json
{
  "action": "ASK_USER",
  "params": { "question": "您希望按季度还是按年度对比？" }
}
```

> M2 起：本接口改为 `text/event-stream`（SSE 流式），且支持 `action=EXPORT_FILE` 等动作返回产物下载地址。

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

## 8. 交互时序（M1 闭环）

```
前端                      后端
  │ POST /api/sessions        │
  │─────────────────────────▶│ 创建会话
  │◀─────────────────────────│ SessionDto
  │ POST /api/sessions/1/files
  │─────────────────────────▶│ 存盘+解析+建索引
  │◀─────────────────────────│ UploadFileResp
  │ POST /api/sessions/1/chat
  │─────────────────────────▶│ RAG检索 → LLM → ActionDto
  │◀─────────────────────────│ ChatResp
```
