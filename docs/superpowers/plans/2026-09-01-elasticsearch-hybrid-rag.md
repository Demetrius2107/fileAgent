# Elasticsearch 企业级混合 RAG 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:executing-plans 在当前会话逐任务实现本计划。仓库规则未授权子代理或 worktree，因此不得使用 subagent-driven-development 或创建额外 worktree。

**目标：** 用 Elasticsearch 替换 `SimpleVectorStore`，实现通用文档结构、BM25 + KNN 双路召回、应用层 RRF、列表型查询区域扩展，并删除上一版 OKR 专用代码和失效测试。

**架构：** `fileagent-document` 将解析结果转换为领域知识块，由 Elasticsearch 索引仓储批量生成通义向量并写入版本化索引；检索适配器分别执行 BM25 和 KNN，再用纯 Java RRF 融合。`fileagent-chat` 只保留通用问题改写和回答模式，不感知 Elasticsearch 或具体业务术语。

**技术栈：** Java 21、Spring Boot 4.1、Spring AI 2.0、Elasticsearch Java Client、Elasticsearch、Testcontainers、JUnit 5、Mockito、Apache POI

---

## 文件结构

### 创建

- `compose.yaml`：本地 Elasticsearch + Kibana。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/domain/ParsedChunk.java`：通用解析结果。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/domain/KnowledgeChunk.java`：搜索索引领域值对象。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/domain/KnowledgeIndexRepository.java`：索引端口。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/ElasticsearchKnowledgeProperties.java`：索引和召回配置。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/ElasticsearchKnowledgeIndexInitializer.java`：物理索引和别名初始化。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/ElasticsearchKnowledgeIndexRepository.java`：Embedding、Bulk 写入和失败清理。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/RrfFusion.java`：纯 Java 排名融合。
- 上述组件对应的单元测试及 `ElasticsearchKnowledgeIntegrationTest.java`。

### 修改

- `pom.xml`、`fileagent-document/pom.xml`、`application.yml`、`docs/SKELETON.md`。
- `KnowledgeSearchPort.java`、`DocumentParser.java`、`ExcelDocumentParser.java`。
- `RagFileAppServiceImpl.java`、`KnowledgeSearchPortImpl.java`。
- `RagRetrievalPlanner.java`、`ChatAppServiceImpl.java`、`RagPromptBuilder.java`。
- 上述组件的现有测试文件。

### 删除

- `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/ParsedChunk.java`：移动到 domain。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/VectorStoreConfig.java`：JSON 向量库退出。

没有整份现有测试类需要删除。只删除验证 Objective、KR、人员、年份、示例 Sheet、占位符过滤和 `SimpleVectorStore` 的测试方法，再用通用行为测试替换。

---

### 任务 1：接入 Elasticsearch 运行时与配置

**文件：**

- 修改：`pom.xml`
- 修改：`fileagent-document/pom.xml`
- 创建：`compose.yaml`
- 创建：`ElasticsearchKnowledgeProperties.java`
- 修改：`fileagent-starter/src/main/resources/application.yml`
- 修改：`docs/SKELETON.md`

- [ ] **步骤 1：确认工具链和版本**

运行 `java -version`、`mvn -version`、`mvn help:evaluate -Dexpression=elasticsearch.version -q -DforceStdout`。

预期：Java 21、Maven 可执行、取得 Spring Boot 管理的 Elasticsearch 版本。若 Maven 不存在，先请求用户授权安装，不跳过后宣称构建成功。

- [ ] **步骤 2：替换依赖**

删除 `spring-ai-vector-store`；增加 `spring-boot-starter-elasticsearch`、测试范围的 `testcontainers-elasticsearch` 和 `testcontainers-junit-jupiter`。客户端和服务端固定相同主版本，Compose 不使用 `latest`。

- [ ] **步骤 3：增加配置类**

`ElasticsearchKnowledgeProperties` 使用 `@Component + @ConfigurationProperties(prefix = "fileagent.elasticsearch")`，字段固定为：

```java
private String indexAlias = "fileagent-knowledge";
private String physicalIndex = "fileagent-knowledge-v1";
private int dimensions = 1024;
private int bm25TopK = 50;
private int knnTopK = 50;
private int knnCandidates = 100;
private int rrfRankConstant = 60;
private int finalTopK = 12;
private int adjacentWindow = 1;
private int maxExpandedChunks = 100;
```

新类添加 `@author raosaijie`。

- [ ] **步骤 4：增加应用和 Compose 配置**

`application.yml` 增加 `spring.elasticsearch.uris/username/password` 环境变量和上述业务配置，删除 `vector-store-path`、`retrieval-similarity-threshold`、旧 `retrieval-top-k`。

