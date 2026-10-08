# Phase 3A 持久化 Agent Runtime 实现计划

> **执行约束：** 得到用户对本阶段的明确确认后，按任务顺序在当前会话实现；除非用户另行批准，不创建 worktree 或子代理。步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 将 Agent Run、Step 和 AgentScope 状态持久化，并让执行脱离 SSE 连接在有界后台线程中继续运行和恢复。

**架构：** H2/JPA 保存业务事实，AgentScope 状态以版本化 AES-GCM 信封保存；`AgentRunExecutionCoordinator` 拥有容量、租约、后台执行和恢复扫描，`AgentRunEventHub` 仅保存有界内存事件。Controller 同步完成 prepare 后再返回 SSE，浏览器断开只取消订阅，不取消 Run。

**技术栈：** Java 21、Spring Boot 4.1、Spring Data JPA、H2、Reactor、AgentScope Java 2.0.1、JUnit 5、Mockito。

---

## 文件结构

**创建：**

- `fileagent-common/src/main/java/com/demetrius/fileagent/common/security/EnvelopeCrypto.java`：带版本、keyId、AAD 的 AES-GCM 与 HMAC。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRunEntity.java`：持久化 Run 聚合和唯一状态机。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRunRepository.java`：Run 领域仓储契约。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/step/AgentStepEntity.java`、`AgentStepType.java`、`AgentStepStatus.java`、`AgentStepRepository.java`：步骤事实。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/checkpoint/AgentCheckpointEntity.java`、`AgentCheckpointRepository.java`：每个 Run 的最新检查点。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/persistence/*JpaRepository.java`：Spring Data 仓储。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/persistence/AgentPersistenceAdapter.java`：集中实现三个小型领域仓储，避免额外 Manager 层。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/state/DatabaseAgentStateStore.java`：AgentScope `AgentStateStore` 适配。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/state/StrictAgentStateLoader.java`：恢复前显式解密和校验，禁止 silent fresh state。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/runtime/AgentExecutionEngine.java`：AgentScope 单次执行端口。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/runtime/AgentRunEventHub.java`：有界事件、游标和订阅。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/runtime/AgentRunExecutionCoordinator.java`：容量、队列、租约和恢复。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentRunPreparation.java`：同步 prepare 结果。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentRunFinalizationService.java`：回答、FINALIZATION Step 和 Run 成功的单事务提交。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentFinalizationContributor.java`：Phase 2C 可选贡献点，默认 no-op。
- 对应单元、JPA 和集成测试文件。

**修改：**

- `fileagent-agent/pom.xml`：加入 Spring Data JPA。
- `fileagent-api/.../AgentRunStatus.java`、`AgentRunEvent.java`、`AgentRunSnapshot.java`、`AgentRuntimePort.java`：持久化快照和可重连事件契约。
- `fileagent-agent/.../AgentRun.java`：迁移可变状态后删除该内存聚合，保留其中确有价值的检索观察值对象。
- `fileagent-agent/.../InMemoryAgentRunRegistry.java`：改名/收缩为仅保存运行取消句柄的 `AgentExecutionHandleRegistry`。
- `fileagent-agent/.../AgentScopeRuntimeAdapter.java`：只实现 `AgentExecutionEngine`，不再持有 Run 事实和 SSE 生命周期。
- `fileagent-agent/.../AgentRunAppService.java`、`AgentRunAppServiceImpl.java`：同步 prepare 和事务最终化。
- `fileagent-agent/.../AgentRunController.java`、`AgentRunQueryController.java`：`X-Agent-Run-Id`、`Last-Event-ID`、active 查询与流前错误。
- `fileagent-agent/.../AgentProperties.java`、`fileagent-starter/src/main/resources/application.yml`：容量、租约、恢复扫描和 replay 配置。
- `fileagent-starter/src/main/resources/static/app.js`、`app.css`：PENDING、断线恢复和事件去重。
- `docs/API.md`、`docs/SKELETON.md`、`docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md`。

### 任务 1：建立持久化状态机和实体

**文件：**
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRunEntity.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/step/AgentStepEntity.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/checkpoint/AgentCheckpointEntity.java`
- 修改：`fileagent-agent/pom.xml`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/domain/run/AgentRunEntityTest.java`

- [ ] **步骤 1：先写状态迁移失败测试**

```java
@Test
void should_reject_terminal_transition() {
    AgentRunEntity run = AgentRunEntity.pending("r1", 1L, "trace", "local-operator", "cipher", "hmac");
    run.start(Instant.parse("2026-10-08T01:00:00Z"));
    run.succeed(10L, Instant.parse("2026-10-08T01:00:01Z"));

    assertThatThrownBy(() -> run.fail("X", Instant.now()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("非法状态转移");
}

@Test
void should_allow_pending_failure_and_release_active_slot() {
    AgentRunEntity run = AgentRunEntity.pending("r1", 1L, "trace", "local-operator", "cipher", "hmac");
    run.fail("AGENT_RUN_INTERRUPTED", Instant.now());

    assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FAILED);
    assertThat(run.getActiveSlot()).isNull();
}
```

- [ ] **步骤 2：运行测试确认实体尚不存在**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentRunEntityTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，编译器提示 `AgentRunEntity` 不存在。

- [ ] **步骤 3：加入 JPA 依赖并实现最小实体**

`AgentRunEntity` 必须包含设计稿字段，并额外持久化 PENDING 恢复所必需的请求：

```java
/**
 * Agent Run 业务事实：保存唯一合法状态机、预算累计、租约和恢复请求密文。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-08
 */
