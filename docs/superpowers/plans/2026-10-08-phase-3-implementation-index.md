# Phase 3 实施计划索引

> **面向 AI 代理的工作者：** 按 3A -> 3B -> 3C -> 3D 顺序执行。每一阶段必须先完成自身退出门槛并提交，再进入下一阶段。

**目标：** 将已确认的 Phase 3 设计拆成四个可独立交付、测试和回滚的实现计划。

**架构：** Phase 3A 先建立持久化 Run/Step/Checkpoint 与后台执行；Phase 3B 在此基础上加入 AgentScope HITL 和前端审批；Phase 3C 接入注册式安全 HTTP 副作用工具；Phase 3D 增加独立 Python Sandbox Worker 和完整安全门禁。

**技术栈：** Java 21、Spring Boot 4.1、Spring Data JPA、H2、Reactor、AgentScope Java 2.0.1、原生 HTML/CSS/JavaScript、Maven。

---

## 执行顺序

1. [Phase 3A：持久化 Agent Runtime](./2026-10-08-phase-3a-persistent-agent-runtime.md)
2. [Phase 3B：HITL 审批与前端交互](./2026-10-08-phase-3b-hitl-approval-ui.md)
3. [Phase 3C：安全注册式 HTTP 工具](./2026-10-08-phase-3c-safe-http-action.md)
4. [Phase 3D：Python 沙箱与安全评测](./2026-10-08-phase-3d-python-sandbox-security.md)

## 固定边界

- 不实现任意 URL、Shell、SQL、宿主文件写入、知识库文件修改和 Artifact 输出。
- 所有新增 Java 类必须包含 `@author raosaijie`、`@since 0.1.0`、`@date 2026-10-08`。
- `fileagent-agent` 与 `fileagent-action` 只能通过 `fileagent-api` Port/DTO 协作，禁止互相增加 Maven 依赖。
- Phase 3C/3D 的副作用开关默认关闭；Phase 3A/3B 完成后，现有四个只读知识工具行为必须保持不变。
- 当前分支存在与本计划无关的未跟踪 `AnswerCitationExtractorTest.java` 和 `context-v2/`，每次提交必须精确 `git add`，禁止 `git add -A`。

## Phase 2C 合并检查点

Phase 3A、3B、3C 和 3D 的主体可继续开发。修改以下共享热点前，先检查 Phase 2C 是否已合入 `master`：

```bash
git log master --oneline -- fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentRunEvent.java
git log master --oneline -- fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentRunSnapshot.java
git status --short
```

若 Phase 2C 已合入，先 rebase 当前分支，再在其 record 尾部追加 Phase 3 字段；不得覆盖 Citation/Evidence 字段。Phase 3A 提供可选 no-op 的 `AgentFinalizationContributor`，Phase 2C 只实现该贡献点，不创建第二条消息落库路径。

## 全阶段最终验证

```bash
mvn -pl fileagent-agent,fileagent-action,fileagent-evaluation,fileagent-starter -am test
mvn clean package
```

部署后依次运行：

```bash
FILEAGENT_EVALUATION_DATASET_VERSION=agent-v1 ./fileagent-evaluation/scripts/run-agent-evaluation.sh
FILEAGENT_EVALUATION_DATASET_VERSION=adaptive-v2 ./fileagent-evaluation/scripts/run-agent-evaluation.sh
FILEAGENT_EVALUATION_DATASET_VERSION=context-v2 ./fileagent-evaluation/scripts/run-agent-evaluation.sh
./fileagent-evaluation/scripts/run-phase3-security-tests.sh
./fileagent-evaluation/scripts/run-phase3-model-attack-evaluation.sh
```

预期：Maven 测试与打包成功；三套既有真实模型评测通过原门禁；Phase 3 确定性安全门禁和模型攻击评测全部通过。
