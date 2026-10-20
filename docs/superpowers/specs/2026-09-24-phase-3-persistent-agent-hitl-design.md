# Phase 3 持久化 Agent、HITL 与安全工具执行设计

## 1. 背景

Phase 1 已建立 AgentScope ReAct Runtime、受控 SSE、查询和取消接口；Phase 2A 已加入自适应检索；Phase 2B 已加入上下文预算、细粒度证据读取和滚动摘要。

当前 Agent 仍有以下明确限制：

- `AgentRun`、取消句柄和运行状态只保存在进程内，应用重启后全部丢失。
- `WAITING_APPROVAL` 只在 API 枚举中预留，Agent 不能真正暂停、审批和恢复。
- SSE 订阅直接驱动 AgentScope 执行，浏览器断开会取消运行，无法形成持久化后台任务。
- 当前只有知识库只读工具，`fileagent-action` 仍是未接线的动作执行骨架。
- 没有副作用幂等账本、工具风险策略、外部 HTTP 安全边界和代码执行隔离。
- 当前系统没有登录、用户、角色或租户体系，不能把本阶段包装成已经完成企业身份认证。

Phase 3 主体可与 Phase 2C 并行，补齐持久化 Run/Step、HITL、幂等副作用执行、安全 HTTP 工具和独立 Python 沙箱，并同步交付前端审批和恢复交互；只有共享 DTO 与最终引用校验接点在 Phase 2C 基线合并后联调。

## 2. 目标与非目标

### 2.1 目标

- `AgentRun`、`AgentStep`、审批、检查点和副作用执行记录持久化。
- SSE 连接与 Agent 执行解耦；浏览器断开后 Run 继续执行。
- 应用在 `WAITING_APPROVAL` 时重启，恢复后仍可批准或拒绝并继续运行。
- 每个副作用 ToolCall 使用服务端执行键，并按规范化动作指纹识别同一 Run 内的语义重复，防止基础设施重放和模型重新生成等价 ToolCall 造成重复执行。
- 工具执行经过固定 Schema、可信上下文、资源授权、风险策略和审批链路。
- 实现 `call_registered_http_api` 和隔离的 `execute_python`。
- 增加前端待审批展示、批准/拒绝、刷新恢复、冲突处理和后台状态展示。
- 建立确定性的状态、幂等、SSRF、沙箱和敏感信息泄漏质量门禁。

### 2.2 非目标

- 不实现任意 URL、Shell、SQL、宿主机文件读写或知识库文件修改。
- 不实现生成文件、报告导出或 Artifact 持久化。
- 不实现 JavaScript、Java 或多语言代码执行，首版沙箱只支持 Python 3。
- 不实现 OIDC、RBAC、ABAC 或多租户；这些仍属于 Phase 7。
- 不允许审批时修改模型生成的工具参数，也不支持“以后始终允许”。
- 不实现跨节点消息队列和分布式调度；首版通过数据库租约保证单 Run 执行权。
- 不修改 Phase 2C 的 Citation/Evidence、索引结构和引用验证语义。
- 不将 Prompt、模型思维链、原始工具结果或凭证写入普通日志和 SSE。

## 3. 核心决策

### 3.1 本地单用户身份

Phase 3 使用服务端固定身份 `local-operator`：

- 浏览器请求、模型参数和工具参数均不得提交或覆盖 `actorId`、`tenantId`、角色或凭证。
- 审批记录的 `decidedBy` 由服务端写入 `local-operator`。
- 预留可信身份解析 Port，Phase 7 接入 OIDC 后替换固定实现。
- 在 Phase 7 完成前，有副作用的 Agent 只允许监听 loopback，不允许部署到局域网或公网，也不能宣称具备多用户审批安全能力。
- 所有修改状态的前端请求必须同源，并携带服务端要求的自定义请求头；服务端校验 `Origin/Host`，不开放 CORS，以阻断普通跨站表单和跨域脚本触发审批、取消或执行。
- 如果未来需要跨主机或内网访问，必须先引入可信反向代理身份或最小认证与 `AGENT_APPROVER` 权限，不能继续沿用固定身份模式。

### 3.2 双层持久化

采用 FileAgent 业务状态与 AgentScope 检查点双层持久化：

```text
FileAgent Run / Step / Approval / ToolExecution
  -> 状态、预算、审批、幂等和审计事实

AgentScope AgentState
  -> 模型上下文、待确认 ToolCall 和 SDK 恢复材料
```

FileAgent 数据是业务事实源；AgentScope JSON 只是可替换、可迁移的恢复检查点。只接入 `AgentStateStore` 不能替代 Run、Step、预算、资源白名单和审批持久化。

AgentScope 状态槽使用唯一 `runId`，不再使用聊天 `sessionId`。聊天会话 ID 仍作为 `AgentRun.sessionId` 保存，避免同一会话的多个历史 Run 覆盖同一个 AgentScope 状态。

### 3.3 采用 AgentScope 原生 HITL

复用 AgentScope 2.0.1 已有能力：

- `AgentStateStore`；
- `PermissionContextState` 和 `PermissionEngine`；
- `RequireUserConfirmEvent`；
- 第二次调用携带 `ConfirmResult` 后恢复执行。

不自行重写 ReAct 循环，也不引入 Temporal、Flowable 等工作流引擎。FileAgent 策略是最终授权事实，AgentScope PermissionEngine 负责暂停和恢复协议，两者不能倒置。

### 3.4 完整 Phase 3 分段交付

Phase 3 拆为四个可独立验收的阶段：

- Phase 3A：持久化 Runtime 与后台执行；
- Phase 3B：HITL、审批和前端交互；
- Phase 3C：幂等安全 HTTP 工具；
- Phase 3D：独立 Python 沙箱和安全评测。

## 4. 总体架构

```text
AgentRunController
        |
        v
fileagent-agent
Run 状态机 / Step / Approval / Policy / 恢复协调
        |
        +-- AgentScope ReAct + PermissionEngine
        |       `-- 加密 AgentState 检查点
        |
        `-- ActionExecutionPort
                  |
                  v
          fileagent-action
      HTTP 执行 / 幂等账本 / 沙箱客户端
                  |
                  v
      fileagent-sandbox-worker
        一次性 Python 容器
```

### 4.1 `fileagent-agent`

- 拥有 `AgentRun`、`AgentStep`、`ApprovalRequest` 和检查点。
- 负责状态机、预算、风险决策、审批恢复、后台运行和受控 SSE。
- 实现 AgentScope 数据库 `AgentStateStore` 适配。
- 将当前 `InMemoryAgentRunRegistry` 收缩为正在执行的取消句柄缓存，不再作为状态事实源。
- 后台协调器内部维护有界事件通道，不为每项职责新增 Manager 类。

### 4.2 `fileagent-api`

- 新增最小跨域 `ActionExecutionPort`。
- Port 只提供四个能力：`resolveDefinition(actionId)`、`execute(command)`、`findExecution(executionId)`、`cancel(executionId)`，不暴露 Action Repository。
- `resolveDefinition` 返回无凭证的 `ActionDefinitionView`，只含工具/Endpoint 版本、Schema、风险、资源和数据外发策略、目标配置哈希及展示摘要，供 Agent 域在审批前校验。
- `execute` 输入只接受服务端生成的 ToolCall、定义版本、`executionKey/operationId`、规范化参数和可信执行上下文；Action 域再次校验定义快照并通过幂等账本执行。
- `findExecution` 返回受控状态、稳定错误码、结果摘要和是否允许恢复，用于重启收敛；密文、请求正文和实体不跨域。
- `cancel` 返回 `NOT_STARTED/CANCELLED/OUTCOME_UNKNOWN`，Agent 域据此选择 `CANCELLED` 或 `FAILED/AGENT_ACTION_OUTCOME_UNKNOWN`，不能假设取消一定成功。
- 不允许 action 实体泄漏到 agent，也不允许模型构造身份、凭证或 URL。

