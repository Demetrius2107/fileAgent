# RAG Reranker 相关性过滤实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 使用 reranker 相关性分数过滤低质量知识命中，让 Prompt 与来源文件保持一致，并补齐 reranker 和多轮问题改写日志。

**架构：** 过滤逻辑内聚在 `DashScopeKnowledgeReranker`，只处理模型成功返回的相关性分数；关闭或降级时继续返回 RRF 候选，不混用两种分数。`KnowledgeSearchPortImpl` 继续负责 `finalTopK` 与父块展开，chat 域自然使用过滤后的同一份 hits。

**技术栈：** Java 21、Spring Boot 3.3、Spring AI 1.0、JUnit 5、AssertJ、MockRestServiceServer、Maven

---

## 文件结构

- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/RerankerProperties.java`，增加 reranker 最低相关性配置。
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/DashScopeKnowledgeReranker.java`，过滤低分结果并输出诊断日志。
- 修改：`fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/DashScopeKnowledgeRerankerTest.java`，覆盖阈值边界与全部过滤场景。
- 修改：`fileagent-chat/src/main/java/com/demetrius/fileagent/chat/application/RagQueryRewriter.java`，补充跳过、成功和失败耗时日志。
- 修改：`fileagent-starter/src/main/resources/application.yml`，声明 `min-relevance-score`，移除未生效的旧检索阈值。
- 修改：`docs/API.md`，说明多轮改写、reranker 阈值与来源口径。

### 任务 1：用测试锁定 reranker 阈值行为

**文件：**
- 修改：`fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/DashScopeKnowledgeRerankerTest.java`

- [ ] **步骤 1：添加低分过滤测试**

新增测试，模拟 reranker 返回 `0.92`、`0.20`、`0.19`，并验证默认阈值 `0.20` 保留前两条：

```java
@Test
void rerankShouldFilterScoresBelowMinimumAndKeepBoundary() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RerankerProperties properties = properties(true);
    DashScopeKnowledgeReranker reranker = new DashScopeKnowledgeReranker(builder, properties);
    server.expect(once(), requestTo(properties.getBaseUrl()))
            .andRespond(withSuccess("""
                    {"results":[
                      {"index":0,"relevance_score":0.92},
                      {"index":1,"relevance_score":0.20},
                      {"index":2,"relevance_score":0.19}
                    ]}
                    """, MediaType.APPLICATION_JSON));

    List<KnowledgeHit> result = reranker.rerank(
            "年度目标", List.of(hit("A"), hit("B"), hit("C")));

    assertThat(result).extracting(KnowledgeHit::chunkId).containsExactly("A", "B");
}
```

- [ ] **步骤 2：添加全部低于阈值时返回空列表的测试**

模拟有效响应只包含 `0.19`，验证结果为空而不是错误降级回原候选：

```java
assertThat(reranker.rerank("无答案问题", List.of(hit("A")))).isEmpty();
```

- [ ] **步骤 3：运行测试并确认先失败**

运行：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  "/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
  -s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
  -Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
  -pl fileagent-document -am test \
  -Dtest=DashScopeKnowledgeRerankerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：测试因缺少最低分过滤而失败。

### 任务 2：实现相关性过滤与 reranker 日志

**文件：**
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/RerankerProperties.java`
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/DashScopeKnowledgeReranker.java`
- 测试：`fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/DashScopeKnowledgeRerankerTest.java`

- [ ] **步骤 1：增加最低相关性配置**

在 `RerankerProperties` 中加入：

```java
private double minRelevanceScore = 0.2;
```

- [ ] **步骤 2：只过滤成功映射的 reranker 结果**

在调用前校验阈值范围，调用成功后统计有效返回项。有效项低于阈值时不加入结果；只在一个有效结果都无法映射时降级原候选：

```java
double minScore = properties.getMinRelevanceScore();
if (minScore < 0.0 || minScore > 1.0) {
    log.warn("Reranker minRelevanceScore 配置无效，降级为 RRF 排序: {}", minScore);
    return candidates;
}

int validResultCount = 0;
List<KnowledgeHit> reranked = new ArrayList<>(response.results().size());
for (int rank = 0; rank < response.results().size(); rank++) {
    RerankResult result = response.results().get(rank);
    if (result.index() < 0 || result.index() >= selected.size()) {
        continue;
    }
    validResultCount++;
    KnowledgeHit hit = selected.get(result.index());
    boolean kept = result.relevanceScore() >= minScore;
    log.debug("Reranker 结果: rank={}, chunkId={}, file={}, score={}, kept={}",
            rank + 1, hit.chunkId(), hit.filename(), result.relevanceScore(), kept);
    if (kept) {
        reranked.add(new KnowledgeHit(
                hit.chunkId(), hit.fileId(), hit.content(), hit.filename(), hit.sheetName(),
                hit.sectionId(), hit.parentId(), hit.chunkIndex(), result.relevanceScore()));
    }
}
return validResultCount == 0 ? candidates : List.copyOf(reranked);
```

