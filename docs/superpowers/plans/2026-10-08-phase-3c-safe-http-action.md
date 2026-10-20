# Phase 3C 安全注册式 HTTP 工具实现计划

> **执行约束：** 得到用户对本阶段的明确确认后，按任务顺序在当前会话实现；除非用户另行批准，不创建 worktree 或子代理。步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 提供只能调用服务端预注册 Endpoint、默认必须审批、具备本地/远端幂等和 SSRF 防护的 `call_registered_http_api` 工具。

**架构：** `fileagent-api` 定义一个具有解析定义、执行、查状态、取消四项能力的 `ActionExecutionPort`；`fileagent-action` 拥有 Endpoint 配置、HTTP 客户端和执行账本；`fileagent-agent` 每个 Run 创建一个 `RunScopedActionTool`，模型只能提交 endpointId 和结构化 arguments。OkHttp 的自定义 DNS 在实际连接使用经过校验的 IP，同时保留原 hostname 的 Host/SNI。

**技术栈：** Java 21、Spring Boot 4.1、Spring Data JPA、OkHttp 4.12.0、networknt JSON Schema Validator 2.0.0、H2、AgentScope ToolBase。

---

## 前置条件

- Phase 3A、3B 全部退出门槛通过。
- 本阶段不提供动态 Endpoint 管理接口；Endpoint 只来自受信任配置。
- 测试 Endpoint 的 loopback 放行只在测试构造器中启用，生产配置无法开启该后门。

## 文件结构

**创建：**

- `fileagent-api/src/main/java/com/demetrius/fileagent/api/port/ActionExecutionPort.java`。
- `fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/action/*`：定义、执行命令、执行视图、取消结果。
- `fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/ActionExecutionStatus.java`、`ActionCancelStatus.java`。
- `fileagent-action/src/main/java/com/demetrius/fileagent/action/domain/ActionToolExecutionEntity.java`、`ActionToolExecutionRepository.java`。
- `fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/persistence/ActionToolExecutionJpaRepository.java`。
- `fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/config/RegisteredHttpActionProperties.java`。
- `fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/RegisteredHttpActionRegistry.java`。
- `fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/CanonicalActionArguments.java`。
- `fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/SafeHttpUriBuilder.java`。
- `fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/BlockedAddressPolicy.java`。
- `fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/ValidatedDns.java`。
- `fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/RegisteredHttpExecutor.java`。
- `fileagent-action/src/main/java/com/demetrius/fileagent/action/application/ActionExecutionService.java`：Port 实现。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/CallRegisteredHttpApiTool.java`。
- 对应 API 契约、JPA、SSRF、幂等、工具测试。

**修改：**

- 根 `pom.xml`：统一 OkHttp 和 JSON Schema Validator 版本。
- `fileagent-action/pom.xml`：JPA、OkHttp、JSON Schema Validator。
- `AgentScopeRuntimeAdapter.java`：actions-enabled 时按 Run 注册 HTTP 工具。
- `AgentPromptFactory.java`：只描述注册工具及一次一个副作用约束。
- `AgentProperties.java`、`application.yml`：动作/HTTP 开关和 Endpoint 定义。
- `app.js`：HTTP 工具名称和 OUTCOME_UNKNOWN 展示。
- 文档和测试。

### 任务 1：固定跨域 ActionExecutionPort 契约

**文件：**
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/port/ActionExecutionPort.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/action/ActionDefinitionView.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/action/ActionExecutionCommand.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/action/ActionExecutionView.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/action/ActionCancelResult.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/ActionExecutionStatus.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/ActionCancelStatus.java`
- 测试：`fileagent-api/src/test/java/com/demetrius/fileagent/api/port/ActionExecutionPortContractTest.java`

- [x] **步骤 1：写 DTO 防御性复制和字段契约测试**

断言 credentials、baseUrl、内部 Header 不存在于 `ActionDefinitionView`；arguments 和 resource scope 构造时防御性复制；取消结果仅允许 NOT_STARTED/CANCELLED/OUTCOME_UNKNOWN。

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-api -am test -Dtest=ActionExecutionPortContractTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，新 Port 不存在。

- [x] **步骤 3：实现精确 Port**

```java
/**
 * 动作执行跨域契约：只暴露定义快照、受控执行状态和取消语义。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-08
 */
public interface ActionExecutionPort {
    ActionDefinitionView resolveDefinition(String actionId);
    ActionExecutionView execute(ActionExecutionCommand command);
    ActionExecutionView findExecution(String executionId);
    ActionCancelResult cancel(String executionId);
}
```

`ActionExecutionCommand` 固定字段：runId、toolCallId、toolName、toolVersion、actionId、definitionVersion、endpointRevision、endpointConfigHash、policyVersion、executionKey、operationFingerprint、operationId、canonicalArguments、canonicalInputHmac、actorId、authorizedResourceScopes、resourceScope。模型不能构造该 record。

