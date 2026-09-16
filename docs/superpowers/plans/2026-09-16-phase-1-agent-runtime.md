# Phase 1：Agent Runtime 与最小 Agentic RAG 实施计划

> **执行方式：** 按本计划在当前 `feat/m3-agent-runtime` 分支内顺序实施。每个任务先写会失败的测试，再完成最小实现并运行该任务列出的验证命令；前一项未通过不进入后一项。

**目标：** 新增独立的 Agent 问答通路。它能在受控预算内自行决定直接回答、检索资料、基于已检索片段补充上下文、或在没有可靠依据时拒答；现有 `/api/sessions/{sessionId}/chat` 保持原样，默认仍走基线 RAG。

**范围边界：**

- 本期仅接入 AgentScope Java 2 Core、三个只读工具、单机内存运行态、SSE 可观测事件、取消/超时、开关和 Agent 专项评测。
- 不做跨节点持久化恢复、写工具、审批/HITL、MCP、长期记忆、多 Agent、多模态、租户鉴权改造。
- 不暴露思考链、工具原始结果、数据库连接信息、模型 API Key 或 Elasticsearch 查询语句。

**总体结构：**

```text
浏览器 Agent 开关
    -> POST /api/sessions/{sessionId}/agent-runs (SSE)
    -> AgentRunAppService
    -> AgentRuntimePort
    -> AgentScopeRuntimeAdapter / ReActAgent
         -> search_docs             -> KnowledgeSearchPort
         -> list_knowledge_files    -> KnowledgeCatalogPort
         -> read_document_context   -> KnowledgeContextPort

Chat 域 -> AgentModelConfigPort -> Agent 模型连接配置
Document 域 -> KnowledgeCatalogPort / KnowledgeContextPort
```

**技术约束：** Java 21、Spring Boot 4.1、Spring AI 2.0、Reactor、AgentScope 2.0.1、Elasticsearch 9。`fileagent-agent` 只依赖 `fileagent-api`，不得直接依赖 `fileagent-chat` 或 `fileagent-document`；chat/document 通过 API Port 提供能力。

## 任务 1：建立模块、依赖与模型配置边界

**涉及文件：**

- 修改：`pom.xml`
- 新增：`fileagent-agent/pom.xml`
- 修改：`fileagent-starter/pom.xml`
- 新增：`fileagent-api/src/main/java/com/demetrius/fileagent/api/port/AgentModelConfigPort.java`
- 新增：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentModelConfig.java`
- 新增：`fileagent-chat/src/main/java/com/demetrius/fileagent/chat/infrastructure/model/AgentModelConfigPortImpl.java`
- 新增：`fileagent-chat/src/test/java/com/demetrius/fileagent/chat/infrastructure/model/AgentModelConfigPortImplTest.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeModelFactory.java`
- 新增：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeModelFactoryTest.java`
- 修改：`docs/SKELETON.md`

**先写测试：**

```java
@Test
void currentShouldPreferEnabledDatabaseChatModel() {
    when(repository.findFirstByEnabledTrueOrderByUpdatedAtDesc())
            .thenReturn(Optional.of(enabledConfig("deepseek-v4-pro")));

    AgentModelConfig config = port.current();

    assertThat(config.model()).isEqualTo("deepseek-v4-pro");
    assertThat(config.apiKey()).isEqualTo("test-key");
}

@Test
void currentShouldUseEnvironmentDefaultsWhenNoDatabaseConfigExists() {
    when(repository.findFirstByEnabledTrueOrderByUpdatedAtDesc()).thenReturn(Optional.empty());

    assertThat(port.current().model()).isEqualTo("configured-default-model");
}
```

**实现步骤：**

1. 根 `pom.xml` 增加 `fileagent-agent` 模块、`agentscope.version` 属性及 `agentscope-core`、`agentscope-extensions-model-openai` 的依赖管理；`fileagent-starter/pom.xml` 增加对 `fileagent-agent` 的依赖。
2. 新模块仅声明 `fileagent-api`、Web/WebFlux、Micrometer tracing、OpenAPI 注解、AgentScope Core/OpenAI 扩展及测试依赖。不要把 `fileagent-chat`、`fileagent-document` 加入依赖。
3. 在 API 模块增加不可变配置契约，模型配置只在进程内传递，绝不写入响应、事件或日志：

