# Phase 2A 自适应检索核心 实施计划

> **面向 AI 代理的工作者：** 当前仓库未获用户批准委派子代理；在同一特性分支内按任务内联执行，并在每个任务完成后运行对应验证。步骤使用复选框（`- [ ]`）语法跟踪进度。

- **规格**：`docs/superpowers/specs/2026-09-20-phase-2a-adaptive-retrieval-design.md`（唯一事实源，本计划不复制其细节，只落执行顺序与验证）
- **路线图**：`docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md` §5.3（2A 开发任务与验收门）
- **分支**：`feat/phase2a-adaptive-retrieval`（基于 master `3ef3fe9`；不直接提交 `master`）

## 目标

在 Phase 1 Agent 运行时（L3）之上落地自适应检索核心（L3→L4）：Agent 通过结构化检索计划（`queryType` + `queries[]`）表达意图，可信服务端代码按类型映射固定策略档位并控制全部检索参数。**不引入独立 Planner 模型调用**；`KnowledgeSearchPort` 仍是 Chat 与 Agent 唯一检索边界。

## 架构与技术栈

- Java 21 · Spring Boot 4.1 · Spring AI 2.0 · Elasticsearch 9（BM25+KNN 混合、RRF）· DashScope reranker · 父子块（CHILD/PARENT）· H2 + JPA · AgentScope Java 2.x ReAct（infrastructure 适配器）
- 模块依赖不变：`api → common`；`session/document/chat/action → api`；`starter → 全部`；evaluation 模块**不依赖** agent 模块，只消费 `AgentAnswerEvaluationPort`
- 各业务域内四层方向不变：`interfaces → application → domain ← infrastructure`

## 计划内决策（规格未细说、此处定死）

1. **工具层截断上限**：`SearchDocsTool` 现有硬编码 `MAX_HITS=5` 在 Adaptive 关闭时原样保留；Adaptive 开启时改为按策略档位"合并后最终上限"截断（NONE 不检索 / SINGLE_HOP 5 / MULTI_HOP 8 / COMPARISON 8 / AGGREGATION 12 / TIME_SENSITIVE 8）。
2. **超时换算**：有效工具超时 = `toolTimeout × 子查询数`（1 条子查询 = 5s，与 Phase 1 完全一致；3 条 = 15s），仍受 runTimeout 硬约束。Adaptive 模式的 runTimeout 独立配置，初始 90s，首份真实评测 P95 出来后人工校准。两项均在 `AdaptiveRetrievalProperties`，启动时范围校验、非法即 fail fast。
3. **"第二轮只改写零命中部分"按语义约束验收**：自动化断言只覆盖计数类硬限制（每 Run ≤2 轮、第二轮计划数上限、重复子查询拒绝、单条子查询 1–200 字符）；改写的指向性由 Prompt 指引 + 第二轮 `RetrievalExecution` 溯源记录支撑**人工核验**，不写自动化断言。
4. **评测数据流**：evaluation 模块经 `AgentAnswerEvaluationPort.Result`（`fileagent-api`）取数，不依赖 `AgentRun`——因此自适应观测字段要在该 Port 的 `Result` 上扩展，由 `AgentAnswerEvaluationService` 从 `AgentRun.RetrievalExecution` 映射填充。

## 前置条件（任务 0）

- Phase 1 加固后的真实模型 `agent-v1` 回归**尚未运行**；报告现持久化到 `evaluation-results/`（已 gitignore，`mvn clean` 不再清掉）。
- `adaptive-v1` 需要比较/多跳语料：现 `agent-v1` 语料只有单一 `employee-handbook.md`，至少再准备 **2 份**含可比内容与跨文档多跳关系的评测文档（见任务 5）。

---

## 文件结构

新增（N）/ 修改（M）：

