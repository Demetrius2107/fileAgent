# Phase 1 Agent Runtime 收口实现计划

> **面向 AI 代理的工作者：** 当前仓库未获用户批准委派子代理；在同一特性分支内按任务内联执行，并在每个任务完成后运行对应验证。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 让 Agent 的共享检索可兼容缺失 usage 字段的 OpenAI 协议实现、严格使用服务端知识范围、在检索故障时一次终止，并让 Agent 评测与报告反映真实运行结果。

**架构：** 在 `fileagent-document` 的 Provider 边界实现一个 `@Primary` 的 OpenAI-compatible `EmbeddingModel`，只放宽可选 usage，继续验证向量、模型名与维度。`search_docs` 将可信 `KnowledgeScope` 映射为结构化检索条件，并通过 `AgentRun` 的暂存工具失败信号使运行时收到工具结束事件后立即中断。评测模块基于终态计算成功率，分别计算引用覆盖率和引用有效性，并仅在门禁未通过时展示代表题。

**技术栈：** Java 21、Spring Boot 4.1、Spring AI 2.0、OpenAI Java SDK 4.39、AgentScope 2.0、JUnit 5、Mockito、AssertJ、Maven。

---

## 文件结构

- 新增 `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/OpenAiCompatibleEmbeddingModel.java`：继承 Spring AI 的 `OpenAiEmbeddingModel`，将 OpenAI Java SDK 的 embedding 响应转换为 Spring AI 响应，不读取可选 usage。
- 新增 `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/OpenAiCompatibleEmbeddingConfiguration.java`：复用既有 `spring.ai.openai.embedding` 与 `FILEAGENT_EMBEDDING_*` 绑定，注册上述 `@Primary EmbeddingModel`。
- 新增 `fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/OpenAiCompatibleEmbeddingModelTest.java`：验证缺少 `prompt_tokens` 仍可返回向量，向量或维度异常仍失败。
- 修改 `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRun.java`：保存尚未消费的工具失败码，供运行时在工具结束事件处统一终态化。
- 修改 `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/SearchDocsTool.java`：注入可信 scope，区分查询校验、零命中与共享检索故障。
- 修改 `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java`：在检索工具结束时中断 Agent，且只下发一条受控失败 SSE。
- 修改 `fileagent-agent/src/test/java/com/demetrius/fileagent/agent/domain/run/AgentRunTest.java`：覆盖暂存工具失败码的生命周期。
- 修改 `fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/SearchDocsToolTest.java`：覆盖 scope 映射、零命中和检索故障。
- 新增 `fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapterTest.java`：覆盖工具失败后的中断与单一失败事件。
- 修改 `fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationObservation.java`：成功语义改为“无评测错误且终态成功”。
- 修改 `fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationReport.java`：替换混合的引用指标，增加运行成功率、引用覆盖率、引用有效性和单题引用状态。
- 修改 `fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationRunner.java`：失败 Run 不调用 Judge；按依据模式计算引用分母与单题状态。
- 修改 `fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentQualityGateEvaluator.java`：导出新的稳定门禁键。
- 修改 `fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationReportWriter.java`：渲染新指标、失败终态和最多三道代表题，不再展开全部单题。
- 修改 `fileagent-evaluation/src/main/resources/evaluation/agent-v1/gate.json`：使用新指标名称和已确认阈值。
- 修改 `fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationRunnerTest.java`：覆盖失败 Run、引用覆盖率、有效性和不适用场景。
- 新增 `fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationReportWriterTest.java`：验证 Markdown 只显示失败指标的代表题。
- 修改 `fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationEndpointControllerTest.java`、`fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationEndpointServiceTest.java`：适配扩展后的报告契约。
- 修改 `docs/API.md`、`docs/TESTING.md`、`fileagent-evaluation/README.md`：同步内部评测接口的终态、引用和报告语义。

### 任务 1：实现可选 usage 的 Embedding Provider 边界

