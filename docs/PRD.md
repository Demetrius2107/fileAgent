# 文件驱动智能 Agent · 需求文档 (PRD)

> 版本: v0.1 (MVP 规划)
> 日期: 2026-08-22
> 定位: 以 Java 后端为学习载体，从零实现一个「上传文件 → 结合会话与历史上下文分析 → 解析并执行用户操作」的智能 Agent。

---

## 1. 背景与目标

### 1.1 要解决的问题
用户上传一份（或多份）文档，期望 Agent 不是简单做"文档问答"，而是：
1. 把**文档内容 + 当前会话提示 + 历史上下文**三者融合理解；
2. 推理出用户真正想完成的任务；
3. 输出**结构化、可解析、可执行**的动作；
4. 执行后把结果回流到会话，形成闭环。

### 1.2 项目目标
- **学习目标**：通过本项目掌握 Agent 后端核心能力——RAG、上下文管理、Function Calling、动作编排、代码沙箱。
- **产品目标**：本地单机可运行，跑通"上传 → 解析 → 推理 → 执行 → 反馈"完整闭环。

### 1.3 非目标 (MVP 阶段不做)
- 多用户 / 多租户 / 权限体系
- 高可用、水平扩展
- 前端复杂交互（先用 REST + 简单页面/Swagger 验证）

---

## 2. 核心概念

| 概念 | 定义 |
|---|---|
| 会话 (Session) | 一次连续交互的容器，包含多轮消息 |
| 消息 (Message) | 会话中的一轮，角色为 user / assistant / tool |
| 文档块 (Chunk) | 文档解析后的最小检索单元，带元数据 |
| 上下文 (Context) | 装配给 LLM 的输入：会话历史 + RAG 片段 + 用户画像 |
| 动作 (Action) | LLM 输出的结构化指令，由执行器消费 |
| 产物 (Artifact) | 动作执行后生成的文件/图表/表格 |

---

## 3. 功能需求

### 3.1 文件接入模块 (F1)
- **F1.1** 提供 REST 上传接口，支持单文件/多文件。
- **F1.2** 支持格式：PDF、DOCX、XLSX/XLS/CSV、PPTX、TXT、MD、图片(PNG/JPG)。
- **F1.3** 文件落本地存储目录，登记元数据（id、原名、大小、MIME、sha256、上传时间、所属会话）。
- **F1.4** sha256 去重，已存在文件复用解析结果。

### 3.2 文档解析模块 (F2)
- **F2.1** 统一解析入口，按 MIME 路由到不同解析器。
- **F2.2** 文本/MD/TXT：直接读取。
- **F2.3** PDF：Apache PDFBox 抽取文本层；扫描件走 OCR（Tess4J）兜底；识别表格结构。
- **F2.4** Office：Apache POI 解析 docx/xlsx/pptx，保留段落、表格、sheet、slide 结构。
- **F2.5** 图片：OCR 抽取文字（必要时调用多模态模型理解）。
- **F2.6** 解析输出统一为 Chunk 列表，Chunk 字段：`{id, docId, content, type(text/table/title), page, position, metadata}`。
- **F2.7** 分块策略：按语义/标题切分，带重叠窗口，单块 token 受控。

### 3.3 向量索引与检索模块 (F3)
- **F3.1** 对 Chunk 生成 Embedding（OpenAI / 通义 / Ollama 本地模型，可切换）。
- **F3.2** 向量存储：MVP 用 Spring AI `SimpleVectorStore`（文件持久化）；后续可换 Redis/Chroma/pgvector。
- **F3.3** 上传后异步/同步建索引，状态可查。
- **F3.4** 检索：向量召回 + 关键词(BM25/全文)召回 + 重排序（MVP 先做纯向量）。
- **F3.5** 支持按 docId / 会话范围过滤检索。

### 3.4 上下文管理模块 (F4)
- **F4.1** 会话历史：持久化存储 messages，按 session 聚合。
- **F4.2** 历史数据上下文三类来源：
  - **F4.2a** 本系统过往会话记录（按用户/会话检索）；
  - **F4.2b** 外部业务数据库（通过连接器 + 自然语言转 SQL 或固定查询模板）；
  - **F4.2c** 用户批量导入的历史文档（入向量库）。
- **F4.3** 上下文装配器：给定当前 prompt + 会话历史，决定召回哪些 RAG 片段、哪些历史会话、是否查外部库。
- **F4.4** Token 预算管理：分配给 RAG / 历史 / 当前 prompt 的额度，超限时对历史做摘要压缩。
- **F4.5** 会话摘要：长会话定期生成摘要存档，供后续引用。