```
fileagent-api/src/main/java/com/demetrius/fileagent/api/
├── enums/RetrievalQueryType.java                          [N]
└── port/
    ├── KnowledgeSearchPort.java                           [M] SearchQuery+options、SearchOptions、SearchResult、searchDetailed
    └── AgentAnswerEvaluationPort.java                     [M] Result 增加可空 RetrievalObservation

fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/
├── KnowledgeSearchPortImpl.java                           [M] 请求级覆盖 + 二次校验 + SearchResult 组装 + rerank 降级码 + 父块开关
├── RrfFusion.java                                         [M] 加权重载（保留等权路径）
└── （同目录 test：KnowledgeSearchPortImplTest / RrfFusionTest 扩展）[M]

fileagent-agent/src/main/java/com/demetrius/fileagent/agent/
├── application/AgentAnswerEvaluationService.java          [M] RetrievalExecution → Result.RetrievalObservation
├── domain/
│   ├── run/AgentRun.java                                  [M] RetrievalExecution 列表 + 非法计划计数 + 两轮限制
│   └── service/AdaptiveRetrievalPolicy.java               [N] 纯 Java：queryType → 策略档位/数量约束（装配时注入配置，不依赖 Spring）
└── infrastructure/
    ├── config/AgentProperties.java                        [M] adaptiveRetrievalEnabled=false
    ├── config/AdaptiveRetrievalProperties.java            [N] 档位/上限/超时 + 启动校验
    ├── runtime/AgentScopeRuntimeConfiguration.java        [M] 仍只注册一个 search_docs（双模式在工具内部）
    └── tool/SearchDocsTool.java                           [M] 双模式 + 串行多查询 + 确定性合并 + 策略截断

fileagent-evaluation/
├── src/main/java/com/demetrius/fileagent/evaluation/
│   ├── AgentEvaluationObservation.java                    [M] 可空自适应字段
│   ├── AgentEvaluationRunner.java                         [M] 映射 + 新指标
│   ├── AgentEvaluationReport.java                         [M] 新指标字段
│   ├── AgentQualityGateEvaluator.java                     [M] adaptive-v1 门键
│   └── AgentEvaluationReportWriter.java                   [M] 渲染自适应指标
└── src/main/resources/evaluation/adaptive-v1/
    ├── cases/agent.jsonl                                  [N] 12 类场景（规格 §8）
    ├── corpus/*.md                                        [N] ≥2 份比较/多跳语料
    └── gate.json                                          [N] 四项强制门 =1.0；三项阈值留待 baseline

fileagent-starter/src/main/resources/application.yml       [M] 自适应开关占位
docs/TESTING.md                                            [M] adaptive-v1 评测口径
fileagent-evaluation/README.md                             [M] 数据集与指标说明
```

红线自检：新增配置不含任何密钥（`AdaptiveRetrievalProperties` 只有数值/布尔）；无新增外部 HTTP 端点（沿用既有 DashScope reranker 客户端）；无 SQL 改动。

---

## 任务 0（前置）：真实模型 agent-v1 基线回归

**文件**：无代码改动，只产出基线报告。

- [ ] 确认 JDK 21、Elasticsearch 运行中（`docker compose up -d elasticsearch`），`FILEAGENT_AI_API_KEY` 等环境变量已按 `application.yml` 占位符就位（真实值只走环境变量，不写入任何文件）。
- [ ] 启动主应用：`mvn -pl fileagent-starter -am spring-boot:run`。
- [ ] 另开终端运行：`sh fileagent-evaluation/scripts/run-agent-evaluation.sh`（默认 `agent-v1`）。
- [ ] 检查 `evaluation-results/` 下报告：`runSuccessRate`、`budgetComplianceRate`、`toolWhitelistPassRate` 达到 `agent-v1/gate.json` 门槛；把报告路径与关键数值记录到 PR 描述，作为 2A 回归基线。
- [ ] 若失败：先修 Phase 1 回归，不进入 2A 编码。

## 任务 1：fileagent-api 契约扩展

**文件**：`RetrievalQueryType.java`（N）、`KnowledgeSearchPort.java`（M）、`AgentAnswerEvaluationPort.java`（M）

