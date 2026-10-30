# 模块分包说明 (SKELETON.md)

> 版本: v0.2 (DDD 多模块重构)
> 架构：**模块化单体主应用**（一个 Spring Boot 主进程 + 多个 Maven 模块 + 领域事件/端口解耦）；可选的 Python Sandbox 以独立 Worker 进程运行。
> 说明：✅ = 已随骨架创建；其余由协作者按里程碑实现（见 PRD §7）。

---

## 1. 目录总览

```
fileAgent/
├── pom.xml                        ✅ parent 聚合工程（统一版本/BOM/仓库）
├── AGENTS.md                      ✅ AI 代理协作规范
├── CONTRIBUTING.md                ✅ 贡献指南
├── README.md                      ✅ 项目入口
├── docs/
│   ├── PRD.md                     ✅ 需求文档
│   ├── API.md                     ✅ 接口文档
│   ├── SKELETON.md                ✅ 本文件
│   ├── CODE-STYLE.md              ✅ 代码规范
│   ├── COMMENT-STYLE.md           ✅ 注释规范
│   ├── TESTING.md                 ✅ 测试规范
│   ├── SANDBOX.md                 Python Sandbox Worker 部署与安全边界
│   └── GIT-WORKFLOW.md            ✅ Git 工作流
│
├── fileagent-common/              ✅ 通用支撑（无业务，被一切模块依赖）
│   └── common/{result,exception}      ApiResult / BizException / GlobalExceptionHandler
│
├── fileagent-api/                 ✅ 契约层（跨域共享，只声明不实现）
│   ├── api/dto/                       8 个 DTO（record，含 ChatStreamEvent / RagFileSummary）
│   ├── api/enums/                     3 个枚举
│   ├── api/event/                     DomainEvent / MessageCreatedEvent / DocumentParsedEvent
│   └── api/port/                      SessionQueryPort / SessionMessagePort / DocumentQueryPort / KnowledgeSearchPort / ChatExecutionPort(流式) / PythonSandboxExecutionPort
│
├── fileagent-session/             ✅ 会话域
│   ├── interfaces/                   SessionController（已实现）
│   ├── application/                  SessionAppService（接口）+ SessionAppServiceImpl（已实现）
│   ├── domain/                       SessionEntity / MessageEntity / SessionRepository
│   └── infrastructure/               SessionJpaRepository / MessageJpaRepository / SessionRepositoryImpl / SessionQueryPortImpl / SessionMessagePortImpl（含滚动摘要 CAS）
│
├── fileagent-document/            ✅ 文档域
│   ├── interfaces/                   FileController / RagFileController（已实现）
│   ├── application/                  DocumentAppService（接口）/ RagFileAppService + RagFileAppServiceImpl（已实现）
│   ├── domain/                       DocumentEntity / RagFileEntity / KnowledgeChunk / KnowledgeIndexRepository
│   └── infrastructure/               JPA 实现 / Elasticsearch 索引与混合检索 / 解析器（TXT/MD/PDF/Word/Excel/CSV）
│
├── fileagent-chat/                ✅ 对话/推理域（核心域，M2 RAG 流式闭环已实现）
│   ├── interfaces/                   ChatController（SSE 流式，已实现）
│   ├── application/                  ChatAppService（接口，extends ChatExecutionPort）+ ChatAppServiceImpl（RAG 编排）+ RagPromptBuilder
│   ├── domain/                       待建（Action / 意图模型）
│   └── infrastructure/               StreamingChatClient（模型流适配）/ DocumentParsedEventListener（索引订阅）

├── fileagent-agent/               ✅ Agent 运行域（Phase 1 + Phase 3 持久化/审批/动作工具，按配置注册高风险动作）
│   ├── interfaces/                   AgentRunController / AgentRunQueryController / AgentApprovalController（SSE、快照、active、取消、单次审批）
│   ├── application/                  AgentRunAppService（同步 prepare）/ AgentRunFinalizationService / AgentApprovalService / AgentApprovalWorkflowService / AgentApprovalExpiryScheduler / AgentPromptFactory / AgentToolContext
│   ├── domain/                       AgentRunEntity / AgentStepEntity / AgentCheckpointEntity / AgentApprovalEntity / AgentRunBudget / AgentActionPolicy
│   └── infrastructure/               AgentScopeRuntimeAdapter（暂停/ConfirmResult 恢复）/ AgentPersistenceAdapter / DatabaseAgentStateStore / AgentRunExecutionCoordinator / AgentRunEventHub / 只读工具 / RunScopedActionTool / CallRegisteredHttpApiTool

├── fileagent-action/              ✅ 动作执行域（Phase 3C 注册式 HTTP + 可选 Python Sandbox Client）
│   ├── interfaces/                   MockExpenseDraftController（默认关闭，仅开发环境验收）
│   ├── application/                  ActionExecutionService / PythonSandboxActionExecutionService（定义复核、幂等执行、结果收敛）/ MockExpenseDraftService
│   ├── domain/                       ActionToolExecutionEntity / ActionToolExecutionRepository（副作用账本）/ MockExpenseDraftEntity
│   └── infrastructure/               Endpoint 注册与 Schema / 安全 URI / DNS-IP 策略 / 受限 OkHttp / SandboxClient / JPA（含 Mock 草稿存储）
│
├── fileagent-sandbox-worker/      ✅ 独立 Python Sandbox Worker（不由 starter 装配）
│   ├── interfaces/                   内部固定执行接口、认证前置 Filter、受控错误响应
│   ├── application/                  SandboxExecutionService / SandboxContainerRuntime / 孤儿容器清理
│   └── infrastructure/               loopback 强制绑定 / Bearer Token / rootless Podman 固定命令与启动校验
│
├── fileagent-evaluation/          ✅ RAG 端到端评测工具
│   ├── evaluation/                   JSONL 加载 / Runner / 指标 / 报告 / 质量门禁
│   └── resources/evaluation/         v1、adaptive-v1/v2、context-v1 语料、题集与门禁阈值
│
├── fileagent-starter/             ✅ 主应用启动装配（自研 RAG Boot 入口）
│   ├── FileAgentApplication.java
│   ├── resources/application.yml
│   └── resources/static/             同源前端工作台（index.html / app.css / app.js）
│
└── ragflow-quickstart/            ✅ RAGFlow HTTP API 独立学习模块
    ├── RagFlowQuickstartApplication.java
    ├── RagFlowClient / RagFlowController
    └── resources/static/             Dataset、文档解析、Assistant、Session 和流式问答页面
```