@Getter
@Entity
@Table(name = "agent_run", uniqueConstraints =
        @UniqueConstraint(name = "uk_agent_run_active_slot", columnNames = "active_slot"))
public class AgentRunEntity {
    @Id
    private String runId;
    private Long sessionId;
    private String activeSlot;
    private Long inputMessageId;
    private Long assistantMessageId;
    private String actorId;
    @Enumerated(EnumType.STRING)
    private AgentRunStatus status;
    private String failureCode;
    private String traceId;
    private int requestSchemaVersion;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String requestCiphertext;
    private String requestHmac;
    private String executionOwner;
    private Instant leaseExpiresAt;
    @Version
    private long version;
    private Instant createdAt;
    private Instant startedAt;
    private Instant endedAt;
    private Instant updatedAt;
}
```

实现 `pending/start/succeed/fail/cancel/timeout`；所有终态方法清空 `activeSlot/executionOwner/leaseExpiresAt`。`requestCiphertext` 保存版本化 `AgentRunCommand`，否则提交后崩溃的 PENDING Run 无法恢复。

`AgentStepEntity` 使用 `UNIQUE(run_id, sequence_no)` 和 `UNIQUE(run_id, tool_call_id)`；`AgentCheckpointEntity` 以 `run_id` 为主键并包含 `agentscope_state_ciphertext`、`authorization_state_ciphertext`、`finalization_context_ciphertext`、schema/checksum/version。

- [ ] **步骤 4：运行领域测试**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentRunEntityTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

- [ ] **步骤 5：提交状态机实体**

```bash
git add fileagent-agent/pom.xml fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain fileagent-agent/src/test/java/com/demetrius/fileagent/agent/domain
git commit -m "feat(agent): 持久化运行状态模型"
```

### 任务 2：实现 JPA 仓储、active slot 和 CAS

**文件：**
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/run/AgentRunRepository.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/step/AgentStepRepository.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/checkpoint/AgentCheckpointRepository.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/persistence/AgentRunJpaRepository.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/persistence/AgentStepJpaRepository.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/persistence/AgentCheckpointJpaRepository.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/persistence/AgentPersistenceAdapter.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/persistence/AgentPersistenceJpaTest.java`

- [ ] **步骤 1：写并发 active slot 与乐观锁测试**

测试使用 `@DataJpaTest`，保存两个相同 `session:1` 的非终态 Run，断言第二次 `saveAndFlush` 抛 `DataIntegrityViolationException`；用两个独立读取副本更新同一 Run，断言第二次提交抛乐观锁异常。

