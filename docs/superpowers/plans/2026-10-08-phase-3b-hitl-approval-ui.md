# Phase 3B HITL 审批与前端交互实现计划

> **执行约束：** 得到用户对本阶段的明确确认后，按任务顺序在当前会话实现；除非用户另行批准，不创建 worktree 或子代理。步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 使用 AgentScope 2.0.1 原生 ASK/ConfirmResult 建立可持久化、可过期、可拒绝、可重启恢复的人类审批流程。

**架构：** FileAgent 的不可变动作快照、策略和 Approval 是授权事实；AgentScope 只负责暂停/恢复协议。副作用工具按 Run 创建 `ToolBase` wrapper，在 `checkPermissions` 中先执行 Schema/资源/风险策略，再返回 ASK；审批通过后 `callAsync` 再次复核。前端把审批区内联在 assistant 消息中。

**技术栈：** Java 21、Spring Boot 4.1、Spring Data JPA、AgentScope PermissionEngine、Reactor SSE、原生 HTML/CSS/JavaScript。

---

## 前置条件

- Phase 3A 全部退出门槛通过。
- 当前分支没有把 `fileagent-chat/.../AnswerCitationExtractorTest.java` 和 `context-v2/` 误加入暂存区。
- `AgentRunEvent` 已有 `eventId/runVersion`；本阶段只在 record 尾部追加审批字段。

## 文件结构

**创建：**

- `fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/ActionRiskLevel.java`、`PolicyDecision.java`、`ApprovalStatus.java`：跨域稳定枚举。
- `fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/AgentApprovalView.java`、`AgentApprovalDecision.java`：公开脱敏 DTO。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/approval/AgentApprovalEntity.java`、`AgentApprovalRepository.java`：审批事实。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/policy/AgentActionCandidate.java`、`AgentActionPolicy.java`：不可变动作候选和 ALLOW/DENY/ASK 规则。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/persistence/AgentApprovalJpaRepository.java`：JPA 仓储。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/RunScopedActionTool.java`：副作用工具公共 wrapper，仅承载必要策略，不形成复杂框架。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentApprovalService.java`：创建、决定、过期和恢复编排。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentApprovalExpiryScheduler.java`：30 秒 CAS 过期扫描。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/interfaces/AgentApprovalController.java`：审批 SSE 接口。
- 对应单元、JPA、AgentScope 恢复和 Controller 测试。

**修改：**

- `AgentRunEvent.java`、`AgentRunSnapshot.java`：追加审批视图。
- `AgentScopeRuntimeAdapter.java`、`AgentScopeEventMapper.java`：捕获 `RequireUserConfirmEvent`，保存 ASKING 检查点后公开事件。
- `AgentRunExecutionCoordinator.java`：扩展 WAITING/APPROVED/DENIED 恢复矩阵。
- `AgentRunQueryController.java`：WAITING 可取消。
- `AgentPromptFactory.java`：一次最多一个副作用 ToolCall。
- `AgentProperties.java`、`application.yml`：动作总开关、审批上限和过期扫描。
- `app.js`、`app.css`、`StaticPageContractTest.java`：审批区和冲突恢复。
- `docs/API.md`、`docs/SKELETON.md`、Roadmap。

### 任务 1：建立 Approval 数据模型和 CAS

**文件：**
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/ActionRiskLevel.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/PolicyDecision.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/ApprovalStatus.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/approval/AgentApprovalEntity.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/approval/AgentApprovalRepository.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/persistence/AgentApprovalJpaRepository.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/persistence/AgentApprovalJpaTest.java`

- [ ] **步骤 1：写并发决定和过期测试**

两个事务同时对 PENDING Approval 做 APPROVE/DENY，断言只一个 CAS 成功；过期事务断言 Approval=`EXPIRED`、Step=`EXPIRED`、Run=`TIMED_OUT` 且 active slot 清空。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-api,fileagent-agent -am test -Dtest=AgentApprovalJpaTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，新实体和枚举不存在。

- [ ] **步骤 3：实现审批实体**