---

## 2. 模块依赖方向（严禁反向）

```
fileagent-api → fileagent-common
fileagent-session / document / chat / action / agent → fileagent-api（各域互不直接依赖）
fileagent-sandbox-worker → fileagent-api + fileagent-common（独立 Boot 进程，不依赖 agent/action/starter）
fileagent-evaluation → fileagent-api（通过 KnowledgeSearchPort 评测正式检索链路）
fileagent-starter → session / document / chat / action / agent / evaluation（不依赖 sandbox-worker）
ragflow-quickstart → Spring Boot WebFlux（不依赖 fileagent-* 业务模块）
```

**跨域解耦的两条通道**：
1. **Port 端口**：跨域取数/触发走 `fileagent-api/port` 接口，由属主域 infrastructure 实现，消费方注入。
   - 例：chat 域 RAG 检索 → `KnowledgeSearchPort.search(query)`（全局知识，document 域实现）
   - 例：chat 域读历史/落消息 → `SessionQueryPort` / `SessionMessagePort`（session 域实现）
   - 例：chat 域流式推理契约 → `ChatExecutionPort.chat(sessionId, prompt)` 返回 `Flux<ChatStreamEvent>`
   - 例：agent 域的 `execute_python` → `PythonSandboxExecutionPort`（action 域实现；它再以固定 loopback HTTP 调独立 Worker）