- [ ] **步骤 2：运行并确认仓储测试失败**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentPersistenceJpaTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，仓储类型不存在。

- [ ] **步骤 3：实现仓储查询与条件更新**

`AgentRunJpaRepository` 至少提供：

```java
Optional<AgentRunEntity> findByActiveSlot(String activeSlot);

@Query("select r from AgentRunEntity r where r.status = 'PENDING' or "
        + "(r.status = 'RUNNING' and (r.leaseExpiresAt is null or r.leaseExpiresAt < :now))")
List<AgentRunEntity> findRecoverable(@Param("now") Instant now);

@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update AgentRunEntity r set r.executionOwner=:owner, r.leaseExpiresAt=:until, "
        + "r.version=r.version+1 where r.runId=:runId and r.version=:version "
        + "and (r.leaseExpiresAt is null or r.leaseExpiresAt < :now)")
int acquireLease(String runId, long version, String owner, Instant now, Instant until);
```

领域仓储只暴露业务方法，不把 Spring Data 类型传入 application。

- [ ] **步骤 4：运行 JPA 测试**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentPersistenceJpaTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

- [ ] **步骤 5：提交仓储**

```bash
git add fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/persistence fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/persistence
git commit -m "feat(agent): 增加运行状态仓储"
```

### 任务 3：实现版本化加密检查点和严格加载

**文件：**
- 创建：`fileagent-common/src/main/java/com/demetrius/fileagent/common/security/EnvelopeCrypto.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/state/DatabaseAgentStateStore.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/state/StrictAgentStateLoader.java`
- 测试：`fileagent-common/src/test/java/com/demetrius/fileagent/common/security/EnvelopeCryptoTest.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/state/DatabaseAgentStateStoreTest.java`

- [ ] **步骤 1：写 AAD、篡改和错误 keyId 测试**

```java
@Test
void should_reject_different_aad() {
    String cipher = crypto.encrypt("secret", "agent_checkpoint:r1:agentscope:v1");
    assertThatThrownBy(() -> crypto.decrypt(cipher, "agent_checkpoint:r2:agentscope:v1"))
            .isInstanceOf(IllegalStateException.class);
}
```

再覆盖密文格式 `v1:local:<base64>`、稳定 HMAC 和单字符篡改失败。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-common,fileagent-agent -am test -Dtest=EnvelopeCryptoTest,DatabaseAgentStateStoreTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，新类型不存在。

- [ ] **步骤 3：实现 `EnvelopeCrypto`**

公开方法固定为：

```java
String encrypt(String plaintext, String aad);
String decrypt(String envelope, String aad);
String hmac(String canonicalText);
```

AES-GCM 调用 `cipher.updateAAD(aad.getBytes(UTF_8))`；HMAC 使用由主密钥和固定上下文 `fileagent-hmac-v1` 派生的 HmacSHA256 key，不记录明文。

- [ ] **步骤 4：实现 `AgentStateStore`**

`DatabaseAgentStateStore` 把同一 `runId` 下 AgentScope 的各 key 序列化为一个版本化 JSON map，再整体加密写入 `AgentCheckpointEntity.agentscopeStateCiphertext`。`RuntimeContext.sessionId` 必须使用 `runId`，`userId` 固定 `local-operator`。

`StrictAgentStateLoader.loadVerified(runId)` 在创建 ReActAgent 前解密并校验 envelope、checksum、schema 和 JSON；失败抛稳定错误，绝不返回空 Optional。验证成功后让本次 AgentScope 读取同一已验证快照，避免 SDK 捕获读取异常后静默创建 fresh state。

- [ ] **步骤 5：运行加密与 Store 测试**