```java
/**
 * Agent 审批事实：绑定不可变动作快照，客户端只能提交决定和审计备注。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-08
 */
@Getter
@Entity
@Table(name = "agent_approval", uniqueConstraints =
        @UniqueConstraint(name = "uk_agent_approval_step", columnNames = "step_id"))
public class AgentApprovalEntity {
    @Id
    private String approvalId;
    private String runId;
    private Long stepId;
    @Enumerated(EnumType.STRING)
    private ApprovalStatus status;
    @Enumerated(EnumType.STRING)
    private ActionRiskLevel riskLevel;
    private String canonicalInputHmac;
    private String toolDefinitionVersion;
    private String endpointRevision;
    private String endpointConfigHash;
    private String policyVersion;
    private String operationId;
    private Instant expiresAt;
    private String decidedBy;
    private String decisionComment;
    private String resolutionReason;
    @Version
    private long version;
}
```

长摘要/预览字段用 CLOB；`decisionComment` 构造时限制 500 字符并移除控制字符。

- [ ] **步骤 4：运行测试并提交**

运行：`mvn -pl fileagent-api,fileagent-agent -am test -Dtest=AgentApprovalJpaTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-api/src/main/java/com/demetrius/fileagent/api/enums fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/approval fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/persistence/AgentApprovalJpaRepository.java fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/persistence/AgentApprovalJpaTest.java
git commit -m "feat(agent): 持久化操作审批"
```

### 任务 2：实现服务端风险策略和不可变动作绑定

**文件：**
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/policy/AgentActionCandidate.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/policy/AgentActionPolicy.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/domain/policy/AgentActionPolicyTest.java`

- [ ] **步骤 1：写策略矩阵测试**

覆盖 READ_ONLY 无数据外发=`ALLOW`；GET 携带模型自由文本=`ASK`；LOW/HIGH_RISK_WRITE=`ASK`；资源越权、未知工具、篡改可信上下文=`DENY`；模型提交 actorId/URL/credential 字段=`DENY`。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentActionPolicyTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，策略类不存在。

- [ ] **步骤 3：实现小型纯领域策略**

```java
public record AgentActionCandidate(
        String runId,
        String toolCallId,
        String toolName,
        String toolDefinitionVersion,
        String endpointRevision,
        String endpointConfigHash,
        String policyVersion,
        ActionRiskLevel riskLevel,
        String resourceScope,
        String canonicalArguments,
        String canonicalInputHmac,
        String operationId,
        String safeSummary,
        String parameterPreview) {
}
```

策略输入只使用服务端 trusted context；不读取 Prompt 文本决定身份和权限。

- [ ] **步骤 4：运行测试并提交**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentActionPolicyTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-agent/src/main/java/com/demetrius/fileagent/agent/domain/policy fileagent-agent/src/test/java/com/demetrius/fileagent/agent/domain/policy
git commit -m "feat(agent): 增加动作风险策略"
```

### 任务 3：实现按 Run 组装的 AgentScope 权限工具基类

**文件：**
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/RunScopedActionTool.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/RunScopedActionToolTest.java`

- [ ] **步骤 1：写权限调用顺序测试**

使用测试子类记录调用顺序，断言 `checkPermissions` 先做 Schema、可信上下文、资源和风险，再返回 ASK；静态 PermissionEngine 不配置 tool-level ASK rule；`callAsync` 再次复核 HMAC 和版本。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-agent -am test -Dtest=RunScopedActionToolTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，基类不存在。

- [ ] **步骤 3：实现基类固定契约**

```java
public abstract class RunScopedActionTool extends ToolBase {
    @Override
    public final Mono<PermissionDecision> checkPermissions(
            Map<String, Object> input, PermissionContextState ignored) {
        return Mono.fromCallable(() -> decide(buildCandidate(input)));
    }

    @Override
    public final Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> executeVerified(buildCandidate(param.getInput())));
    }

    protected abstract AgentActionCandidate buildCandidate(Map<String, Object> input);
    protected abstract ToolResultBlock executeVerified(AgentActionCandidate candidate);
}
```

`ToolCallParam.getInput()` 和 `getToolUseBlock().getId()` 分别取得参数和调用 ID；trusted context 只能使用 wrapper 构造参数，不能改从 `RuntimeContext` 或模型入参读取。

- [ ] **步骤 4：运行测试并提交**

运行：`mvn -pl fileagent-agent -am test -Dtest=RunScopedActionToolTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/RunScopedActionTool.java fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/RunScopedActionToolTest.java
git commit -m "feat(agent): 接入运行级工具权限检查"
```

### 任务 4：捕获 ASK、原子保存检查点和 Approval

**文件：**
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeEventMapper.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentApprovalService.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeApprovalPauseTest.java`