2. **领域事件**：跨域异步通知走 `fileagent-api/event`，发布方发事件，订阅方监听。
   - 例：document 域解析完发 `DocumentParsedEvent` → chat 域订阅更新索引

## 3. 领域内 DDD 四层职责

依赖方向（域内）：`interfaces → application → domain ← infrastructure`（domain 不依赖任何上层）。

| 层 | 职责 | 禁止 |
|---|---|---|
| interfaces | Controller、参数校验、出入参 DTO | 写业务 / 直接碰 repository |
| application | 用例编排、事务、跨域 port 调用 | 写持久化 / 写 HTTP |
| domain | 聚合根、值对象、领域服务、Repository 接口、领域事件 | 依赖 Spring / 依赖 infrastructure |
| infrastructure | Repository JPA 实现、外部客户端、解析器、事件监听 | 被上层反向依赖 |

> ⚠️ 命名注意：接口层包名是 **`interfaces`（复数）**，`interface` 是 Java 关键字不能作包名。

## 4. 依赖处理说明（pom.xml）

### 模块间依赖
- parent `pom.xml` 用 `<dependencyManagement>` 统一模块版本，子模块依赖模块时**不写 version**。
- Spring AI 用 `spring-ai-bom` 管理；`repositories` 指向 spring milestone 仓库（已配在 parent）。
- starter 模块打包主应用 jar 并配 `spring-boot-maven-plugin`；`sandbox-worker` 也有独立可执行包，但不由 starter 装配。

### 各模块依赖
| 模块 | 依赖 |
|---|---|
| common | spring-boot-starter-web + spring-boot-starter-test(test) |
| api | common + reactor-core（Flux 契约） |
| session | api + web + data-jpa + h2(runtime) + spring-boot-data-jpa-test(test) |
| document | api + web + data-jpa + spring-ai-starter-model-openai（Embedding）+ spring-boot-starter-elasticsearch（知识索引与检索）+ pdfbox + poi-ooxml + h2(runtime) + testcontainers-elasticsearch / testcontainers-junit-jupiter / spring-boot-data-jpa-test(test) |
| chat | api + web + webflux（流式调用）+ spring-ai-starter-model-deepseek + spring-ai-starter-model-openai（多 Provider 动态构建）+ data-jpa（模型配置实体）+ micrometer-tracing + reactor-test(test) |
| agent | api + web + webflux（SSE）+ data-jpa + h2(runtime) + agentscope-core + agentscope-extensions-model-openai + JSON Schema Validator + swagger-annotations-jakarta + micrometer-tracing + reactor-test / spring-boot-data-jpa-test(test) |
| action | api + web + data-jpa + h2(runtime) + OkHttp + JSON Schema Validator（注册式 HTTP 动作与 loopback Sandbox Client） |
| sandbox-worker | api + common + web + validation；独立可执行 Boot 包，通过主机上的 rootless Podman 运行固定 Python 镜像 |
| evaluation | api + web + Jackson 3（内部接口默认关闭，通过 API Port 执行正式 RAG 并接收 DeepSeek Judge 结果） |
| starter | 主应用业务域 + evaluation + h2(runtime) + springdoc + spring-boot-starter-opentelemetry（不装配 sandbox-worker） |
| ragflow-quickstart | webflux + validation（独立调用 RAGFlow HTTP API） |

### 后续里程碑新增
| 依赖 | 用途 | 里程碑 |
|---|---|---|
| `org.apache.tika` | 统一解析兜底 | M2 |
| `net.sourceforge.tess4j` | OCR | M2 |
| rootless Podman（主机运行时） | 独立 Worker 的一次性 Python 容器隔离 | M3 / Phase 3D |
| `testcontainers-elasticsearch` | Elasticsearch 集成测试 | M2 |

> pdfbox / poi-ooxml 在 M1 提前引入（F1.2 需支持 PDF/DOCX/XLSX/CSV），版本由 parent `pom.xml` 统一管理。

## 5. 配置说明（application.yml，位于 starter）