运行：`mvn -pl fileagent-common,fileagent-agent -am test -Dtest=EnvelopeCryptoTest,DatabaseAgentStateStoreTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

- [ ] **步骤 6：提交检查点能力**

```bash
git add fileagent-common/src/main/java/com/demetrius/fileagent/common/security/EnvelopeCrypto.java fileagent-common/src/test/java/com/demetrius/fileagent/common/security/EnvelopeCryptoTest.java fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/state fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/state
git commit -m "feat(agent): 持久化加密运行检查点"
```

### 任务 4：扩展事件契约并实现有界 replay

**文件：**
- 修改：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentRunEvent.java`
- 修改：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentRunSnapshot.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/runtime/AgentRunEventHub.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/runtime/AgentRunEventHubTest.java`

- [ ] **步骤 1：写 replay 和溢出测试**

覆盖：按 `Last-Event-ID` 只返回后续事件；重复订阅事件 ID 不变；超过 100 项时优先合并/丢弃 `message.delta`；`run.failed/run.completed` 不丢；未知游标只先返回当前 `run.status`。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentRunEventHubTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，Hub 不存在。

- [ ] **步骤 3：在共享 DTO 尾部增加字段**

先检查 Phase 2C 字段并只追加：

```java
String eventId,
Long runVersion
```

增加 `run.status(PENDING)` 工厂方法；所有事件必须有 `runId/eventId`，事件 ID 由 Hub 发布时生成，不由 Controller 或模型生成。3B 再在 record 尾部追加 `AgentApprovalView approval`。

- [ ] **步骤 4：实现 Hub**

每个 Run 一个 100 条/256 KB 上限的环和 `Sinks.Many<AgentRunEvent>`；终态关闭 sink，TTL 只清内存 channel，不删数据库。`message.delta` 可以合并，相同 `(runId,eventId)` 不重复发布。

- [ ] **步骤 5：运行测试并提交**

运行：`mvn -pl fileagent-api,fileagent-agent -am test -Dtest=AgentRunEventHubTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-api/src/main/java/com/demetrius/fileagent/api/dto fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/runtime/AgentRunEventHub.java fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/runtime/AgentRunEventHubTest.java
git commit -m "feat(agent): 增加可重连运行事件通道"
```

### 任务 5：实现有界 Coordinator、租约和启动恢复

**文件：**
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/runtime/AgentExecutionEngine.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/runtime/AgentRunExecutionCoordinator.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AgentProperties.java`
- 修改：`fileagent-starter/src/main/resources/application.yml`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/runtime/AgentRunExecutionCoordinatorTest.java`

- [ ] **步骤 1：写容量、租约和恢复矩阵测试**

固定覆盖：4 active + 32 queued 后第 37 个返回容量不足；事务失败释放 permit；PENDING 重入队；陈旧 RUNNING 无 ASKING 检查点转 `FAILED/AGENT_RUN_INTERRUPTED`；终态不执行；启动扫描先恢复旧 Run 再接受新 Run。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentRunExecutionCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，Coordinator 不存在。

- [ ] **步骤 3：实现容量和调度**

`AgentProperties` 增加并校验：

```java
private int maxConcurrentRuns = 4;
private int maxQueuedRuns = 32;
private Duration pendingStartTimeout = Duration.ofMinutes(2);
private Duration leaseDuration = Duration.ofSeconds(30);
private Duration recoveryScanInterval = Duration.ofSeconds(15);
private int replayMaxEvents = 100;
private int replayMaxBytes = 256 * 1024;
```

Coordinator 使用固定 `ThreadPoolTaskExecutor` 和容量 permit，不使用无界 common pool。Run 进入 WAITING/终态释放 permit；SSE 订阅不占 permit。

- [ ] **步骤 4：实现周期恢复**

启动和每 15 秒扫描 `PENDING`、租约过期 `RUNNING`。3A 只恢复 PENDING；普通陈旧 RUNNING 安全失败。3B 再扩展 ASKING 恢复分支。

- [ ] **步骤 5：运行测试并提交**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentRunExecutionCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/runtime fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AgentProperties.java fileagent-starter/src/main/resources/application.yml fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/runtime
git commit -m "feat(agent): 增加后台运行协调器"
```

### 任务 6：拆分 AgentScope 执行引擎并移除断连取消

