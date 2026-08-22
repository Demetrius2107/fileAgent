# AGENTS.md — AI 代理协作规范

> 本文件供 **AI 编码代理**（Cursor / Claude Code / 本 Agent 等）在仓库中工作时读取。
> 目标：让任何代理在不动手动改他人代码、不泄露密钥、不破坏约定的前提下，高效产出符合本项目风格的代码。

---

## 1. 项目是什么

- **定位**：文件驱动的智能 Agent 后端。用户上传文件 → 结合会话提示 + 历史上下文 + 文档内容分析 → 输出并执行结构化动作。
- **技术栈**：Java 21 · Spring Boot 3.3 · Spring AI 1.0 · H2 · Maven · Lombok
- **基础包**：`com.demetrius.fileagent`
- **启动**：`mvn spring-boot:run`（需 JDK 21；本机当前是 JDK 8，跑 Boot 3 会失败，这是环境问题不是代码问题）
- **包管理**：`mvn clean package` / `mvn compile`

## 2. 架构约束（写代码前必读）

**模块化单体 + DDD**：Maven 聚合工程，模块间靠 `fileagent-api` 的 Port 接口与领域事件解耦。

模块依赖方向：`api → common`；`session/document/chat/action → api`；`starter → 全部`。**各业务域互不直接依赖**。

| 模块 | 职责 |
|---|---|
| `fileagent-common` | 统一响应/异常（`ApiResult` / `BizException` / `GlobalExceptionHandler`） |
| `fileagent-api` | 跨域契约：`dto`(record) / `enums` / `event` / `port` |
| `fileagent-session` | 会话域 |
| `fileagent-document` | 文档域 |
| `fileagent-chat` | 对话/推理域（核心域） |
| `fileagent-action` | 动作执行域 |
| `fileagent-starter` | 启动装配（唯一 Boot 入口） |

每个业务域内部四层（依赖方向：`interfaces → application → domain ← infrastructure`）：

| 层 | 职责 | 禁止 |
|---|---|---|
| `interfaces` | Controller、出入参 DTO | 写业务 / 直接碰 repository |
| `application` | 用例编排、事务、跨域 port 调用 | 写持久化 |
| `domain` | 聚合根、值对象、Repository 接口、领域服务、领域事件 | 依赖 Spring / infrastructure |
| `infrastructure` | JPA 实现、外部客户端、解析器、事件监听 | 被上层反向依赖 |

**约定清单**：
- `api/dto` 用 `record`；实体用 Lombok `@Getter/@Setter`。
- 枚举持久化用 `@Enumerated(EnumType.STRING)`。
- 长文本用 `@Lob + columnDefinition="CLOB"`。
- 日志用 `@Slf4j`，禁 `System.out`。
- 所有对外接口返回 `ApiResult<T>`；业务异常抛 `BizException`。
- 对外绝不直接返回 `Entity`，一律 DTO；接口层包名是 `interfaces`（复数，`interface` 是关键字）。

## 3. 敏感信息（红线）

- API Key / 密码 / Token **绝不**写入仓库文件。
- `application.yml` 密钥用 `${AI_API_KEY}` 占位，真实值走环境变量。
- `storage/`（上传文件、H2 库、向量库 JSON）已在 `.gitignore`，**禁止** `git add -f` 强加。
- 新增密钥类配置 → 加 `.gitignore` 或环境变量占位。

## 4. Git 规则（代理提交时）

1. **不直接提交到 `main`**。工作分支必须是 `feat/<milestone>-<功能>` 或 `fix/`。
2. 提交信息遵循 Conventional Commits：
   - `feat(chat): 新增 RAG 问答` / `fix(parse): 修复 xlsx 解析 NPE`
   - scope：`chat` `document` `parse` `session` `repo` `pom` `docs` 等
3. 只 `git add` 本次相关的文件，禁止 `git add -A` 夹带无关改动。
4. 改动前先 `git status` / `git diff` 确认当前状态；不盲目覆盖。
5. 大改动先与用户对齐方案（计划模式），再动手。

## 5. 行为准则

- **只做被要求的事**：不顺手重构无关代码、不扩大改动面。
- **跟随既有风格**：新代码风格必须与相邻文件一致（Lombok / record / 注释密度 / 命名）。
- **注释密度**：与相邻代码一致；只为"代码无法表达的设计约束"写注释，不为"做了什么"写。
- **如实报告**：编译失败、测试失败、跳过某步，都要原样说明，不粉饰。
- **先读后改**：修改前先 Read 目标文件；写完用工具结果确认，不臆造文件状态。

## 6. 文档

- `docs/PRD.md` 需求 · `docs/API.md` 接口 · `docs/SKELETON.md` 分包
- `docs/CODE-STYLE.md` 代码 · `docs/COMMENT-STYLE.md` 注释 · `docs/TESTING.md` 测试 · `docs/GIT-WORKFLOW.md` Git
- 新增/变更接口时**同步更新** `docs/API.md`；新增依赖时更新 `docs/SKELETON.md §3`。

## 7. 里程碑分工（谁来做什么）

| 里程碑 | 分工 |
|---|---|
| M1 闭环 | A：session/document 域（上传→解析→索引）；B：chat 域（检索→LLM→动作→落库） |
| M2 多格式+多动作 | A：PDF/Office 解析器；B：export/chart 执行器+SSE |
| M3 Agent+沙箱 | A：ReAct 编排+工具注册；B：GraalVM 沙箱+外部 API |
| M4 历史上下文 | A：会话/历史文档检索；B：业务库连接器 |

**代理不要擅自实现/改写对方负责的未完成模块**，除非用户明确要求。