| 键 | 说明 | 是否入库 |
|---|---|---|
| `spring.ai.openai.api-key` | `${FILEAGENT_AI_API_KEY}` 环境变量占位 | ❌ 严禁提交 |
| `spring.ai.openai.base-url` | `${FILEAGENT_AI_BASE_URL}` | ❌ 按需 |
| `spring.datasource.url` | H2 文件库 `./storage/db` | ✅ |
| `fileagent.storage-dir` | 上传文件目录 | ✅ 目录在 .gitignore |
| `spring.elasticsearch.*` | Elasticsearch 地址与凭证（环境变量注入） | ❌ 凭证严禁提交 |
| `fileagent.elasticsearch.*` | 索引别名、BM25/KNN/RRF 与结果数量 | ✅ |
| `fileagent.chat-history-limit` | 对话注入的历史条数（取最后 N 条） | ✅ |
| `fileagent.agent.enabled` | Agent 入口开关，默认关闭 | ✅ |
| `fileagent.agent.max-concurrent-runs` / `max-queued-runs` | 后台执行并发与排队上限 | ✅ |
| `fileagent.agent.pending-start-timeout` / `lease-duration` / `recovery-scan-interval` | PENDING 超时、执行租约与重启恢复扫描 | ✅ |
| `fileagent.agent.replay-max-events` / `replay-max-bytes` | 单个 Run 的内存事件回放上限 | ✅ |
| `fileagent.agent.actions-enabled` | 是否向 Agent 注册受控副作用工具，默认关闭 | ✅ |
| `fileagent.agent.authorized-action-resource-scopes` | 本地运行身份允许的动作资源范围，默认空即全部拒绝 | ✅ |
| `fileagent.agent.approval-timeout` / `approval-expiry-scan-interval` | 单次审批有效期与过期扫描周期 | ✅ |
| `fileagent.action.http.enabled` | 注册式 HTTP 动作开关，默认关闭，且依赖 `actions-enabled` | ✅ |
| `fileagent.action.http.endpoints` | 服务端 Endpoint、Schema、权限、出站和幂等定义 | ✅，凭证值除外 |
| `fileagent.sandbox.enabled` | 是否创建 Python Sandbox Client 与 Agent 工具工厂，默认关闭 | ✅ |
| `fileagent.sandbox.base-url` / `token` / `call-timeout` | 主应用到 Worker 的 loopback 地址、Token 和调用上限 | Token ❌；其余 ✅ |
| `fileagent.sandbox.worker.*` | 独立 Worker 的 loopback 绑定、固定本地 image ID、超时和孤儿清理参数 | Token / image ID ❌；其余 ✅ |
| `fileagent.evaluation.endpoint.enabled` | 是否开启内部评测接口，默认 false | ✅ |
| `fileagent.evaluation.endpoint.token` | `${FILEAGENT_EVALUATION_TOKEN}` | ❌ 严禁提交 |
| RAG Judge 模型 | 固定使用 `deepseek-v4-pro`，复用 `FILEAGENT_CHAT_API_KEY` | ❌ API Key 严禁提交 |

## 6. 两人分工建议（按里程碑）

| 里程碑 | 建议分工 |
|---|---|
| M1 闭环 | A：session/document 域（上传→解析→索引）；B：chat 域（检索→LLM→动作→落库） |
| M2 多格式+多动作 | A：PDF/Office 解析器；B：export/chart 执行器 + SSE |
| M3 Agent+沙箱 | A：ReAct 编排 + 工具注册；B：rootless Podman Python Sandbox + 外部 API 动作 |
| M4 历史上下文 | A：过往会话/历史文档检索；B：业务库连接器 + NL2SQL |

### 分支约定
- `main`：受保护，仅可 PR 合入，始终可运行。
- 功能分支：`feat/<里程碑>-<功能>`，如 `feat/m1-chat`。
- 提交信息：`<type>(<scope>): <subject>`，scope 用模块名（session/document/chat/action/api/common/starter/pom）。
- 合并：PR + 至少 1 人评审后 squash merge。
