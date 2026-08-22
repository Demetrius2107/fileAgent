# 模块分包说明 (SKELETON.md)

> 版本: v0.1 (M1 骨架)
> 说明：以下为完整目标结构。✅ = 已随骨架创建；其余由协作者按里程碑实现（见 PRD §7）。

---

## 1. 目录总览

```
fileAgent/
├── pom.xml                        ✅ 依赖管理（见 §3）
├── docs/
│   ├── PRD.md                     ✅ 需求文档
│   ├── API.md                     ✅ 接口文档
│   └── SKELETON.md                ✅ 本文件
└── src/main/java/com/demetrius/fileagent/
    ├── FileAgentApplication.java  ✅ 启动类
    ├── config/                    M2  配置类（AiConfig / WebConfig）
    ├── controller/                M2  REST 控制器
    ├── service/                   M2  业务服务
    ├── parser/                    M2  文档解析器
    ├── repo/                      M2  JPA Repository
    ├── common/                    ✅ 通用层
    │   ├── exception/
    │   │   ├── BizException.java
    │   │   └── GlobalExceptionHandler.java
    │   └── result/
    │       └── ApiResult.java
    └── model/                     ✅ 模型契约层（无业务逻辑）
        ├── enums/
        │   ├── ActionType.java
        │   ├── MessageType.java
        │   └── ParseStatus.java
        ├── entity/
        │   ├── SessionEntity.java
        │   ├── MessageEntity.java
        │   └── DocumentEntity.java
        └── dto/
            ├── CreateSessionReq.java
            ├── SessionDto.java
            ├── UploadFileResp.java
            ├── ChatReq.java
            ├── ChatResp.java
            └── ActionDto.java

src/main/resources/
└── application.yml                ✅ 配置（API Key 走环境变量，不入库）
```

---

## 2. 各包职责与依赖方向

依赖方向：`controller → service → repo/parser → model`，禁止反向。

| 包 | 职责 | 要点 |
|---|---|---|
| `controller` | HTTP 入口，参数校验、调 service、包 ApiResult | 不写业务逻辑 |
| `service` | 业务编排：文档处理、RAG、聊天、动作执行 | 核心逻辑所在 |
| `repo` | JPA 数据访问 | 只声明接口 |
| `parser` | 文档解析为 Chunk | `DocumentParser` 接口 + 按 MIME 路由的 Registry |
| `config` | Spring Bean / AI 客户端 / 向量库装配 | |
| `common` | 统一异常、统一响应、通用工具 | 已建 |
| `model.entity` | 持久化实体（JPA 注解） | 已建 |
| `model.dto` | 接口出入参契约（record） | 已建 |
| `model.enums` | 枚举 | 已建 |

### 分层调用示例
```
FileController
   └─ DocumentService.handleUpload()
        ├─ ParserRegistry.parse(file)      → List<Chunk>
        └─ VectorStoreService.add(chunks)  → 建索引
ChatController
   └─ ChatService.answer(sessionId, prompt)
        ├─ VectorStoreService.search(prompt)
        ├─ ChatClient(rag-prompt.st)
        └─ ActionDto → 落 MessageEntity
```

---

## 3. 依赖处理说明（pom.xml）

### 核心依赖
| 依赖 | 用途 | 里程碑 |
|---|---|---|
| `spring-boot-starter-web` | Web / 上传 / SSE | M1 |
| `spring-boot-starter-data-jpa` | 会话/消息/文档持久化 | M1 |
| `spring-ai-openai-spring-boot-starter` | ChatClient + Embedding + Function Calling | M1 |
| `com.h2database:h2` (runtime) | 本地文件数据库（元数据） | M1 |
| `springdoc-openapi-starter-webmvc-ui` | Swagger UI (`/swagger-ui.html`) | M1 |
| `org.projectlombok:lombok` | 实体样板代码 | M1 |

### 后续里程碑新增
| 依赖 | 用途 | 里程碑 |
|---|---|---|
| `org.apache.pdfbox` | PDF 解析 | M2 |
| `org.apache.poi` (poi-ooxml) | Word/Excel/PPT 解析 | M2 |
| `org.apache.tika` | 统一文档解析兜底 | M2 |
| `net.sourceforge.tess4j` | OCR | M2 |
| `org.graalvm.polyglot` | 代码沙箱 | M3 |
| `spring-ai-vector-store-*` (Redis/pgvector) | 生产向量库 | M4 |

### 注意
- Spring AI 走 **Maven 里程碑仓库**（已在 pom 配置 `<repositories>` 指向 `repo.spring.io/milestone`）。
- 版本统一走 `spring-ai-bom` 管理，避免版本冲突。
- 升级/新增依赖后运行 `mvn dependency:tree` 检查冲突。

---

## 4. 配置说明（application.yml）

| 键 | 说明 | 是否入库 |
|---|---|---|
| `spring.ai.openai.api-key` | `${AI_API_KEY}` 环境变量占位 | ❌ 严禁提交 |
| `spring.ai.openai.base-url` | `${AI_BASE_URL}` | ❌ 按需 |
| `spring.datasource.url` | H2 文件库 `./storage/db` | ✅ |
| `fileagent.storage-dir` | 上传文件目录 | ✅ 目录在 .gitignore |
| `fileagent.vector-store-path` | 向量库 JSON 文件 | ✅ 目录在 .gitignore |
| `fileagent.retrieval-top-k` | RAG 召回条数 | ✅ |

> 运行需先设环境变量：`AI_API_KEY`、可选 `AI_BASE_URL` / `AI_CHAT_MODEL` / `AI_EMBEDDING_MODEL`。

---

## 5. 两人分工建议（按里程碑）

| 里程碑 | 建议分工 |
|---|---|
| M1 闭环 | A：文档上传/解析 → 索引；B：检索 → LLM → 动作 → 会话落库 |
| M2 多格式+多动作 | A：PDF/Office 解析器；B：export/chart 动作执行器 + SSE |
| M3 Agent+沙箱 | A：ReAct 编排 + 工具注册；B：GraalVM 沙箱 + 外部 API 动作 |
| M4 历史上下文 | A：过往会话/历史文档检索；B：业务库连接器 + NL2SQL |

### 分支约定
- `main`：受保护，仅可 PR 合入，始终可运行。
- 功能分支：`feat/<里程碑>-<功能>`，如 `feat/m1-chat`。
- 提交信息：`<type>(<scope>): <subject>`，如 `feat(chat): 实现 RAG 问答`。
- 合并：PR + 至少 1 人评审后 squash merge。
