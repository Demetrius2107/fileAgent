# fileAgent — 文件驱动的智能 Agent

> 上传文件，融合**会话提示 + 历史上下文 + 文档内容**，自动分析、解析并执行用户操作。
> 技术栈：Java 21 · Spring Boot 3.3 · Spring AI 1.0 · H2 · Maven

## 架构

**模块化单体（DDD）**：Maven 多模块聚合工程，一个 Spring Boot 进程，领域间用 Port 接口 + 领域事件解耦。

```
fileagent-common   通用支撑（统一响应/异常）
fileagent-api      契约层（DTO / 枚举 / 端口 / 领域事件）
fileagent-session  会话域        fileagent-document  文档域
fileagent-chat     对话/推理域(核心) fileagent-action    动作执行域
fileagent-starter  启动装配（唯一 Boot 入口）
```

每个业务域内部四层：`interfaces → application → domain ← infrastructure`。

## 快速开始

```bash
# 1. 需要 JDK 21（本项目不支持 Java 8）
# 2. 配置环境变量（必须）
export AI_API_KEY=你的OpenAI兼容Key
# 可选
export AI_BASE_URL=https://api.openai.com
export AI_CHAT_MODEL=gpt-4o-mini
export AI_EMBEDDING_MODEL=text-embedding-3-small

# 3. 启动（在 fileagent-starter 模块）
mvn spring-boot:run -pl fileagent-starter
# Swagger UI: http://localhost:8080/swagger-ui.html
```

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
| `AGENTS.md` | AI 编码代理协作规范（Cursor/Claude Code 等读取） |
| `CONTRIBUTING.md` | 协作者贡献指南 |

## 里程碑（详见 PRD §7）

- **M1** 闭环骨架：上传 TXT/MD → 解析 → 向量索引 → RAG 问答 → ANSWER 动作
- **M2** 多格式（PDF/Office）+ 多动作（导出/图表）+ SSE 流式
- **M3** Agent 多步规划 + 代码沙箱 + 外部 API
- **M4** 历史上下文 + 业务库连接
- **M5** 工程化：审计 / 可观测 / 简单前端

## 仓库规范

- 两人协作，`main` 受保护，开发走 `feat/<里程碑>-<功能>` 分支 + PR 合入
- 业务逻辑实现由协作者完成，骨架与契约已就位
- 提交信息遵循 Conventional Commits（见 `docs/GIT-WORKFLOW.md`）
