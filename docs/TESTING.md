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
- 每次运行默认保存在 `evaluation-results/<版本>/<UTC运行时间>/`，不会覆盖之前的报告，也不会被 `mvn clean` 清理；可通过 `FILEAGENT_EVALUATION_OUTPUT` 覆盖输出根目录。
- PR 门禁使用 `gate.json` 的绝对下限，并可与已认可的 `report.json` 比较最大回退量。
- `citationPrecision` 和 `citationRecall` 只统计回答正文实际标注、且本次检索命中的文件名；它们不再把所有检索候选误当作回答引用。
- 完整命令、数据格式和指标定义见 `fileagent-evaluation/README.md`。

## 7. Agent 模式评测

- 评测数据位于 `fileagent-evaluation/src/main/resources/evaluation/agent-v1/`（`cases/agent.jsonl` 题库 + `gate.json` 门禁）。
- Agent 评测把指标拆成两类，独立追踪：
  - **答案质量（复用 Judge）**：`answer.answerDecisionAccuracy` / `answer.requiredFactCoverage` / `answer.forbiddenFactSafety` / `answer.unsupportedClaimSafety`。
  - **Agent 行为（运行时受控性）**：`agent.runSuccessRate`（Run 以 `SUCCEEDED` 结束）/ `agent.budgetComplianceRate`（预算遵守）/ `agent.toolWhitelistPassRate`（工具白名单）/ `agent.citationCoverageRate`（知识库题至少引用一个检索文件）/ `agent.citationValidityRate`（实际引用全部来自检索结果）/ `agent.refusalDecisionAccuracy`（拒答正确）。
- 非 `SUCCEEDED` 的 Run 不调用 Judge，但保留回答、检索结果、引用、终态和失败码，便于定位运行时问题。
- 未标注禁答事实的题不参与 `answer.forbiddenFactSafety`；没有可评样本时报告为“不适用”（JSON 为 `null`），如门禁要求该指标则报缺少结果。`agent.refusalDecisionAccuracy` 使用 Judge 的语义拒答判断，仅统计成功完成 Judge 的题，拒答文字非空不视为回答。
- 引用覆盖率和引用有效性分开看：覆盖率低表示回答没有给出有效知识库来源；有效性低表示回答给出的来源中混入了本次检索未返回的文件。通用知识题、拒答题和失败 Run 的引用指标不适用。
- 每道题在 `expected.groundingMode` 明确回答依据：`KNOWLEDGE_BASED` 为企业事实，必须检索；`GENERAL_KNOWLEDGE` 为通用知识或正常创作，可直接回答；`REFUSE` 仅用于提示词注入、数据泄露等安全越界请求。
- 端到端入口：`fileagent-evaluation/scripts/run-agent-evaluation.sh` 调用已部署实例的 `/internal/evaluation/agent/run`，真实执行 AgentScope、当前知识库、当前启用的聊天模型和 `deepseek-v4-pro` Judge。它不启动新应用，也不要求重复配置模型 API Key。
- `mvn test` 中的 `AgentEvaluationRunnerTest` 只是评测器单元测试，验证数据解析、指标与门禁计算，不能替代真实报告。
- 真实评测路径 `AgentAnswerEvaluationPort -> AgentScopeRuntimeAdapter.evaluate()` 复用生产运行时，但**不创建会话、不写数据库**，只保留受控观察供评测分析。
- Phase 2B 使用独立数据集 `context-v1`，包含长历史摘要、否定/日期/金额/ID 原样保留、目录与细粒度读取、工具预算耗尽后正常收口、通用知识无假引用和知识库基础设施失败等场景。先上传 `./fileagent-evaluation/scripts/upload-corpus.sh context-v1`，再执行 `FILEAGENT_EVALUATION_DATASET_VERSION=context-v1 ./fileagent-evaluation/scripts/run-agent-evaluation.sh`。
- `context.*` 指标定义：`promptPreservationRate` 当前问题是否被完整保留；`toolBudgetComplianceRate` 工具结果是否未超过单次/累计上限；`budgetExhaustionCompletionRate` 预算耗尽后是否仍正常完成；`historyRequiredFactCoverage` 历史题必答事实覆盖；`summaryUnsupportedClaimRate` 摘要无依据主张率；`fakeCitationRate` 通用知识题输出知识库引用的比例。前四项通常配置最低值，后两项配置最高值。