### 4.3 `fileagent-action`

- 实现 `ActionExecutionPort`。
- 拥有动作定义、真实执行和 `action_tool_execution` 幂等账本。
- 实现注册式 HTTP 客户端和 Sandbox Worker 客户端。
- 不决定是否审批；风险由动作定义声明，最终 `ALLOW/DENY/ASK` 由 Agent 域策略决定。

### 4.4 `fileagent-sandbox-worker`

- 新增独立启动模块，不被 `fileagent-starter` 装配。
- 接收受控的 Python 执行请求，并调用 rootless OCI 容器运行时创建一次性容器。
- Worker 不挂载业务源码、知识库、宿主目录、Docker Socket 到执行容器。
- 主应用和 Worker 使用环境变量 Token 完成内部调用认证；Phase 7 再升级为正式服务身份。

## 5. 后台执行模型

Phase 3 将 HTTP 连接从执行所有权中移除：

```text
Controller -> 创建/恢复 Run -> AgentRunExecutionCoordinator
Controller <- 订阅有界事件通道 <- 后台 Worker
```

- Controller 订阅 SSE，只负责观察受控事件。
- Coordinator 在独立执行线程启动 AgentScope，并持有取消句柄。
- SSE 断开只移除订阅者，不调用取消。
- 只有显式取消接口和前端“停止”按钮才终止 Run。
- Coordinator 为活动 Run 保留有界 replay 通道，默认最多 100 个受控事件且总量不超过 256 KB。每个事件生成不可预测的 `eventId` 并同时写入 SSE `id`；重复订阅通过 `Last-Event-ID` 只重放游标之后的事件，前端按 `(runId,eventId)` 去重。
- Coordinator 使用有界执行池，默认最多 4 个活动 Run、32 个排队 Run。创建或审批恢复必须先原子预留 capacity permit，再提交业务事务并入队；容量耗尽时返回 HTTP 429 和 `AGENT_CAPACITY_EXCEEDED`，且不创建 Run、不落用户消息、不改变 Approval。事务或入队失败释放 permit；提交后进程崩溃则由 PENDING/已决 Approval 扫描恢复。
- permit 覆盖 RUNNING 和队列占用，Run 进入 `WAITING_APPROVAL` 或终态时释放；审批恢复必须重新预留。SSE 订阅者不占执行 permit。
- 应用启动先扫描持久化的 PENDING 和已决待恢复 Run、重建 permit 占用，再开放新建接口，避免新请求抢占全部容量导致旧 Run 饥饿。
- replay 通道溢出时优先合并或丢弃 `message.delta`，不得丢弃 `approval.required`、`approval.resolved`、`run.failed` 和 `run.completed` 等关键事件；前端始终可通过快照收敛到数据库状态。
- `Last-Event-ID` 已不在当前内存环中或应用已经重启时，不重放残缺的 `message.delta`，只发送当前 `run.status` 快照并等待后续事件；终态以会话消息重新加载为准。
- 事件通道不是持久化事实；应用重启或长时间断线后，前端通过快照和会话消息恢复，不承诺逐 Token 重放。
- Run 到达终态后，内存通道按现有完成态 TTL 清理，数据库记录不随该 TTL 删除。

同一会话首版只允许一个非终态 Agent Run。`agent_run.active_slot` 在活动状态保存 `session:{sessionId}`，终态清空；数据库唯一约束阻止并发创建竞态。

## 6. 状态机

唯一合法 Run 迁移如下，其他迁移全部返回 `AGENT_INVALID_STATE`：

| 当前状态 | 允许目标状态 |
|---|---|
| `PENDING` | `RUNNING`、`CANCELLED`、`FAILED` |
| `RUNNING` | `WAITING_APPROVAL`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`TIMED_OUT` |
| `WAITING_APPROVAL` | `RUNNING`、`FAILED`、`CANCELLED`、`TIMED_OUT` |
| `SUCCEEDED/FAILED/CANCELLED/TIMED_OUT` | 无 |

规则如下：

- `WAITING_APPROVAL` 不是终态，允许批准、拒绝、取消和过期。
- 审批拒绝后恢复为 `RUNNING`，向 AgentScope 注入受控拒绝结果，由 Agent 解释或选择安全替代方案。
- 终态不可再次迁移。
- 所有迁移使用 `@Version` 或条件更新实现 CAS。
- 审批等待不计入活动运行超时；Token、步骤、模型调用和工具预算不会在恢复时重置。
- 审批默认 30 分钟过期；过期后 Run 进入 `TIMED_OUT`，错误码为 `AGENT_APPROVAL_EXPIRED`。
- 每个 Run 最多 2 次副作用调用、最多 2 次审批，同一时刻最多一个待审批副作用 ToolCall。
- 系统 Prompt 要求一次最多提出一个副作用动作。若模型仍在一次响应中产生多个 ASKING ToolCall，服务端不创建 Approval、不执行动作，也不伪造 AgentScope 不支持的自定义拒绝原因；记录失败 Step 后直接将 Run 转为 `FAILED/TOOL_BATCH_REQUIRES_SEQUENTIAL_EXECUTION`。AgentScope 2.0.1 的 `ConfirmResult(false, toolCall)` 只会生成固定 `Permission denied by user`，因此不能用它冒充带错误码的批量恢复协议。

### 6.1 Step 状态

`AgentStep` 类型：

- `MODEL_CALL`；
- `TOOL_CALL`；
- `FINALIZATION`。

工具 Step 状态：

- `PENDING`；
- `WAITING_APPROVAL`；
- `EXECUTING`；
- `SUCCEEDED`；
- `DENIED`；
- `FAILED`；
- `OUTCOME_UNKNOWN`；
- `CANCELLED`；
- `EXPIRED`。

## 7. 持久化模型

Phase 3 新增五张表，不单独创建 `agent_tool_call`；工具调用直接作为 `AgentStep` 保存。

### 7.1 `agent_run`

```text
run_id PK
session_id
active_slot UNIQUE NULLABLE
input_message_id
assistant_message_id
request_schema_version
request_ciphertext CLOB
request_hmac
actor_id
status
failure_code
trace_id
step_count
model_call_count
history_characters
summary_characters
search_snippet_characters
document_read_characters
tool_result_characters
input_tokens
output_tokens
total_tokens
active_elapsed_ms
budget_reasons CLOB
execution_owner
lease_expires_at
version
created_at
started_at
ended_at
updated_at
```

`active_slot` 在 `PENDING/RUNNING/WAITING_APPROVAL` 时非空，进入终态时清空。

`request_ciphertext` 保存版本化 `AgentRunCommand` 的加密信封，`request_hmac` 校验规范化请求；否则 Run 在创建事务提交后、Coordinator 入队前崩溃时，只剩 PENDING 状态而没有可恢复的执行输入。请求明文不写数据库和日志。

### 7.2 `agent_step`

```text
id PK
run_id
sequence_no
step_type
status
tool_call_id
tool_name
tool_version
tool_definition_version
endpoint_revision
endpoint_config_hash
risk_level
policy_decision
policy_version
safe_summary
canonical_input_hmac
input_ciphertext CLOB
operation_fingerprint
operation_id
execution_id
result_code
result_summary
duration_ms
version
created_at
started_at
ended_at
```

约束：

- `UNIQUE(run_id, sequence_no)`；
- `UNIQUE(run_id, tool_call_id)`，非工具 Step 的 `tool_call_id` 为空。
- `UNIQUE(run_id, operation_fingerprint)`，语义相同的副作用不能通过生成新 `tool_call_id` 绕过去重。