- [x] 先写失败测试：`SearchQuery` 四参构造保留、五参含 `options`；`searchDetailed` 对无 `options` 查询返回 candidates/finalHits 相同、`appliedOptions=null`、`rerankApplied=false`。
- [x] 新增 `RetrievalQueryType` 枚举：`NONE / SINGLE_HOP / MULTI_HOP / COMPARISON / AGGREGATION / TIME_SENSITIVE`（NONE 仅用于 Run 级意图，**不允许**作为 `search_docs` 参数）。
- [x] `KnowledgeSearchPort`：`SearchQuery` 追加第 5 个可空组件 `SearchOptions options`（保留四参构造器）；新增嵌套 `SearchOptions`（strategyId、bm25TopK、knnTopK、knnCandidates、bm25Weight、knnWeight、finalTopK、rerankEnabled、parentExpansionEnabled）；新增嵌套 `SearchResult`（candidates、finalHits、appliedOptions、rerankApplied、fallbackCode）；新增 `default SearchResult searchDetailed(SearchQuery)`。
- [x] `AgentAnswerEvaluationPort.Result` 追加可空嵌套 `RetrievalObservation`（queryType、strategyId、plannedQueryCount、executedQueryCount、perQueryHitCounts、candidateChunkIds、finalChunkIds、rerankRequested、rerankApplied、fallbackCode），非自适应运行为 `null`；同步更新现有构造调用点。
- [x] 验证：`mvn -pl fileagent-api -am test`。（73c787b 提交）

## 任务 2：fileagent-document 检索执行扩展

**文件**：`KnowledgeSearchPortImpl.java`（M）、`RrfFusion.java`（M）及其测试

- [x] 先写失败测试（两条主线）：
  - **无 `options` 等价回归**：与现行为产生完全相同的 ES 请求参数与返回（固定 BM25 字段权重、固定 TopK、rerank 总尝试、父块总展开），防止 Phase 1 行为漂移。
  - **有 `options` 覆盖**：bm25TopK/knnTopK/knnCandidates/双权重/finalTopK/rerank 开关/父块开关逐一生效。
- [x] `RrfFusion` 增加加权重载 `fuse(bm25Hits, knnHits, rankConstant, bm25Weight, knnWeight)`；等权路径保留给固定 RAG。
- [x] `KnowledgeSearchPortImpl` 实现 `searchDetailed`：请求级参数**二次校验**（服务端上限内，越界抛 `BizException`）；组装 `SearchResult`（candidates=融合后有界列表，finalHits=截断/rerank/父块展开后，`appliedOptions` 回填，`rerankApplied` 记录实际值）。
- [x] rerank 语义：`options` 显式关闭 → 走加权 RRF，`fallbackCode=RERANK_DISABLED_BY_POLICY`；rerank 调用基础设施失败 → 降级加权 RRF，`fallbackCode=RERANK_FAILED`。父块关闭 → 直接返回 CHILD 命中。
- [x] 验证：`mvn -pl fileagent-document -am test`。（2e71309 提交）

## 任务 3：fileagent-agent 策略与工具双模式

**文件**：`AgentProperties.java`（M）、`AdaptiveRetrievalProperties.java`（N）、`AdaptiveRetrievalPolicy.java`（N）、`SearchDocsTool.java`（M）、`AgentScopeRuntimeConfiguration.java`（M）

- [x] 先写失败测试：
  - `AdaptiveRetrievalPolicy`：六类 queryType → 规格 §4 的档位与查询数量约束一一对应；未知类型抛 `BizException`。
  - `SearchDocsTool`（Adaptive 开）：NONE 参数被拒；单条子查询 >200 字符被拒；>3 条子查询被拒；规范化后重复子查询被拒；多子查询按输入顺序**串行**执行；合并按 规格 §6（chunkId 去重、每查询溯源保留、按分数截断到档位上限）；零命中返回"缺失子查询汇总"而非失败。
