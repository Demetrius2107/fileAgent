# 模块分包说明 (SKELETON.md)

> 版本: v0.2 (DDD 多模块重构)
> 架构：**模块化单体**（一个 Spring Boot 进程 + 多个 Maven 模块 + 领域事件/端口解耦）
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
│   └── GIT-WORKFLOW.md            ✅ Git 工作流
│
├── fileagent-common/              ✅ 通用支撑（无业务，被一切模块依赖）
│   └── common/{result,exception}      ApiResult / BizException / GlobalExceptionHandler
│
├── fileagent-api/                 ✅ 契约层（跨域共享，只声明不实现）
│   ├── api/dto/                       8 个 DTO（record，含 ChatStreamEvent / RagFileSummary）
│   ├── api/enums/                     3 个枚举
│   ├── api/event/                     DomainEvent / MessageCreatedEvent / DocumentParsedEvent
│   └── api/port/                      SessionQueryPort / SessionMessagePort / DocumentQueryPort / KnowledgeSearchPort / ChatExecutionPort(流式)
│
├── fileagent-session/             ✅ 会话域
│   ├── interfaces/                   SessionController（已实现）
│   ├── application/                  SessionAppService（接口）+ SessionAppServiceImpl（已实现）
│   ├── domain/                       SessionEntity / MessageEntity / SessionRepository
│   └── infrastructure/               SessionJpaRepository / MessageJpaRepository / SessionRepositoryImpl / SessionQueryPortImpl / SessionMessagePortImpl
│
├── fileagent-document/            ✅ 文档域
│   ├── interfaces/                   FileController / RagFileController（已实现）
│   ├── application/                  DocumentAppService（接口）/ RagFileAppService + RagFileAppServiceImpl（已实现）
│   ├── domain/                       DocumentEntity / DocumentRepository / RagFileEntity / RagFileRepository
│   └── infrastructure/               DocumentJpaRepository / RagFileJpaRepository / RagFileRepositoryImpl / KnowledgeSearchPortImpl / 解析器（TXT/MD/PDF/Word/Excel/CSV）/ VectorStoreConfig
│
├── fileagent-chat/                ✅ 对话/推理域（核心域，M2 RAG 流式闭环已实现）
│   ├── interfaces/                   ChatController（SSE 流式，已实现）
│   ├── application/                  ChatAppService（接口，extends ChatExecutionPort）+ ChatAppServiceImpl（RAG 编排）+ RagPromptBuilder
│   ├── domain/                       待建（Action / 意图模型）
│   └── infrastructure/               StreamingChatClient（模型流适配）/ DocumentParsedEventListener（索引订阅）
│
├── fileagent-action/              ✅ 动作执行域
│   ├── interfaces/                   待建（风险确认接口）
│   ├── application/                  ActionExecutorService（接口）
│   ├── domain/                       ActionHandler（接口）
│   └── infrastructure/               ActionHandlerRegistry
│
└── fileagent-starter/             ✅ 启动装配（唯一 Boot 入口）
    ├── FileAgentApplication.java
    ├── resources/application.yml
    └── resources/static/             同源前端工作台（index.html / app.css / app.js）
```

---

## 2. 模块依赖方向（严禁反向）

```
fileagent-api → fileagent-common
fileagent-session / document / chat / action → fileagent-api（各域互不直接依赖）
fileagent-starter → 全部业务域
```

**跨域解耦的两条通道**：
1. **Port 端口**：跨域取数/触发走 `fileagent-api/port` 接口，由属主域 infrastructure 实现，消费方注入。
   - 例：chat 域 RAG 检索 → `KnowledgeSearchPort.search(query)`（全局知识，document 域实现）
   - 例：chat 域读历史/落消息 → `SessionQueryPort` / `SessionMessagePort`（session 域实现）
   - 例：chat 域流式推理契约 → `ChatExecutionPort.chat(sessionId, prompt)` 返回 `Flux<ChatStreamEvent>`
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
- starter 模块打包 jar 并配 `spring-boot-maven-plugin`（唯一可执行包）。

### 各模块依赖
| 模块 | 依赖 |
|---|---|
| common | spring-boot-starter-web |
| api | common + reactor-core（Flux 契约） |
| session | api + web + data-jpa + h2(runtime) |
| document | api + web + data-jpa + spring-ai-openai + spring-ai-vector-store + pdfbox + poi-ooxml + h2(runtime) |
| chat | api + web + webflux（流式调用）+ spring-ai-openai + reactor-test(test) |
| action | api + web（M2+ 加 poi / graalvm polyglot） |
| starter | 全部业务域 + h2(runtime) + springdoc |

### 后续里程碑新增
| 依赖 | 用途 | 里程碑 |
|---|---|---|
| `org.apache.tika` | 统一解析兜底 | M2 |
| `net.sourceforge.tess4j` | OCR | M2 |
| `org.graalvm.polyglot` | 代码沙箱 | M3 |
| `spring-ai-vector-store-*` | 生产向量库 | M4 |

> pdfbox / poi-ooxml 在 M1 提前引入（F1.2 需支持 PDF/DOCX/XLSX/CSV），版本由 parent `pom.xml` 统一管理。

## 5. 配置说明（application.yml，位于 starter）

| 键 | 说明 | 是否入库 |
|---|---|---|
| `spring.ai.openai.api-key` | `${AI_API_KEY}` 环境变量占位 | ❌ 严禁提交 |
| `spring.ai.openai.base-url` | `${AI_BASE_URL}` | ❌ 按需 |
| `spring.datasource.url` | H2 文件库 `./storage/db` | ✅ |
| `fileagent.storage-dir` | 上传文件目录 | ✅ 目录在 .gitignore |
| `fileagent.vector-store-path` | 向量库 JSON 文件 | ✅ 目录在 .gitignore |
| `fileagent.retrieval-top-k` | RAG 召回条数 | ✅ |
| `fileagent.retrieval-similarity-threshold` | RAG 相似度阈值 | ✅ |
| `fileagent.chat-history-limit` | 对话注入的历史条数（取最后 N 条） | ✅ |

## 6. 两人分工建议（按里程碑）

| 里程碑 | 建议分工 |
|---|---|
| M1 闭环 | A：session/document 域（上传→解析→索引）；B：chat 域（检索→LLM→动作→落库） |
| M2 多格式+多动作 | A：PDF/Office 解析器；B：export/chart 执行器 + SSE |
| M3 Agent+沙箱 | A：ReAct 编排 + 工具注册；B：GraalVM 沙箱 + 外部 API 动作 |
| M4 历史上下文 | A：过往会话/历史文档检索；B：业务库连接器 + NL2SQL |

### 分支约定
- `main`：受保护，仅可 PR 合入，始终可运行。
- 功能分支：`feat/<里程碑>-<功能>`，如 `feat/m1-chat`。
- 提交信息：`<type>(<scope>): <subject>`，scope 用模块名（session/document/chat/action/api/common/starter/pom）。
- 合并：PR + 至少 1 人评审后 squash merge。
