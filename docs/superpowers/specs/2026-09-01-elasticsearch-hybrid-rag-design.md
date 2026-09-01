# Elasticsearch 企业级混合检索设计

- 日期：2026-09-01
- 状态：已确认
- 分支：`feat/m2-structured-rag-retrieval`

## 1. 目标

将当前基于 `SimpleVectorStore` 和 JSON 文件的知识检索替换为 Elasticsearch，保留自主实现文档解析、分块、Embedding、检索编排和回答生成，以学习企业级 RAG 的完整链路。

本阶段必须实现：

1. 通用文档结构和元数据，不为 OKR、Objective、KR、人员或年份编写业务识别规则。
2. Elasticsearch 持久化文档正文、通用元数据和通义 Embedding 向量。
3. BM25 全文检索与向量 KNN 检索双路召回。
4. 应用层 RRF 排名融合、结果去重和元数据过滤。
5. 列举型问题的检索范围扩展，避免固定 `topK` 导致内容不完整。
6. 在 RRF 之后保留明确的 Reranker 延后接入标记，本阶段不调用重排模型。

## 2. 不在本阶段实现

- Reranker 模型调用。
- OCR、图片理解和扫描件解析。
- 多租户、部门和用户级权限体系。
- Elasticsearch 集群部署、容量规划和灾备自动化。
- 旧 `vectorstore.json` 数据迁移工具。
- AgentScope、ReAct 和工具调用循环。

## 3. 技术决策

### 3.1 Elasticsearch 访问方式

使用 Elasticsearch 官方 Java Client完成索引和检索，不把 Spring AI `ElasticsearchVectorStore` 作为核心检索入口。

原因：本项目需要同时控制 BM25、KNN、字段过滤、候选数量和 RRF。统一 `VectorStore` 抽象适合向量相似度检索，但不能清晰表达完整混合检索链路。

Spring AI 仅保留 `EmbeddingModel`，继续调用通义 `text-embedding-v4` 生成 1024 维向量。Elasticsearch Java Client 与 Elasticsearch 服务端固定相同主版本，Docker 镜像不得使用 `latest`。

### 3.2 RRF 位置

BM25 与 KNN 分别请求 Elasticsearch，各自召回候选结果；Java 应用按照稳定的 RRF 公式融合：

```text
score(document) = sum(1 / (rankConstant + rank))
```

默认 `rankConstant=60`。应用层融合不依赖 Elasticsearch 服务端 RRF 的版本或授权，并可用纯单元测试验证。

### 3.3 Reranker 延后接入

RRF 完成后、最终裁剪前保留下面的代码标记：

```java
// TODO: 接入 Reranker，对混合召回结果进行语义重排
```

本阶段不创建空的 Reranker 接口，不增加没有行为的抽象。

## 4. 模块与边界

### 4.1 fileagent-api

保留 `KnowledgeSearchPort` 作为 chat 域访问知识检索的唯一跨域接口。

`SearchQuery` 只表达通用检索语义：

- `text`：独立检索问题。
- `answerMode`：`SINGLE` 或 `LIST_ALL`。
- `ragName`、`knowledgeTag`、`fileId`：可选的平台级过滤条件。

删除 `year`、`person` 和 `contentType`。这些字段来自特定业务语义，不属于通用知识检索契约。

`KnowledgeHit` 增加 `chunkId`、`fileId`、`sheetName`、`sectionId`、`chunkIndex` 和融合分数，供来源展示、去重、范围扩展和诊断使用；不暴露 Elasticsearch SDK 类型。

### 4.2 fileagent-document

文档应用服务不再直接依赖 `SimpleVectorStore`。在 document 域内部定义一个最小索引接口，负责批量写入知识片段；Elasticsearch 适配器实现索引接口以及 `KnowledgeSearchPort`。

职责保持为：

```text
DocumentParser -> ParsedChunk -> EmbeddingModel -> KnowledgeIndex -> Elasticsearch
```

索引失败时文件状态为 `FAILED`；全部片段成功写入后才标记为 `SUCCESS`。索引文档使用确定性 ID `fileId:chunkIndex`，重试写入覆盖同一片段。Bulk 响应中任一条失败都视为本次索引失败，并按 `fileId` 清理本次可能产生的残留片段。

### 4.3 fileagent-chat

`RagRetrievalPlanner` 只负责：

- 判断是否需要知识检索。
- 根据最近对话生成独立检索问题。
- 判断回答模式是 `SINGLE` 还是 `LIST_ALL`。

删除 Objective、KR、年份和人员提取正则。业务词语保留在自然语言查询中，由 BM25 和向量检索理解。

`ChatAppServiceImpl` 保持原有流式回答主链路，只把通用 `SearchQuery` 交给 `KnowledgeSearchPort`，不感知 Elasticsearch。

## 5. 通用解析与分块

### 5.1 所有格式的通用元数据

每个 `ParsedChunk` 至少携带：

- `sourceType`
- `sectionId`
- 格式相关的位置字段，例如 `sheetName`、`rowIndex` 或页码

入索引前统一补充：

- `fileId`
- `ragName`
- `knowledgeTag`
- `filename`
- `chunkIndex`

不从文件名猜测人员、年份和文档业务类型。

### 5.2 Excel

Excel 解析遵循通用表格规则：

1. 跳过隐藏 Sheet 和完全空行，不按 Sheet 名称判断业务类型。
2. 根据连续非空区域划分 `sectionId`，空行作为区域边界。
3. 在区域前部通过单元格覆盖率、文本比例及后续行列覆盖情况选择表头。
4. 数据行序列化为 `列名: 值`；多行表头按列合并为完整列名。
5. 无法可靠识别表头时使用 Excel 列坐标作为字段名，确保内容不丢失。
6. 保留 Sheet 名、原始行号、区域编号和原始顺序。