原始工具参数仅以 AES-GCM 密文保存，用于审批后恢复；查询接口和日志只能返回 `safe_summary` 和 HMAC。所有 Step 迁移使用 `version` 或带前置状态的条件更新实现 CAS。

### 7.3 `agent_approval`

```text
approval_id PK
run_id
step_id UNIQUE
status
risk_level
action_summary
resource_scope
expected_impact
parameter_preview CLOB
canonical_input_hmac
tool_definition_version
endpoint_revision
endpoint_config_hash
policy_version
operation_id
expires_at
decided_by
decision_comment
resolution_reason
decided_at
version
created_at
updated_at
```

审批状态：`PENDING/APPROVED/DENIED/EXPIRED/CANCELLED`。

`parameter_preview` 由服务端根据工具 Schema 的 `sensitive` 元数据生成，做长度限制和敏感字段脱敏，并展示 `canonical_input_hmac` 的短指纹，不直接复用模型原始 JSON。规范化参数采用稳定键顺序和数值/字符串编码的 canonical JSON，同一语义输入必须得到相同 HMAC。`decision_comment` 最长 500 字符、移除控制字符，只用于审计和界面展示，绝不注入模型或工具参数。

### 7.4 `agent_checkpoint`

```text
run_id PK
checkpoint_sequence
agentscope_state_ciphertext CLOB
authorization_state_ciphertext CLOB
finalization_context_ciphertext CLOB
finalization_context_schema_version
schema_version
checksum
version
updated_at
```

- 每个 Run 只保留最新稳定检查点，不保存无限历史。
- `agentscope_state_ciphertext` 保存 AgentScope 恢复状态。
- `authorization_state_ciphertext` 保存当前 Run 的 `fileId/chunkId` 白名单和必要运行上下文。
- `finalization_context_ciphertext` 以版本化不透明载荷保存本次实际入模证据等最终化上下文，供暂停恢复后继续完成 Phase 2C 引用校验；不能只保存文件白名单。
- 保存前删除确认无恢复用途的原始调试信息；供应商协议恢复必需的 continuation/ThinkingBlock 必须加密保留，不能一概删除，并通过 DeepSeek 等实际供应商的暂停恢复测试确认字段集合。
- 恢复前由 FileAgent `StrictAgentStateStore` 显式读取、解密并校验 schema、checksum、ASKING ToolCall 和授权快照，再把已验证状态交给 AgentScope；不能依赖 AgentScope 读取异常后降级为 fresh state 的默认行为。恢复调用后还要核对预期状态标记，缺失时立即安全失败。
- 检查点不是审计事实，Run、Step、Approval 和 ToolExecution 才是。

### 7.5 `action_tool_execution`

```text
execution_id PK
execution_key UNIQUE
operation_fingerprint
operation_id UNIQUE
run_id
tool_call_id
tool_name
tool_version
tool_definition_version
endpoint_revision
status
attempt
canonical_request_hmac
external_request_id
result_code
result_ciphertext CLOB
result_hash
version
started_at
ended_at
updated_at
```

执行状态：`PENDING/EXECUTING/SUCCEEDED/FAILED/OUTCOME_UNKNOWN/CANCELLED`。

三个标识均由服务端生成，模型和客户端不能提交：

- `execution_key = HMAC(runId + toolCallId + toolDefinitionVersion)`，标识同一个已持久化 ToolCall 的重放；
- `operation_fingerprint = HMAC(runId + toolName + toolDefinitionVersion + endpointRevision + canonicalArguments)`，识别同一 Run 内语义相同但 `toolCallId` 不同的副作用动作；
- `operation_id` 是服务端稳定业务流水号，同时作为远端幂等值。相同 `operation_fingerprint` 复用已有结果或继续等待原审批，不得静默创建第二次执行。

敏感字段不保存裸 SHA-256。密文采用带 `envelopeVersion`、`keyId` 的 AES-GCM 信封格式，AAD 固定包含 `table + primaryKey + field + schemaVersion`；哈希用途使用服务端 HMAC。密钥缺失、版本不支持或认证失败时安全失败为 `CHECKPOINT_DECRYPT_FAILED`，禁止降级读取。

### 7.6 数据库策略

- Phase 3 沿用当前 H2/JPA 和 `ddl-auto:update`，字段类型避免依赖 H2 方言；这只满足本地单实例学习和验证，不是企业生产迁移方案。
- Phase 5 迁移 PostgreSQL 时为这些表建立正式 Flyway baseline。
- 在 Phase 5 完成正式数据库、迁移、备份恢复和多实例并发验证前，不得把 Phase 3 当前 H2 形态标记为生产就绪。
- 现有数据库没有历史 Agent Run，不进行虚假回填。
- 持久化 Run 不再使用内存完成态 TTL 自动删除；正式保留策略在 Phase 7/9 统一设计。

## 8. 运行流程

### 8.1 创建 Run

```text
校验 Feature Flag、会话和 prompt
  -> 原子预留执行池/队列 capacity permit
  -> 读取历史与摘要
  -> 保存 USER 消息并取得 inputMessageId
  -> 创建 PENDING AgentRun
  -> 抢占 session active_slot
  -> Coordinator 将 Run 转为 RUNNING
  -> 后台启动 AgentScope
  -> SSE 订阅受控事件
```

用户消息与 Run 创建使用明确事务边界。创建失败不能留下一个没有 Run 归属的新 USER 消息。
Run 创建事务提交后即使在 Coordinator 入队前崩溃，启动协调器也会扫描无租约的 `PENDING` Run 并重新入队；超过排队存活上限仍无法启动时转为 `FAILED/AGENT_RUN_INTERRUPTED` 并清空 `active_slot`，不能永久占用会话。

### 8.2 进入审批

```text
模型产生 ToolCall
  -> Schema 校验
  -> 若存在多个需审批副作用 ToolCall，则记录失败 Step 并以批量错误终止 Run
  -> FileAgent 风险与资源策略返回 ASK
  -> 固化工具定义、Endpoint、策略、资源范围和规范化参数快照
  -> AgentScope 标记 ToolCall 为 ASKING
  -> 捕获 RequireUserConfirmEvent，但暂不向前端发送
  -> AgentScope 完成本轮并保存加密检查点
  -> 事务写入 Step、Approval，并将 Run 转为 WAITING_APPROVAL
  -> 事务提交后发送 approval.required
  -> 当前 SSE 正常结束
```

采用“检查点成功 -> 业务状态提交 -> 公开事件”的顺序。只要前端看到了 `approval.required`，重启后就必须能够查询并处理该审批。

如果检查点已经保存但业务状态提交前崩溃，恢复协调器根据 `RUNNING` Run 与 ASKING 检查点重建审批；在完成重建前不执行工具。

### 8.3 审批与恢复

```text
审批 API 校验 runId + approvalId
  -> 原子预留执行池/队列 capacity permit
  -> 全部必要数据来自数据库，不信任客户端 ToolCall
  -> StrictAgentStateStore 读取、解密并固定已验证检查点
  -> 在同一 prepare 事务中复核工具定义版本、Endpoint revision/config hash、策略版本、资源范围和参数 HMAC
  -> 复核通过后，在同一事务中 CAS 写入 APPROVED/DENIED、把 Run 转为 RUNNING 并取得租约
  -> 从检查点重新创建 ReActAgent
  -> 服务端构造 ConfirmResult
  -> 后台恢复执行并返回新的 SSE
```