**文件：**
- 创建：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/OpenAiCompatibleEmbeddingModel.java`
- 创建：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/OpenAiCompatibleEmbeddingConfiguration.java`
- 测试：`fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/OpenAiCompatibleEmbeddingModelTest.java`

- [ ] **步骤 1：编写缺失 usage、空向量和维度异常的失败测试**

```java
@Test
void shouldReturnVectorWhenProviderDoesNotProvidePromptTokens() {
    CreateEmbeddingResponse response = mock(CreateEmbeddingResponse.class);
    Embedding providerEmbedding = mock(Embedding.class);
    when(providerEmbedding.embedding()).thenReturn(List.of(0.1F, 0.2F));
    when(providerEmbedding.index()).thenReturn(0L);
    when(response.data()).thenReturn(List.of(providerEmbedding));
    when(response.model()).thenReturn("text-embedding-v4");
    when(response.usage()).thenThrow(new IllegalStateException("prompt_tokens is not set"));
    when(client.embeddings().create(any())).thenReturn(response);

    EmbeddingResponse actual = model.call(new EmbeddingRequest(List.of("年度目标"), null));

    assertThat(actual.getResult().getOutput()).containsExactly(0.1F, 0.2F);
    assertThat(actual.getMetadata().getModel()).isEqualTo("text-embedding-v4");
    verify(response, never()).usage();
}

@Test
void shouldRejectMissingVectorOrUnexpectedDimension() {
    // 分别让 data 为空、embedding 为空、embedding 长度为 3；均断言 BizException 且消息不含 API Key。
}
```

- [ ] **步骤 2：运行测试，确认新类型尚不存在**

运行：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  /Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn \
  -s /Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml \
  -Dmaven.repo.local=/Users/a1/Desktop/software/mavenlibrary \
  -pl fileagent-document -am test \
  -Dtest=OpenAiCompatibleEmbeddingModelTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：FAIL，编译错误指出 `OpenAiCompatibleEmbeddingModel` 不存在。

- [ ] **步骤 3：实现只忽略 usage 的模型适配器**

`OpenAiCompatibleEmbeddingModel` 继承 `OpenAiEmbeddingModel`，文件头包含 `@author raosaijie`。构造器先调用父类构造器以保留 Spring AI 的 `Document` embedding 入口，再覆写 `call`；覆写方法合并默认 `OpenAiEmbeddingOptions` 与请求选项，通过 `OpenAIClient.embeddings().create(...)` 请求向量；不调用 `CreateEmbeddingResponse.usage()`、`Usage.promptTokens()` 或 `Usage.totalTokens()`。

```java
@Override
public EmbeddingResponse call(EmbeddingRequest request) {
    OpenAiEmbeddingOptions actualOptions = OpenAiEmbeddingOptions.builder()
            .from(defaultOptions)
            .merge(request.getOptions())
            .build();
    CreateEmbeddingResponse response = client.embeddings()
            .create(actualOptions.toOpenAiCreateParams(request.getInstructions()));
    String model = requiredModel(response);
    List<org.springframework.ai.embedding.Embedding> vectors = response.data().stream()
            .map(this::toEmbedding)
            .toList();
    if (vectors.isEmpty()) {
        throw new BizException("Embedding 响应缺少向量数据");
    }
    EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
    metadata.setModel(model);
    return new EmbeddingResponse(vectors, metadata);
}

private org.springframework.ai.embedding.Embedding toEmbedding(Embedding providerEmbedding) {
    List<Float> values = providerEmbedding.embedding();
    if (values == null || values.size() != expectedDimensions) {
        throw new BizException("Embedding 响应向量维度与索引配置不一致");
    }
    float[] vector = new float[values.size()];
    for (int index = 0; index < values.size(); index++) {
        vector[index] = values.get(index);
    }
    return new org.springframework.ai.embedding.Embedding(vector, Math.toIntExact(providerEmbedding.index()));
}
```