删除 Objective/KR 正则、OKR Sheet 判断、示例 Sheet 判断和 `XXXX` 占位符过滤。是否属于有效业务内容由后续检索排序决定，不由通用解析器猜测。

## 6. Elasticsearch 索引

使用版本化物理索引和稳定别名：

```text
物理索引：fileagent-knowledge-v1
读写别名：fileagent-knowledge
```

核心字段：

| 字段 | 类型 | 用途 |
|---|---|---|
| `chunkId` | keyword | 去重和确定性写入 |
| `fileId` | keyword | 文件过滤和清理 |
| `ragName` | keyword + text | 知识库过滤与召回 |
| `knowledgeTag` | keyword + text | 标签过滤与召回 |
| `filename` | keyword + text | 文件精确过滤与全文召回 |
| `sourceType` | keyword | 文件类型过滤 |
| `sheetName` | keyword + text | 表格范围过滤与召回 |
| `sectionId` | keyword | 区域扩展 |
| `rowIndex` | integer | 表格顺序 |
| `chunkIndex` | integer | 全文档顺序 |
| `content` | text | BM25 全文检索 |
| `embedding` | dense_vector, 1024, cosine | KNN 检索 |
| `metadata` | flattened | 非核心扩展元数据 |

中文全文字段使用 Elasticsearch 内建分析器作为第一版基线，并通过多字段保留精确值。分析器配置封装在索引模板中，后续更换中文分析策略时通过新版本索引和别名切换完成，不直接修改既有字段映射。

## 7. 检索流程

### 7.1 双路召回

对同一个独立查询：

1. 使用 `multi_match` 对 `content`、`filename`、`sheetName`、`ragName` 和 `knowledgeTag` 执行 BM25，默认召回 50 条。
2. 使用通义 Embedding 生成查询向量，执行 KNN，默认召回 50 条、候选 100 条。
3. 两路检索使用相同的平台级过滤条件。
4. 按 `chunkId` 执行应用层 RRF 融合和去重。
5. 记录两路排名、融合分数、文件和片段位置，便于调试召回质量。

### 7.2 普通问题

普通问题从融合结果中保留前 12 条，并对高分命中补充同一 `sectionId` 内相邻片段。扩展后按照来源和位置去重，在统一上下文预算内裁剪。

### 7.3 列举型问题

`LIST_ALL` 不直接把 `topK` 当成完整答案：

1. 使用混合检索定位高置信度的文件和 `sectionId`。
2. 按融合分数选择目标区域。
3. 使用 `fileId + sectionId` 精确查询该区域全部片段。
4. 按 `rowIndex`、`chunkIndex` 排序后交给回答模型。
5. 超过上下文预算时明确标记资料被截断，不宣称已经完整列举。

该策略解决“正确内容存在，但未进入固定 topK”的问题，同时不要求系统理解 Objective、KR 等业务概念。

## 8. 配置与本地部署

新增 Elasticsearch 连接与检索配置，所有账号密码使用环境变量：

- Elasticsearch URL、用户名、密码。
- 索引别名和物理索引版本。
- BM25 召回数、KNN 召回数、KNN 候选数。
- RRF 常量、最终结果数和上下文预算。

仓库提供本地 Elasticsearch 与 Kibana Compose 配置，用于学习、查看索引和调试查询。生产环境使用外部集群或托管服务，不依赖应用容器内置 Elasticsearch。

`storage/vectorstore.json` 不删除但不再读取。切换后通过重新上传现有文档建立 Elasticsearch 索引，不实现一次性迁移程序。

## 9. 错误处理与可观测性

- Elasticsearch 连接或查询失败：返回现有知识检索错误事件，不静默降级为模型通用知识。
- Embedding 失败：索引或查询失败，日志区分模型调用与 Elasticsearch 调用。
- Bulk 部分失败：记录失败的 `chunkId` 和原因，清理该 `fileId` 的片段并将文件标记为 `FAILED`。
- 查询日志：记录查询文本、过滤条件、BM25/KNN 命中数、RRF 最终命中数和耗时，不打印向量或文档全文。
- 索引日志：记录文件、片段数、成功数、失败数和耗时，不记录密钥。

## 10. 测试与验收

### 10.1 单元测试

- Excel 多行表头、无表头、空行分区、隐藏 Sheet 和公式单元格。
- 索引文档字段映射和确定性 ID。
- BM25/KNN 查询参数及通用过滤条件。
- RRF 融合、同分稳定排序和去重。
- 普通问题邻近片段扩展。
- `LIST_ALL` 区域完整读取和上下文预算截断。
- Planner 不再依赖 OKR 业务正则。

### 10.2 集成测试

使用 Testcontainers 启动与生产主版本一致的 Elasticsearch，验证：

- 索引初始化和别名。
- Bulk 写入后 BM25、KNN、过滤和顺序读取。
- 重复索引覆盖。
- Bulk 失败处理。

没有 Docker 的环境自动跳过容器集成测试，但单元测试和项目编译必须执行。本机当前没有 `mvn` 或 Maven Wrapper，实施完成时需要如实标记无法在本机执行的 Maven 验证，并提供应运行的命令。

### 10.3 行为验收

使用同一批文档验证：

- 精确名称、年份和编号能够通过 BM25 命中。
- 同义表达能够通过 KNN 命中。
- 混合结果包含两路独有的有效内容。
- “有哪些/全部”能按目标表格区域返回完整、有序的内容。
- 代码中不存在 Objective、KR、OKR 人名等业务识别规则。

## 11. 实施边界

保留当前工作区中的既有改动，只改造与本设计重叠的文件，不执行回退或清理命令。新增 Java 文件遵循项目注释规范并包含：

```java
/**
 * @author raosaijie
 */
```