- [x] **步骤 4：运行测试并提交**

运行：`mvn -pl fileagent-api -am test -Dtest=ActionExecutionPortContractTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-api/src/main/java/com/demetrius/fileagent/api/port/ActionExecutionPort.java fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/action fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/ActionExecutionStatus.java fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/ActionCancelStatus.java fileagent-api/src/test/java/com/demetrius/fileagent/api/port/ActionExecutionPortContractTest.java
git commit -m "feat(api): 定义动作执行契约"
```

### 任务 2：实现副作用幂等账本

**文件：**
- 创建：`fileagent-action/src/main/java/com/demetrius/fileagent/action/domain/ActionToolExecutionEntity.java`
- 创建：`fileagent-action/src/main/java/com/demetrius/fileagent/action/domain/ActionToolExecutionRepository.java`
- 创建：`fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/persistence/ActionToolExecutionJpaRepository.java`
- 修改：`fileagent-action/pom.xml`
- 测试：`fileagent-action/src/test/java/com/demetrius/fileagent/action/infrastructure/persistence/ActionToolExecutionJpaTest.java`

- [x] **步骤 1：写三标识约束和 CAS 测试**

断言 `executionKey` 唯一、`operationId` 唯一、`UNIQUE(run_id, operation_fingerprint)`；并发 begin 只有一个进入 EXECUTING；OUTCOME_UNKNOWN 不允许自动重试。

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-action -am test -Dtest=ActionToolExecutionJpaTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，账本实体不存在。

- [x] **步骤 3：实现账本实体**

```java
@Entity
@Table(name = "action_tool_execution", uniqueConstraints = {
        @UniqueConstraint(name = "uk_action_execution_key", columnNames = "execution_key"),
        @UniqueConstraint(name = "uk_action_operation_id", columnNames = "operation_id"),
        @UniqueConstraint(name = "uk_action_fingerprint", columnNames = {"run_id", "operation_fingerprint"})
})
public class ActionToolExecutionEntity {
    @Id
    private String executionId;
    private String executionKey;
    private String operationFingerprint;
    private String operationId;
    @Enumerated(EnumType.STRING)
    private ActionExecutionStatus status;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String resultCiphertext;
    @Version
    private long version;
}
```

其余字段按设计稿完整实现；请求和结果哈希使用 HMAC，结果正文只存信封密文。

- [x] **步骤 4：运行测试并提交**

运行：`mvn -pl fileagent-action -am test -Dtest=ActionToolExecutionJpaTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-action/pom.xml fileagent-action/src/main/java/com/demetrius/fileagent/action/domain fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/persistence fileagent-action/src/test/java/com/demetrius/fileagent/action/infrastructure/persistence
git commit -m "feat(action): 增加副作用幂等账本"
```

### 任务 3：实现 Endpoint 注册、Schema 和规范化参数

**文件：**
- 修改：`pom.xml`
- 修改：`fileagent-action/pom.xml`
- 创建：`fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/config/RegisteredHttpActionProperties.java`
- 创建：`fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/RegisteredHttpActionRegistry.java`
- 创建：`fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/CanonicalActionArguments.java`
- 测试：`fileagent-action/src/test/java/com/demetrius/fileagent/action/infrastructure/http/RegisteredHttpActionRegistryTest.java`

- [x] **步骤 1：写启动校验测试**

覆盖重复 endpointId、非 HTTPS 凭证 Endpoint、写操作缺失 idempotencyHeader、Schema 深度/字段/Body 超限、缺失 dataEgressPolicy、配置哈希不稳定。

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-action -am test -Dtest=RegisteredHttpActionRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL。

- [x] **步骤 3：加入依赖并实现注册表**

根 POM 固定：

```xml
<okhttp.version>4.12.0</okhttp.version>
<json-schema-validator.version>2.0.0</json-schema-validator.version>
```

注册定义包含 endpointId/displayName/definitionVersion/endpointRevision/configHash/policyVersion/method/baseUrl/pathTemplate/requestSchema/riskLevel/dataEgressPolicy/credentialRef/timeouts/maxResponseBytes/contentTypes/idempotencyHeader。`configHash` 排除凭证值，只覆盖目标、请求和权限边界。

`CanonicalActionArguments` 使用 Jackson 递归排序 object key，数组保持顺序，拒绝 NaN/Infinity，输出稳定 canonical JSON 和 HMAC。

- [x] **步骤 4：运行测试并提交**