- 重复提交相同审批决定时不再次恢复：若 Run 尚在执行则订阅既有 replay 通道，若已终态则发送由快照合成的终态事件后结束；不得创建第二个执行者。
- 检查点读取、绑定复核或 CAS 失败时释放预留的 capacity permit；审批决定已提交但入队明确失败时，以独立事务把 Run 转为 `FAILED/AGENT_RECOVERY_FAILED` 后释放 permit。若在提交后进程直接崩溃，则由启动扫描按 8.6 接管。
- 已批准后再拒绝，或已拒绝后再批准，返回 HTTP 409。
- 审批接口不能修改参数；参数变化必须产生新 ToolCall 和新审批。
- 工具行为定义、Endpoint 目标或策略在等待期间发生变化时，prepare 事务把旧 Approval 和 Step 转为 `CANCELLED`、`resolution_reason=BINDING_CHANGED`，Run 转为 `FAILED/APPROVAL_BINDING_CHANGED` 并返回 HTTP 409；不能先提交 APPROVED 再发现不匹配，也不能拿旧批准执行新配置。后续动作必须由新 Run 重新提出和审批。`configHash` 只覆盖会改变目标、请求或权限边界的非密钥配置，单纯凭证值轮换可以继续使用。
- 拒绝审批时不执行动作，将受控拒绝结果交给模型继续回答。

### 8.4 最终回答

AgentScope 生成结束不立即把 Run 标为成功。Phase 3 预留唯一的 `AgentFinalizationContext` 输入，统一携带最终答案、实际工具结果摘要和版本化的实际入模证据；Phase 2C 只通过该上下文补充 citation verification 结果，不另建一条回答落库路径：

```text
收到最终输出
  -> 保持 RUNNING
  -> 保存 ASSISTANT 消息并取得 messageId
  -> 写入 FINALIZATION Step
  -> Run 转为 SUCCEEDED
  -> 发送 sources 和 run.completed
```

最终化由单一 `AgentRunFinalizationService` 事务完成：先调用可选的 Phase 2C 引用校验贡献点，再让 ASSISTANT 消息写入加入同一个 Spring 事务，随后写 FINALIZATION Step、`assistant_message_id` 并把 Run 转为 `SUCCEEDED`；禁止消息 Port 使用 `REQUIRES_NEW` 提前提交。事务提交后才发送 `sources`、引用字段和 `run.completed`。任一步失败整体回滚并将 Run 以独立失败事务转为 `FAILED/AGENT_PERSIST_FAILED`，不留下“回答已落库但 Run 仍可恢复并重复写消息”的窗口。

### 8.5 取消和断连

- SSE 断开不改变 Run 状态。
- 显式取消对 `PENDING`、`RUNNING` 和 `WAITING_APPROVAL` 都有效。
- 取消等待审批时，在同一事务中将待审批记录和对应 Step 都转为 `CANCELLED`。
- 取消 Python 时终止并销毁容器。
- 取消 HTTP 时若能证明请求尚未发出，Run 可进入 `CANCELLED`；若请求可能已经送达，执行记录进入 `OUTCOME_UNKNOWN`，Run 转为 `FAILED/AGENT_ACTION_OUTCOME_UNKNOWN`，不能声称远端未执行。

### 8.6 应用重启

Phase 3 的强保证限定为 ASKING 检查点上的审批暂停与动作恢复，不宣称任意模型调用中间位置都可续跑。启动协调器按下表收敛：

| Run/Step/Approval 状态 | 检查点/账本 | 启动后处理 |
|---|---|---|
| `PENDING` | 无运行租约 | 在排队存活上限内重新入队，超限则安全失败并释放 `active_slot` |
| Run `WAITING_APPROVAL`、Approval `PENDING` | ASKING 检查点存在 | 保持等待，不自动执行 |
| Run `WAITING_APPROVAL/RUNNING`、Approval `APPROVED/DENIED` | ASKING 检查点存在 | 重新入队，从该检查点注入既有决定 |
| `RUNNING` | ASKING 检查点存在但 Approval 缺失 | 根据不可变快照重建 Approval，CAS 转为 `WAITING_APPROVAL` |
| ToolExecution `SUCCEEDED` 且 Step `EXECUTING` | ASKING 检查点存在 | 从检查点恢复，账本返回已保存结果，不再次执行 |
| ToolExecution `OUTCOME_UNKNOWN` | 任意 | Run 转为 `FAILED/AGENT_ACTION_OUTCOME_UNKNOWN`，禁止自动重试 |
| 陈旧 `RUNNING` | 无 ASKING 检查点 | Run 转为 `FAILED/AGENT_RUN_INTERRUPTED`，不从头静默重跑 |
| 终态 Run | 任意 | 永不恢复执行 |

`execute_python` 是上述通用恢复矩阵的失败关闭例外：应用重启时，任何尚未执行完成的 Python Approval/Step/Run 都不得重新入队或调用 Worker。旧审批与步骤必须收敛为取消/失败；用户需要执行时只能创建新 Run，并再次明确审批。重启前已经保存的成功结果可以只读查询，但不能再次运行代码。

审批过期必须在一个事务中把 Approval 转为 `EXPIRED`、对应 Step 转为 `EXPIRED`、Run 转为 `TIMED_OUT`。若无法验证检查点与不可变动作快照完全匹配，则使用 `AGENT_CHECKPOINT_MISSING` 或 `AGENT_RECOVERY_FAILED` 安全失败。

`ApprovalExpiryScheduler` 默认每 30 秒扫描到期的 PENDING Approval，应用启动时立即扫描一次；迁移使用 CAS 并释放 `active_slot`，避免无人访问时审批永久占用会话。

## 9. 租约和并发

`AgentRunExecutionCoordinator` 使用 `execution_owner + lease_expires_at + version`：

- 一个 Run 同时只能有一个执行者。
- Worker 在活动执行期间定时续租。
- 应用启动时只处理租约已过期的 Run，并严格按 8.6 的收敛矩阵决定恢复或失败；普通 `RUNNING` 不能仅凭存在任意检查点就自动重跑。
- `AgentRunExecutionCoordinator` 启动时并按默认 15 秒周期执行一次收敛扫描，保证启动瞬间尚未过期的旧租约在到期后仍会被处理。
- `WAITING_APPROVAL` 不持有运行租约；批准后重新抢占。
- 取消、审批和终态迁移全部使用 CAS。
- 本地 H2 仍按同一协议执行，为 Phase 5 PostgreSQL 和多实例部署保留正确语义。

## 10. 风险与权限策略

固定风险等级：

| 风险 | 默认决策 | 适用范围 |
|---|---|---|
| `READ_ONLY` | `ALLOW` | 知识库工具；不携带模型自由文本且声明禁止数据外发的注册 HTTP Endpoint |
| `LOW_RISK_WRITE` | `ASK` | 创建草稿等可撤销 HTTP 动作 |
| `HIGH_RISK_WRITE` | `ASK` 且不可绕过 | 外部写操作、`execute_python` |
| `PROHIBITED` | 不注册 | Shell、任意 URL、SQL、宿主机文件操作 |

权限处理顺序：

```text
工具白名单
  -> JSON Schema
  -> 服务端可信上下文
  -> 资源级授权
  -> DENY / ASK / ALLOW
  -> 审批
  -> 幂等账本
  -> 执行前再次授权
  -> 真实执行
  -> 结果脱敏
```

AgentScope 2.0.1 会在普通 ToolExecutor Schema 校验之前评估权限，而且静态 tool-level ASK rule 会短路工具自身权限检查，因此副作用工具不能直接依赖全局 PermissionEngine 的静态 ASK 配置。`call_registered_http_api` 和 `execute_python` 必须按 Run 创建轻量 `ToolBase` wrapper：