## 8. 自适应检索评测（adaptive-v1）

- 评测数据位于 `fileagent-evaluation/src/main/resources/evaluation/adaptive-v1/`（`cases/agent.jsonl` 12 道题 + `corpus/` 2 份含可比条目与跨文档多跳关系的 Markdown 语料 + `gate.json` 门禁）。
- 12 类场景：无需检索、单跳、多跳、比较、聚合、时间敏感、部分零命中、全部零命中、重复子查询、非法计划、reranker 降级、检索基础设施失败。其中"reranker 降级"与"检索基础设施失败"是环境驱动场景，题库只固定问题与正确标注，行为在真实评测的 Observation 中体现。
- 新增自适应指标（`AdaptiveMetrics`），分母口径：
  - `adaptive.queryTypeAccuracy`：实际查询类型与人工标注 `expectedQueryType` 一致的比例；分母是标注了预期类型的题（失败 Run 也参与——规划是运行时受控行为）。
  - `adaptive.unnecessaryRetrievalRate`：**越低越好**；无需检索题（标注 `NONE`）实际执行结构化检索或调用知识工具的比例。
  - `adaptive.queryCountComplianceRate`：计划子查询数 ∈ [1,3]、单次 Run 的 `search_docs` ≤2 轮、实际执行数 ≤ 计划数且未因非法计划终止的 Run 比例。
  - `adaptive.strategyComplianceRate`：实际 `strategyId` 来自服务端允许档位且与 `queryType` 一致的 Run 比例。
  - `adaptive.subQuestionCoverage`：多跳/比较题的标注必要子问题被计划覆盖的比例（现阶段为数量代理：计划子查询数/标注必要子问题数；语义覆盖在真实评测中人工核验）。
  - 未执行结构化检索的 Run（`RetrievalObservation=null`）不参与以上任何分母；全量非自适应数据集的指标为 0.0，不产生 NaN。
- 四项强制门（`gate.json` `minimumScores`）：`adaptive.queryCountComplianceRate=1.0`、`adaptive.strategyComplianceRate=1.0`、`agent.toolWhitelistPassRate=1.0`、`agent.budgetComplianceRate=1.0`。
- 三项阈值**留空待人工确认**：`adaptive.queryTypeAccuracy`、`adaptive.subQuestionCoverage` 与多跳/比较收益阈值，须在首份真实 baseline 经人工核验后填入门禁（在此之前只展示、不断言）；`gate.json` 的 `regressionMetrics` 同样留空。
- 数据集契约由 `EvaluationDatasetContractTest` 固定：12 道题、12 类分类齐备、强制门齐备、待定阈值未出现在门禁中。

### 8.1 校准题库（adaptive-v2）

- v1 原题库与历史报告不改；v2 为独立的 13 题题库和知识库 `fileagent-eval-adaptive-v2`。先执行 `./fileagent-evaluation/scripts/upload-corpus.sh adaptive-v2`，部署新代码后运行 `FILEAGENT_EVALUATION_DATASET_VERSION=adaptive-v2 ./fileagent-evaluation/scripts/run-agent-evaluation.sh`。不同版本的分数不能直接用于 baseline 回退比较。
- `MULTI_QUERY` 表示互不依赖的并列事实，至少两条子查询；`MULTI_HOP` 表示先取得中间结果再查下一步，允许首轮一条、最多两轮。历史年份对比归 `COMPARISON`，当前语料缺乏可靠版本时间元数据，v2 不以历史比较题声称已测到 `TIME_SENSITIVE`。
- Observation 的 `retrievals` 保留每轮成功执行的计划，旧字段 `retrieval` 仍是最后一轮。`queryTypeAccuracy` 按首轮分类，`queryCountComplianceRate` 与 `strategyComplianceRate` 检查全部轮次。`subQuestionCoverage` 仍为数量代理：多跳累计各轮计划数，其他类型看首轮计划数；它不证明子查询语义覆盖或第二轮改写正确。
- 引用允许输出多个独立的 `[来源：文件名]`；兼容读取同一标记内用中文或英文分号分隔的文件名，未知文件名仍计入无效引用。这只修复当前文件名格式，不替代 Phase 2C 的版本、chunk 级引用验证。