运行：`mvn -pl fileagent-action -am test -Dtest=RegisteredHttpActionRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add pom.xml fileagent-action/pom.xml fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/config fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/RegisteredHttpActionRegistry.java fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/CanonicalActionArguments.java fileagent-action/src/test/java/com/demetrius/fileagent/action/infrastructure/http/RegisteredHttpActionRegistryTest.java
git commit -m "feat(action): 注册受控 HTTP 动作"
```

### 任务 4：实现 URI 构建和 SSRF 地址策略

**文件：**
- 创建：`fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/SafeHttpUriBuilder.java`
- 创建：`fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/BlockedAddressPolicy.java`
- 创建：`fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/ValidatedDns.java`
- 测试：`fileagent-action/src/test/java/com/demetrius/fileagent/action/infrastructure/http/SafeHttpUriBuilderTest.java`
- 测试：`fileagent-action/src/test/java/com/demetrius/fileagent/action/infrastructure/http/ValidatedDnsTest.java`

- [x] **步骤 1：写攻击向量测试**

覆盖绝对 URL、`//host`、userinfo、反斜线、CRLF、单/双编码 `..`；IPv4/IPv6 loopback、RFC1918、link-local、metadata、组播、保留、ULA、IPv4-mapped IPv6；混合合法/禁止解析结果必须整体拒绝；每次 lookup 重新校验。

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-action -am test -Dtest=SafeHttpUriBuilderTest,ValidatedDnsTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL。

- [x] **步骤 3：实现结构化 URI Builder**

只能把 Schema 声明的 path/query/body 字段编码到注册模板；先单次 decode 检查，再拒绝结果仍含 `%2e/%2f/%5c/%0d/%0a` 的双重编码信号。禁止字符串拼 URL。

- [x] **步骤 4：实现 OkHttp DNS 固定**

```java
final class ValidatedDns implements okhttp3.Dns {
    private final Dns delegate;
    private final BlockedAddressPolicy policy;

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        List<InetAddress> addresses = delegate.lookup(hostname);
        if (addresses.isEmpty() || addresses.stream().anyMatch(policy::isBlocked)) {
            throw new UnknownHostException("HTTP_SSRF_BLOCKED");
        }
        return List.copyOf(addresses);
    }
}
```

OkHttp 使用原 hostname 建 TLS/SNI，并直接连接本次 `lookup` 返回地址。Client 固定 `Proxy.NO_PROXY`、`followRedirects(false)`、`followSslRedirects(false)`、`retryOnConnectionFailure(false)`。

- [x] **步骤 5：运行测试并提交**

运行：`mvn -pl fileagent-action -am test -Dtest=SafeHttpUriBuilderTest,ValidatedDnsTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http fileagent-action/src/test/java/com/demetrius/fileagent/action/infrastructure/http
git commit -m "feat(action): 阻断 HTTP SSRF 与路径注入"
```

### 任务 5：实现受限 HTTP 执行与未知结果语义

**文件：**
- 创建：`fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/RegisteredHttpExecutor.java`
- 创建：`fileagent-action/src/main/java/com/demetrius/fileagent/action/application/ActionExecutionService.java`
- 测试：`fileagent-action/src/test/java/com/demetrius/fileagent/action/infrastructure/http/RegisteredHttpExecutorTest.java`
- 测试：`fileagent-action/src/test/java/com/demetrius/fileagent/action/application/ActionExecutionServiceTest.java`

- [x] **步骤 1：写本地测试 Server 和失败窗口测试**

使用 `com.sun.net.httpserver.HttpServer` 与测试专用 loopback policy，覆盖 operationId Header、压缩后响应超 256 KB、Header 超限、错误 Content-Type、3xx、连接/读取/总超时、远端成功但本地记账前故障 -> OUTCOME_UNKNOWN、相同 fingerprint 只计数一次。

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-action -am test -Dtest=RegisteredHttpExecutorTest,ActionExecutionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL。

- [x] **步骤 3：实现流式读取和幂等**

响应经 OkHttp 解压后从 `ResponseBody.byteStream()` 累计读取，超过 Endpoint `maxResponseBytes` 立即关闭；不调用无界 `string()`。写 Endpoint 把稳定 `operationId` 放入注册的幂等 Header。日志只记录 endpointId、executionId、状态、耗时和字节数。

`ActionExecutionService.execute` 先原子创建/读取账本，再调用 Executor；SUCCEEDED 返回密文结果摘要；新鲜 EXECUTING 返回处理中；OUTCOME_UNKNOWN 永不自动重试。

- [x] **步骤 4：实现取消语义**

请求未发送返回 NOT_STARTED；OkHttp Call 成功取消且确认未写出返回 CANCELLED；可能已经送达返回 OUTCOME_UNKNOWN。不得把最后一种映射成普通取消。

- [x] **步骤 5：运行测试并提交**