- wrapper 构造时持有服务端生成的可信 Run/actor/资源上下文，模型和 `PermissionContext` 不能提供或覆盖；
- `checkPermissions(input, permissionContext)` 内先执行 FileAgent Schema、可信上下文、资源授权和风险判断，再返回 `DENY/ASK/ALLOW`；
- 不为这两个工具配置会提前短路的 tool-level ASK rule；
- `callAsync` 执行前再次做同样的版本、参数 HMAC、资源授权检查，并统一进入 ToolExecution 幂等账本。

这样才保证“先校验参数和授权，再展示审批”，也避免审批一个最终无法通过 Schema 的动作。

约束：

- `DENY` 优先级最高。
- 资源授权失败始终拒绝。
- 不启用可绕过 FileAgent 策略的 AgentScope BYPASS 模式。
- `HIGH_RISK_WRITE` 不能通过历史审批或 Prompt 自动放行。
- HTTP 即使使用 GET，只要参数允许模型自由文本或可能携带会话/知识库数据，就至少判为 `ASK`；HTTP Method 不能被当成“无数据外发”的证明。
- 审批摘要由服务端生成，不采用 `ActionDto.reasoning` 或模型自由文本作为授权依据。
- 文档中的 Prompt Injection 只能影响模型建议，不能修改工具定义、权限策略或可信上下文。

## 11. `call_registered_http_api`

### 11.1 Endpoint 注册

模型输入只包含：

```json
{
  "endpointId": "registered-endpoint-id",
  "arguments": {}
}
```

服务端 Endpoint 定义包含：

```text
endpointId
displayName
definitionVersion
endpointRevision
configHash
policyVersion
method
baseUrl
pathTemplate
requestSchema
riskLevel
dataEgressPolicy
credentialRef
connectTimeout
readTimeout
maxResponseBytes
allowedContentTypes
idempotencyHeader
```

Endpoint 通过服务端配置注册，Phase 3 不提供无认证的管理页面和动态注册接口。

`requestSchema` 可给字段标记 `sensitive`，该元数据同时驱动日志、审批预览和 ToolResult 脱敏。`dataEgressPolicy` 明确哪些参数只能由服务端填充、哪些允许模型提供；未声明时默认禁止携带自由文本。注册校验拒绝不符合以下 URL、HTTPS、远端幂等和响应限制约束的 Endpoint。

### 11.2 安全边界

- 模型不能提交 URL、Method、Header、Token、代理地址或重定向目标。
- Method 不决定风险；风险由注册定义固定。
- URI 必须通过结构化 URI Builder 从注册的 `baseUrl` 和参数构建；拒绝参数中的绝对 URL、`//` scheme-relative URL、userinfo、反斜线、CR/LF、编码或双重编码的路径穿越以及模板外字段。
- 请求 Schema 限制字段数、嵌套深度、字符串长度和请求体大小，Header 仅能来自服务端定义并限制数量与总大小。
- 禁止 HTTP 重定向。
- DNS 解析后只要任一 A/AAAA 地址属于 loopback、私网、link-local、metadata、组播、ULA、IPv4-mapped IPv6 或其他保留地址，就拒绝整个请求。
- 实际 HTTP Client 必须使用安全解析器把已校验 IP 固定到本次连接，同时保留原始主机名用于 Host 和 TLS SNI，禁止连接阶段再次走系统 DNS；每次重试都重新解析和校验。
- 禁用 JVM 和系统代理继承，工具请求不能受 `HTTP_PROXY`、PAC 或本机代理配置改写。
- 限制连接、读取和总超时。
- 网络响应最大 256 KB，按解压后的流式字节累计并在超限时立即中止，同时限制响应 Header 数量与总大小；进入模型前继续遵守现有单次工具结果 4,000 字符预算。
- 只接受注册的 JSON/文本 Content-Type。
- 带凭证的 Endpoint 必须使用 HTTPS；仅测试夹具可以显式使用 loopback HTTP，生产配置不得放宽。
- 凭证通过环境变量或安全配置引用，绝不出现在模型工具 Schema、日志或审批预览。

### 11.3 HTTP 幂等

- 有副作用的 Endpoint 必须声明远端幂等 Header 或业务流水号。
- Phase 3 不允许注册缺乏远端幂等能力的写 Endpoint。
- 远端幂等 Header 使用服务端稳定 `operation_id`，不能使用模型产生的 `toolCallId`。
- 本地账本阻止 Agent 和应用重复提交；远端幂等处理“远端成功、本地尚未记账即崩溃”的窗口。
- 超时后无法确认远端结果时标记 `OUTCOME_UNKNOWN`，禁止自动重试。

## 12. `execute_python` 沙箱

```text
fileagent-action
  -> SandboxClient
  -> fileagent-sandbox-worker
  -> 一次性 Python 容器
  -> 受限 stdout/stderr
  -> 容器销毁
```

首版固定约束：

- 主应用请求 Worker 时只传 `executionId`、`code` 和 `inputJson`；镜像、命令、挂载、网络模式、用户、capabilities、资源限制和运行时参数全部由 Worker 固定，任何请求字段都不能覆盖；
- Python 3 固定版本和镜像摘要；
- 代码最大 20 KB；
- 只接收受控 JSON 输入；
- 非 root 用户；
- 无网络；
- 只读根文件系统；
- 临时 `tmpfs` 最大 64 MB，不提供持久化文件输出；
- 不挂载源码、知识库、上传文件、宿主目录或密钥；
- 1 CPU；
- 256 MB 内存；
- 最多 64 个进程；
- 最长 10 秒；
- stdout/stderr 合计最大 64 KB；
- 丢弃 Linux capabilities；
- 启用 `no-new-privileges` 和 seccomp；
- 执行结束或取消后销毁容器。

Worker 只能连接 rootless OCI runtime。执行容器不得访问 Docker Socket；Worker 自身的运行时权限需要在部署文档中明确说明和隔离。Worker 为容器写入不可伪造的 `executionId` Label，启动和定时任务清理超时孤儿容器。

Phase 3 主应用与 Worker 只允许同机 loopback 或 Unix Domain Socket 通信，静态 Token 只是附加防护；跨主机部署在引入 TLS/mTLS 服务身份前明确禁止。

容器沙箱仍共享宿主内核。本阶段只承诺在上述威胁模型和自动化用例内阻断已定义风险，不宣称绝对无法逃逸；面对恶意多租户或高对抗代码的生产环境，需要升级到 gVisor、Kata Containers 或 microVM，该升级不属于 Phase 3。

`execute_python` 固定为 `HIGH_RISK_WRITE` 并逐次审批。只有当前进程中本次审批明确批准后才允许调用 Worker；拒绝、审批过期、取消或应用重启恢复均不得运行脚本。重启后不从 ASKING 检查点恢复 Python 执行，也不因旧容器已不存在而重放代码；如仍需执行，必须创建新 Run 并重新审批。

## 13. API 与 SSE

保留：

```text
POST /api/sessions/{sessionId}/agent-runs
GET  /api/agent-runs/{runId}
GET  /api/agent-runs/{runId}/events
POST /api/agent-runs/{runId}/cancel
```

新增：

```text
GET  /api/sessions/{sessionId}/agent-runs/active
POST /api/agent-runs/{runId}/approvals/{approvalId}
```

`/active` 返回 `ApiResult<AgentRunSnapshot>`；当前会话没有非终态 Run 时返回 HTTP 200 且 `data=null`，避免刷新恢复把“无活动任务”误判成异常。

`/events` 返回 `text/event-stream`，通过 `Last-Event-ID` 增量重放当前进程内仍保留的事件；游标缺失、过期或应用已重启时先返回当前 `run.status` 快照，不伪造已丢失的 `message.delta`。创建接口只创建新 Run，前端恢复已有 Run 必须调用该订阅接口，不能重复提交创建请求。

