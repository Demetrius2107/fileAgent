# fileAgent — 文件驱动的智能 Agent

> 上传文件，融合**会话提示 + 历史上下文 + 全局知识库**，流式问答并标明回答来源。
> 技术栈：Java 21 · Spring Boot 4.1 · Spring AI 2.0 · Reactor · H2 · Maven

## 架构

**模块化单体（DDD）**：主应用是 Maven 多模块聚合的 Spring Boot 进程，领域间用 Port 接口 + 领域事件解耦。可选的 Python Sandbox 以独立 Worker 进程运行，不并入主应用 JVM。

```
fileagent-common   通用支撑（统一响应/异常）
fileagent-api      契约层（DTO / 枚举 / 端口 / 领域事件）
fileagent-session  会话域        fileagent-document  文档域
fileagent-chat     对话/推理域(核心) fileagent-action    动作执行域
fileagent-agent    Agent 运行域（Phase 1 最小 Agentic RAG）
fileagent-sandbox-worker 独立 Python Sandbox Worker（rootless Podman）
fileagent-evaluation RAG 评测集 / 内部评测接口 / baseline 门禁
fileagent-starter  主应用启动装配（不装配独立 Sandbox Worker）
ragflow-quickstart RAGFlow HTTP API 独立学习模块
```

每个业务域内部四层：`interfaces → application → domain ← infrastructure`。

## 快速开始

要求：JDK 21 + Maven 3.9+ + Docker（Elasticsearch）。

```bash
# 0. 启动 Elasticsearch（知识库索引与检索的前置依赖）
docker compose up -d elasticsearch

# 1. 配置环境变量（必须，密钥绝不写入仓库）
export FILEAGENT_AI_API_KEY='<由运行者提供>'
export FILEAGENT_AI_BASE_URL='https://兼容服务地址'
export FILEAGENT_AI_CHAT_MODEL='聊天模型名'
export FILEAGENT_AI_EMBEDDING_MODEL='向量模型名'

# 2. 启动（在仓库根目录）
mvn -pl fileagent-starter -am spring-boot:run
# 工作台: http://localhost:8080/
# Swagger UI: http://localhost:8080/swagger-ui.html
```

RAGFlow 托管式 RAG 的独立调用工程位于 [`ragflow-quickstart`](ragflow-quickstart/README.md)，已加入根 Maven 聚合，可在 IDEA 中作为独立模块运行。

自研 RAG 的评测框架和首批 30 题位于 [`fileagent-evaluation`](fileagent-evaluation/README.md)。

## Agent 模式（Phase 1 至 Phase 3）

> 最小 Agentic RAG：由 AI 自主检索知识库并分步作答。默认**关闭**，不影响既有 `/chat` 流程。

- **启用**：`fileagent.agent.enabled=true`（默认 `false`）；模型沿用 chat 域启用配置（`spring.ai.deepseek.*`），不新增密钥配置。
- **接口**：`POST /api/sessions/{id}/agent-runs`（SSE 流式）、`GET /api/agent-runs/{runId}`（快照）、`POST /api/agent-runs/{runId}/cancel`（取消），见 `docs/API.md` §4.2。
- **默认只读工具**：`search_docs`、`list_knowledge_files`、`read_document_context`、`get_document_outline`。默认运行上限为 8 步、4 次模型调用、45 秒；具体值可由 `fileagent.agent.*` 配置调整。
- **可选 Python Sandbox**：`execute_python` 默认不注册。只有同时开启 Agent 动作和 Sandbox、授权 `sandbox:python` 范围，并由用户单次审批后，才会通过独立 loopback Worker 执行。当前仅支持 Python，不使用 GraalVM；部署前提、资源限制和威胁模型见 [`docs/SANDBOX.md`](docs/SANDBOX.md)。
- **评测**：`./fileagent-evaluation/scripts/run-agent-evaluation.sh` 调用已部署实例，真实执行 AgentScope、检索和 Judge；通用知识可直接回答，企业事实才要求检索依据，见 `docs/TESTING.md` §7。

打开 `http://localhost:8080/` 即可使用同源工作台：新建会话 → 右侧「知识库」上传 TXT/MD/PDF/DOCX/XLSX/CSV → 中间提问，回答流式输出并标明来源文件；刷新页面后会话与消息仍在（H2 落库，知识索引存于 Elasticsearch）。

> ⚠️ API Key **不要**写进 `application.yml`，只通过环境变量传入，`storage/` 目录已 gitignore。

## 文档索引

| 文档 | 内容 |
|---|---|
| `docs/PRD.md` | 需求文档：功能模块 / 数据模型 / 里程碑 / 风险 |
| `docs/API.md` | 接口文档：全部 REST 接口与示例 |
| `docs/SKELETON.md` | 模块分包与依赖说明 |
| `docs/CODE-STYLE.md` | Java 代码规范 |
| `docs/COMMENT-STYLE.md` | 注释规范 |
| `docs/GIT-WORKFLOW.md` | Git 分支 / 提交 / PR 流程 |
| `docs/TESTING.md` | 测试规范 |
| `docs/SECURITY.md` | 安全与敏感信息规范 |
| `docs/SANDBOX.md` | Python Sandbox Worker 的部署、边界与威胁模型 |
| `docs/CICD.md` | CI/CD 流水线说明（GitHub Actions） |
| `docs/M1-UPLOAD-SPLIT.md` | F1 上传模块两人分工方案 |
| `AGENTS.md` | AI 编码代理协作规范（Cursor/Claude Code 等读取） |
| `CONTRIBUTING.md` | 协作者贡献指南 |
| `.agents/skills/add-code-comments/` | 注释技能（触发"加注释/补注释"自动按规范执行） |

## 里程碑（详见 PRD §7）

- **M1** 闭环骨架：上传 TXT/MD → 解析 → 向量索引 → RAG 问答 → ANSWER 动作
- **M2** 多格式（PDF/Office）+ 多动作（导出/图表）+ SSE 流式
- **M3** Agent 多步规划 + 代码沙箱 + 外部 API
- **M4** 历史上下文 + 业务库连接
- **M5** 工程化：审计 / 可观测 / 简单前端

**当前进度（2026-10）**：M1 ✅；M2 大部分 ✅（多格式解析、SSE 流式、RAG 工作台，知识库已重构为 ES 混合检索；导出/图表动作未完成）；M3 已具备 Agent 运行时、持久化、审批和注册式 HTTP 动作。Python Sandbox 代码以默认关闭的独立 Worker 形式接入，真实模型攻击评测和浏览器审批验收需按 [`docs/SANDBOX.md`](docs/SANDBOX.md) 与测试文档在目标环境分别执行；M4 未开始。

## 仓库规范

- 两人协作，`master` 受保护，开发走 `feat/<里程碑>-<功能>` 分支 + PR 合入；CI/CD 配置走 `ci-cd` 分支
- 业务逻辑实现由协作者完成，骨架与契约已就位
- 提交信息遵循 Conventional Commits（见 `docs/GIT-WORKFLOW.md`）