- [ ] **步骤 1：写 ASK 暂停测试**

测试工具返回 `PermissionDecision.ask(...)`，断言先完成 AgentStateStore 保存，再事务写 WAITING Step/Approval/Run，最后才发布 `approval.required`；SSE 正常完成。一次出现两个 ASKING ToolCall 时 Run=`FAILED/TOOL_BATCH_REQUIRES_SEQUENTIAL_EXECUTION` 且没有 Approval。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentScopeApprovalPauseTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，当前事件 mapper 忽略 `REQUIRE_USER_CONFIRM`。

- [ ] **步骤 3：实现暂停顺序**

Adapter 收集 `RequireUserConfirmEvent`，但不立即外发；AgentScope 本轮正常结束且检查点 checksum 可验证后，`AgentApprovalService.pause(...)` 在一个事务里创建 Tool Step、Approval 并转 WAITING，事务提交后由 Hub 发布 `approval.required`。

- [ ] **步骤 4：追加公开 DTO**

在 Phase 2C 当前 record 尾部追加 `AgentApprovalView approval`；视图只含 approvalId/tool/riskLevel/actionSummary/resourceScope/expectedImpact/parameterPreview/parameterFingerprint/expiresAt/status。

- [ ] **步骤 5：运行测试并提交**

运行：`mvn -pl fileagent-api,fileagent-agent -am test -Dtest=AgentScopeApprovalPauseTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-api/src/main/java/com/demetrius/fileagent/api/dto fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentApprovalService.java fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeApprovalPauseTest.java
git commit -m "feat(agent): 持久化 AgentScope 审批暂停"
```

### 任务 5：实现批准、拒绝、绑定复核和重启恢复

**文件：**
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentApprovalService.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/runtime/AgentRunExecutionCoordinator.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/AgentApprovalServiceTest.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/AgentApprovalRestartIntegrationTest.java`

- [ ] **步骤 1：写审批 prepare 时序测试**

覆盖：严格加载 ASKING 检查点；同一事务复核定义版本/HMAC/资源后才 CAS APPROVED/DENIED + Run PENDING；首次 CAS 成功后再预留 permit，容量不足时回滚该事务；绑定变化把 Approval/Step CANCELLED、Run FAILED/APPROVAL_BINDING_CHANGED；同决定重复提交不二次恢复；相反决定 409。

- [ ] **步骤 2：写 Spring Context 重启测试**

覆盖 WAITING+PENDING 保持等待；APPROVED/DENIED 已提交未入队时重启重新入队；ASKING 检查点存在但 Approval 缺失时重建；损坏/错误 key 检查点安全失败而不是 fresh state。

- [ ] **步骤 3：运行测试确认失败**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentApprovalServiceTest,AgentApprovalRestartIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，恢复分支尚未实现。

- [ ] **步骤 4：构造原生 ConfirmResult**

批准：使用数据库中的原始 ASKING ToolCall 构造 `new ConfirmResult(true, storedToolCall, List.of())`；拒绝：`new ConfirmResult(false, storedToolCall, List.of())`。不允许客户端修改 ToolCall，也不传“以后始终允许”规则。AgentScope 拒绝文本固定为 `Permission denied by user`，FileAgent 的审计理由只留在 Approval，不注入模型。

- [ ] **步骤 5：运行测试并提交**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentApprovalServiceTest,AgentApprovalRestartIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application
git commit -m "feat(agent): 恢复审批后的 Agent 执行"
```

### 任务 6：实现审批接口、同源保护和过期扫描