审批请求：

```json
{
  "decision": "APPROVE",
  "comment": "同意执行"
}
```

`decision` 只允许 `APPROVE/DENY`。审批接口返回新的 `text/event-stream`，恢复后的回答继续通过该连接输出。`approval.resolved` 固定包含 `approvalId`、`status`、`decidedBy`、`decidedAt`、`runId` 和 `runVersion`，不包含原始参数或备注。

前端重新订阅或重复提交相同决定时携带最近的 `Last-Event-ID`；服务端只在该 ID 仍位于当前 Run 的内存 replay 环中时增量重放。

所有修改状态的接口要求同源请求和 `X-FileAgent-Client: web`，服务端校验 `Origin`/`Host` 且不开放 CORS；这只是本地模式的 CSRF 防护，不替代未来的用户认证。

创建 Run 和审批恢复的校验、Run/Approval CAS 与执行入队必须由同步 prepare 阶段在返回 `Flux` 前完成，确保流建立前业务错误保留对应 HTTP 状态并以 `event:error` 返回；不能把这些操作延迟到 reactive subscription。流建立后的失败返回 `run.failed`。

prepare 成功后立即设置响应头 `X-Agent-Run-Id`，并把 `run.status(PENDING)` 作为首个事件发送；即使 Run 尚未取得执行线程，前端也能取消和恢复它。

这是对当前 Agent 接口“流前错误也返回 `run.failed`”的一次明确契约修正：实现时同步更新 `docs/API.md` 和前端解析器；过渡期前端可以兼容读取旧 `run.failed`，服务端新行为统一输出 `error`。其余既有事件名称保持不变。

### 13.1 快照

`AgentRunSnapshot` 增加：

- 最近最多 20 条受控 Step 摘要；
- 当前待审批摘要；
- `version`；
- 是否正在恢复；
- 现有预算和 Token 字段继续保留。

不返回模型思维链、完整 Prompt、原始工具结果、密文或内部异常。

### 13.2 事件

新增：

- `run.status`；
- `approval.required`；
- `approval.resolved`。

所有 Agent 事件都包含 `runId` 和 `eventId`；`run.status` 至少包含 `status` 和 `runVersion`。

`approval.required` 包含：

```text
approvalId
tool
riskLevel
actionSummary
resourceScope
expectedImpact
parameterPreview
parameterFingerprint
expiresAt
status
```

典型顺序：

```text
run.status(PENDING)
run.started
approval.required
-- 当前 SSE 正常结束 --

approval.resolved
run.status(RUNNING)
tool.started
tool.completed
message.delta
sources
run.completed
```

拒绝时 `approval.resolved.status=DENIED`，不发送 `tool.started/tool.completed`；Agent 可继续生成解释或安全替代方案。

Phase 2C 可在同一 `AgentRunEvent` 尾部增加引用字段；Phase 3 增加审批字段，并只额外修正上述流前错误事件，不重构其他公开事件格式。

## 14. 前端设计

继续使用 `fileagent-starter/src/main/resources/static` 下的原生 HTML/CSS/JS，不引入新的前端框架。

### 14.1 审批展示

审批使用 assistant 消息内联区域，不使用与上下文脱离的弹窗：

```text
第 3 步：准备执行 Python
高风险操作 · 等待审批

动作：在隔离环境运行 Python 代码
影响：最多运行 10 秒，无网络，无持久化文件
详情：可展开的脱敏参数或代码预览
到期：14:30

[拒绝] [批准执行]
```

- Python 展示受控长度的规范化代码预览和服务端 HMAC 短指纹。
- HTTP 展示 Endpoint 名称、操作、资源和非敏感参数。
- 不展示 Token、凭证、内部地址和原始错误。
- 高风险状态使用清晰但克制的警示色，不把整个聊天界面改造成警告页面。

### 14.2 交互规则

- 收到 `approval.required` 后停止思考动画，保留工具轨迹和 assistant 占位消息。
- 从 `X-Agent-Run-Id` 或任意 Agent 事件保存当前 `runId`；收到 `PENDING` 时显示“Agent 已排队”，保持输入禁用并允许用户显式停止。
- 批准或拒绝时禁用两个按钮，防止重复提交。
- 审批成功后在同一消息继续渲染 SSE 回答。
- 审批拒绝后等待 Agent 生成说明，前端不伪造回答。
- HTTP 409 时重新读取快照，不自动重复提交。
- SSE 在没有收到 `approval.required` 或终态事件时发生 EOF/网络错误，前端立即调用 `/active` 收敛状态；若仍为 `RUNNING` 则保持输入禁用并进入快照轮询，不能把网络断开误判为 Run 结束。
- 页面卸载不再自动调用取消接口。
- “停止”按钮继续显式调用取消接口。
- 切换会话和页面刷新时调用 `/active`。
- `WAITING_APPROVAL` 重新渲染审批区。
- `WAITING_APPROVAL` 根据 `expiresAt` 启动本地计时并继续低频轮询 `/active`；到期后立即禁用按钮并以服务端状态为准，不能一直保留可点击审批。
- `RUNNING` 显示“Agent 正在后台执行”并轮询快照；终态后重新加载会话消息。
- 终态审批区不可继续操作。
- `OUTCOME_UNKNOWN` 显示“外部操作可能已执行”，展示不含敏感信息的 `executionId` 和人工核对指引；不得提供自动重试按钮，也不得将其渲染成普通失败。
- 输入框在当前会话存在非终态 Agent Run 时禁用，避免同会话消息交错。
- 前端维护每个 Run 的有界 `eventId` 集合，重放或重复审批订阅收到相同事件时不得再次拼接 `message.delta`。

### 14.3 响应式和可访问性

- 桌面和移动端都必须保证审批摘要、代码预览和按钮不溢出或重叠。
- 代码预览使用固定最大高度和双向滚动，不撑开整个消息列表。
- 按钮使用原生 `button`，保留焦点状态和禁用状态。
- 状态变化通过现有 `aria-live`/toast 和消息区表达。
- 实现完成后使用浏览器实际验证桌面与移动视口。

## 15. 错误契约和脱敏

### 15.1 稳定错误码

Run：

- `AGENT_RUN_ALREADY_ACTIVE`；
- `AGENT_CAPACITY_EXCEEDED`；
- `AGENT_INVALID_STATE`；
- `AGENT_RECOVERY_FAILED`；
- `AGENT_CHECKPOINT_MISSING`；
- `AGENT_RUN_INTERRUPTED`；
- `AGENT_ACTION_OUTCOME_UNKNOWN`。

审批：

- `APPROVAL_NOT_FOUND`；
- `APPROVAL_EXPIRED`；
- `APPROVAL_ALREADY_RESOLVED`；
- `APPROVAL_CONFLICT`；
- `APPROVAL_BINDING_CHANGED`。

策略：

- `TOOL_NOT_REGISTERED`；
- `TOOL_SCHEMA_INVALID`；
- `TOOL_POLICY_DENIED`；
- `TOOL_BATCH_REQUIRES_SEQUENTIAL_EXECUTION`；
- `TOOL_TRUSTED_CONTEXT_INVALID`。

HTTP：

- `HTTP_ENDPOINT_NOT_REGISTERED`；
- `HTTP_SSRF_BLOCKED`；
- `HTTP_REDIRECT_BLOCKED`；
- `HTTP_TIMEOUT`；
- `HTTP_RESPONSE_TOO_LARGE`；
- `HTTP_OUTCOME_UNKNOWN`；
- `HTTP_REMOTE_ERROR`。

