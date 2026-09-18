# 测试规范 (TESTING.md)

> 目标：核心逻辑有测试，测试能稳定跑、能传达意图。先写有价值的测试，不追求覆盖率数字。

## 1. 框架与约定

- JUnit 5 + Mockito（Spring Boot 自带 `spring-boot-starter-test`）。
- 测试目录：`src/test/java/com/demetrius/fileagent/...`，包结构与生产类一致。
- 命名：类 `XxxTest`；方法 `should_<期望> when_<条件>` 或中文描述，如 `should_returnChunks_when_parseMarkdown`。

## 2. 分层测试策略

| 层 | 类型 | 范围 |
|---|---|---|
| parser | 纯单元 | 给文件→断言 Chunk 内容/数量 |
| service | 单元(Mock) | Mock repo/vectorStore，验编排逻辑 |
| controller | MockMvc | 断言 HTTP 状态/JSON 结构 |
| 集成 | `@SpringBootTest` | 端到端：上传→问答（M1 至少一条 happy path） |
| RAG 端到端评测 | `fileagent-evaluation` | 固定题库计算检索、回答、引用指标并比较 baseline |

## 3. 原则

- 每个测试只验一个行为；用 `given/when/then` 三段式。
- 断言要精确（字段值），避免 `assertNotNull` 一把梭。
- 测试数据自包含，不依赖 DB 残留状态。
- 集成测试若调用真实 LLM，打 `@Disabled` 或用 mock，避免 CI 花钱/不稳。
- 不写"永远通过"的空测试。

## 4. 测试资源

- 测试用样例文件放 `src/test/resources/fixtures/`（如 `sample.md`）。
- 测试不写 `storage/` 真实目录；用临时目录或 mock。

## 5. 跑测试

```bash
mvn test              # 全部
mvn test -Dtest=DocumentServiceTest   # 单类
mvn verify            # 含集成（如有）
```

提交前确保 `mvn test` 通过；新增功能必须带测试。

## 6. RAG 质量评测

- 评测数据位于 `fileagent-evaluation/src/main/resources/evaluation/<版本>/`。
- 普通 `mvn verify` 只验证评测框架和数据 Schema，不调用外部模型。
- 真实 baseline 由脚本调用已部署实例的内部评测接口，通过正式检索、Prompt 和 Chat 回答链路生成，再由 `deepseek-v4-pro` 逐题进行语义评判，但不写入用户会话。
- 每次运行保存在 `target/evaluation/<版本>/<UTC运行时间>/`，不会覆盖之前的报告。
- PR 门禁使用 `gate.json` 的绝对下限，并可与已认可的 `report.json` 比较最大回退量。
- `citationPrecision` 和 `citationRecall` 只统计回答正文实际标注、且本次检索命中的文件名；它们不再把所有检索候选误当作回答引用。
- 完整命令、数据格式和指标定义见 `fileagent-evaluation/README.md`。

## 7. Agent 模式评测

- 评测数据位于 `fileagent-evaluation/src/main/resources/evaluation/agent-v1/`（`cases/agent.jsonl` 题库 + `gate.json` 门禁）。
- Agent 评测把指标拆成两类，独立追踪：
  - **答案质量（复用 Judge）**：`answer.answerDecisionAccuracy` / `answer.requiredFactCoverage` / `answer.forbiddenFactSafety` / `answer.unsupportedClaimSafety`。
  - **Agent 行为（运行时受控性）**：`agent.runSuccessRate`（Run 以 `SUCCEEDED` 结束）/ `agent.budgetComplianceRate`（预算遵守）/ `agent.toolWhitelistPassRate`（工具白名单）/ `agent.citationCoverageRate`（知识库题至少引用一个检索文件）/ `agent.citationValidityRate`（实际引用全部来自检索结果）/ `agent.refusalDecisionAccuracy`（拒答正确）。
- 非 `SUCCEEDED` 的 Run 不调用 Judge，但保留回答、检索结果、引用、终态和失败码，便于定位运行时问题。
- 引用覆盖率和引用有效性分开看：覆盖率低表示回答没有给出有效知识库来源；有效性低表示回答给出的来源中混入了本次检索未返回的文件。通用知识题、拒答题和失败 Run 的引用指标不适用。
- 每道题在 `expected.groundingMode` 明确回答依据：`KNOWLEDGE_BASED` 为企业事实，必须检索；`GENERAL_KNOWLEDGE` 为通用知识或正常创作，可直接回答；`REFUSE` 仅用于提示词注入、数据泄露等安全越界请求。
- 端到端入口：`fileagent-evaluation/scripts/run-agent-evaluation.sh` 调用已部署实例的 `/internal/evaluation/agent/run`，真实执行 AgentScope、当前知识库、当前启用的聊天模型和 `deepseek-v4-pro` Judge。它不启动新应用，也不要求重复配置模型 API Key。
- `mvn test` 中的 `AgentEvaluationRunnerTest` 只是评测器单元测试，验证数据解析、指标与门禁计算，不能替代真实报告。
- 真实评测路径 `AgentAnswerEvaluationPort -> AgentScopeRuntimeAdapter.evaluate()` 复用生产运行时，但**不创建会话、不写数据库**，只保留受控观察供评测分析。