**文件：**
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/interfaces/AgentApprovalController.java`
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentApprovalExpiryScheduler.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AgentProperties.java`
- 修改：`fileagent-starter/src/main/resources/application.yml`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/interfaces/AgentApprovalControllerTest.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/AgentApprovalExpirySchedulerTest.java`

- [ ] **步骤 1：写接口与 scheduler 测试**

断言缺少/错误 `Origin/Host/X-FileAgent-Client` 被拒绝；成功响应 SSE；相同决定订阅原 replay；不同决定 409；备注被清洗且不进入 ConfirmResult；无人请求时 scheduler 仍原子过期三个状态。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentApprovalControllerTest,AgentApprovalExpirySchedulerTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，接口和 scheduler 不存在。

- [ ] **步骤 3：实现接口**

```text
POST /api/agent-runs/{runId}/approvals/{approvalId}
Headers: Accept=text/event-stream, X-FileAgent-Client=web, Last-Event-ID 可选
Body: {"decision":"APPROVE|DENY","comment":"..."}
```

Controller 在返回 Flux 前调用同步 prepare；响应 `X-Agent-Run-Id`。`approval.resolved` 不返回 comment 和原始参数。

- [ ] **步骤 4：实现过期扫描**

默认每 30 秒扫描，启动立即执行一次；用 CAS 把 Approval/Step/Run 一起收敛并释放 active slot。

- [ ] **步骤 5：运行测试并提交**

运行：`mvn -pl fileagent-agent -am test -Dtest=AgentApprovalControllerTest,AgentApprovalExpirySchedulerTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-agent/src/main/java/com/demetrius/fileagent/agent/interfaces/AgentApprovalController.java fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/AgentApprovalExpiryScheduler.java fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AgentProperties.java fileagent-starter/src/main/resources/application.yml fileagent-agent/src/test/java/com/demetrius/fileagent/agent/interfaces/AgentApprovalControllerTest.java fileagent-agent/src/test/java/com/demetrius/fileagent/agent/application/AgentApprovalExpirySchedulerTest.java
git commit -m "feat(agent): 提供审批接口与过期处理"
```

### 任务 7：实现前端审批区

**文件：**
- 修改：`fileagent-starter/src/main/resources/static/app.js`
- 修改：`fileagent-starter/src/main/resources/static/app.css`
- 修改：`fileagent-starter/src/test/java/com/demetrius/fileagent/StaticPageContractTest.java`

- [ ] **步骤 1：扩展静态契约测试**

断言 `approval.required/resolved`、APPROVE/DENY、`expiresAt`、409 快照恢复、`OUTCOME_UNKNOWN` 文案均存在；不得出现“始终允许”按钮。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-starter -am test -Dtest=StaticPageContractTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL。

- [ ] **步骤 3：实现内联审批交互**

审批区展示服务端摘要、影响、资源、脱敏预览、指纹、到期时间；点击后同时禁用两个按钮；批准/拒绝继续同一 assistant 消息 SSE；409 重新读快照；WAITING 用本地 timer + `/active` 低频轮询，到期立即禁用。

- [ ] **步骤 4：运行测试并提交**

运行：`mvn -pl fileagent-starter -am test -Dtest=StaticPageContractTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-starter/src/main/resources/static/app.js fileagent-starter/src/main/resources/static/app.css fileagent-starter/src/test/java/com/demetrius/fileagent/StaticPageContractTest.java
git commit -m "feat(web): 增加 Agent 操作审批交互"
```

### 任务 8：Phase 3B 集成验证与文档

**文件：**
- 修改：`docs/API.md`
- 修改：`docs/SKELETON.md`
- 修改：`docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeApprovalPauseTest.java`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeApprovalResumeTest.java`

- [x] **步骤 1：实现真实 AgentScope HITL 集成测试**

用固定模型桩产生一个 ASKING ToolCall，验证 ASK -> 保存检查点 -> 新 ReActAgent -> APPROVE/DENY -> 恢复；再验证多个 ASKING 直接失败且无副作用。

- [x] **步骤 2：运行 Agent 模块测试**

运行：`mvn -pl fileagent-agent,fileagent-starter -am test`

预期：PASS。

- [x] **步骤 3：更新 API 与 Roadmap**

记录 Approval API、事件字段、同源头、过期、409 和恢复语义；只把 3B 标为完成。

- [x] **步骤 4：运行全量测试并提交**

运行：`mvn test`

预期：PASS。

```bash
git add docs/API.md docs/SKELETON.md docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeApprovalPauseTest.java fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeApprovalResumeTest.java
git commit -m "docs(agent): 完成 Phase 3B 验收"
```

## Phase 3B 退出门槛

- WAITING_APPROVAL 重启后仍可批准或拒绝。
- 客户端不能提交身份、修改参数或加入永久授权规则。
- 重复/并发审批不会重复恢复，绑定变化不会执行旧批准。
- 审批过期不会永久占用会话。
- 前端刷新、断线、409、过期和移动端布局均完成验证。