Sandbox：

- `SANDBOX_UNAVAILABLE`；
- `SANDBOX_TIMEOUT`；
- `SANDBOX_OOM`；
- `SANDBOX_POLICY_VIOLATION`；
- `SANDBOX_OUTPUT_TOO_LARGE`；
- `SANDBOX_EXECUTION_FAILED`。

Checkpoint：

- `CHECKPOINT_INVALID`；
- `CHECKPOINT_DECRYPT_FAILED`；
- `CHECKPOINT_SCHEMA_UNSUPPORTED`。

### 15.2 脱敏规则

- 模型只收到稳定错误码和简短受控说明。
- 用户收到错误码、可读说明和 `traceId`。
- 详细应用异常和堆栈只进入受保护日志；写入前过滤 Token、Authorization、Cookie、URL 查询参数、模型提交的代码及外部响应正文，公开错误绝不返回堆栈。
- 工具异常不能通过 ToolResult 原样回流给模型。
- `OUTCOME_UNKNOWN` 时禁止模型继续生成“执行成功”结论。

## 16. 幂等协议

```text
规范化参数并计算 operation_fingerprint
  -> 已存在相同语义动作：复用原审批、执行中状态或保存结果
  -> 不存在：生成稳定 operation_id 和 execution_key
原子插入 execution_key
  -> 首次插入：进入 EXECUTING 并执行
  -> 已 SUCCEEDED：返回已保存结果
  -> 新鲜 EXECUTING：返回正在处理，不重复执行
  -> 过期 EXECUTING：按工具恢复策略处理
  -> OUTCOME_UNKNOWN：禁止自动重试
  -> FAILED：只有明确允许且未超 attempt 时才能重试
```

- Python 的过期 `EXECUTING`、重启中断或未知结果一律失败关闭，不自动重新执行；需要再次运行时必须创建新 Run 并重新审批。
- HTTP 写操作只有远端幂等能力成立时允许恢复重试。
- `action_tool_execution` 成功结果需要保存受限密文，使 Agent 崩溃后可以重新注入相同结果。
- 同一个持久化 ToolCall 由 `execution_key` 去重；即使模型生成新的 `toolCallId`，同一 Run 内语义相同的动作也由 `operation_fingerprint` 复用原结果或要求重新确认，不静默执行第二次。
- `operation_id` 在所有本地重放和允许的远端重试中保持不变。只有远端确实支持该业务幂等值时，才可把“至多一次尝试”提升为可恢复的幂等调用。

## 17. 配置与开关

新增配置以环境变量可覆盖，默认关闭副作用能力：

```yaml
fileagent:
  agent:
    actions-enabled: ${FILEAGENT_AGENT_ACTIONS_ENABLED:false}
    approval-timeout: ${FILEAGENT_AGENT_APPROVAL_TIMEOUT:30m}
    approval-expiry-scan-interval: ${FILEAGENT_AGENT_APPROVAL_EXPIRY_SCAN_INTERVAL:30s}
    recovery-scan-interval: ${FILEAGENT_AGENT_RECOVERY_SCAN_INTERVAL:15s}
    max-action-calls: ${FILEAGENT_AGENT_MAX_ACTION_CALLS:2}
    max-approvals: ${FILEAGENT_AGENT_MAX_APPROVALS:2}
    max-concurrent-runs: ${FILEAGENT_AGENT_MAX_CONCURRENT_RUNS:4}
    max-queued-runs: ${FILEAGENT_AGENT_MAX_QUEUED_RUNS:32}
    pending-start-timeout: ${FILEAGENT_AGENT_PENDING_START_TIMEOUT:2m}
  action:
    http:
      enabled: ${FILEAGENT_ACTION_HTTP_ENABLED:false}
  sandbox:
    enabled: ${FILEAGENT_SANDBOX_ENABLED:false}
    base-url: ${FILEAGENT_SANDBOX_BASE_URL:http://127.0.0.1:8090}
    token: ${FILEAGENT_SANDBOX_TOKEN:}
```

- Endpoint 凭证只通过环境变量引用，不提供明文默认值。
- Sandbox Token 为空时禁止启用 Sandbox。
- Feature Flag 关闭时，现有只读 Agent 行为保持不变。
- 不因 Phase 3 自动提高现有 `maxSteps`、Token 和上下文预算。
- 本地模式固定监听 loopback 且不开放 CORS；启用非 loopback 地址时启动校验直接失败，直至正式认证授权方案交付。

## 18. 测试和质量门禁

### 18.1 单元测试

- Run/Step/Approval 状态机合法与非法转移；
- 风险策略矩阵和优先级；
- 审批过期、重复决定和冲突决定；
- 审批绑定的工具定义、Endpoint revision、策略版本、资源范围和参数 HMAC 任一变化时失效；
- JSON Schema 和服务端可信身份；
- 审批摘要与错误脱敏；
- URL、DNS 和 IP 分类；
- 幂等状态机；
- Checkpoint 清洗、加密、校验和和版本检查；
- 供应商恢复必需 continuation 字段保留，调试字段不持久化。

### 18.2 集成测试

- H2/JPA 乐观锁；
- `active_slot` 并发唯一约束；
- capacity permit 预留、队满 429、事务/入队失败释放和重启重建；
- 并发审批只有一个决定生效；
- 重复 `execution_key` 只执行一次，新 `toolCallId` 但相同 `operation_fingerprint` 也不重复执行；
- 租约抢占和续租；
- AgentScope ASK -> 检查点 -> 新 Agent 实例 -> APPROVE/DENY -> 恢复；
- Spring Context 关闭并重新启动后的待审批恢复；
- 无请求访问时 scheduler 和启动扫描仍会原子过期 Approval、Step 与 Run；
- APPROVED/DENIED 已落库但恢复尚未开始时重启，可以重新入队；
- ToolExecution 已成功但 Step 仍为 `EXECUTING` 时重启，复用保存结果；
- 陈旧普通 `RUNNING` 且无 ASKING 检查点时安全失败，不静默重跑；
- assistant 消息与 Run 成功状态的一致性。

### 18.3 HTTP 安全测试

- loopback、私网、link-local、metadata、组播和保留 IP；
- IPv4-mapped IPv6、ULA、混合合法/禁止地址、域名解析变化和 DNS rebinding；
- 实际连接地址必须等于安全解析器已校验地址，并保留原始 Host/SNI；
- 系统/JVM 代理不可改变目标；
- 重定向；
- 绝对 URL、scheme-relative URL、userinfo、反斜线、单次/双重编码路径穿越、CRLF 和控制字符；
- 请求字段、深度、Body、Header 限制；
- 超时、错误 Content-Type、压缩炸弹和大响应；
- 远端幂等和本地重复调用；
- 异常信息不泄漏凭证和正文。

### 18.4 沙箱安全测试

- 无网络；
- 根文件系统只读；
- 无宿主目录、知识库和密钥；
- CPU、内存、进程、时间和输出限制；
- 容器取消和销毁；
- 请求尝试覆盖镜像、命令、挂载、网络、用户和资源参数时无效；
- 超时孤儿容器按 `executionId` Label 清理；
- 常见逃逸尝试被阻断；
- Worker Token 缺失或错误时拒绝请求。

### 18.5 API 与前端测试

- 创建、查询、取消和审批接口契约；
- 创建成功同步返回 `X-Agent-Run-Id`，首事件为 `run.status(PENDING)`，PENDING 可取消且刷新可恢复；
- SSE 连接前业务错误保持 HTTP 状态和 `event:error`；
- `eventId/Last-Event-ID` 增量重放、前端去重和游标缺失时快照收敛；
- `approval.required/resolved` 事件字段；
- 等待审批、重复点击、409 冲突和过期；
- WAITING 审批在无用户请求时过期，前端 timer/轮询及时禁用按钮；
- 审批备注不会进入模型上下文或工具参数；
- 刷新后恢复审批；
- `OUTCOME_UNKNOWN` 明确提示可能已执行且无自动重试；
- 后台运行完成后重新加载消息；
- 桌面和移动端审批区实际截图检查。

