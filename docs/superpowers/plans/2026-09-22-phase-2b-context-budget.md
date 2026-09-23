# Phase 2B 上下文预算与细粒度证据读取实现计划

> **面向 AI 代理的工作者：** 必需子技能：默认使用 `superpowers:executing-plans` 按批次执行；只有用户明确批准委派后，才可使用 `superpowers:subagent-driven-development`。步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 在不改变固定 RAG 和非 Adaptive Agent 行为的前提下，为 Adaptive Agent 增加持久化滚动摘要、search-to-read 证据读取、统一字符与 Token 预算、可观测快照及 `context-v1` 质量门禁。

**架构：** 继续使用现有 `AgentRun` 作为单次运行状态的唯一事实源，`AgentRunBudget` 保存不可变上限，`AgentToolContext` 只负责把两者和现有 Port 传给工具。会话摘要由 session 域持久化并以 CAS 更新；Agent 域只通过 API Port 读取和写入。搜索、目录、精读形成逐级授权链，所有模型调用和工具输出共享同一个 Run 预算。

**技术栈：** Java 21、Spring Boot 4.1、Spring AI 2.0、AgentScope Java 2.0.1、Spring Data JPA、H2、Elasticsearch 9、Reactor、JUnit 5、Mockito、Maven。

---

## 实施边界

- Phase 2B 只在 `fileagent.agent.adaptive-retrieval-enabled=true` 时启用。
- 固定 `POST /api/sessions/{id}/chat` 与非 Adaptive Agent 保持现状。
- `maxSteps` 保持 `8`：它只统计工具调用，不统计历史摘要和最终回答。
- Adaptive `maxModelCalls` 调整为 `6`，其中滚动摘要也计数；最后一次模型调用移除工具 Schema。
- 原始消息不删除；超出预算的旧历史滚动压缩到 `chat_session.summary`。
- 不增加 `AgentContextBudget`、`BudgetManager` 或第二套运行上下文。
- 不计算金额成本，只记录供应商返回的真实 Token。
- 不实现 Phase 2C 的页码坐标、`quoteHash`、文档版本和回答后引用核验。
- 当前工作区未跟踪的 `fileagent-chat/src/test/java/com/demetrius/fileagent/chat/application/AnswerCitationExtractorTest.java` 不属于本计划，不修改也不暂存。

## 文件结构

### 新增文件

```text
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentHistoryService.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/port/HistorySummaryPort.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentBudgetMiddleware.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeHistorySummaryAdapter.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/GetDocumentOutlineTool.java
fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/AgentHistoryServiceTest.java
fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentBudgetMiddlewareTest.java
fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeHistorySummaryAdapterTest.java
fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/GetDocumentOutlineToolTest.java
fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/ListKnowledgeFilesToolTest.java
fileagent-session/src/test/java/com/demetrius/fileagent/session/infrastructure/SessionSummaryJpaTest.java
fileagent-session/src/test/java/com/demetrius/fileagent/session/SessionJpaTestBootstrap.java
fileagent-evaluation/src/main/resources/evaluation/context-v1/cases/agent.jsonl
fileagent-evaluation/src/main/resources/evaluation/context-v1/corpus/context-budget-handbook.md
fileagent-evaluation/src/main/resources/evaluation/context-v1/corpus/context-budget-procedure.md
fileagent-evaluation/src/main/resources/evaluation/context-v1/gate.json
```

所有新建 Java 文件都必须包含：

```java
/**
 * @author raosaijie
 */
```

### 主要修改文件

```text
fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentRunCommand.java
fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentRunSnapshot.java
fileagent-api/src/main/java/com/demetrius/fileagent/api/port/AgentAnswerEvaluationPort.java
fileagent-api/src/main/java/com/demetrius/fileagent/api/port/KnowledgeContextPort.java
fileagent-api/src/main/java/com/demetrius/fileagent/api/port/SessionMessagePort.java
fileagent-api/src/main/java/com/demetrius/fileagent/api/port/SessionQueryPort.java
fileagent-session/src/main/java/com/demetrius/fileagent/session/domain/SessionEntity.java
fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/MessageJpaRepository.java
fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/SessionJpaRepository.java
fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/SessionMessagePortImpl.java
fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/SessionQueryPortImpl.java
fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/knowledge/KnowledgeContextPortImpl.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentRunAppServiceImpl.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/prompt/AgentPromptFactory.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/tool/AgentToolContext.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRun.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRunBudget.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AdaptiveRetrievalProperties.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AgentProperties.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeModelFactory.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/ListKnowledgeFilesTool.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/ReadDocumentContextTool.java
fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/SearchDocsTool.java
fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationObservation.java
fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationReport.java
fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationReportWriter.java
fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationRunner.java
fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentQualityGateEvaluator.java
fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/QualityGateConfig.java
fileagent-evaluation/scripts/upload-corpus.sh
fileagent-starter/src/main/resources/application.yml
docs/API.md
docs/SKELETON.md
docs/TESTING.md
docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md
fileagent-evaluation/README.md
```