`compose.yaml` 使用固定官方 Elasticsearch/Kibana 镜像、本地命名卷、健康检查和端口 9200/5601；仅本地开发关闭安全认证，不写入密钥。

- [ ] **步骤 5：验证和提交**

运行 `mvn -pl fileagent-document -am help:effective-pom -DskipTests`，预期 `BUILD SUCCESS`。

提交只使用明确路径：

```bash
git commit --only pom.xml fileagent-document/pom.xml compose.yaml fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/ElasticsearchKnowledgeProperties.java fileagent-starter/src/main/resources/application.yml docs/SKELETON.md -m "build(rag): 接入 Elasticsearch 检索基础设施"
```

---

### 任务 2：将 Excel 改为通用结构解析

**文件：**

- 删除 infrastructure `ParsedChunk.java`，创建 domain `ParsedChunk.java`。
- 修改 `DocumentParser.java`、`ExcelDocumentParser.java`、`ExcelDocumentParserTest.java`。

- [ ] **步骤 1：先改测试**

删除 `parseShouldIgnoreExampleSheetsAndPlaceholderRows`、`parseChunksShouldKeepOkrStructure`、`parseShouldKeepPlaceholderLikeTextInOrdinarySheets`。

新增断言：表头 `姓名/目标/权重` 与数据行 `张三/完成系统升级/40%` 生成 `姓名: 张三`、`目标: 完成系统升级`、`权重: 40%`；元数据包含 `sourceType=xlsx`、Sheet、一基行号和稳定 `sectionId`。另测空行分区、隐藏 Sheet、无表头使用 A/B/C、多行表头、`XXXX-1001` 原样保留。

- [ ] **步骤 2：运行测试确认失败**

运行 `mvn -pl fileagent-document -am test -Dtest=ExcelDocumentParserTest -Dsurefire.failIfNoSpecifiedTests=false`。

- [ ] **步骤 3：移动通用解析值对象**

```java
public record ParsedChunk(String content, Map<String, Object> metadata) {
    public ParsedChunk {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ParsedChunk text(String content) {
        return new ParsedChunk(content, Map.of("sourceType", "text"));
    }
}
```

`DocumentParser` 攰为导入 domain 类型。

- [ ] **步骤 4：实现通用 Excel 算法**

删除所有业务正则、`isOkrSheet`、`isExampleSheet`、`containsPlaceholder`。连续非空行形成区域；最多取前两行作表头；候选表头以文本为主且下一行覆盖至少一半列；无法识别时使用 Excel 列坐标。每个数据行一个 chunk，保留 Sheet、行号、`sectionId`。

- [ ] **步骤 5：验证和提交**

运行测试后用 `rg` 确认旧方法无命中。提交只包含上述五个精确路径，消息为 `refactor(parse): 改为通用表格结构分块`。

---

### 任务 3：建立知识索引端口并改造上传

**文件：**

- 创建 `KnowledgeChunk.java`、`KnowledgeIndexRepository.java`。
- 修改 `RagFileAppServiceImpl.java`、`RagFileAppServiceImplTest.java`。
- 删除 `VectorStoreConfig.java`。

- [ ] **步骤 1：先改上传测试**

将 `SimpleVectorStore` Mock 换成 `KnowledgeIndexRepository`。验证确定性 ID `fileId:chunkIndex`、文件信息和通用 parser 元数据；断言没有 `person/contentType`。删除 `vectorStorePath`、Spring AI `Document`、年份/人员断言。增加索引异常后状态为 `FAILED` 且调用 `deleteByFileId` 的测试。

- [ ] **步骤 2：运行测试确认失败**

运行 `mvn -pl fileagent-document -am test -Dtest=RagFileAppServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`。

- [ ] **步骤 3：创建最小领域类型**

```java
public record KnowledgeChunk(
        String chunkId, Long fileId, String ragName, String knowledgeTag,
        String filename, String content, int chunkIndex,
        Map<String, Object> metadata) {
}
```

```java
public interface KnowledgeIndexRepository {
    void saveAll(List<KnowledgeChunk> chunks);
    void deleteByFileId(Long fileId);
}
```

新文件添加 `@author raosaijie`。

- [ ] **步骤 4：替换应用服务依赖**

`RagFileAppServiceImpl` 注入 `KnowledgeIndexRepository`。删除 `SimpleVectorStore`、JSON 落盘、年份/人员正则和 Spring AI `Document` 映射。成功后标记 `SUCCESS`；失败时清理 fileId 并标记 `FAILED`。

- [ ] **步骤 5：验证和提交**