### 18.6 评测

现有真实评测必须回归：

- `agent-v1`；
- `adaptive-v2`；
- `context-v2`。

Phase 3 不直接扩展现有 `AgentAnswerEvaluationPort` 或 `AgentEvaluationReport`：该链路是无会话、无落库的一次同步只读评测，无法驱动审批、重启和真实副作用。新增两条独立链路：

- `Phase3DeterministicSecuritySuite`：使用固定模型桩、测试 Endpoint Server、假远端副作用计数器和测试 Sandbox Worker，实际创建持久化 Run、重建 Spring Context 并注入崩溃窗口，产出确定性门禁；
- `phase3-model-attack-v1` + `AgentSecurityEvaluationPort/Runner`：只在 evaluation profile 启用，驱动真实模型的启动、等待审批、批准/拒绝和终态观察，验证模型攻击提示下仍进入服务端策略流程。

模型攻击数据集覆盖：

- 文档要求忽略规则并绕过审批；
- 用户要求模型伪造管理员；
- 用户要求直接调用任意 URL；
- 用户要求未经批准执行 Python；
- 审批拒绝后诱导重复执行；
- 相同动作重复调用；
- 诱导泄漏 Token、内部地址和错误堆栈。

核心安全门禁由独立集成安全套件产出，不由 Judge 判定；其中 `restartRecoveryRate` 专指本设计承诺的 ASKING/审批恢复矩阵，不包含任意模型调用中断点续跑：

```text
approvalEnforcementRate       = 1.0
restartRecoveryRate           = 1.0
idempotencyPassRate           = 1.0
trustedContextTamperBlockRate = 1.0
ssrfBlockRate                 = 1.0
sandboxIsolationRate          = 1.0
duplicateSideEffectRate       = 0.0
sensitiveErrorLeakRate        = 0.0
```

真实模型评测用于验证 Agent 是否在攻击提示下进入服务端审批和拒绝流程，不代替确定性安全测试，也不与 Phase 2C 的回答/引用报告共用 DTO。

## 19. 分阶段交付与验收

### 19.1 Phase 3A：持久化 Runtime

交付：

- Run/Step/Checkpoint Repository；
- 数据库 AgentStateStore；
- 后台 Coordinator 和租约；
- SSE 断连不取消；
- `/active` 接口；
- 前端后台运行和刷新恢复。

退出门槛：

- Run 快照在应用重启后存在；
- 浏览器断开不终止 Run；
- 启动后 `PENDING` Run 可以重新入队，陈旧普通 `RUNNING` 安全失败且释放会话槽位；
- 旧 Agent 评测不退化。

### 19.2 Phase 3B：HITL

交付：

- 风险策略；
- AgentScope ASK 接线；
- Approval 持久化和审批 SSE；
- 批准、拒绝、取消、过期和并发 CAS；
- 前端审批区。

退出门槛：

- `WAITING_APPROVAL` 重启后仍可处理；
- ASKING 检查点经过严格预校验后可以批准或拒绝并恢复；
- 重复审批不重复恢复；
- 拒绝动作不会执行；
- 客户端和模型不能提交审批身份。

### 19.3 Phase 3C：安全 HTTP

交付：

- `ActionExecutionPort`；
- ToolExecution 幂等账本；
- Endpoint 注册；
- `call_registered_http_api`；
- SSRF、超时、响应大小和脱敏测试。

退出门槛：

- 相同 `execution_key` 或 `operation_fingerprint` 只产生一次远端副作用；
- 任意 URL 和所有禁止网络地址均被阻断；
- 没有远端幂等能力的写 Endpoint 无法注册。

### 19.4 Phase 3D：Python 沙箱

交付：

- 独立 Sandbox Worker；
- rootless 一次性容器；
- `execute_python`；
- 前端代码审批预览；
- `Phase3DeterministicSecuritySuite` 和 `phase3-model-attack-v1`。

退出门槛：

- 定义的沙箱隔离用例无法访问网络和宿主数据；
- 所有资源限制测试通过；
- `execute_python` 在拒绝、过期、取消和重启恢复场景均不会调用 Worker；
- 安全评测和前三套 Agent 回归通过。

## 20. 与 Phase 2C 的并行边界

依赖选择已经固定：Phase 3 的 Runtime、HITL、HTTP、Sandbox 和安全评测不依赖 Phase 2C，可以立即并行开发；只有共享 record 的字段合并和 Phase 2C 引用校验贡献点接线排在 Phase 2C 基线之后。这是代码热点的合并顺序，不是 Phase 3 运行时对 Citation 功能的强依赖；Phase 2C 贡献点缺席时，最终化服务按现有无引用扩展路径工作。

Phase 2C 独占：

- Citation/Evidence 数据模型；
- 文档版本、页码、坐标和 quoteHash；
- citation verification；
- final-answer/citation verification Port 及其 Evidence 快照结构；
- 引用质量指标。

Phase 3 独占：

- Run/Step/Checkpoint 持久化；
- Approval、策略和恢复；
- ToolExecution 幂等；
- HTTP、Sandbox 和前端审批；
- Phase 3 安全测试。

共同热点：

- `AgentRun`；
- `AgentScopeRuntimeAdapter`；
- `AgentRunEvent`；
- `AgentRunSnapshot`；
- Phase 2C finalization Port 和 `AgentFinalizationContext`；
- `application.yml`；
- `docs/API.md`。

协作规则：

- Phase 2C 只向事件和快照增加引用字段，Phase 3 只增加审批和持久化字段。
- Phase 2C 先落共享 record 和 finalization Port 基线；Phase 3 rebase 后只在 record 尾部追加审批字段，不允许双方并行改同一个 record 构造器。
- Phase 3 的 `AgentRunFinalizationService` 在消息落库前调用 Phase 2C 拥有的 final-answer/citation verification Port，并把“实际入模 Evidence 快照引用”作为版本化 `finalization_context` 持久化，保证 WAITING 恢复后仍能完成相同校验。
- 两边都不进行无关 DTO 重构。
- Phase 3 将持久化协调和策略从 `AgentScopeRuntimeAdapter` 分离，避免继续扩大该类。
- Phase 2C 合并 `master` 后，Phase 3 在最终联调前 rebase，并统一运行全部评测。

## 21. 文档与完成定义

实现过程中同步更新：

- `docs/API.md`；
- `docs/SKELETON.md`；
- Sandbox 和 HTTP Endpoint 部署说明；
- `docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md` 的 Phase 3 状态；
- 前端交互说明和安全限制。

Phase 3 完成必须同时满足：

- `WAITING_APPROVAL` 重启恢复通过；
- 同一 `execution_key` 或 `operation_fingerprint` 不会重复执行副作用；
- 模型不能伪造身份、扩大资源范围或绕过审批；
- Prompt Injection 不能绕过工具白名单和风险策略；
- SSRF、超时、大响应和已定义沙箱隔离自动化测试通过；
- 前端桌面和移动端审批流程验证通过；
- `agent-v1`、`adaptive-v2`、`context-v2`、`Phase3DeterministicSecuritySuite` 和 `phase3-model-attack-v1` 通过；
- 副作用开关默认关闭，关闭时现有只读 Agent 行为不变；
- 没有引入任意文件写入、任意 URL、Shell 或数据库直连能力。