`requiredModel` 将空白或 SDK 的必填字段异常转换为 `BizException("Embedding 响应缺少 model")`；不得将原始 HTTP 响应、请求头或 API Key 拼入异常文本。

- [ ] **步骤 4：注册主 EmbeddingModel，保留现有配置键**

`OpenAiCompatibleEmbeddingConfiguration` 使用 `OpenAiAutoConfigurationUtil.resolveCommonProperties(...)` 合并 `OpenAiCommonProperties` 与 `OpenAiEmbeddingProperties`，并从合并结果创建 SDK 客户端。它复用现有 `spring.ai.openai.embedding.base-url`、`api-key`、`model`、`dimensions`，因此 `FILEAGENT_EMBEDDING_*` 不需要变更。

```java
@Bean
@Primary
OpenAiCompatibleEmbeddingModel compatibleEmbeddingModel(OpenAiCommonProperties common,
                                                         OpenAiEmbeddingProperties embedding,
                                                         ElasticsearchKnowledgeProperties elasticsearch,
                                                         ObjectProvider<ObservationRegistry> observations) {
    ResolvedConnectionProperties connection =
            OpenAiAutoConfigurationUtil.resolveCommonProperties(common, embedding);
    SpringAiOpenAiHttpClient httpClient = SpringAiOpenAiHttpClient.builder()
            .timeout(connection.getTimeout())
            .proxy(connection.getProxy())
            .build();
    ClientOptions.Builder clientOptions = ClientOptions.builder()
            .httpClient(httpClient)
            .baseUrl(connection.getBaseUrl())
            .apiKey(connection.getApiKey())
            .maxRetries(connection.getMaxRetries());
    applyOrganizationAndHeaders(clientOptions, connection);
    return new OpenAiCompatibleEmbeddingModel(
            new OpenAIClientImpl(clientOptions.build()), embedding.getMetadataMode(),
            embedding.toOptions(), observations.getIfAvailable(() -> ObservationRegistry.NOOP),
            elasticsearch.getDimensions());
}
```

`applyOrganizationAndHeaders` 仅复制已绑定的组织和自定义请求头；不新增 `application.yml` 密钥、不记录 `apiKey`，也不引入第二套 `fileagent.*` 模型配置。Bean 方法返回具体的 `OpenAiCompatibleEmbeddingModel`（它是 `OpenAiEmbeddingModel` 子类），使 Spring AI `@ConditionalOnMissingBean` 的严格 OpenAI 模型自动配置退避；`@Primary` 保持注入选择显式。

- [ ] **步骤 5：运行文档域定向测试**

运行：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  /Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn \
  -s /Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml \
  -Dmaven.repo.local=/Users/a1/Desktop/software/mavenlibrary \
  -pl fileagent-document -am test \
  -Dtest=OpenAiCompatibleEmbeddingModelTest,KnowledgeSearchPortImplTest,ElasticsearchKnowledgeIndexRepositoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：PASS；已有索引与检索测试继续验证维度约束，新增测试证明 usage 缺失不会阻断向量。

- [ ] **步骤 6：提交 Provider 边界变更**

```bash
git add fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/OpenAiCompatibleEmbeddingModel.java \
  fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/OpenAiCompatibleEmbeddingConfiguration.java \
  fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/OpenAiCompatibleEmbeddingModelTest.java
git commit -m "fix(document): 兼容缺失 usage 的 embedding 响应"
```

### 任务 2：收紧 search_docs 范围并中断检索故障 Run

**文件：**
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRun.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/SearchDocsTool.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/domain/run/AgentRunTest.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/SearchDocsToolTest.java`
- 创建：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapterTest.java`

- [ ] **步骤 1：为 scope、零命中和基础设施失败补充失败测试**