### 3.5 Agent 推理模块 (F5)
- **F5.1** 意图识别：判断任务类型（问答 / 抽取 / 生成 / 执行 / 对比 / 分析）。
- **F5.2** 任务规划：ReAct 或 Plan-and-Execute，拆解子步骤。
- **F5.3** 工具注册（Spring AI Function Calling）：
  - `search_docs`：检索文档片段
  - `search_history`：检索过往会话
  - `query_business_db`：查外部业务库
  - `execute_script`：代码沙箱
  - `call_external_api`：调外部 API
  - `export_file`：生成产物文件
  - `draw_chart`：生成图表
  - `ask_user`：需要澄清时反问
- **F5.4** 多轮工具调用循环，直到产出最终动作或答案。
- **F5.5** LLM 提供商抽象层，支持切换（OpenAI / Anthropic / 通义 / Ollama 本地）。

### 3.6 动作解析模块 (F6)
- **F6.1** LLM 输出受 JSON Schema 约束的结构化动作：
  ```json
  {
    "action": "export_file | answer | call_api | execute_script | draw_chart | ask_user",
    "params": { "...": "..." },
    "reasoning": "为什么这么做",
    "summary": "给用户看的说明"
  }
  ```
- **F6.2** 动作枚举与每种动作的参数 Schema 定义（白名单）。
- **F6.3** 校验：Schema 合法性 + 必填参数 + 值域。
- **F6.4** 风险分级：只读/安全动作直接执行；有副作用动作（调外部 API、执行脚本、写文件）需用户确认（MVP 用配置开关，默认确认）。

### 3.7 动作执行模块 (F7)
- **F7.1** 执行器注册表：`action -> ActionHandler`。
- **F7.2** 各 Handler：
  - `AnswerHandler`：结构化答案/表格回写到消息。
  - `ExportFileHandler`：生成 Excel(POI)/PDF/报告，落产物目录。
  - `DrawChartHandler`：用 ECharts spec 或图片生成。
  - `CallApiHandler`：HTTP 调用外部系统（可配置端点 + 鉴权）。
  - `ExecuteScriptHandler`：代码沙箱执行。
- **F7.3** 执行结果回写会话（assistant 消息 + 产物引用）。
- **F7.4** 失败处理：捕获异常 → 错误信息回流给 LLM → 自我修正重试（限制次数）。

### 3.8 代码沙箱模块 (F8)
- **F8.1** 支持语言：JS（GraalJS）/ Python（GraalVM Polyglot 或 subprocess）。
- **F8.2** 资源限制：超时、内存、禁用文件/网络访问（MVP 用 GraalVM Polyglot 沙箱权限控制）。
- **F8.3** 输入：LLM 生成的脚本 + 上下文数据（JSON 注入）。
- **F8.4** 输出：stdout/结果对象/错误，回传 LLM。
- **F8.5** 安全：白名单 API，禁止反射逃逸。

### 3.9 会话与产物模块 (F9)
- **F9.1** 对话 API：发送消息（流式 SSE 输出）。
- **F9.2** 会话管理：创建、列表、历史消息分页、删除。
- **F9.3** 产物列表与下载接口。
- **F9.4** 文档管理：列出已上传文档、重新解析、删除。

### 3.10 系统支撑 (F10)
- **F10.1** 配置：LLM provider/key、向量库、存储路径、沙箱开关，统一 application.yml。
- **F10.2** 审计日志：每次动作执行留痕（action、params、结果、耗时）。
- **F10.3** 可观测：关键链路日志（解析、检索、LLM 调用、执行）。
- **F10.4** 健康检查与状态接口。

---

## 4. 数据模型（核心表）

```
sessions(id, title, created_at, updated_at, summary)
messages(id, session_id, role, content, artifacts_json, action_json, created_at)
documents(id, session_id, filename, mime, size, sha256, storage_path, parse_status, created_at)
chunks(id, document_id, content, type, page, position, embedding, metadata)
artifacts(id, session_id, message_id, type, filename, storage_path, created_at)
audit_logs(id, session_id, action, params, result, status, duration_ms, created_at)
business_db_connectors(id, name, type, jdbc_url, username, encrypted_pwd, query_templates)
```

---