- [x] `AgentProperties` 增加 `adaptiveRetrievalEnabled = false`（绑定 `fileagent.agent.adaptive-retrieval-enabled`，环境变量 `FILEAGENT_AGENT_ADAPTIVE_RETRIEVAL_ENABLED`）。
- [x] `AdaptiveRetrievalProperties`（前缀 `fileagent.agent.adaptive-retrieval`）：五类档位参数、`runTimeout`（初始 90s）、工具超时换算系数；构造期范围校验（TopK ≥1、权重 ∈(0,2]、runTimeout > toolTimeout×3），非法即启动失败。
- [x] `SearchDocsTool` 双模式：关闭 → Phase 1 单查询路径逐字节保留（含 `MAX_HITS=5`）；开启 → 结构化 Schema（`queryType` + `queries[]`），执行前按档位换算有效工具超时（`toolTimeout × 子查询数`）；基础设施异常时取消剩余子查询、`recordToolFailure` 并返回现有提示文案。
- [x] `AgentScopeRuntimeConfiguration`：仍只注册**一个** `SearchDocsTool` bean（双模式在工具内部切换）；Adaptive 模式下系统提示增加结构化输入与两轮改写指引（含决策 3 的改写指向性要求）。
- [x] 验证：`mvn -pl fileagent-agent -am test`。（68 个测试全绿）

## 任务 4：AgentRun 检索执行记录与非法计划终止

**文件**：`AgentRun.java`（M）

- [x] 先写失败测试：首次非法计划只记 `invalidPlanCount` 并消耗一个 Step；第二次非法计划 → Run 终止，`failureCode=AGENT_RETRIEVAL_PLAN_INVALID`；正常两轮检索（第二轮仅当首轮部分零命中）通过；第三轮检索被拒。
- [x] `AgentRun` 增加有界 `RetrievalExecution` 列表（queryType、strategyId、plannedQueryCount、executedQueries、perQueryHitCount、candidateChunkIds、finalChunkIds、rerankRequested、rerankApplied、fallbackCode）；非法计划计数；`AGENT_RETRIEVAL_PLAN_INVALID` 失败码常量；检索轮数计数（≤2）。
- [x] 新常量加入与 `KNOWLEDGE_SEARCH_FAILURE_CODE` 相邻位置，风格一致。
- [x] 验证：`mvn -pl fileagent-agent -am test`。（77 个测试全绿）
- [x] 偏差说明：按规格 §5/§6/§7 补齐计划外接线——`SearchDocsTool` 增加 `callStructured` 入口（第三轮拒绝、非法计划计数、溯源记录、候选/最终集合拆分）与 `AgentScopeRuntimeAdapter.mapRetrievalObservation` 评测映射；Step 计数统一由运行时 `TOOL_CALL_START` 消耗，领域层不再重复计数。

## 任务 5：fileagent-evaluation 扩展与 adaptive-v1 数据集

**文件**：`AgentEvaluationObservation.java`（M）、`AgentEvaluationRunner.java`（M）、`AgentEvaluationReport.java`（M）、`AgentQualityGateEvaluator.java`（M）、`AgentEvaluationReportWriter.java`（M）、`adaptive-v1/` 资源（N）

- [x] 先写失败测试（以固定桩 `AgentAnswerEvaluationPort` 驱动）：
  - 新指标计算：`queryTypeAccuracy`、`unnecessaryRetrievalRate`、`queryCountComplianceRate`、`strategyComplianceRate`、`subQuestionCoverage`。
  - `queryCountComplianceRate` 覆盖决策 3 的自动化口径：≤2 轮、重复子查询拒绝、计划数上限。
  - 非自适应 Run（`RetrievalObservation=null`）不参与自适应指标分母，报告不产生 NaN。