```java
@Test
void executeShouldUseTrustedScopeInsteadOfModelSuppliedRange() {
    AgentToolContext context = context(startedRun(), port,
            new KnowledgeScope("人事制度", "年假"));
    tool.execute(context, "年假天数");

    verify(port).search(new KnowledgeSearchPort.SearchQuery(
            "年假天数", "人事制度", "年假", null));
}

@Test
void executeShouldKeepRunRunningWhenSearchReturnsNoHits() {
    when(port.search(any(KnowledgeSearchPort.SearchQuery.class))).thenReturn(List.of());

    ToolResultBlock result = tool.execute(context(startedRun(), port), "不存在的制度");

    assertThat(text(result)).contains("未检索到相关文档片段");
    assertThat(run.pendingToolFailureCode()).isNull();
}

@Test
void executeShouldRecordControlledFailureWhenKnowledgeSearchThrows() {
    when(port.search(any(KnowledgeSearchPort.SearchQuery.class)))
            .thenThrow(new IllegalStateException("provider token detail"));

    ToolResultBlock result = tool.execute(context(run, port), "年度目标");

    assertThat(run.pendingToolFailureCode()).isEqualTo(AgentRun.KNOWLEDGE_SEARCH_FAILURE_CODE);
    assertThat(text(result)).contains("知识库检索暂时不可用").doesNotContain("provider token detail");
}
```

- [ ] **步骤 2：运行工具测试，确认 scope 和失败信号尚未实现**

运行：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  /Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn \
  -s /Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml \
  -Dmaven.repo.local=/Users/a1/Desktop/software/mavenlibrary \
  -pl fileagent-agent -am test \
  -Dtest=AgentRunTest,SearchDocsToolTest,AgentScopeRuntimeAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：FAIL，`pendingToolFailureCode` 与带 scope 的查询尚不存在。

- [ ] **步骤 3：在聚合根保存待消费的工具失败码**

在 `AgentRun` 增加唯一共享常量和最小状态字段，不改变既有终态状态机：

```java
public static final String KNOWLEDGE_SEARCH_FAILURE_CODE = "AGENT_KNOWLEDGE_SEARCH_FAILED";

private volatile String pendingToolFailureCode;

public void recordToolFailure(String code) {
    if (isRunning()) {
        this.pendingToolFailureCode = code;
    }
}

public String pendingToolFailureCode() {
    return pendingToolFailureCode;
}
```

`fail(code, now)` 仍是唯一把 Run 转为 `FAILED` 的方法；暂存字段只在工具结束事件之前传递失败事实，不能被模型输入修改。

- [ ] **步骤 4：将可信范围映射到结构化检索并封装故障**

保留空白和超长 query 的 `BizException`，它们是模型参数校验错误，不属于检索基础设施故障。校验通过后才进入 `try/catch`：

```java
KnowledgeSearchPort.SearchQuery searchQuery = new KnowledgeSearchPort.SearchQuery(
        trimmed, context.scope().ragName(), context.scope().knowledgeTag(), null);
List<KnowledgeHit> hits;
try {
    hits = context.knowledgeSearchPort().search(searchQuery).stream()
            .limit(MAX_HITS)
            .toList();
} catch (RuntimeException exception) {
    log.warn("search_docs 检索失败 runId={}, queryLength={}",
            context.run().runId(), trimmed.length(), exception);
    context.run().recordToolFailure(AgentRun.KNOWLEDGE_SEARCH_FAILURE_CODE);
    context.run().recordToolResult(0);
    return ToolResultBlock.text("知识库检索暂时不可用，当前运行将结束。");
}
```

成功和零命中路径继续记录真实结果数；模型不能从工具参数设置 `ragName`、`knowledgeTag` 或 `fileId`。日志保留服务端异常用于排查，工具文本、SSE 和评测摘要只含固定展示语。

- [ ] **步骤 5：在工具结束事件处立即终止运行**

扩展 `AgentAssembly` 持有本次构造的 `AgentToolContext`，并把它传入 `mapEvent`。将 `TOOL_RESULT_END` 分支抽为同包可测的 `handleToolResultEnd(...)`：