```java
public record AgentModelConfig(
        ModelProvider provider,
        String baseUrl,
        String apiKey,
        String model,
        double temperature) {
}

public interface AgentModelConfigPort {
    AgentModelConfig current();
}
```

4. chat 域的 `AgentModelConfigPortImpl` 复用已有启用模型配置、密钥解密和 `ModelProvider` 规则：启用的数据库 Chat 模型优先；没有启用模型时读取已有的 `FILEAGENT_CHAT_*` 默认配置。它不能复用或返回 Spring AI 的 `ChatModel`，因为 AgentScope 使用自己的模型对象。
5. `AgentScopeModelFactory` 每次运行依据 `AgentModelConfigPort.current()` 构造一个 OpenAI-compatible AgentScope 模型。模型名、base URL、provider formatter 的选择必须集中在这里；日志仅记录 provider、模型名和脱敏后的 base URL。
6. `AgentScopeModelFactoryTest` 不调用真实模型，只断言用 DeepSeek/OpenAI-compatible 配置可以构建 AgentScope 模型。真实 API 连通性留给第 9 项的受控手工冒烟。
7. 更新 `docs/SKELETON.md` 的模块清单和新增依赖说明。

**验证：**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
"/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
-s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
-Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
-pl fileagent-agent,fileagent-chat -am test \
-Dtest=AgentModelConfigPortImplTest,AgentScopeModelFactoryTest \
-Dsurefire.failIfNoSpecifiedTests=false
```

**提交：** `feat(agent): 新增 AgentScope 模型配置边界`

## 任务 2：补齐文档目录与片段上下文 Port

**涉及文件：**

- 新增：`fileagent-api/src/main/java/com/demetrius/fileagent/api/port/KnowledgeCatalogPort.java`
- 新增：`fileagent-api/src/main/java/com/demetrius/fileagent/api/port/KnowledgeContextPort.java`
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/domain/knowledge/KnowledgeIndexRepository.java`
- 新增：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/knowledge/KnowledgeCatalogPortImpl.java`
- 新增：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/knowledge/KnowledgeContextPortImpl.java`
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/knowledge/ElasticsearchKnowledgeIndexRepository.java`
- 新增：`fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/knowledge/KnowledgeCatalogPortImplTest.java`
- 修改：`fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/knowledge/ElasticsearchKnowledgeIndexRepositoryTest.java`

**先写测试：**

```java
@Test
void findByChunkIdsShouldKeepRequestedOrderAndExcludeEmbedding() {
    List<KnowledgeChunk> chunks = repository.findByChunkIds(List.of("chunk-b", "chunk-a"));

    assertThat(chunks).extracting(KnowledgeChunk::chunkId)
            .containsExactly("chunk-b", "chunk-a");
    assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.embedding()).isEmpty());
}

@Test
void listShouldApplyScopeAndCapResultAtTwenty() {
    List<KnowledgeFile> files = catalogPort.list(new KnowledgeCatalogPort.Query("rag-a", "policy", 20));

    assertThat(files).hasSizeLessThanOrEqualTo(20);
    assertThat(files).allSatisfy(file -> assertThat(file.ragName()).isEqualTo("rag-a"));
}
```

**实现步骤：**

1. API 中新增 `KnowledgeCatalogPort`，其查询只接受可信的 `ragName`、`knowledgeTag` 和 `limit`；返回文件 ID、文件名、RAG 名称、标签、状态、分片数，不返回文件存储路径。
2. API 中新增 `KnowledgeContextPort`，其输入为 chunk ID 列表，返回 chunk ID、file ID、文件名、正文、sheet/section、chunkIndex 和分数相关元数据。单次最多 3 个 ID 的上限在 Agent 工具层再次校验。
3. 给文档领域的 `KnowledgeIndexRepository` 增加 `findByChunkIds(List<String>)`。Elasticsearch 实现使用 `_mget`，source excludes 排除 embedding，随后按请求 ID 顺序回排；复用现有 source-to-domain 映射，不能在 Agent 模块复制 Elasticsearch 文档结构。
4. `KnowledgeCatalogPortImpl` 复用 `RagFileRepository`，仅返回 `SUCCESS` 的文件，按创建时间倒序并强制 `1..20` 的 limit。
5. `KnowledgeContextPortImpl` 将领域 `KnowledgeChunk` 映射为 API DTO，不在 API DTO 中透出 ES 索引名、向量或原始 metadata map。

**验证：**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
"/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
-s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
-Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
-pl fileagent-document -am test \
-Dtest=KnowledgeCatalogPortImplTest,ElasticsearchKnowledgeIndexRepositoryTest,KnowledgeSearchPortImplTest \
-Dsurefire.failIfNoSpecifiedTests=false
```