- [x] `Observation` 追加可空自适应字段（null 安全）；Runner 从 `Result.retrieval()` 映射。
- [x] `adaptive-v1/cases/agent.jsonl`：12 类场景（规格 §8）——无需检索、单跳、多跳、比较、聚合、时间敏感、部分零命中、全部零命中、重复子查询、非法计划、reranker 降级、检索基础设施失败。
- [x] `adaptive-v1/corpus/`：≥2 份含可比条目（同一维度多对象对比）与跨文档多跳关系的 Markdown 语料；题目断言与之对齐。
- [x] `adaptive-v1/gate.json`：`queryCountComplianceRate=1.0`、`strategyComplianceRate=1.0`、`toolWhitelistPassRate=1.0`、`budgetComplianceRate=1.0`；`queryTypeAccuracy`、`subQuestionCoverage`、收益类阈值**留空**，待首份真实 baseline 人工确认后填入（在此之前只展示、不断言）。
- [x] 验证：`mvn -pl fileagent-evaluation -am test`。（43 个测试全绿）
- [x] 偏差说明：① `EvaluationCase.java` 超出本任务文件清单——规格 §8 的人工标注要求 `Expected` 增加 `expectedQueryType`/`expectedSubQuestions` 字段（含枚举校验与 4/5 参兼容构造），测试与数据集都要用；② 计划外补充 `EvaluationDatasetContractTest` 一条 adaptive-v1 契约测试（12 道题可解析、五项强制门齐备、待定阈值未断言）；③ 指标口径细化：失败 Run 也参与规划类分母（规划是运行时行为合规指标），`subQuestionCoverage` 现阶段是数量代理（计划子查询数/标注必要子问题数），语义覆盖待真实评测人工核验。

## 任务 6：文档同步、完整构建与真实 adaptive-v1 评测

**文件**：`application.yml`（M）、`docs/TESTING.md`（M）、`fileagent-evaluation/README.md`（M）

- [x] `application.yml` 增加 `fileagent.agent.adaptive-retrieval-enabled: ${FILEAGENT_AGENT_ADAPTIVE_RETRIEVAL_ENABLED:false}`（默认关；agent 总开关保持关闭，固定 `/chat` 不动）。
- [x] `docs/TESTING.md` 补 adaptive-v1 口径（12 类场景、四项强制门、三项待定阈值）；`fileagent-evaluation/README.md` 补数据集与指标。规格 §11：对外公开契约不变，`docs/API.md` 不需改（若实现中发现公开端点有变，再同步）。
- [x] 全量构建：`mvn clean test`（Windows 本机命令，无路径前缀）。
- [x] 偏差说明：计划外扩展 `fileagent-evaluation/scripts/upload-corpus.sh`——现有脚本硬编码 v1 语料目录与 `ragName=fileagent-eval-v1`，无法上传 adaptive-v1 语料；改为按数据集版本参数（默认 `v1` 行为不变，`adaptive-v1` 上传自适应语料），并同步 `fileagent-evaluation/README.md` 的上传命令说明。这是"真实评测"前置的脚本准备，不涉及 Java 代码与公开契约。
- [ ] 真实评测：`sh fileagent-evaluation/scripts/run-agent-evaluation.sh`（agent-v1 回归，对比任务 0 基线）+ `FILEAGENT_EVALUATION_DATASET_VERSION=adaptive-v1 sh fileagent-evaluation/scripts/run-agent-evaluation.sh`。
- [ ] 人工核验：四项强制门全过；agent-v1 相对任务 0 基线无 >0.1 回归；"第二轮只改写零命中部分"按第二轮 `RetrievalExecution` 溯源记录抽查确认；据此人工确定 `queryTypeAccuracy`/`subQuestionCoverage`/收益阈值并回填 `gate.json`。
- [ ] 回滚演练：仅翻转 `adaptive-retrieval-enabled` 子开关复跑一次，确认回到 Phase 1 行为（无数据/索引变更）。

---

## 提交约定

- 分支 `feat/phase2a-adaptive-retrieval`；Conventional Commits，按任务分批：
  - `feat(api): 扩展检索契约支持自适应策略`
  - `feat(document): 支持请求级检索参数与加权 RRF`
  - `feat(agent): 接入自适应检索策略与结构化检索工具`
  - `feat(evaluation): 新增 adaptive-v1 数据集与自适应指标`
  - `docs(evaluation): 补充 adaptive-v1 评测口径`
- 只 `git add` 本次相关文件；`storage/`、`evaluation-results/`、任何密钥不得入库。