```java
Flux<AgentRunEvent> handleToolResultEnd(AgentRun run, ReActAgent agent,
                                        RuntimeContext context, String toolName,
                                        int step, long durationMs) {
    String failureCode = run.pendingToolFailureCode();
    if (failureCode != null) {
        agent.interrupt(context);
        return failIfRunning(run, failureCode, "知识库检索暂时不可用");
    }
    return Flux.just(eventMapper.toolCompleted(run.runId(), toolName, step,
            run.lastToolResultCount(), durationMs));
}
```

`failIfRunning` 将 Run 置为 `FAILED` 并发出唯一 `run.failed` 事件。后续 AgentScope 异常进入 `onError` 时因 Run 已终态直接返回空流，避免第二条失败 SSE 和再次模型调用。测试使用 mock `ReActAgent` 验证 `interrupt(context)` 被调用一次、事件序列仅为 `run.failed`、失败码为 `AGENT_KNOWLEDGE_SEARCH_FAILED`。

- [ ] **步骤 6：运行 Agent 定向测试**

运行：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  /Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn \
  -s /Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml \
  -Dmaven.repo.local=/Users/a1/Desktop/software/mavenlibrary \
  -pl fileagent-agent -am test \
  -Dtest=AgentRunTest,SearchDocsToolTest,AgentScopeRuntimeAdapterTest,AgentRuntimeCancellationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：PASS；正常零命中仍不是失败，检索异常只发生一次工具调用并终态失败。

- [ ] **步骤 7：提交 Agent 运行时收口变更**

```bash
git add fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRun.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/SearchDocsTool.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/domain/run/AgentRunTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/SearchDocsToolTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapterTest.java
git commit -m "fix(agent): 收紧检索范围并终止检索故障运行"
```

### 任务 3：修正 Agent 评测成功、引用与报告口径

**文件：**
- 修改：`fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationObservation.java`
- 修改：`fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationReport.java`
- 修改：`fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationRunner.java`
- 修改：`fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentQualityGateEvaluator.java`
- 修改：`fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationReportWriter.java`
- 修改：`fileagent-evaluation/src/main/resources/evaluation/agent-v1/gate.json`
- 测试：`fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationRunnerTest.java`
- 创建：`fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationReportWriterTest.java`
- 测试：`fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationEndpointControllerTest.java`
- 测试：`fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationEndpointServiceTest.java`

- [ ] **步骤 1：编写失败 Run、引用覆盖和引用有效性的测试**

```java
@Test
void failedRunShouldNotBeCountedAsSuccessOrJudged() {
    AgentAnswerEvaluationPort agentPort = query -> failedResult(
            AgentRunStatus.FAILED, "AGENT_KNOWLEDGE_SEARCH_FAILED");

    AgentEvaluationReport report = runner.run("agent-v1", agentPort);

    assertThat(judgeCalls.get()).isZero();
    assertThat(report.successfulCases()).isZero();
    assertThat(report.failedCases()).isEqualTo(report.totalCases());
    assertThat(report.cases()).allMatch(c -> c.terminalStatus() == AgentRunStatus.FAILED);
}

@Test
void knowledgeBasedCitationShouldSeparateCoverageFromValidity() {
    // 无引用：coverage=0，单题为 MISSING；有效性不以它为分母。
    // 真实来源加伪造来源：coverage=1，validity=0，单题为 INVALID。
    // GENERAL_KNOWLEDGE 和 REFUSE：单题为 NOT_APPLICABLE，均不进入引用分母。
}
```

- [ ] **步骤 2：运行评测测试，确认旧口径不满足新断言**

运行：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  /Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn \
  -s /Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml \
  -Dmaven.repo.local=/Users/a1/Desktop/software/mavenlibrary \
  -pl fileagent-evaluation -am test \
  -Dtest=AgentEvaluationRunnerTest,AgentEvaluationReportWriterTest,AgentEvaluationEndpointControllerTest,AgentEvaluationEndpointServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：FAIL，旧实现会把 `FAILED` 计入成功，并使用 `citationOnlyFromRetrievedRate`。