运行上传测试。提交明确列出两个新领域文件、应用服务、`VectorStoreConfig.java` 和测试，消息为 `refactor(document): 通过领域端口写入知识索引`。

---

### 任务 4：实现 Elasticsearch 初始化与 Bulk 写入

**文件：**

- 创建 `ElasticsearchKnowledgeIndexInitializer.java`。
- 创建 `ElasticsearchKnowledgeIndexRepository.java`。
- 创建 `ElasticsearchKnowledgeIndexRepositoryTest.java`。

- [ ] **步骤 1：先写失败测试**

覆盖批量 Embedding 和确定性 ID、任一 Bulk item 失败、`deleteByFileId` 精确过滤。Mock 两段正文返回两个 1024 维数组；捕获请求验证别名、ID、正文、元数据、向量。

- [ ] **步骤 2：实现索引初始化**

启动时检查别名；不存在则创建物理索引，显式映射 keyword、text(cjk)、integer、flattened、dense_vector；向量使用配置维度和 cosine；随后创建别名。初始化失败抛 `IllegalStateException`。

- [ ] **步骤 3：实现批量写入**

```java
List<float[]> embeddings = embeddingModel.embed(
        chunks.stream().map(KnowledgeChunk::content).toList());
if (embeddings.size() != chunks.size()) {
    throw new BizException("Embedding 返回数量与知识片段数量不一致");
}
BulkResponse response = elasticsearchClient.bulk(buildBulkRequest(chunks, embeddings));
if (response.errors()) {
    throw new BizException("Elasticsearch 批量索引失败: " + failedIds(response));
}
```

`deleteByFileId` 使用 term 查询；日志不输出正文、向量和密钥。

- [ ] **步骤 4：验证和提交**

运行 `ElasticsearchKnowledgeIndexRepositoryTest`，预期 PASS。提交三个精确文件，消息为 `feat(document): 写入 Elasticsearch 知识索引`。

---

### 任务 5：实现纯 Java RRF

**文件：**

- 创建 `RrfFusion.java`、`RrfFusionTest.java`。

- [ ] **步骤 1：先写失败测试**

BM25 顺序 A/B/C，KNN 顺序 C/A/D，断言融合顺序 A/C/B/D；另测空列表、单路命中、重复 ID、非法 rankConstant。

- [ ] **步骤 2：实现稳定 RRF**

每一路第 rank 位累加 `1.0 / (rankConstant + rank)`。按 chunkId 去重，候选内容取第一次出现；分数降序，同分 chunkId 升序。

- [ ] **步骤 3：验证和提交**

运行 `RrfFusionTest`，提交两个精确文件，消息为 `feat(rag): 增加 RRF 排名融合`。

---

### 任务 6：实现混合召回和列表区域扩展

**文件：**

- 修改 `KnowledgeSearchPort.java`。
- 重写 `KnowledgeSearchPortImpl.java`、`KnowledgeSearchPortImplTest.java`。

- [ ] **步骤 1：定义通用契约**

```java
record SearchQuery(String text, String answerMode,
                   String ragName, String knowledgeTag, Long fileId) {
    public static SearchQuery single(String text) {
        return new SearchQuery(text, "SINGLE", null, null, null);
    }
}
```

`KnowledgeHit` 包含 chunkId、fileId、content、filename、sheetName、sectionId、chunkIndex、score。删除 year、person、contentType、listAll。

- [ ] **步骤 2：先写失败测试**

删除全部 `SimpleVectorStore` 和 Objective 专用测试。新增空查询、BM25/KNN 同过滤、两路融合、最终 topK、`LIST_ALL` 只扩展最高分 fileId+sectionId 并按位置排序、外部异常透传。

- [ ] **步骤 3：实现双路召回**

BM25 `multi_match` 权重：`content^3`、`filename^2`、`sheetName^1.5`、`ragName`、`knowledgeTag`。KNN 使用查询向量、`knnTopK`、`knnCandidates` 和相同 term filters。两路结果交给 RRF。

RRF 后、裁剪前加入唯一延期标记：

```java
// TODO: 接入 Reranker，对混合召回结果进行语义重排
```

普通模式保留 finalTopK；`LIST_ALL` 用最高分 fileId+sectionId 精确查询，按 rowIndex/chunkIndex 排序，最多 maxExpandedChunks。

- [ ] **步骤 4：验证和提交**

运行 `KnowledgeSearchPortImplTest,RrfFusionTest`。提交 API、实现和测试三个精确文件，消息为 `feat(rag): 实现 Elasticsearch 混合召回`。

---

### 任务 7：清理 Chat 域业务规划和补检索