**文件：**
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/run/InMemoryAgentRunRegistry.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeConfiguration.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentRuntimeCancellationTest.java`

- [ ] **步骤 1：把断连行为测试改成 Run 继续**

订阅执行流后取消订阅，断言 `AgentExecutionHandleRegistry` 的显式 cancel handle 未触发且持久化 Run 仍为 RUNNING；调用取消接口时才触发 handle。

- [ ] **步骤 2：运行测试确认当前实现失败**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentRuntimeCancellationTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，当前 `doOnCancel` 会中断 Agent。

- [ ] **步骤 3：让 Adapter 只负责一次执行**

`AgentScopeRuntimeAdapter` 实现 `AgentExecutionEngine.execute(AgentRunCommand)`，向 Coordinator 发布事件；删除 `doOnCancel` 里的状态迁移和 `agent.interrupt`。AgentScope builder 接入 `DatabaseAgentStateStore`，`RuntimeContext.sessionId(command.runId())`，取消句柄只保存在 `AgentExecutionHandleRegistry`。

- [ ] **步骤 4：保持离线 evaluate 独立**

`AgentAnswerEvaluationService` 继续调用不落业务 Run 的 evaluate 路径；不得让既有 `agent-v1/adaptive-v2/context-v2` 评测污染会话和新表。

- [ ] **步骤 5：运行 Runtime 测试并提交**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentRuntimeCancellationTest,AgentScopeRuntimeAdapterTest,AgentAnswerEvaluationServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/AgentAnswerEvaluationServiceTest.java
git commit -m "refactor(agent): 分离后台执行与事件订阅"
```

### 任务 7：实现同步 prepare、事务最终化和查询接口

**文件：**
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentRunPreparation.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentRunFinalizationService.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentFinalizationContributor.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentRunAppService.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentRunAppServiceImpl.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/interfaces/AgentRunController.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/interfaces/AgentRunQueryController.java`
- 修改：`fileagent-api/src/main/java/com/demetrius/fileagent/api/port/AgentRuntimePort.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/AgentRunAppServiceImplTest.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/interfaces/AgentRunControllerTest.java`

- [ ] **步骤 1：写同步 prepare 和原子最终化测试**

断言：容量不足时不写 USER/Run；成功 prepare 在方法返回前已写 USER+PENDING Run；响应头有 `X-Agent-Run-Id`；流前 BizException 保留 HTTP 状态并输出 `event:error`；assistant append 失败时 FINALIZATION 和 SUCCEEDED 均不提交。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentRunAppServiceImplTest,AgentRunControllerTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，当前执行被包在 `Flux.defer` 中且流前错误使用 `run.failed`。

- [ ] **步骤 3：实现同步 prepare**

创建用例接口固定为：

```java
AgentRunPreparation prepare(Long sessionId, StartAgentRunRequest request, String traceId);
```

`AgentRunPreparation` 包含 `runId` 和 `Flux<AgentRunEvent>`。prepare 顺序必须是预留 permit -> 校验/读取历史 -> 同一事务保存 USER 和 PENDING Run -> 入队 -> 返回订阅流；失败按设计释放 permit。

- [ ] **步骤 4：实现单事务最终化**

`AgentRunFinalizationService.finalizeSuccess(runId, answer, finalizationContext)` 使用 `@Transactional`，依次调用全部 `AgentFinalizationContributor`、`SessionMessagePort.append`、保存 FINALIZATION Step、设置 messageId、Run SUCCEEDED。Contributor 列表为空就是明确 no-op，供 Phase 2C 合入后追加实现。

- [ ] **步骤 5：收缩 Runtime Port 并实现 active/snapshot/events/cancel**

`AgentRuntimePort` 不再负责创建 Run，固定为持久化运行的查询、订阅和取消端口：

```java
AgentRunSnapshot snapshot(String runId);
AgentRunSnapshot active(Long sessionId);
Flux<AgentRunEvent> events(String runId, String lastEventId);
AgentRunSnapshot cancel(String runId);
```

新增 `GET /api/sessions/{sessionId}/agent-runs/active` 和 `GET /api/agent-runs/{runId}/events`；后者读取 `Last-Event-ID` 并返回 `text/event-stream`。无活动 Run 返回 `ApiResult.ok(null)`。取消支持 PENDING/RUNNING；`WAITING_APPROVAL` 在 3B 扩展。

- [ ] **步骤 6：运行测试并提交**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentRunAppServiceImplTest,AgentRunControllerTest,AgentRunQueryControllerTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-api/src/main/java/com/demetrius/fileagent/api/port/AgentRuntimePort.java fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application fileagent-agent/src/main/java/com/demetrius/fileagent/agent/interfaces fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application fileagent-agent/src/test/java/com/demetrius/fileagent/agent/interfaces
git commit -m "feat(agent): 提供持久化运行接口"
```