**提交：** `feat(document): 提供 Agent 知识目录与上下文查询能力`

## 任务 3：定义 Agent 运行契约、预算和单机状态机

**涉及文件：**

- 新增：`fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/AgentRunStatus.java`
- 新增：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentRunEvent.java`
- 新增：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentRunSnapshot.java`
- 新增：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentRunCommand.java`
- 新增：`fileagent-api/src/main/java/com/demetrius/fileagent/api/port/AgentRuntimePort.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRun.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRunBudget.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/run/InMemoryAgentRunRegistry.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AgentProperties.java`
- 新增：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/domain/run/AgentRunTest.java`
- 新增：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/run/InMemoryAgentRunRegistryTest.java`

**先写测试：**

```java
@Test
void shouldAllowOnlyLegalStatusTransitions() {
    AgentRun run = AgentRun.pending("run-1", 8L, "trace-1", clock.instant());

    run.start(clock.instant());
    run.succeed(clock.instant());

    assertThat(run.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
    assertThatThrownBy(() -> run.cancel(clock.instant()))
            .isInstanceOf(IllegalStateException.class);
}

@Test
void shouldEvictCompletedRunAfterTtlButKeepRunningRun() {
    registry.save(completedRun(clock.instant().minus(Duration.ofMinutes(16))));
    registry.save(runningRun(clock.instant().minus(Duration.ofHours(1))));

    registry.evictExpired();

    assertThat(registry.find("completed")).isEmpty();
    assertThat(registry.find("running")).isPresent();
}
```

**实现步骤：**

1. 状态枚举固定为 `PENDING`、`RUNNING`、`WAITING_USER_INPUT`、`WAITING_APPROVAL`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`TIMED_OUT`。本期只会进入 PENDING/RUNNING/终态，保留两个 waiting 状态以保证后续 HITL 的 API 兼容性。
2. `AgentRunEvent` 只承载事件类型、runId、状态、步骤号、工具名、结果数量、耗时、增量文本、来源文件、错误码/展示信息和 traceId。工具事件不能带文档正文，`message.delta` 不能带模型推理内容。
3. `AgentRunCommand` 是服务端构造的内部调用契约，包含 runId、sessionId、traceId、prompt、历史消息、`KnowledgeScope` 和预算。HTTP 请求体只允许提交 `prompt`，不允许浏览器提交 scope、文件 ID、chunk ID、用户身份或模型参数。
4. `AgentRun` 实现状态转移、开始/结束时间、步骤数、模型调用数、已检索 chunk 白名单、取消标记和最终 assistant message ID；`AgentRunBudget` 强制 maxSteps=4、maxModelCalls=4、总工具结果 12000 字符、单工具结果 4000 字符。
5. `InMemoryAgentRunRegistry` 用并发容器维护 run、取消句柄与完成时间；完成记录保留 15 分钟，运行中的记录不因 TTL 被清理。进程重启后记录自然丢失，这一限制在 API 文档中明确说明。
6. `AgentProperties` 以 `fileagent.agent` 绑定以下默认值：`enabled=false`、`maxSteps=4`、`maxModelCalls=4`、`runTimeout=45s`、`toolTimeout=5s`、`maxOutputTokens=2048`、`maxToolResultCharacters=12000`、`completedRunTtl=15m`。

**验证：**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
"/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
-s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
-Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
-pl fileagent-agent -am test \
-Dtest=AgentRunTest,InMemoryAgentRunRegistryTest \
-Dsurefire.failIfNoSpecifiedTests=false
```

**提交：** `feat(agent): 建立受控 Agent 运行状态与预算`

## 任务 4：实现三个受限的只读工具与证据提示词

**涉及文件：**

- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/tool/AgentToolContext.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/SearchDocsTool.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/ListKnowledgeFilesTool.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/ReadDocumentContextTool.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/prompt/AgentPromptFactory.java`
- 新增：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/SearchDocsToolTest.java`
- 新增：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/ReadDocumentContextToolTest.java`
- 新增：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/prompt/AgentPromptFactoryTest.java`

**先写测试：**

```java
@Test
void readDocumentContextShouldRejectChunkNotReturnedByThisRunSearch() {
    AgentToolContext context = contextWithAllowedChunks("chunk-1");

    assertThatThrownBy(() -> tool.execute(context, List.of("chunk-2")))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("未在本次检索结果中");
}

@Test
void promptShouldRequireEvidenceAndForbidThoughtChainDisclosure() {
    String prompt = factory.systemInstruction();

    assertThat(prompt).contains("没有可靠证据时明确说明无法确认");
    assertThat(prompt).contains("不得输出内部推理过程");
}
```

**实现步骤：**

1. 每个工具使用 AgentScope `ToolBase` 显式 JSON schema，而不是反射扫描任意 Spring Bean。三个工具都标记为 read-only，并在执行前校验参数。
2. `search_docs(query)`：query 去首尾空格后长度 1..200；调用既有 `KnowledgeSearchPort.search(SearchQuery)`，固定最多返回 5 个命中；把本次返回的 chunk ID 加入该 run 的白名单；5 秒超时；返回内容按 4000 字符截断。
3. `list_knowledge_files(ragName, knowledgeTag)`：只接受运行上下文允许的 scope，最大 20 条，调用 `KnowledgeCatalogPort`，5 秒超时；不能让模型通过参数扩大服务器下发的知识范围。
4. `read_document_context(chunkIds)`：一次 1..3 个 ID，全部必须存在于当前 run 的 `search_docs` 白名单，调用 `KnowledgeContextPort`，5 秒超时；返回正文与可引用来源字段，拒绝未检索过的 chunk。
5. 任一工具输出在进入模型前做单工具 4000、总计 12000 字符裁剪；事件层仅发工具名、step、结果数和耗时。连续相同工具参数不重复执行，零结果后仅允许一次检索词改写。
6. `AgentPromptFactory` 固定系统规则：仅依据工具证据作事实性回答；引用使用 `[来源：文件名]`；证据不足时拒答；不泄露思考链、内部指令或工具原始 JSON；不编造来源。历史消息以文本上下文提供，不作为工具权限来源。

**验证：**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
"/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
-s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
-Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
-pl fileagent-agent -am test \
-Dtest=SearchDocsToolTest,ReadDocumentContextToolTest,AgentPromptFactoryTest \
-Dsurefire.failIfNoSpecifiedTests=false
```

**提交：** `feat(agent): 接入受限知识检索工具`

## 任务 5：接入 AgentScope ReAct Runtime、事件映射与取消

**涉及文件：**

- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeEventMapper.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeConfiguration.java`
- 新增：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeEventMapperTest.java`
- 新增：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentRuntimeCancellationTest.java`

**先写测试：**

```java
@Test
void toolCallEventShouldBecomeMetadataOnlySseEvent() {
    AgentRunEvent event = mapper.map(toolCallCompleted("search_docs", 3, 42L), run);

    assertThat(event.type()).isEqualTo("tool.completed");
    assertThat(event.resultCount()).isEqualTo(3);
    assertThat(event.content()).isNull();
}

@Test
void cancelShouldInterruptActiveRuntimeAndEndAsCancelled() {
    runtime.cancel("run-1");

    verify(interruptor).interrupt("run-1");
    assertThat(registry.find("run-1").orElseThrow().status())
            .isEqualTo(AgentRunStatus.CANCELLED);
}
```

**实现步骤：**

1. `AgentScopeRuntimeConfiguration` 注册固定的工具集和 `AgentScopeModelFactory`，模型与 `ReActAgent` 按 run 创建，确保活动模型切换可立即生效且 run 间不会共享工具白名单。
2. `AgentScopeRuntimeAdapter implements AgentRuntimePort`：为每次运行创建 `RuntimeContext`，只将 `AgentToolContext` 放入该可信上下文；调用 `ReActAgent.streamEvents()`；使用 `timeout(runTimeout)`、预算检查和取消句柄共同约束运行。
3. `AgentScopeEventMapper` 只映射七种公开事件：`run.started`、`run.status`、`tool.started`、`tool.completed`、`message.delta`、`sources`、`run.completed`，以及失败时的 `run.failed`。不要把 AgentScope 的 reasoning、内部消息、完整 tool result 或异常堆栈透传给客户端。
4. 文本增量仅拼接最终回答；成功结束时从实际工具证据生成去重来源列表并发 `sources`，然后发 `run.completed`。没有可用证据的拒答也是 `SUCCEEDED`，但最终回答必须明确说明无法确认。
5. `cancel(runId)` 幂等：运行中则调用 AgentScope `interrupt(RuntimeContext)` 并 dispose Reactor 订阅，状态转为 `CANCELLED`；已经终态则返回当前快照；不存在则由应用层映射为 404。
6. 用可控的 AgentScope 事件样本测试事件映射；取消测试通过可替换 interruptor 验证，不调用真实模型。

**验证：**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
"/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
-s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
-Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
-pl fileagent-agent -am test \
-Dtest=AgentScopeEventMapperTest,AgentRuntimeCancellationTest \
-Dsurefire.failIfNoSpecifiedTests=false
```

**提交：** `feat(agent): 实现 AgentScope ReAct 运行时`

## 任务 6：编排会话落库、SSE 控制器与运行查询接口

**涉及文件：**

- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentRunAppService.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/interfaces/AgentRunController.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/interfaces/dto/StartAgentRunRequest.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/interfaces/dto/AgentRunResponse.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/interfaces/AgentRunExceptionHandler.java`
- 新增：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/AgentRunAppServiceTest.java`
- 新增：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/interfaces/AgentRunControllerTest.java`
- 修改：`docs/API.md`

**先写测试：**

```java
@Test
void shouldPersistAssistantMessageOnlyAfterSuccessfulAgentRun() {
    when(runtime.run(any())).thenReturn(Flux.just(started(), delta("答案"), completed()));

    service.start(sessionId, new StartAgentRunRequest("问题"), traceId).collectList().block();

    verify(messageCommandPort).saveUserMessage(sessionId, "问题");
    verify(messageCommandPort).saveAssistantMessage(sessionId, "答案", any());
}

@Test
void postStreamFailureShouldKeepHttpOkAndSendRunFailedEvent() {
    webTestClient.post().uri("/api/sessions/{sessionId}/agent-runs", sessionId)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(body -> assertThat(body).contains("event:run.failed"));
}
```

**实现步骤：**

1. `AgentRunAppService` 先验证会话存在和 feature flag；从会话域加载允许持久化的历史消息与知识 scope；服务端生成 UUID runId、traceId；随后创建并保存用户消息。它不保存模型原始思考过程。
2. runtime 成功或基于证据的拒答结束后才保存 assistant 消息、引用和来源 metadata；模型失败、超时、取消时保留用户消息和已产生的 run 事件，不保存半截 assistant 消息。
3. `AgentRunController` 提供以下接口：

```text
POST /api/sessions/{sessionId}/agent-runs       text/event-stream
GET  /api/agent-runs/{runId}                    ApiResult<AgentRunResponse>
POST /api/agent-runs/{runId}/cancel             ApiResult<AgentRunResponse>
```

4. `POST` 在流开始前校验失败时沿用 chat 的 SSE 错误表达形式并保留 400/404/409/503 HTTP 状态；流已建立后的模型、工具、预算、超时失败统一发 `event:run.failed` 并以 `200` 结束流。响应头统一带 `X-Trace-Id`。
5. 定义稳定错误码：`AGENT_FEATURE_DISABLED`、`AGENT_RUN_NOT_FOUND`、`AGENT_RUN_TIMEOUT`、`AGENT_RUN_CANCELLED`、`AGENT_BUDGET_EXHAUSTED`、`AGENT_TOOL_TIMEOUT`、`AGENT_MODEL_UNAVAILABLE`。错误事件只写可展示中文信息，详细异常仅记服务端日志并携带 traceId。
6. API 文档写清 SSE 事件字段、状态转移、取消语义、15 分钟运行记录 TTL、默认开关关闭及不自动回退 `/chat` 的约束。

**验证：**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
"/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
-s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
-Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
-pl fileagent-agent -am test \
-Dtest=AgentRunAppServiceTest,AgentRunControllerTest \
-Dsurefire.failIfNoSpecifiedTests=false
```

**提交：** `feat(agent): 提供可观测 Agent SSE 接口`

## 任务 7：在现有静态页面加入受控 Agent 模式

**涉及文件：**

- 修改：`fileagent-starter/src/main/resources/static/index.html`
- 修改：`fileagent-starter/src/main/resources/static/app.js`
- 修改：`fileagent-starter/src/main/resources/static/app.css`
- 修改：`fileagent-starter/src/test/java/com/demetrius/fileagent/starter/StaticPageContractTest.java`
- 修改：`docs/API.md`

**先写测试：**

```java
@Test
void pageShouldContainAgentModeSwitchAndClientShouldUseAgentRunEndpoint() throws IOException {
    assertThat(read("static/index.html")).contains("agent-mode-toggle");
    assertThat(read("static/app.js")).contains("/agent-runs");
    assertThat(read("static/app.js")).contains("run.completed");
}
```

**实现步骤：**

1. 在聊天输入区增加 `id="agent-mode-toggle"` 的 checkbox 开关和简短标签，默认关闭。保留同一个文本框、发送按钮和历史渲染区域，不能复制一套聊天页面。
2. `app.js` 按开关选择既有 `streamChat()` 或新的 `streamAgentRun()`；Agent SSE 消费 `run.started`、工具状态、文本增量、来源、完成/失败事件。工具状态只渲染简短进行态，完成或失败后自动收起，不展示工具参数与原始资料。
3. 收到 `run.started` 后缓存 runId；用户停止生成、网络中断或页面卸载时，除中止 SSE 外额外尽力调用取消接口。完成、拒答、失败、取消后都恢复发送按钮状态。
4. 当后端返回 `AGENT_FEATURE_DISABLED` 时显示错误而不是静默降级到普通 `/chat`，避免用户误以为使用了 Agent。
5. CSS 复用现有配色与组件尺寸，只增加紧凑 toggle 和运行状态行，保证窄屏不溢出。

**验证：**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
"/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
-s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
-Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
-pl fileagent-starter -am test \
-Dtest=StaticPageContractTest \
-Dsurefire.failIfNoSpecifiedTests=false
```

**提交：** `feat(starter): 增加 Agent 问答模式开关`

## 任务 8：建立独立 Agent 评测 Profile 与发布门禁

**涉及文件：**

- 新增：`fileagent-api/src/main/java/com/demetrius/fileagent/api/port/AgentAnswerEvaluationPort.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentAnswerEvaluationService.java`
- 新增：`fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/application/AgentEvaluationRunner.java`
- 新增：`fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/application/AgentEvaluationReportWriter.java`
- 新增：`fileagent-evaluation/src/main/resources/evaluation/agent-v1/cases.jsonl`
- 新增：`fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/application/AgentEvaluationRunnerTest.java`
- 新增：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/AgentAnswerEvaluationServiceTest.java`
- 修改：`fileagent-evaluation/scripts/run-evaluation.sh`
- 新增：`fileagent-evaluation/scripts/run-agent-evaluation.sh`
- 修改：`docs/TESTING.md`

**先写测试：**

```java
@Test
void reportShouldSeparateAnswerQualityFromAgentBehavior() {
    AgentEvaluationReport report = runner.run("agent-v1", fakeAgentPort);

    assertThat(report.answerMetrics().answerDecisionAccuracy()).isEqualTo(1.0d);
    assertThat(report.agentMetrics().budgetComplianceRate()).isEqualTo(1.0d);
    assertThat(report.agentMetrics().citationOnlyFromRetrievedRate()).isEqualTo(1.0d);
}

@Test
void evaluationShouldNotPersistConversationMessages() {
    evaluationService.evaluate(caseInput());

    verifyNoInteractions(messageCommandPort);
}
```

**实现步骤：**

1. 不修改已有 `v1` 30 题基线集和 `run-evaluation.sh` 的含义。新增 `agent-v1` profile，数据格式继续使用可扩展 JSONL；后续增加题目只追加一行，不修改 runner 代码。
2. `AgentAnswerEvaluationPort` 返回最终答案、拒答决定、检索命中、最终引用、步骤数、模型调用数、工具调用序列、耗时、终态和失败码。`AgentAnswerEvaluationService` 使用同一 Runtime，但启用 evaluation mode：不创建 session/message、不写数据库，只保留评测所需的受控观察数据。
3. 首版 `agent-v1` 放入 8 个行为场景：直接证据回答、需检索回答、零结果拒答、检索后读上下文、重复工具调用阻止、超预算终止、工具超时、取消。知识事实题复用已生成的评测资料，避免再次引入不受控文档。
4. `AgentEvaluationRunner` 复用现有 Judge 的答案质量指标（回答决策、事实覆盖、忠实度、引用正确率），另输出 Agent 行为指标：预算遵守率、工具白名单通过率、引用仅来自检索结果比率、拒答正确率、平均步骤数、平均耗时和失败分类。
5. 报告输出路径独立为 `target/evaluation/agent-v1/<UTC 时间戳>/`，绝不覆盖 RAG baseline；摘要只展示未过门禁指标及其最具代表性的 3 道题，详细原始结果放在 `details.jsonl`。
6. 初始门禁不阻止默认 RAG 发布：Agent 必须满足 `budgetComplianceRate=1.0`、`citationOnlyFromRetrievedRate=1.0`、`refusalDecisionAccuracy>=0.95`；答案质量阈值由 `agent-v1` 首次稳定基线报告确认后写入。feature flag 默认仍为 false，只有评测连续通过后才允许在测试环境打开。
7. `run-agent-evaluation.sh` 只调用已部署并已配置模型的 FileAgent HTTP 接口，沿用 `FILEAGENT_BASE_URL` 和 `FILEAGENT_EVALUATION_TOKEN`，不要求重复配置 chat/vector/reranker API Key。

**验证：**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
"/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
-s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
-Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
-pl fileagent-evaluation,fileagent-agent -am test \
-Dtest=AgentEvaluationRunnerTest,AgentAnswerEvaluationServiceTest \
-Dsurefire.failIfNoSpecifiedTests=false
```

**提交：** `feat(evaluation): 增加 Agentic RAG 独立评测门禁`

## 任务 9：全量回归、真实模型冒烟与交付检查

**涉及文件：**

- 修改：`README.md`（仅补充 Agent 默认关闭、启用方式、评测命令和运行限制）
- 修改：`docs/API.md`
- 修改：`docs/TESTING.md`

**执行步骤：**

1. 运行完整 Maven 测试，确认既有 RAG 评测和 chat SSE 回归未受影响。
2. 用 `fileagent.agent.enabled=false` 启动应用，验证 Agent 接口返回 `AGENT_FEATURE_DISABLED` 且旧 `/chat` 正常。
3. 仅在本地或测试环境启用 `fileagent.agent.enabled=true`，以已部署且已配置 DeepSeek `deepseek-v4-pro` 的应用执行四条人工冒烟：有依据问答、无依据拒答、取消、超时。检查 SSE 中 `X-Trace-Id`、工具事件不含正文、最终引用只来自工具检索。
4. 执行 `./fileagent-evaluation/scripts/run-evaluation.sh` 和 `./fileagent-evaluation/scripts/run-agent-evaluation.sh`，确认两份报告输出目录不同，且 Agent 门禁满足强制安全指标。
5. 检查 `git status --short`、`git diff --check`，只暂存本期文件；保留已有未跟踪的 `AnswerCitationExtractorTest.java`，不纳入本期提交。

**验证：**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
"/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
-s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
-Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" test

git diff --check
git status --short
```

**最终提交：** `feat(agent): 完成最小 Agentic RAG 运行时`

## 验收清单

- [ ] 默认关闭时，旧 `/chat` 行为与当前基线一致，Agent 不会自动接管请求。
- [ ] 启用后，Agent 只拥有 `search_docs`、`list_knowledge_files`、`read_document_context` 三个只读工具。
- [ ] `read_document_context` 无法读取未被本 run 检索到的 chunk。
- [ ] 一次运行不超过 4 次工具/推理步骤和 45 秒，取消、超时、工具失败都有可追踪终态。
- [ ] SSE 不泄露思考链、API Key、ES DSL、工具原始结果或异常堆栈。
- [ ] 成功/拒答才落 assistant 消息；失败、取消、超时保留用户提问和已有观测，不落半截答案。
- [ ] `/api/agent-runs/{runId}` 可在 15 分钟内查询，超出 TTL 返回明确的未找到错误。
- [ ] Agent 与 baseline RAG 评测分目录输出，Agent 安全门禁通过才允许在测试环境打开开关。