---

## 任务 1：持久化会话滚动摘要与 CAS 契约

**文件：**

- 修改：`fileagent-api/src/main/java/com/demetrius/fileagent/api/port/SessionQueryPort.java`
- 修改：`fileagent-api/src/main/java/com/demetrius/fileagent/api/port/SessionMessagePort.java`
- 修改：`fileagent-session/src/main/java/com/demetrius/fileagent/session/domain/SessionEntity.java`
- 修改：`fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/MessageJpaRepository.java`
- 修改：`fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/SessionJpaRepository.java`
- 修改：`fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/SessionQueryPortImpl.java`
- 修改：`fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/SessionMessagePortImpl.java`
- 修改：`fileagent-session/pom.xml`
- 新增：`fileagent-session/src/test/java/com/demetrius/fileagent/session/infrastructure/SessionSummaryJpaTest.java`
- 新增：`fileagent-session/src/test/java/com/demetrius/fileagent/session/SessionJpaTestBootstrap.java`
- 修改：`fileagent-session/src/test/java/com/demetrius/fileagent/session/infrastructure/SessionMessagePortImplTest.java`
- 修改：`docs/SKELETON.md`

- [ ] **步骤 1：先写 CAS 持久化失败测试**

覆盖以下行为：

1. 新会话摘要为空、检查点为空、版本为 `0`。
2. `expectedVersion=0` 首次更新成功，版本变为 `1`。
3. 相同旧版本再次更新返回 `false`，不能覆盖新摘要。
4. 小于或等于当前检查点的更新返回 `false`，检查点不能回退。
5. `listMessagesAfter(sessionId, checkpoint)` 只返回检查点之后的消息且按时间正序。

测试使用 Spring Boot 4 的 JPA 测试注解：

```java
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class SessionSummaryJpaTest {
    // 通过真实 H2 验证 JPQL 条件更新，而不是只 Mock Repository。
}
```

- [ ] **步骤 2：运行测试，确认因契约和字段不存在而失败**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  "/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
  -s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
  -Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
  -pl fileagent-session -am test \
  -Dtest=SessionSummaryJpaTest,SessionMessagePortImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：编译失败，指出摘要查询或 CAS 更新 API 尚不存在。

- [ ] **步骤 3：扩展跨域 Port，不暴露 session 实体**

`SessionQueryPort` 增加：

```java
SessionSummary getSummary(Long sessionId);

List<MessageDto> listMessagesAfter(Long sessionId, Long afterMessageId);

record SessionSummary(
        String content,
        Long throughMessageId,
        Instant updatedAt,
        long version,
        String sourceHash) {

    public static SessionSummary empty() {
        return new SessionSummary(null, null, null, 0L, null);
    }
}
```

`SessionMessagePort` 增加：

```java
boolean updateSummary(
        Long sessionId,
        long expectedVersion,
        String summary,
        Long throughMessageId,
        String sourceHash);
```

- [ ] **步骤 4：增加实体字段和只向前推进的条件更新**

`SessionEntity` 增加 `summary`、`summaryThroughMessageId`、`summaryUpdatedAt`、`summaryVersion`、`summarySourceHash`。`summary` 使用 `@Lob` 和 `CLOB`，`summaryVersion` 初始值为 `0L`。