- [ ] **步骤 3：以终态定义 Agent 成功并跳过失败 Run 的 Judge**

```java
public boolean agentSucceeded() {
    return error == null && terminalStatus == AgentRunStatus.SUCCEEDED;
}

public boolean judgeSucceeded() {
    return agentSucceeded() && judgeDecision != null;
}
```

在 `collect` 中，Agent 返回 `FAILED`、`TIMED_OUT`、`CANCELLED` 或 null 终态时，直接创建 Observation，保留答案、检索文件、引用、工具序列、耗时、终态和失败码，并将 Judge 字段保持空值；不得调用 `judgePort.judge(...)`。只有 `SUCCEEDED` 才能进入 Judge。`evaluate` 的成功数改为 `AgentEvaluationObservation::agentSucceeded`。

- [ ] **步骤 4：替换混合引用指标并计算单题状态**

在 `AgentEvaluationReport` 中将 `citationOnlyFromRetrievedRate` 替换为 `citationCoverageRate`、`citationValidityRate`，并在 `AgentMetrics` 增加 `runSuccessRate`。在 `CaseResult` 增加 `terminalStatus`、`failureCode` 和嵌套枚举 `CitationStatus`：`PASSED`、`MISSING`、`INVALID`、`NOT_APPLICABLE`。

```java
private static CitationStatus citationStatus(EvaluationCase evaluationCase,
                                             AgentEvaluationObservation observation) {
    if (!observation.agentSucceeded()
            || evaluationCase.expected().groundingMode() != AnswerGroundingMode.KNOWLEDGE_BASED) {
        return CitationStatus.NOT_APPLICABLE;
    }
    if (observation.citedFilenames().isEmpty()) {
        return CitationStatus.MISSING;
    }
    return citationsOnlyFromRetrieved(observation)
            ? CitationStatus.PASSED
            : CitationStatus.INVALID;
}
```

引用覆盖率的分母是成功的 `KNOWLEDGE_BASED` 题；题目至少有一个来自本次检索文件的引用才通过。引用有效性的分母是上述范围内实际标注了引用的题；所有标注文件均来自本次检索才通过。`GENERAL_KNOWLEDGE`、`REFUSE` 和非成功 Run 都是 `NOT_APPLICABLE`，不会影响两个分母。无有效性样本时数值为 `1.0`，但 Markdown 必须同时展示“无已标注引用样本”，避免把真空真误读为证据充分。

- [ ] **步骤 5：更新门禁键、Markdown 和代表题选择**

`AgentQualityGateEvaluator.flatten` 输出以下稳定键：

```java
scores.put("agent.runSuccessRate", agent.runSuccessRate());
scores.put("agent.citationCoverageRate", agent.citationCoverageRate());
scores.put("agent.citationValidityRate", agent.citationValidityRate());
```

`gate.json` 删除旧键，新增：

```json
"agent.runSuccessRate": 1.0,
"agent.budgetComplianceRate": 1.0,
"agent.toolWhitelistPassRate": 1.0,
"agent.citationCoverageRate": 0.9,
"agent.citationValidityRate": 1.0
```

报告汇总展示运行成功率、预算、白名单、引用覆盖率、引用有效性、拒答、平均步骤数和平均耗时，并在指标名称旁说明分母。质量门禁块仅输出未通过的 violations；仅当门禁失败时追加最多三道代表题，选择与失败指标直接相关的最低分案例，并显示终态、失败码、引用状态、检索文件和答案摘要。通过门禁的报告不输出全量单题表；完整答案、检索结果与 Judge 结果继续位于 `observations.jsonl`。

- [ ] **步骤 6：适配端点报告构造测试并运行评测模块测试**

运行：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  /Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn \
  -s /Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml \
  -Dmaven.repo.local=/Users/a1/Desktop/software/mavenlibrary \
  -pl fileagent-evaluation -am test \
  -Dtest=AgentEvaluationRunnerTest,AgentEvaluationReportWriterTest,AgentEvaluationEndpointControllerTest,AgentEvaluationEndpointServiceTest,AgentQualityGateEvaluatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：PASS；报告 JSON、Markdown、门禁和 Observation 对同一终态和引用状态给出一致结论。