### 任务 8：实现前端后台运行和断线恢复

**文件：**
- 修改：`fileagent-starter/src/main/resources/static/app.js`
- 修改：`fileagent-starter/src/main/resources/static/app.css`
- 修改：`fileagent-starter/src/test/java/com/demetrius/fileagent/StaticPageContractTest.java`

- [ ] **步骤 1：扩展静态契约测试**

断言脚本包含 `/agent-runs/active`、`/agent-runs/${runId}/events`、`Last-Event-ID`、`X-Agent-Run-Id`、`run.status`，且不再包含 `beforeunload` 取消和切换会话自动取消注释。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-starter -am test -Dtest=StaticPageContractTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，当前页面卸载和切会话都会取消 Run。

- [ ] **步骤 3：实现 UI 状态收敛**

前端从响应头或任意事件保存 `currentRunId`；PENDING 显示“Agent 已排队”；EOF/网络异常且未收到 WAITING/终态时立即查询 `/active`；RUNNING 保持输入禁用并轮询；只由停止按钮调用取消。维护每个 Run 的有界 eventId Set，重复 delta 不再次拼接。

- [ ] **步骤 4：运行测试并提交**

运行：`mvn -pl fileagent-starter -am test -Dtest=StaticPageContractTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-starter/src/main/resources/static/app.js fileagent-starter/src/main/resources/static/app.css fileagent-starter/src/test/java/com/demetrius/fileagent/StaticPageContractTest.java
git commit -m "feat(web): 恢复后台 Agent 运行状态"
```

### 任务 9：文档、全量测试和 Phase 3A 验收

**文件：**
- 修改：`docs/API.md`
- 修改：`docs/SKELETON.md`
- 修改：`docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/runtime/AgentRuntimeRestartIntegrationTest.java`

- [ ] **步骤 1：写重建 Spring Context 的集成测试**

测试创建 PENDING Run、关闭第一个 context、启动第二个 context，断言 Run 被重新入队；另建陈旧 RUNNING 且无 ASKING 检查点，断言安全失败并清空 active slot。

- [ ] **步骤 2：运行 Phase 3A 模块测试**

运行：`mvn -pl fileagent-agent,fileagent-starter -am test`

预期：PASS。

- [ ] **步骤 3：更新文档**

`docs/API.md` 写明 prepare、SSE `id`、`Last-Event-ID`、active、PENDING 和流前 `event:error`；Roadmap 只把 3A 标为完成，3B-3D 保持未完成。

- [ ] **步骤 4：运行完整回归**

运行：`mvn test`

预期：PASS，且没有真实模型网络调用。

- [ ] **步骤 5：检查并提交 Phase 3A**

```bash
git diff --check
git status --short
git add docs/API.md docs/SKELETON.md docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/runtime/AgentRuntimeRestartIntegrationTest.java
git commit -m "docs(agent): 完成 Phase 3A 验收"
```

## Phase 3A 退出门槛

- Run/Step/Checkpoint 在应用重启后仍可查询。
- 浏览器断开不终止 Run，显式停止仍能取消。
- PENDING Run 重启后重新入队；普通陈旧 RUNNING 安全失败且释放 active slot。
- 队列容量、租约和 replay 全部有确定性测试。
- 现有 Agent 单元测试与 `agent-v1/adaptive-v2/context-v2` 评测入口没有被改成持久化评测。