`SessionJpaRepository` 使用单条 JPQL 条件更新：

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
        update SessionEntity s
           set s.summary = :summary,
               s.summaryThroughMessageId = :throughMessageId,
               s.summaryUpdatedAt = :updatedAt,
               s.summaryVersion = s.summaryVersion + 1,
               s.summarySourceHash = :sourceHash
         where s.id = :sessionId
           and s.summaryVersion = :expectedVersion
           and (s.summaryThroughMessageId is null
                or s.summaryThroughMessageId < :throughMessageId)
        """)
int updateSummary(...);
```

不使用 JPA `@Version`，避免普通消息追加造成无关版本冲突。

- [ ] **步骤 5：实现查询和写入适配器**

`MessageJpaRepository` 增加按消息 ID 查询；`SessionQueryPortImpl` 在会话不存在时沿用现有异常语义；`SessionMessagePortImpl.updateSummary` 在事务中调用条件更新并返回 `updatedRows == 1`。

- [ ] **步骤 6：补测试依赖和架构文档**

仅在 `fileagent-session/pom.xml` 增加 `spring-boot-data-jpa-test` 测试依赖，并在 `docs/SKELETON.md` 的依赖表同步说明。项目当前依赖 `ddl-auto=update`，本任务不引入 Flyway；文档明确生产数据库需要在上线阶段补正式 DDL。

- [ ] **步骤 7：运行任务 1 测试，确认通过**

运行步骤 2 的同一命令。预期：测试通过，CAS 旧版本和检查点回退用例均返回 `false`。

- [ ] **步骤 8：提交任务 1**

```bash
git add fileagent-api/src/main/java/com/demetrius/fileagent/api/port/SessionQueryPort.java \
  fileagent-api/src/main/java/com/demetrius/fileagent/api/port/SessionMessagePort.java \
  fileagent-session/src/main/java/com/demetrius/fileagent/session/domain/SessionEntity.java \
  fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/MessageJpaRepository.java \
  fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/SessionJpaRepository.java \
  fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/SessionMessagePortImpl.java \
  fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/SessionQueryPortImpl.java \
  fileagent-session/src/test/java/com/demetrius/fileagent/session/infrastructure/SessionSummaryJpaTest.java \
  fileagent-session/src/test/java/com/demetrius/fileagent/session/infrastructure/SessionMessagePortImplTest.java \
  fileagent-session/pom.xml docs/SKELETON.md
git commit -m "feat(session): 持久化会话滚动摘要"
```

---

## 任务 2：把字符、调用和 Token 预算收口到 AgentRun

**文件：**

- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AgentProperties.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AdaptiveRetrievalProperties.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRunBudget.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRun.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/tool/AgentToolContext.java`
- 修改：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentRunSnapshot.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java`
- 修改：`fileagent-starter/src/main/resources/application.yml`
- 修改测试：对应的 `AgentRunTest`、`AdaptiveRetrievalPropertiesTest`、`AgentScopeRuntimeAdapterTest`、`AgentRunQueryControllerTest`

- [ ] **步骤 1：写预算聚合根失败测试**

测试必须覆盖：

- Adaptive 预算为模型调用 `6`、工具步数 `8`；非 Adaptive 仍为 `4` 和 `8`。
- 字符用量只能增加，不能出现负数。
- 工具结果累计超过 `12000` 后拒绝继续扩展并记录 `TOOL_BUDGET_EXHAUSTED`。
- 真实 Token 累计达到 `60000` 后记录 `TOKEN_BUDGET_EXHAUSTED`，但 Run 不直接失败。
- `allowedFileIds` 和 `allowedChunkIds` 均为服务端维护集合。
- Snapshot 只输出计数和原因，不输出 prompt、摘要或工具正文。

建议断言：

```java
assertThat(adaptiveBudget.maxSteps()).isEqualTo(8);
assertThat(adaptiveBudget.maxModelCalls()).isEqualTo(6);
assertThat(run.shouldStopToolExpansion(adaptiveBudget)).isTrue();
assertThat(run.budgetReasons()).contains("TOKEN_BUDGET_EXHAUSTED");
```

- [ ] **步骤 2：运行测试，确认新增 API 尚不存在**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  "/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
  -s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
  -Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
  -pl fileagent-agent -am test \
  -Dtest=AgentRunTest,AdaptiveRetrievalPropertiesTest,AgentScopeRuntimeAdapterTest,AgentRunQueryControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：编译失败或断言失败，证明新预算尚未生效。

- [ ] **步骤 3：扩展配置并做启动校验**

`AgentProperties` 增加默认值：

```text
maxPromptCharacters=8000
maxHistoryCharacters=8000
maxSummaryCharacters=2000
maxRecentHistoryCharacters=6000
searchSnippetCharacters=500
outlineMaxEntries=50
readMaxChunks=3
maxTotalTokens=60000
```

`AdaptiveRetrievalProperties` 增加 `maxModelCalls=6`。校验摘要加最近消息预算不超过历史总预算、单次工具结果不超过累计工具结果、所有限制为正数。

- [ ] **步骤 4：扩展不可变 AgentRunBudget**

`AgentRunBudget` 保存所有固定上限；通过 `AgentProperties` 和 Adaptive 开关创建，不能在工具内临时读取配置。保留 `maxSteps=8`，不要因摘要调用增加 Step。

- [ ] **步骤 5：扩展 AgentRun 实际用量和授权状态**

直接在现有 `AgentRun` 增加：

```text
allowedFileIds
historyCharacters
summaryCharacters
searchSnippetCharacters
documentReadCharacters
toolResultCharacters
inputTokens
outputTokens
totalTokens
historyCompressed
budgetReasons
```

所有计数、权限检查和预算原因去重均由 `AgentRun` 方法完成。至少支持这些原因：

```text
HISTORY_SUMMARIZED
HISTORY_BACKLOG_PARTIAL
SUMMARY_FAILED_FALLBACK
SUMMARY_CONCURRENT_UPDATE
SEARCH_RESULT_TRUNCATED
DOCUMENT_READ_TRUNCATED
TOOL_BUDGET_EXHAUSTED
TOKEN_BUDGET_EXHAUSTED
```

- [ ] **步骤 6：简化 AgentToolContext 并扩展 Snapshot**

`AgentToolContext` 只保存现有对象：

```java
public record AgentToolContext(
        AgentRun run,
        AgentRunBudget budget,
        KnowledgeSearchPort knowledgeSearchPort,
        KnowledgeCatalogPort knowledgeCatalogPort,
        KnowledgeContextPort knowledgeContextPort,
        KnowledgeScope scope) {
}
```

`AgentRunSnapshot` 直接增加预算字段，不再创建 Budget DTO。

- [ ] **步骤 7：运行测试，确认 `maxSteps=8` 与 Adaptive `maxModelCalls=6` 同时成立**

运行步骤 2 的命令。预期：全部通过。

- [ ] **步骤 8：提交任务 2**

```bash
git add fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AgentProperties.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AdaptiveRetrievalProperties.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRun.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRunBudget.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/tool/AgentToolContext.java \
  fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentRunSnapshot.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/domain/run/AgentRunTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/config/AdaptiveRetrievalPropertiesTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapterTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/interfaces/AgentRunQueryControllerTest.java \
  fileagent-starter/src/main/resources/application.yml
git commit -m "feat(agent): 建立上下文与 Token 预算"
```

---

## 任务 3：实现有界历史组装与持久化滚动摘要

**文件：**

- 修改：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentRunCommand.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/port/HistorySummaryPort.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentHistoryService.java`
- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeHistorySummaryAdapter.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentRunAppServiceImpl.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeModelFactory.java`
- 新增及修改测试：`AgentHistoryServiceTest`、`AgentScopeHistorySummaryAdapterTest`、`AgentRunAppServiceImplTest`、`AgentScopeRuntimeAdapterTest`、`AgentScopeModelFactoryTest`

- [ ] **步骤 1：写历史选择与摘要失败测试**

测试至少覆盖：

1. 当前 prompt 超过 8000 字符时，在保存 USER 消息前抛 `AGENT_PROMPT_TOO_LARGE`。
2. 未超 8000 字符时不调用摘要模型。
3. 最近消息从新到旧选取完整消息，最终按正序交给模型；不截断单条消息。
4. 旧摘要最多 2000 字符，新进入摘要的连续旧消息最多使用剩余 6000 字符。
5. 超大历史积压只推进连续检查点并记录 `HISTORY_BACKLOG_PARTIAL`。
6. 摘要 JSON 不合法、模型失败时使用旧摘要和最近历史并记录 `SUMMARY_FAILED_FALLBACK`。
7. CAS 冲突时不覆盖，重新读取较新的摘要并记录 `SUMMARY_CONCURRENT_UPDATE`。
8. 当前 prompt 不进入摘要来源，且最终模型输入只出现一次。
9. Adaptive 关闭时沿用现有最近 10 条历史行为，不触发摘要。

- [ ] **步骤 2：运行测试并确认失败**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  "/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
  -s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
  -Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
  -pl fileagent-agent -am test \
  -Dtest=AgentHistoryServiceTest,AgentScopeHistorySummaryAdapterTest,AgentRunAppServiceImplTest,AgentScopeRuntimeAdapterTest,AgentScopeModelFactoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：新增服务和 Port 不存在，测试先红。

- [ ] **步骤 3：扩展 AgentRunCommand 的历史基线**

命令增加摘要内容、检查点、摘要版本和来源哈希；保留一个六参数兼容构造器，避免非 Adaptive 测试和评测调用被迫感知摘要细节：

```java
public AgentRunCommand(
        String runId,
        Long sessionId,
        String traceId,
        String prompt,
        List<MessageDto> history,
        KnowledgeScope knowledgeScope) {
    this(runId, sessionId, traceId, prompt,
            null, null, 0L, null, history, knowledgeScope);
}
```

- [ ] **步骤 4：定义最小摘要 Port**

```java
public interface HistorySummaryPort {

    Mono<SummaryResult> summarize(String existingSummary, List<MessageDto> messages);

    record SummaryResult(String summary, int inputTokens, int outputTokens, int totalTokens) {
    }
}
```

摘要适配器使用温度 `0`、固定 JSON Schema、`maxTokens=2048`；校验五个数组字段均存在且总字符不超过 2000。Prompt 明确只能提取原消息中的显式事实，保留否定、数字、单位、日期、文件名和 ID。

- [ ] **步骤 5：在 AgentHistoryService 中实现选择算法**

服务返回内部嵌套 record `PreparedHistory`，不新增通用 Context 类型。算法固定为：

1. 预算内保留完整最近消息。
2. 旧消息从检查点开始取连续批次。
3. 摘要成功后用 CAS 写入。
4. CAS 冲突则重新读取最新摘要。
5. 模型失败或摘要非法则受控降级。

哈希基于“旧摘要版本 + 新摘要消息 ID 和正文”的 UTF-8 SHA-256，日志只记录哈希，不记录正文。

- [ ] **步骤 6：把摘要调用纳入同一 Run**

Runtime 创建并注册 `AgentRun`、发送 `run.started` 后，再调用 `AgentHistoryService`。摘要的模型次数和 Token 直接累计到同一个 `AgentRun`，并受相同总超时控制。摘要不调用工具，因此不增加 `stepCount`。

- [ ] **步骤 7：运行测试并确认通过**

运行步骤 2 的命令。额外断言：有摘要时 `modelCallCount` 增加 1，但 `stepCount` 仍为 0。

- [ ] **步骤 8：提交任务 3**

```bash
git add fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentRunCommand.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentHistoryService.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentRunAppServiceImpl.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/port/HistorySummaryPort.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeHistorySummaryAdapter.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeModelFactory.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/AgentHistoryServiceTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/AgentRunAppServiceImplTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeHistorySummaryAdapterTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeModelFactoryTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapterTest.java
git commit -m "feat(agent): 增加持久化滚动摘要"
```

---

## 任务 4：提供文档目录查询 Port

**文件：**

- 修改：`fileagent-api/src/main/java/com/demetrius/fileagent/api/port/KnowledgeContextPort.java`
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/knowledge/KnowledgeContextPortImpl.java`
- 修改：`fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/knowledge/KnowledgeContextPortImplTest.java`

- [ ] **步骤 1：写目录分页失败测试**

覆盖：只返回 CHILD chunk；按 `chunkIndex` 升序；`afterChunkIndex` 为排他游标；最多 50 项；多取 1 项判断 `nextChunkIndex`；缺少真实 section 元数据时只回退为 chunkIndex 和短预览，不生成虚假章节名。

- [ ] **步骤 2：运行测试并确认 outline API 不存在**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  "/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
  -s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
  -Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
  -pl fileagent-document -am test \
  -Dtest=KnowledgeContextPortImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **步骤 3：扩展 KnowledgeContextPort**

```java
DocumentOutlinePage outline(Long fileId, int afterChunkIndex, int limit);

record DocumentOutlinePage(
        List<DocumentOutlineItem> items,
        Integer nextChunkIndex) {
}

record DocumentOutlineItem(
        String chunkId,
        String parentId,
        String filename,
        String sourceType,
        String sheetName,
        String sectionId,
        Integer rowIndex,
        int chunkIndex,
        String preview) {
}
```

- [ ] **步骤 4：实现 Elasticsearch 元数据映射**

复用 `KnowledgeIndexRepository.findByFileId`，过滤 CHILD，按 chunkIndex 排序后分页。XLSX 输出 sheet、section、row；其他格式只输出实际存在的元数据。预览按 Unicode code point 安全截断，不能切坏代理对。

- [ ] **步骤 5：运行测试并提交**

```bash
git add fileagent-api/src/main/java/com/demetrius/fileagent/api/port/KnowledgeContextPort.java \
  fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/knowledge/KnowledgeContextPortImpl.java \
  fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/knowledge/KnowledgeContextPortImplTest.java
git commit -m "feat(document): 提供文档目录读取"
```

---

## 任务 5：实现 search-to-read 授权链和共享工具预算

**文件：**

- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/GetDocumentOutlineTool.java`
- 新增：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/GetDocumentOutlineToolTest.java`
- 修改：`SearchDocsTool.java`、`ReadDocumentContextTool.java`、`ListKnowledgeFilesTool.java`
- 修改：对应三个现有测试类
- 修改：`AgentScopeRuntimeAdapter.java`、`AgentPromptFactory.java`
- 修改：`AdaptiveRetrievalProperties.java`
- 修改：`AgentScopeRuntimeAdapterTest.java`、`AgentPromptFactoryTest.java`、`AdaptiveRetrievalPropertiesTest.java`

- [ ] **步骤 1：先写四类工具的失败测试**

断言以下规则：

- `search_docs` 每个摘要最多 500 字符，整次结果最多 4000 字符，只授权实际返回的 CHILD、真实 parentId 和 fileId。
- 相同 chunkId 去重；排序保持已有相关性顺序，同分按 fileId、chunkIndex、chunkId。
- 预算截断时先保留不同文件的最高相关项，再按全局相关性补齐。
- `list_knowledge_files` 只授权实际返回的 fileId，也消耗共享工具结果预算。
- `get_document_outline` 只接受已授权 fileId，最多 50 项；返回项的 chunkId 和 parentId 成为可精读白名单。
- `read_document_context` 只读白名单，单次最多 3 个 chunk，整次共用 4000 字符，不是每个 chunk 各 4000。
- 四个知识工具累计最多 12000 字符；耗尽后返回受控结果，不触发 Elasticsearch 调用。
- 搜索和精读截断分别记录对应原因。
- Adaptive 各检索档位都关闭自动父块扩展。

- [ ] **步骤 2：运行工具测试并确认失败**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  "/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
  -s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
  -Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
  -pl fileagent-agent -am test \
  -Dtest=SearchDocsToolTest,ReadDocumentContextToolTest,ListKnowledgeFilesToolTest,GetDocumentOutlineToolTest,AgentPromptFactoryTest,AdaptiveRetrievalPropertiesTest,AgentScopeRuntimeAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **步骤 3：修正 SearchDocsTool 总量截断和授权时机**

先完成去重、确定性排序和跨文件优先选择，再渲染到剩余单次及累计预算。只有成功进入最终工具结果的条目才能调用 `addAllowedChunk`、`addAllowedFile` 和 `addRetrievedHit`。

- [ ] **步骤 4：实现目录工具和文件授权**

`list_knowledge_files` 的返回文件进入 `allowedFileIds`；`get_document_outline` 拒绝未授权 fileId。目录响应包含游标但不包含整段正文。

- [ ] **步骤 5：修正精读总量预算**

按请求顺序返回，缺失 chunk 跳过；累计到整次 4000 或 Run 剩余预算即停止。字符截断统一使用 code point 工具方法，避免在各工具复制算法。

- [ ] **步骤 6：更新 Prompt 和 Runtime 工具注册**

Prompt 告诉模型：先看搜索摘要，信息不足再看目录或精读；知识库无答案可用通用知识，但必须以“以下内容基于通用知识”标识，且不得伪造知识库引用；基础设施失败不能当成零命中。

- [ ] **步骤 7：验证 `maxSteps=8` 的边界**

在 Runtime 测试构造 8 次工具调用，断言前 8 步可按现有边界执行、第 9 步被拒绝；再构造摘要加最终回答，断言两者不增加 Step。该测试是本期不调整 `maxSteps` 的回归保护。

- [ ] **步骤 8：运行测试并提交**

```bash
git add fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/prompt/AgentPromptFactory.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AdaptiveRetrievalProperties.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/GetDocumentOutlineTool.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/ListKnowledgeFilesTool.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/ReadDocumentContextTool.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/SearchDocsTool.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/prompt/AgentPromptFactoryTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/config/AdaptiveRetrievalPropertiesTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapterTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/GetDocumentOutlineToolTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/ListKnowledgeFilesToolTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/ReadDocumentContextToolTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/SearchDocsToolTest.java
git commit -m "feat(agent): 实现细粒度证据读取"
```

---

## 任务 6：收口模型输出、调用次数与真实 Token

**文件：**

- 新增：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentBudgetMiddleware.java`
- 新增：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentBudgetMiddlewareTest.java`
- 修改：`AgentScopeModelFactory.java`、`AgentScopeRuntimeAdapter.java`
- 修改：对应 Runtime 与 ModelFactory 测试

- [ ] **步骤 1：写模型预算失败测试**

覆盖：

1. 普通调用使用 `maxTokens=2048`。
2. 摘要模型温度为 0，仍受输出上限约束。
3. `ModelCallEndEvent` 的 input/output/total token 累加到 Run。
4. Adaptive 第 6 次模型调用收到空工具列表，无法请求第 7 次调用。
5. Token 达到软上限后的下一次调用也收到空工具列表。
6. 非 Adaptive 仍最多 4 次模型调用。
7. 模型调用超限是硬失败，Token 软上限只停止工具扩展并允许最后回答。

- [ ] **步骤 2：运行测试并确认失败**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  "/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
  -s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
  -Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
  -pl fileagent-agent -am test \
  -Dtest=AgentBudgetMiddlewareTest,AgentScopeModelFactoryTest,AgentScopeRuntimeAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **步骤 3：在 ModelFactory 统一设置输出上限**

通过 `GenerateOptions.builder().maxTokens(properties.getMaxOutputTokens())` 设置正式回答；摘要模型另外固定 temperature 0，但不创建第二套预算配置。

- [ ] **步骤 4：实现最小 AgentBudgetMiddleware**

中间件只做一件事：当 Token 软上限已触发，或当前调用即将使用最后一个模型调用名额时，把 `ModelCallInput.tools()` 替换为 `List.of()`，其他输入原样透传。调用次数的判断仍委托 `AgentRun`。

- [ ] **步骤 5：消费 ModelCallEndEvent usage**

从 `ChatUsage` 读取真实 input/output/total tokens；供应商未返回某项时保留未知状态，不用字符数伪造 Token。日志和 Snapshot 仅记录数值。

- [ ] **步骤 6：运行测试并提交**

```bash
git add fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentBudgetMiddleware.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeModelFactory.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentBudgetMiddlewareTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeModelFactoryTest.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapterTest.java
git commit -m "feat(agent): 收口模型调用与 Token 预算"
```

---

## 任务 7：增加 Phase 2B 评测观测、指标和双向门禁

**文件：**

- 修改：`fileagent-api/src/main/java/com/demetrius/fileagent/api/port/AgentAnswerEvaluationPort.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentAnswerEvaluationService.java`
- 修改：`fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationObservation.java`
- 修改：`AgentEvaluationReport.java`、`AgentEvaluationRunner.java`、`AgentEvaluationReportWriter.java`
- 修改：`QualityGateConfig.java`、`AgentQualityGateEvaluator.java`
- 修改对应测试和 `EvaluationDatasetContractTest.java`

- [ ] **步骤 1：写指标和最大值门禁失败测试**

新增指标：

```text
promptPreservationRate
toolBudgetComplianceRate
budgetExhaustionCompletionRate
summaryUnsupportedClaimRate
fakeCitationRate
historyRequiredFactCoverage
```

测试 `minimumScores` 继续判断越高越好；新增 `maximumScores` 判断越低越好。`summaryUnsupportedClaimRate=0` 和 `fakeCitationRate=0` 必须走最大值门禁，不能反向套用最小值逻辑。

- [ ] **步骤 2：运行 Evaluation 单测并确认失败**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  "/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
  -s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
  -Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
  -pl fileagent-evaluation -am test \
  -Dtest=AgentEvaluationRunnerTest,AgentEvaluationReportWriterTest,AgentQualityGateEvaluatorTest,EvaluationDatasetContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **步骤 3：扩展评测 Port 的观测数据**

评测结果增加预算用量、预算原因、prompt 是否原样保留、生成摘要及摘要来源消息。为避免正式 Snapshot 增加 DTO，评测专用信息可使用 `AgentAnswerEvaluationPort` 内部嵌套 record；不得暴露到公开 Run 查询接口。

- [ ] **步骤 4：实现六个指标**

- `promptPreservationRate`：当前问题在最终模型输入中完整且只出现一次。
- `toolBudgetComplianceRate`：单次和累计工具字符均未超过配置上限。
- `budgetExhaustionCompletionRate`：标记 `budget-exhaustion` 的用例在停止扩展后仍正常完成回答。
- `summaryUnsupportedClaimRate`：Judge 只对生成摘要和原历史消息做事实支持判断。
- `fakeCitationRate`：GENERAL_KNOWLEDGE 用例出现知识库文件引用即计假引用。
- `historyRequiredFactCoverage`：标记 `history` 的用例按必需事实覆盖率计算。

- [ ] **步骤 5：保持报告聚焦**

Markdown 主报告只显示汇总、门禁和未达标指标的典型失败；完整逐题观测继续写结构化结果，不把所有回答重新铺到报告正文。

- [ ] **步骤 6：运行测试并提交**

```bash
git add fileagent-api/src/main/java/com/demetrius/fileagent/api/port/AgentAnswerEvaluationPort.java \
  fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentAnswerEvaluationService.java \
  fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/AgentAnswerEvaluationServiceTest.java \
  fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationObservation.java \
  fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationReport.java \
  fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationReportWriter.java \
  fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentEvaluationRunner.java \
  fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/AgentQualityGateEvaluator.java \
  fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/QualityGateConfig.java \
  fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationRunnerTest.java \
  fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentEvaluationReportWriterTest.java \
  fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/AgentQualityGateEvaluatorTest.java \
  fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/EvaluationDatasetContractTest.java
git commit -m "feat(evaluation): 增加上下文预算指标"
```

---

## 任务 8：增加 context-v1 数据集、文档和全量验证

**文件：**

- 新增：`fileagent-evaluation/src/main/resources/evaluation/context-v1/**`
- 修改：`fileagent-evaluation/scripts/upload-corpus.sh`
- 修改：`fileagent-evaluation/README.md`
- 修改：`docs/API.md`
- 修改：`docs/SKELETON.md`
- 修改：`docs/TESTING.md`
- 修改：`docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md`

- [ ] **步骤 1：先补数据集契约测试**

`context-v1` 至少覆盖：

- 长会话中早期用户约束仍能回答。
- 否定、日期、金额、单位、文件名和 ID 在摘要后不失真。
- 超大历史积压分批推进。
- 搜索摘要足够时不强制精读。
- 搜索摘要不足时走 outline + read。
- 工具累计预算耗尽后仍生成最终回答。
- 知识库无答案时输出通用知识标识且不伪造引用。
- 模拟基础设施失败时不能降级伪装成通用知识。

- [ ] **步骤 2：新增语料和门禁**

`gate.json` 使用：

```json
{
  "minimumScores": {
    "promptPreservationRate": 1.0,
    "toolBudgetComplianceRate": 1.0,
    "budgetExhaustionCompletionRate": 1.0,
    "historyRequiredFactCoverage": 0.9
  },
  "maximumScores": {
    "summaryUnsupportedClaimRate": 0.0,
    "fakeCitationRate": 0.0
  }
}
```

上传脚本为 `context-v1` 使用独立知识库名 `fileagent-eval-context-v1`，复用服务端现有防重复上传能力。

- [ ] **步骤 3：更新公开契约和操作文档**

`docs/API.md` 记录 Snapshot 新字段和 `AGENT_PROMPT_TOO_LARGE`；`docs/TESTING.md` 与 Evaluation README 写清上传、执行、报告定位；Roadmap 把 Phase 2B 验收项同步为实际实现，不把 Phase 2C 能力写成本期完成。

- [ ] **步骤 4：运行全部自动化测试**

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  "/Users/a1/Desktop/software/apache-maven-3.9.9/bin/mvn" \
  -s "/Users/a1/Desktop/software/apache-maven-3.9.9/conf/settings.xml" \
  -Dmaven.repo.local="/Users/a1/Desktop/software/mavenlibrary" \
  clean test
```

预期：所有模块测试通过。若失败，修复本期引入问题后重跑同一命令；不得通过跳过测试完成验收。

- [ ] **步骤 5：检查结构和敏感信息**

```bash
git diff --unified=0 9a9cbcb | rg "^\+.*(TO[D]O|TB[D]|适当错[误]处理)"
git diff --unified=0 9a9cbcb | rg -i "^\+.*(api[-_.]?key|password)[[:space:]]*[:=][[:space:]]*[^$]"
git status --short
git diff --check
```

预期：本期新增代码没有占位实现；配置中只有环境变量占位和字段名，没有真实密钥；未跟踪的 `AnswerCitationExtractorTest.java` 仍未被暂存；`git diff --check` 无错误。

- [ ] **步骤 6：提交数据集和文档**

```bash
git add fileagent-evaluation/src/main/resources/evaluation/context-v1 \
  fileagent-evaluation/scripts/upload-corpus.sh fileagent-evaluation/README.md \
  docs/API.md docs/SKELETON.md docs/TESTING.md docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md
git commit -m "test(evaluation): 增加 Phase 2B 评测集"
```

- [ ] **步骤 7：重启服务后执行真实模型评测**

以下步骤需要用户已启动的 FileAgent、现有模型配置、Elasticsearch 和评测 Token：

```bash
./fileagent-evaluation/scripts/upload-corpus.sh context-v1

FILEAGENT_EVALUATION_DATASET_VERSION=context-v1 \
FILEAGENT_EVALUATION_RUN_ID=phase2b-context-$(date -u +%Y%m%dT%H%M%SZ) \
./fileagent-evaluation/scripts/run-agent-evaluation.sh
```

预期：报告输出到独立 Run 目录，六项门禁通过。若失败，保留报告和结构化结果，不覆盖历史 Run。

- [ ] **步骤 8：执行 Phase 2A 回归评测**

```bash
FILEAGENT_EVALUATION_DATASET_VERSION=adaptive-v2 \
FILEAGENT_EVALUATION_RUN_ID=phase2b-adaptive-regression-$(date -u +%Y%m%dT%H%M%SZ) \
./fileagent-evaluation/scripts/run-agent-evaluation.sh
```

预期：`adaptive-v2` 原有门禁继续通过。真实模型结果具有波动性，任何失败都必须先查看典型失败和结构化观测，再决定调整代码、Prompt 或数据标签。

---

## 完成标准

- [ ] Adaptive 模式历史超限时压缩而不是删除，摘要可跨 Run 持久化。
- [ ] 摘要失败和 CAS 冲突均受控降级，不覆盖较新摘要。
- [ ] 搜索、文件列表、目录、精读遵守逐级授权与 4000/12000 字符预算。
- [ ] `maxSteps=8` 未调整，并有“8 步允许、第 9 步拒绝、摘要和最终回答不计 Step”的测试。
- [ ] Adaptive 模型调用最多 6 次，最后一次无工具；非 Adaptive 保持 4 次。
- [ ] Snapshot 和评测记录真实字符与 Token，不泄露正文和内部 Prompt。
- [ ] 无知识库证据时可明确标识通用知识，不伪造引用；基础设施失败不会伪装成零命中。
- [ ] `context-v1` 和 `adaptive-v2` 使用真实模型完成评测，报告保存在不同 Run 目录。
- [ ] 全量 Maven 测试通过，`git diff --check` 通过，未夹带用户的未跟踪文件。
