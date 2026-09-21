# RAG 评测模块

`fileagent-evaluation` 提供版本化评测集、真实检索与回答采集、评分、报告和质量门禁。它通过 API Port 复用现有 BM25 + KNN + RRF + rerank + Chat 回答链路，并使用 DeepSeek V4 Pro 进行回答语义评判，不复制业务检索逻辑，也不会创建或写入用户会话。

## 目录

```text
src/main/resources/evaluation/v1/
├── corpus/                 固定评测语料
├── cases/                  JSONL 题库，可继续增加文件和题目
└── gate.json               绝对阈值与 baseline 回退阈值

src/main/resources/evaluation/adaptive-v1/
├── corpus/                 2 份含可比条目与跨文档多跳关系的 Markdown 语料
├── cases/agent.jsonl       12 类自适应检索场景
└── gate.json               四项强制门 =1.0；自适应收益阈值待 baseline 人工确认
```

v1 首批包含 7 份语料和 30 道题，覆盖事实、语义、CSV 表格、跨文件比较、版本冲突、无答案、提示注入和错误前提。

## 一道题的格式

```json
{
  "schemaVersion": "1.0",
  "id": "fact-001",
  "category": "FACT",
  "tags": ["policy", "employee"],
  "question": "员工入职第一年有多少天年假？",
  "history": [],
  "filters": {
    "ragName": "fileagent-eval-v1",
    "knowledgeTag": "baseline",
    "fileId": null
  },
  "expected": {
    "shouldAnswer": true,
    "relevantSources": [
      {"filename": "employee-handbook.md", "relevance": 3}
    ],
    "requiredFacts": ["年假 5 天"],
    "forbiddenFacts": []
  }
}
```

可选字段都有默认值。来源可按 `chunkId`、`fileId`、`filename`、`sheetName`、`sectionId`、`chunkIndex` 的任意组合定位；指定的字段必须全部匹配。普通文档默认使用文件名，CSV 表格题增加稳定的行 `chunkIndex`，避免命中同文件无关行也被算作正确。

后续加题只需在 `cases/` 任意 JSONL 文件追加记录；新增语料时把文件放进 `corpus/` 并同步修改 `scripts/upload-corpus.sh`。只有新增指标、来源定位方式或多模态数据结构时，才需要升级 Schema 和 Java 代码。

## 运行真实检索 baseline

评测由已经部署好的 FileAgent 执行，使用该实例当前生效的 Chat、Embedding、Elasticsearch 和可选 reranker 配置。脚本不会启动新应用，也不接收任何模型 API Key。

部署 FileAgent 时额外开启内部评测接口并设置独立 Token：

```bash
export FILEAGENT_EVALUATION_ENABLED=true
export FILEAGENT_EVALUATION_TOKEN='<随机长 Token，不写入仓库>'
```

评测接口默认关闭；开启但未配置 Token 时，应用拒绝启动。应在网关或防火墙上限制 `/internal/evaluation/**`，不要把它作为普通公开接口。

将固定语料上传到同一个部署实例，然后执行评测：

```bash
export FILEAGENT_BASE_URL='https://fileagent.example.com'
export FILEAGENT_EVALUATION_TOKEN='<与部署实例一致>'

# 首次运行或重建独立评测知识库时执行一次
./fileagent-evaluation/scripts/upload-corpus.sh

# 调用已部署实例，下载评测结果
./fileagent-evaluation/scripts/run-evaluation.sh

# 调用已部署实例，真实运行 AgentScope、知识库和 Judge
./fileagent-evaluation/scripts/run-agent-evaluation.sh
```

输出文件：

- `evaluation-results/<版本>/<UTC运行时间>/observations.jsonl`：逐题检索、回答、来源和 Judge 原始响应；单条 Judge 原始响应最多保留 4000 个字符。
- `evaluation-results/<版本>/<UTC运行时间>/report.json`：供程序和 CI 比较。
- `evaluation-results/<版本>/<UTC运行时间>/report.md`：供人工阅读，包含指标解释，并按未通过门禁的指标展示最多 3 道代表问题。

每次运行会创建新目录；同一秒内重复执行会自动追加 `-2`、`-3`，不会覆盖历史报告。可用 `FILEAGENT_EVALUATION_RUN_ID` 设置便于识别的运行名，例如 `release-2026-09-09`。

Agent 评测默认使用 `agent-v1`，输出到 `evaluation-results/agent-v1/<UTC运行时间>/`。它通过 `/internal/evaluation/agent/run` 真实调用部署实例的 AgentScope Runtime、当前知识库和 Judge；不需要另行配置聊天、向量或 reranker 的 API Key。`GENERAL_KNOWLEDGE` 题允许直接回答通用知识且无需引用，`KNOWLEDGE_BASED` 题才要求以本次检索证据回答，`REFUSE` 题仅用于安全越界请求。当前 `agent-v1` 有 8 题，每次会产生最多 8 次 Agent 调用和 8 次 Judge 调用。

Agent 报告中的 `agent.runSuccessRate` 衡量 Run 是否以 `SUCCEEDED` 结束；非成功 Run 不调用 Judge，但仍保留回答、检索文件、引用文件、终态和失败码。`agent.citationCoverageRate` 衡量成功的知识库题是否至少引用一个本次检索命中的文件，`agent.citationValidityRate` 衡量已经写出的引用是否全部来自本次检索结果。通用知识题、拒答题和失败 Run 的引用状态为 `NOT_APPLICABLE`。

## 自适应检索评测（adaptive-v1）

`adaptive-v1` 评测 Phase 2A 自适应检索：Agent 通过 `search_docs` 的结构化入参声明查询类型，服务端按类型映射固定策略档位。开启部署实例的 `FILEAGENT_AGENT_ADAPTIVE_RETRIEVAL_ENABLED=true` 后运行：