运行：`mvn -pl fileagent-action -am test -Dtest=RegisteredHttpExecutorTest,ActionExecutionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-action/src/main/java/com/demetrius/fileagent/action/application/ActionExecutionService.java fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/http/RegisteredHttpExecutor.java fileagent-action/src/test/java/com/demetrius/fileagent/action
git commit -m "feat(action): 执行幂等注册 HTTP 动作"
```

### 任务 6：接入 `call_registered_http_api`

**文件：**
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/CallRegisteredHttpApiTool.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/prompt/AgentPromptFactory.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AgentProperties.java`
- 修改：`fileagent-starter/src/main/resources/application.yml`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/CallRegisteredHttpApiToolTest.java`

- [x] **步骤 1：写工具输入和审批测试**

模型 Schema 只能包含 endpointId 和 arguments；URL/method/header/credential/actorId 字段被拒绝；定义版本和 HMAC 在批准后变化时不执行；同语义新 toolCallId 复用原 operation。

- [x] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-agent -am test -Dtest=CallRegisteredHttpApiToolTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL。

- [x] **步骤 3：实现按 Run 工具**

工具继承 `RunScopedActionTool`；构造时固定 runId/actorId/授权资源。`buildCandidate` 调用 `resolveDefinition`、Schema 校验、canonical JSON 和策略；`executeVerified` 组装 `ActionExecutionCommand` 并调用 Port。

只有 `fileagent.agent.actions-enabled=true` 且 `fileagent.action.http.enabled=true` 时注册；默认 false。

- [x] **步骤 4：运行测试并提交**

运行：`mvn -pl fileagent-agent -am test -Dtest=CallRegisteredHttpApiToolTest,AgentScopeRuntimeAdapterTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/CallRegisteredHttpApiTool.java fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/prompt/AgentPromptFactory.java fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/config/AgentProperties.java fileagent-starter/src/main/resources/application.yml fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/CallRegisteredHttpApiToolTest.java
git commit -m "feat(agent): 接入注册式 HTTP 工具"
```

### 任务 7：前端、文档和 Phase 3C 验收

**文件：**
- 修改：`fileagent-starter/src/main/resources/static/app.js`
- 修改：`fileagent-starter/src/main/resources/static/app.css`
- 修改：`fileagent-starter/src/test/java/com/demetrius/fileagent/StaticPageContractTest.java`
- 修改：`docs/API.md`
- 修改：`docs/SKELETON.md`
- 修改：`docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md`
- 创建：`docs/HTTP-ACTION-CONFIG.md`

- [x] **步骤 1：补前端契约**

HTTP 审批展示 Endpoint displayName、动作、资源和非敏感参数；OUTCOME_UNKNOWN 显示“外部操作可能已执行”、executionId、人工核对指引，且无自动重试按钮。

- [x] **步骤 2：运行模块测试**

运行：`mvn -pl fileagent-api,fileagent-action,fileagent-agent,fileagent-starter -am test`

预期：PASS。

- [x] **步骤 3：写部署文档**

文档给出一个默认关闭的测试 GET Endpoint 和一个带远端幂等 Header 的写 Endpoint 配置；凭证只引用环境变量，不写真实 Token；明确生产只允许 HTTPS 和公开地址。

- [x] **步骤 4a：运行全量测试**

运行：`mvn test`

预期：PASS。

- [ ] **步骤 4b：在已配置模型和知识库的部署实例上运行既有 Agent 回归**

部署后运行：

```bash
FILEAGENT_EVALUATION_DATASET_VERSION=agent-v1 ./fileagent-evaluation/scripts/run-agent-evaluation.sh
FILEAGENT_EVALUATION_DATASET_VERSION=adaptive-v2 ./fileagent-evaluation/scripts/run-agent-evaluation.sh
FILEAGENT_EVALUATION_DATASET_VERSION=context-v2 ./fileagent-evaluation/scripts/run-agent-evaluation.sh
```

预期：三套门禁通过。

- [x] **步骤 5：提交 Phase 3C**

```bash
git diff --check
git add fileagent-starter/src/main/resources/static/app.js fileagent-starter/src/main/resources/static/app.css fileagent-starter/src/test/java/com/demetrius/fileagent/StaticPageContractTest.java docs/API.md docs/SKELETON.md docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md docs/HTTP-ACTION-CONFIG.md
git commit -m "docs(action): 完成 Phase 3C 验收"
```

## Phase 3C 退出门槛

- 模型和客户端不能提交 URL、Method、Header、凭证或身份。
- executionKey 和 operationFingerprint 任一重复都不会产生第二次副作用。
- 实际连接地址经过 DNS 校验并阻断全部禁止地址、代理、重定向和路径注入。
- 写 Endpoint 缺少远端幂等能力时启动失败。
- OUTCOME_UNKNOWN 不自动重试，模型和前端都不能声称执行成功。