## 5. 接口清单（REST，MVP）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/sessions | 创建会话 |
| GET  | /api/sessions | 会话列表 |
| GET  | /api/sessions/{id}/messages | 会话消息历史 |
| POST | /api/sessions/{id}/files | 上传文件到会话 |
| GET  | /api/sessions/{id}/documents | 会话文档列表 |
| POST | /api/sessions/{id}/chat | 发送消息（SSE 流式） |
| POST | /api/chat/{msgId}/confirm | 确认执行风险动作 |
| GET  | /api/artifacts/{id} | 下载产物 |
| POST | /api/documents/import-batch | 批量导入历史文档 |
| POST | /api/connectors | 配置业务库连接 |
| GET  | /api/health | 健康检查 |

---

## 6. 技术选型

| 层 | 选型 |
|---|---|
| 框架 | Spring Boot 3.x + Spring AI |
| LLM 编排 | Spring AI（Function Calling / RAG / ChatMemory） |
| 文档解析 | Apache Tika（统一入口）+ PDFBox + POI + Tess4J(OCR) |
| 向量库 | MVP: SimpleVectorStore；演进: Redis Stack / pgvector |
| Embedding | 可切换（OpenAI / 通义 / Ollama 本地） |
| 代码沙箱 | GraalVM Polyglot（JS/Python） |
| 存储 | H2/SQLite（元数据）+ 本地文件系统（文件/产物） |
| 构建 | Maven |
| Java | 21（虚拟线程 + GraalVM 友好） |

---

## 6.5 架构（DDD 模块化单体）

**形态**：模块化单体 —— 一个 Spring Boot 进程，多 Maven 模块，领域间用 **Port 端口 + 领域事件** 解耦。后续可按需拆独立进程。

**模块划分**（依赖方向：`api→common`，`session/document/chat/action→api`，`starter→全部`）：

| 模块 | 类型 | 职责 |
|---|---|---|
| `fileagent-common` | 支撑 | 统一响应 / 异常 / 通用工具 |
| `fileagent-api` | 契约 | 跨域 DTO / 枚举 / Port / 领域事件 |
| `fileagent-session` | 业务域 | 会话 / 消息 |
| `fileagent-document` | 业务域 | 文档 / 解析 / 索引 |
| `fileagent-chat` | 核心域 | 对话 / RAG / 推理编排 |
| `fileagent-action` | 业务域 | 动作执行 / 产物 / 沙箱 |
| `fileagent-starter` | 装配 | 唯一 Boot 入口，聚合各域 Bean |

**域内四层**（`interfaces → application → domain ← infrastructure`）：

| 层 | 职责 |
|---|---|
| interfaces | Controller / 出入参 DTO |
| application | 用例编排 / 事务 / 跨域 port 调用 |
| domain | 聚合根 / 值对象 / Repository 接口 / 领域服务 / 事件 |
| infrastructure | JPA 实现 / 外部客户端 / 解析器 / 事件监听 |

**跨域解耦通道**：
- 同步取数 → `api/port` 接口（如 chat 调 `DocumentQueryPort.searchChunks`）
- 异步通知 → `api/event` 事件（如 document 解析完发 `DocumentParsedEvent` → chat 订阅建索引）

---

## 7. 里程碑

### M1 — 闭环骨架（最小可跑通）
- Spring Boot 项目 + 基础分层
- 上传 TXT/MD → 解析 → SimpleVectorStore 索引
- 单轮问答（向量检索 + LLM）→ answer 动作
- REST 接口 + Swagger

### M2 — 多格式 + 多动作
- PDF/Word/Excel 解析
- export_file / draw_chart 动作
- 会话历史 + ChatMemory
- SSE 流式输出

### M3 — Agent 能力 + 沙箱
- ReAct 多步规划 + 多工具调用
- 代码沙箱（GraalJS）
- 外部 API 调用动作
- 风险动作确认流程

### M4 — 历史上下文 + 业务库
- 过往会话检索
- 批量历史文档导入
- 业务库连接器（NL2SQL 或模板查询）
- 上下文 Token 预算管理 + 会话摘要

### M5 — 工程化
- 审计日志、可观测
- 向量库迁移（pgvector/Redis）
- 简单前端页面

---

## 8. 风险与开放问题

- **Java 文档解析生态弱于 Python**：用 Tika 兜底，复杂表格/扫描件可能需要调 Python 侧车。MVP 先覆盖文本型 PDF/Office。
- **沙箱安全**：GraalVM Polyglot 需严格配权限，生产级沙箱应考虑容器隔离。
- **本地 LLM**：若用 Ollama，需本地有 GPU/够大内存；MVP 可先用云端 API。
- **NL2SQL 准确性**：外部业务库查询初期用固定模板，避免幻觉。
- **Token 成本**：长上下文 + 多轮工具调用消耗大，需做好预算与缓存。