```bash
FILEAGENT_EVALUATION_DATASET_VERSION=adaptive-v1 ./fileagent-evaluation/scripts/run-agent-evaluation.sh
```

数据集包含 12 道题，覆盖无需检索、单跳、多跳、比较、聚合、时间敏感、部分零命中、全部零命中、重复子查询、非法计划、reranker 降级和检索基础设施失败。语料为 `department-standards-2026.md`（三部门多维度可比条目，支撑比较与聚合）与 `travel-policy-2026.md`（版本变化内容，并以《部门标准》互引支撑跨文档多跳）；上传语料时使用 `ragName=fileagent-eval-adaptive-v1`。

每道题在 `expected.expectedQueryType` 标注正确规划器应声明的查询类型，在 `expected.expectedSubQuestions` 标注多跳/比较题的必要子问题。自适应指标及分母：

- `adaptive.queryTypeAccuracy`：实际查询类型与标注一致的比例；分母是标注了预期类型的题，失败 Run 也参与（规划是运行时受控行为）。
- `adaptive.unnecessaryRetrievalRate`：**越低越好**；无需检索题实际执行结构化检索或调用知识工具的比例。
- `adaptive.queryCountComplianceRate`：计划子查询数 ∈ [1,3]、单次 Run 的 `search_docs` ≤2 轮、执行数 ≤ 计划数且未因非法计划终止的 Run 比例。
- `adaptive.strategyComplianceRate`：实际策略档位来自服务端允许值且与查询类型一致的 Run 比例。
- `adaptive.subQuestionCoverage`：标注必要子问题被计划覆盖的比例（现阶段为数量代理，语义覆盖需人工核验）。

未执行结构化检索的 Run 不参与自适应指标；Markdown 报告会展示自适应指标与分母口径。`gate.json` 当前只强制 `adaptive.queryCountComplianceRate=1.0`、`adaptive.strategyComplianceRate=1.0`、`agent.toolWhitelistPassRate=1.0`、`agent.budgetComplianceRate=1.0`；`queryTypeAccuracy`、`subQuestionCoverage` 与多跳/比较收益阈值须在首份真实 baseline 经人工确认后填入，在此之前只展示、不断言。

普通 RAG 的 `v1` 每次运行会真实生成 30 个回答，并逐题调用 `deepseek-v4-pro` 评判，因此会产生 30 次回答调用和 30 次 Judge 调用。Judge 复用部署已有的 `FILEAGENT_CHAT_API_KEY` 与 DeepSeek 端点，不需要新增 API Key。评测接口是同步批量执行，反向代理的请求超时时间应覆盖整批运行耗时。

服务端会自动把 Judge 模型、当前实际生效的 Embedding 模型、向量维度、索引、BM25/KNN/RRF 参数和 reranker 配置写入报告，不记录 API Key。

确认首份真实报告有效后，把 `report.json` 作为团队认可的 baseline 存入版本化目录。后续比较时设置：

```bash
export FILEAGENT_EVALUATION_BASELINE='fileagent-evaluation/src/main/resources/evaluation/v1/baselines/baseline.json'
./fileagent-evaluation/scripts/run-evaluation.sh
```

旧版只包含检索指标的 baseline 缺少回答和来源指标，升级到端到端评测后应重新生成并确认，不能直接作为新基线。

回答提示会要求每个基于资料的事实使用 `[来源：文件名]` 标注。评测中的引用指标只计算回答正文实际标注、且本次检索确实返回的文件名，不会把全部检索候选误算为引用。

门禁失败时进程返回失败。`gate.json` 当前是启动阈值；首次真实运行后，应根据业务验收结果收紧，不能为了让 CI 通过而降低。

Markdown 报告不会展开全部题目。代表问题先按单题分数从低到高选择，同分时优先覆盖不同题型；每题展示期望结果、实际回答、来源和 Top 1 检索片段。完整逐题结果始终保留在 `report.json` 和 `observations.jsonl`。

可通过 `FILEAGENT_EVALUATION_DATASET_VERSION` 选择部署包内的数据集版本，通过 `FILEAGENT_EVALUATION_OUTPUT` 修改本地报告根目录。默认结果目录不属于 Maven `target`，执行 `mvn clean` 不会清理历史报告。脚本运行依赖 `curl` 和 `jq`。

## 指标边界

- 检索：`HitRate@K`、`Recall@K`、`Precision@K`、`MRR`、`nDCG@K`，重复命中不会重复计算相关来源。
- 无答案：`noAnswerEmptyRetrieval` 衡量检索是否返回空结果。
- 回答：`deepseek-v4-pro` 按语义判断期望事实覆盖、错误事实、回答/拒答决策以及无依据内容，不做答案字符串包含匹配。
- 无依据内容：`unsupportedClaimSafety` 衡量回答是否避免输出证据不支持的具体结论、数值或规则。
- 可审计性：Judge 返回逐项理由并写入 observation；`hasUnsupportedClaims=false` 时允许省略 `unsupportedClaimsReason`，为 `true` 时原因必须非空。Markdown 代表问题会展示已有的 Judge 理由。
- 失败诊断：Judge 格式校验失败时仍保留已经生成的回答、检索结果和引用，并在 observation 的 `judgeRawResponse` 中保存受控长度的模型原始响应。
- 扩展 Judge：observation 的 `judgeScores` 仍可承载 `faithfulness` 等 0～1 的扩展分数，报告会输出为 `judge.faithfulness`。

普通 `mvn verify` 会运行评测框架、接口鉴权和 30 题 Schema 契约测试，不调用真实模型。真实检索回归由 CI 调用已部署的评测实例，不在 CI 内重复配置模型供应商密钥。
