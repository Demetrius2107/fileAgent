# RAG 评测模块

`fileagent-evaluation` 提供版本化评测集、真实检索与回答采集、评分、报告和质量门禁。它通过 API Port 复用现有 BM25 + KNN + RRF + rerank + Chat 回答链路，并使用 DeepSeek V4 Pro 进行回答语义评判，不复制业务检索逻辑，也不会创建或写入用户会话。

## 目录

```text
src/main/resources/evaluation/v1/
├── corpus/                 固定评测语料
├── cases/                  JSONL 题库，可继续增加文件和题目
└── gate.json               绝对阈值与 baseline 回退阈值
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
```

输出文件：

- `target/evaluation/<版本>/<UTC运行时间>/observations.jsonl`：逐题检索、回答与来源原始结果。
- `target/evaluation/<版本>/<UTC运行时间>/report.json`：供程序和 CI 比较。
- `target/evaluation/<版本>/<UTC运行时间>/report.md`：供人工阅读，包含指标解释，并按未通过门禁的指标展示最多 3 道代表问题。

每次运行会创建新目录；同一秒内重复执行会自动追加 `-2`、`-3`，不会覆盖历史报告。可用 `FILEAGENT_EVALUATION_RUN_ID` 设置便于识别的运行名，例如 `release-2026-09-09`。

v1 每次运行会真实生成 30 个回答，并逐题调用 `deepseek-v4-pro` 评判，因此会产生 30 次回答调用和 30 次 Judge 调用。Judge 复用部署已有的 `FILEAGENT_CHAT_API_KEY` 与 DeepSeek 端点，不需要新增 API Key。评测接口是同步批量执行，反向代理的请求超时时间应覆盖整批运行耗时。

服务端会自动把 Judge 模型、当前实际生效的 Embedding 模型、向量维度、索引、BM25/KNN/RRF 参数和 reranker 配置写入报告，不记录 API Key。

确认首份真实报告有效后，把 `report.json` 作为团队认可的 baseline 存入版本化目录。后续比较时设置：

```bash
export FILEAGENT_EVALUATION_BASELINE='fileagent-evaluation/src/main/resources/evaluation/v1/baselines/baseline.json'
./fileagent-evaluation/scripts/run-evaluation.sh
```

旧版只包含检索指标的 baseline 缺少回答和来源指标，升级到端到端评测后应重新生成并确认，不能直接作为新基线。

门禁失败时进程返回失败。`gate.json` 当前是启动阈值；首次真实运行后，应根据业务验收结果收紧，不能为了让 CI 通过而降低。

Markdown 报告不会展开全部题目。代表问题先按单题分数从低到高选择，同分时优先覆盖不同题型；每题展示期望结果、实际回答、来源和 Top 1 检索片段。完整逐题结果始终保留在 `report.json` 和 `observations.jsonl`。

可通过 `FILEAGENT_EVALUATION_DATASET_VERSION` 选择部署包内的数据集版本，通过 `FILEAGENT_EVALUATION_OUTPUT` 修改本地报告根目录。脚本运行依赖 `curl` 和 `jq`。

## 指标边界

- 检索：`HitRate@K`、`Recall@K`、`Precision@K`、`MRR`、`nDCG@K`，重复命中不会重复计算相关来源。
- 无答案：`noAnswerEmptyRetrieval` 衡量检索是否返回空结果。
- 回答：`deepseek-v4-pro` 按语义判断期望事实覆盖、错误事实、回答/拒答决策以及无依据内容，不做答案字符串包含匹配。
- 无依据内容：`unsupportedClaimSafety` 衡量回答是否避免输出证据不支持的具体结论、数值或规则。
- 可审计性：Judge 返回逐项理由并写入 observation；Markdown 代表问题会展示对应指标的 Judge 理由。
- 扩展 Judge：observation 的 `judgeScores` 仍可承载 `faithfulness` 等 0～1 的扩展分数，报告会输出为 `judge.faithfulness`。

普通 `mvn verify` 会运行评测框架、接口鉴权和 30 题 Schema 契约测试，不调用真实模型。真实检索回归由 CI 调用已部署的评测实例，不在 CI 内重复配置模型供应商密钥。