**文件：**

- 修改 `RagRetrievalPlanner.java`、`ChatAppServiceImpl.java`、`RagPromptBuilder.java` 及对应测试。

- [ ] **步骤 1：删除失效测试并增加通用测试**

删除 year/person/contentType/OBJECTIVE、Objective 1-4 补检索和 `isStructuredList` 断言。保留独立问题改写、无需检索、`LIST_ALL`、规划异常降级、知识来源、检索失败 SSE。追问测试只断言完整自然语言查询。

- [ ] **步骤 2：简化 Planner**

```java
public record RetrievalPlan(
        boolean needRetrieval,
        String standaloneQuery,
        String answerMode) {
}
```

删除业务正则、字段恢复、`retryQuery`、`RetrievalEvaluation` 和二次评估 Prompt。

- [ ] **步骤 3：简化 Chat 编排**

删除 `mergeRetryHits` 和二次检索，统一调用：

```java
return knowledgeSearchPort.search(new KnowledgeSearchPort.SearchQuery(
        plan.standaloneQuery(), plan.answerMode(), null, null, null));
```

Prompt 来源标记加入可选 Sheet 和片段位置，不把分数发送给大模型。

- [ ] **步骤 4：验证和提交**

运行三个 Chat 测试类；`rg` 确认生产代码无 Objective/KR/业务元数据判断。提交六个精确文件，消息为 `refactor(chat): 移除业务专用检索规划`。

---

### 任务 8：增加真实 Elasticsearch 集成测试

**文件：**

- 创建 `ElasticsearchKnowledgeIntegrationTest.java`。

- [ ] **步骤 1：编写容器测试**

```java
@Testcontainers(disabledWithoutDocker = true)
class ElasticsearchKnowledgeIntegrationTest {
    @Container
    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(ELASTICSEARCH_IMAGE);
}
```

镜像与 Compose 一致。使用固定二维向量，不调用通义；验证索引和别名、Bulk、BM25、KNN、平台过滤、区域顺序、重复 ID 覆盖。

- [ ] **步骤 2：运行和提交**

运行单个集成测试。有 Docker 时 PASS；无 Docker 时 SKIPPED，不能 FAIL。提交该文件，消息为 `test(rag): 覆盖 Elasticsearch 索引与检索`。

---

### 任务 9：全量清理和验收

- [ ] **步骤 1：检查旧实现退出**

运行：

```bash
rg -n "SimpleVectorStore|vectorstore.json|vector-store-path|OBJECTIVE_PATTERN|KEY_RESULT_PATTERN|OKR_PERSON_PATTERN|contentType|objectiveNo|keyResultNo|parentObjectiveNo" --glob '!docs/superpowers/**' --glob '!storage/**'
rg -n "SimpleVectorStore|OBJECTIVE|KEY_RESULT|objectiveNo|contentType" fileagent-*/src/test
git diff --check
```

预期：生产代码和测试无旧实现命中，diff 检查无输出。

- [ ] **步骤 2：运行测试和打包**

```bash
mvn -pl fileagent-document -am test
mvn -pl fileagent-chat -am test
mvn -pl fileagent-starter -am clean package
```

预期全部 `BUILD SUCCESS`；无 Docker 只允许容器测试 SKIPPED。

- [ ] **步骤 3：启动依赖和应用**

运行 `docker compose up -d`、`curl --fail http://localhost:9200/_cluster/health`、`mvn -pl fileagent-starter spring-boot:run`。预期 Elasticsearch 返回 health JSON，应用连接成功。真实上传和聊天仍需两个模型 API Key。

- [ ] **步骤 4：人工验收**

重新上传文档，提问“2026年饶赛杰的OKR目标有哪些？”、“2026年饶赛杰的O都有哪些？”、“我问的是2026呀”。日志必须显示 BM25、KNN、RRF 命中数；列表结果来自同一文件/区域并保持顺序，不依赖业务硬编码。

- [ ] **步骤 5：提交遗漏清理**

先运行 `git diff --name-only`，再用 `git commit --only` 加逐个明确路径；不得使用目录、通配符或未替换占位符。

---

## 实施约束

- 当前工作区有未提交改动，禁止 `git reset --hard`、`git checkout --` 或覆盖无关文件。
- 删除旧逻辑使用精确 `apply_patch`，不用整文件回退。
- 每次提交使用 `git commit --only`，避免误带 staged 文件。
- 新 Java 文件必须包含 `@author raosaijie`。
- `storage/vectorstore.json` 不删除，应用切换后不再读取。
- Reranker 注释是用户明确批准的延期范围，不属于本阶段缺陷。