- [ ] **步骤 3：增加调用摘要和耗时日志**

使用 `System.nanoTime()` 记录耗时。DEBUG 日志包含 query、候选数、提交数、`topN`、阈值、返回数、保留数、过滤数和耗时，不记录正文或认证信息。

- [ ] **步骤 4：运行 reranker 测试确认通过**

运行任务 1 的 Maven 命令。

预期：`DashScopeKnowledgeRerankerTest` 全部通过。

- [ ] **步骤 5：提交相关性过滤**

```bash
git add \
  fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/RerankerProperties.java \
  fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/DashScopeKnowledgeReranker.java \
  fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/DashScopeKnowledgeRerankerTest.java
git commit -m "fix(rag): 过滤 reranker 低相关性结果"
```

### 任务 3：补齐问题改写日志与配置文档

**文件：**
- 修改：`fileagent-chat/src/main/java/com/demetrius/fileagent/chat/application/RagQueryRewriter.java`
- 修改：`fileagent-starter/src/main/resources/application.yml`
- 修改：`docs/API.md`
- 测试：`fileagent-chat/src/test/java/com/demetrius/fileagent/chat/application/RagQueryRewriterTest.java`

- [ ] **步骤 1：记录问题改写决策和耗时**

保持第二轮起每次调用模型的逻辑不变，仅增加日志：

```java
if (!hasPreviousUserMessage(history)) {
    log.debug("多轮问题改写跳过: reason=no_previous_user_message, question={}", question);
    return question;
}
long startedAt = System.nanoTime();
```

成功与失败日志增加 `elapsedMs`，继续保留失败时返回原问题的行为。

- [ ] **步骤 2：更新应用配置**

在 `fileagent.reranker` 下增加：

```yaml
min-relevance-score: ${FILEAGENT_RERANKER_MIN_RELEVANCE_SCORE:0.2}
```

删除没有 Java 绑定和实际效果的顶层 `retrieval-similarity-threshold: 0.55`，避免误认为它控制当前 ES 检索。

- [ ] **步骤 3：更新 API 文档**

将聊天主流程写明为：历史问题改写 -> 混合召回 -> reranker -> 最低分过滤 -> 父块展开 -> Prompt。说明 `sources.files` 来自过滤后的命中，reranker 关闭或降级时不应用该阈值。

- [ ] **步骤 4：运行 chat 与 document 相关测试**

运行：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  "/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
  -s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
  -Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
  -pl fileagent-chat,fileagent-document -am test \
  -Dtest=RagQueryRewriterTest,ChatAppServiceImplTest,DashScopeKnowledgeRerankerTest,KnowledgeSearchPortImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：指定测试全部通过，问题改写调用条件没有变化。

- [ ] **步骤 5：提交日志和文档**

```bash
git add \
  fileagent-chat/src/main/java/com/demetrius/fileagent/chat/application/RagQueryRewriter.java \
  fileagent-starter/src/main/resources/application.yml \
  docs/API.md
git commit -m "chore(rag): 补充检索链路诊断信息"
```

### 任务 4：全量验证

**文件：**
- 检查：本计划涉及的全部文件

- [ ] **步骤 1：检查差异和敏感信息**

```bash
git diff master...HEAD --check
git status --short
git diff master...HEAD -- \
  fileagent-document fileagent-chat fileagent-starter/src/main/resources/application.yml docs/API.md
```

确认没有 API Key、片段正文日志、上传去重代码或定时扫描任务。

- [ ] **步骤 2：运行全量测试**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  "/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
  -s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
  -Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
  -pl fileagent-starter -am test
```

预期：所有模块 `BUILD SUCCESS`；若 Testcontainers Elasticsearch 环境不可用，只允许既有集成测试按现有条件跳过。

### 任务 5：一次性清理确认过的孤儿索引

**文件：**
- 不修改仓库文件

- [ ] **步骤 1：删除前再次确认范围**

分别查询 `fileId=15` 和 `fileId=18` 的 ES 数量，并确认 H2 中只有 `fileId=18` 的有效记录。

- [ ] **步骤 2：精准删除 `fileId=15`**

```http
POST fileagent-knowledge/_delete_by_query?refresh=true&conflicts=proceed
{
  "query": {
    "term": {
      "fileId": "15"
    }
  }
}
```

- [ ] **步骤 3：验证清理结果**

使用 `_count` 确认 `fileId=15` 为 `0`，并确认 `fileId=18` 仍为 `1`。不增加定时任务，不扫描全部 H2 或 ES 数据。