- [ ] **步骤 7：提交评测与报告变更**

```bash
git add fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationObservation.java \
  fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationReport.java \
  fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationRunner.java \
  fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentQualityGateEvaluator.java \
  fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationReportWriter.java \
  fileagent-evaluation/src/main/resources/evaluation/agent-v1/gate.json \
  fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationRunnerTest.java \
  fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationReportWriterTest.java \
  fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationEndpointControllerTest.java \
  fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationEndpointServiceTest.java
git commit -m "fix(evaluation): 对齐 Agent 终态与引用评测口径"
```

### 任务 4：同步文档并完成分层验证

**文件：**
- 修改：`docs/API.md`
- 修改：`docs/TESTING.md`
- 修改：`fileagent-evaluation/README.md`

- [ ] **步骤 1：更新接口和指标说明**

在 `docs/API.md` 的内部 Agent 评测节明确：非 `SUCCEEDED` Run 不调用 Judge，`observations` 保留失败终态与受控行为摘要；`report` 使用 `agent.runSuccessRate`、`agent.citationCoverageRate`、`agent.citationValidityRate`，不再返回旧的 `citationOnlyFromRetrievedRate`。

在 `docs/TESTING.md` 和评测模块 README 明确：

```text
引用覆盖率：成功 KNOWLEDGE_BASED 题中，至少一个实际标注来源来自本次检索的比例。
引用有效性：成功且标注来源的 KNOWLEDGE_BASED 题中，所有标注来源均来自本次检索的比例。
运行成功率：终态为 SUCCEEDED 且无评测错误的比例。
```

保留脚本“调用已部署 FileAgent、无需重复配置 chat/vector/reranker API Key”的说明；不增加脚本参数或在仓库写入密钥。

- [ ] **步骤 2：运行所有受影响模块的离线测试**

运行：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  /Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn \
  -s /Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml \
  -Dmaven.repo.local=/Users/a1/Desktop/software/mavenlibrary \
  -pl fileagent-document,fileagent-agent,fileagent-evaluation -am test
```

预期：PASS；不会调用真实聊天、Embedding 或 Judge。

- [ ] **步骤 3：构建完整应用并检查工作区**

运行：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  /Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn \
  -s /Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml \
  -Dmaven.repo.local=/Users/a1/Desktop/software/mavenlibrary \
  clean package -DskipTests
git diff --check
git status --short
```

预期：构建成功、无空白错误；只出现本计划的已提交变更，以及开始前已存在的未跟踪 `AnswerCitationExtractorTest.java`，该文件不纳入本次提交。

- [ ] **步骤 4：重启部署实例后执行真实 Agent 评测**

确保 IDEA 或命令行运行的是本分支新构建的 classpath，且部署实例已保留既有 `FILEAGENT_CHAT_*`、`FILEAGENT_EMBEDDING_*`、Elasticsearch 与 `FILEAGENT_EVALUATION_*` 配置。然后运行：

```bash
cd /Applications/idea_code/fileAgent/fileagent-evaluation
sh scripts/run-agent-evaluation.sh
```

预期：脚本仍只调用 `http://127.0.0.1:8080/internal/evaluation/agent/run`；企业事实题不再因 `prompt_tokens is not set` 进入预算终止，成功事实题具有真实来源，报告不把失败 Run 标为成功。若真实模型输出仍违反质量门禁，保留新目录中的 `report.md`、`report.json`、`observations.jsonl` 作为下一轮质量优化证据，不降低阈值伪造通过。

- [ ] **步骤 5：提交文档与验证记录**

```bash
git add docs/API.md docs/TESTING.md fileagent-evaluation/README.md
git commit -m "docs(agent): 说明运行终态与引用评测口径"
```
