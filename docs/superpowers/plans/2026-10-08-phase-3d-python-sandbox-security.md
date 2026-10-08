# Phase 3D Python 沙箱与安全评测实现计划

> **执行约束：** 得到用户对本阶段的明确确认后，按任务顺序在当前会话实现；除非用户另行批准，不创建 worktree 或子代理。步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 通过独立 loopback Worker 和 rootless Podman 一次性容器提供受审批的 `execute_python`，并建立独立于回答 Judge 的 Phase 3 确定性安全门禁与真实模型攻击评测。

**架构：** 主应用只向 Worker 发送 executionId/code/inputJson；Worker 固定镜像、命令、网络、挂载、用户和资源限制，调用 rootless Podman。`execute_python` 复用 3C 的 ActionExecutionPort/幂等账本和 3B 的审批。确定性安全用 Maven profile + 固定模型桩/测试服务器/测试 Worker，真实模型攻击使用独立 `AgentSecurityEvaluationPort/Runner`。

**技术栈：** Java 21、Spring Boot 4.1、rootless Podman、Python 3 固定镜像摘要、Reactor、JUnit 5、Maven。

---

## 前置条件

- Phase 3A、3B、3C 全部退出门槛通过。
- 本机真实沙箱门禁需要 rootless Podman；`podman info --format '{{.Host.Security.Rootless}}'` 必须输出 `true`。
- Worker 只绑定 `127.0.0.1` 或 Unix Domain Socket；本阶段不接受跨主机 base URL。

## 文件结构

**创建：**

- Maven 模块 `fileagent-sandbox-worker/`，包含独立 Boot 入口、Controller、Token 校验、Properties、Runtime Port 和 Podman 实现。
- `fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/sandbox/SandboxExecutionRequest.java`、`SandboxExecutionResponse.java`：内部稳定 DTO。
- `fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/sandbox/SandboxClient.java`：主应用到 Worker 客户端。
- `fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/sandbox/PythonSandboxActionDefinition.java`：固定动作定义。
- `fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/ExecutePythonTool.java`：按 Run 的审批工具。
- `deploy/sandbox/Dockerfile`、`deploy/sandbox/runner.py`：固定容器入口。
- Phase 3 安全 Maven profile、确定性测试套件、真实模型攻击数据集/Runner/报告/脚本。
- `docs/SANDBOX.md`。

**修改：**

- 根 `pom.xml`：新增 worker 模块和 `phase3-security` profile。
- `fileagent-action/pom.xml`、`fileagent-agent` 装配、`application.yml`。
- `app.js`、`app.css`：Python 审批预览和未知结果展示。
- `fileagent-evaluation` 的 POM、README 和内部评测 Controller。
- API/Skeleton/Roadmap。

### 任务 1：创建独立 Sandbox Worker 和固定请求契约

**文件：**
- 修改：`pom.xml`
- 创建：`fileagent-sandbox-worker/pom.xml`
- 创建：`fileagent-sandbox-worker/src/main/java/com/demetrius/fileagent/sandbox/FileAgentSandboxWorkerApplication.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/sandbox/SandboxExecutionRequest.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/sandbox/SandboxExecutionResponse.java`
- 创建：`fileagent-sandbox-worker/src/main/java/com/demetrius/fileagent/sandbox/interfaces/SandboxExecutionController.java`
- 创建：`fileagent-sandbox-worker/src/main/java/com/demetrius/fileagent/sandbox/infrastructure/config/SandboxWorkerProperties.java`
- 测试：`fileagent-sandbox-worker/src/test/java/com/demetrius/fileagent/sandbox/interfaces/SandboxExecutionControllerTest.java`

- [ ] **步骤 1：写请求面和认证测试**

断言 JSON 只接受 executionId/code/inputJson；出现 image/command/mount/network/user/capabilities/resources/runtimeFlags 时因未知字段失败；空 Token 启动失败；错误 Token 401；executionId 非 UUID、代码超过 20 KB、inputJson 超过 64 KB 返回受控错误。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-sandbox-worker -am test -Dtest=SandboxExecutionControllerTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，模块和类型不存在。

- [ ] **步骤 3：创建模块和 DTO**

```java
public record SandboxExecutionRequest(
        String executionId,
        String code,
        String inputJson) {
}
```

Jackson 为请求 DTO 开启 unknown property failure。Worker `application.yml` 固定 `server.address: 127.0.0.1` 和 `server.port: 8090`；启动校验 base address 不是 loopback 时失败。

- [ ] **步骤 4：实现 Token 过滤**

仅接受 `Authorization: Bearer <FILEAGENT_SANDBOX_TOKEN>`，使用常量时间字节比较；日志不得打印 Header 和请求代码。

- [ ] **步骤 5：运行测试并提交**

运行：`mvn -pl fileagent-sandbox-worker -am test -Dtest=SandboxExecutionControllerTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add pom.xml fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/sandbox fileagent-sandbox-worker
git commit -m "feat(sandbox): 创建独立 Python Worker"
```

### 任务 2：构建固定 Python 镜像和 Runner

**文件：**
- 创建：`deploy/sandbox/Dockerfile`
- 创建：`deploy/sandbox/runner.py`
- 创建：`fileagent-sandbox-worker/src/test/java/com/demetrius/fileagent/sandbox/RunnerContractTest.java`

- [ ] **步骤 1：写 Runner 输入输出契约测试**

用本机 Python 执行 runner，stdin 输入 `{"code":"print(input_data['x'])","input":{"x":3}}`，断言 stdout 只输出结构化 JSON；语法错误、运行异常和超过输出限制返回稳定 code，不输出 Python traceback。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-sandbox-worker -am test -Dtest=RunnerContractTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL，runner 文件不存在。

- [ ] **步骤 3：实现固定 runner**

runner 从 stdin 读取 JSON，把 input 暴露为只读变量 `input_data`，捕获 stdout/stderr，各自限制并输出：

```json
{"status":"SUCCEEDED","stdout":"3\n","stderr":"","errorCode":null}
```

不提供文件产物、网络辅助函数、动态依赖安装或 shell。

- [ ] **步骤 4：解析并固定镜像摘要**

先执行以下命令，取得本机拉取镜像的唯一摘要：

```bash
podman pull docker.io/library/python:3.13-slim
podman image inspect --format '{{.Digest}}' docker.io/library/python:3.13-slim
```

将第二条命令输出的 `sha256:` 摘要逐字写入 Dockerfile 的 `FROM docker.io/library/python@...`，复制 runner 到 `/opt/fileagent/runner.py`，创建 uid/gid 65532，ENTRYPOINT 固定为 `python3 -I -S /opt/fileagent/runner.py`。`RunnerContractTest` 额外断言 `FROM` 符合 `@sha256:[0-9a-f]{64}`，拒绝 tag 和 `latest`；部署文档与 Worker 默认配置使用同一个摘要。

- [ ] **步骤 5：运行测试并提交**

运行：`mvn -pl fileagent-sandbox-worker -am test -Dtest=RunnerContractTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add deploy/sandbox fileagent-sandbox-worker/src/test/java/com/demetrius/fileagent/sandbox/RunnerContractTest.java
git commit -m "feat(sandbox): 固定 Python 执行镜像"
```

### 任务 3：实现 rootless Podman 执行和孤儿清理

**文件：**
- 创建：`fileagent-sandbox-worker/src/main/java/com/demetrius/fileagent/sandbox/application/SandboxContainerRuntime.java`
- 创建：`fileagent-sandbox-worker/src/main/java/com/demetrius/fileagent/sandbox/infrastructure/runtime/PodmanSandboxContainerRuntime.java`
- 创建：`fileagent-sandbox-worker/src/main/java/com/demetrius/fileagent/sandbox/application/OrphanContainerCleanup.java`
- 测试：`fileagent-sandbox-worker/src/test/java/com/demetrius/fileagent/sandbox/infrastructure/runtime/PodmanCommandContractTest.java`
- 测试：`fileagent-sandbox-worker/src/test/java/com/demetrius/fileagent/sandbox/application/OrphanContainerCleanupTest.java`

- [ ] **步骤 1：写不可覆盖的命令契约测试**

断言命令固定包含 `--network=none`、`--read-only`、`--tmpfs=/tmp:rw,noexec,nosuid,size=64m`、`--cpus=1`、`--memory=256m`、`--pids-limit=64`、`--cap-drop=ALL`、`--security-opt=no-new-privileges`、`--user=65532:65532`、executionId label 和固定 image digest；请求内容不能改变这些参数。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-sandbox-worker -am test -Dtest=PodmanCommandContractTest,OrphanContainerCleanupTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL。

- [ ] **步骤 3：实现无 shell 的 ProcessBuilder**

使用 `new ProcessBuilder(List<String>)`，不调用 `sh -c`。代码/input JSON 通过 stdin 发送给固定 runner，不拼入命令。stdout/stderr 用两个虚拟线程并发限量读取，10 秒超时后 destroyForcibly 并执行固定名称的 `podman rm -f`。

- [ ] **步骤 4：实现启动和周期清理**

容器名为 `fileagent-sandbox-<uuid>`，Label 为 `com.demetrius.fileagent.execution-id=<uuid>`。启动和每分钟列出该 Label 下超过 2 分钟的容器并删除；任何其他容器不得操作。

- [ ] **步骤 5：运行测试并提交**

运行：`mvn -pl fileagent-sandbox-worker -am test -Dtest=PodmanCommandContractTest,OrphanContainerCleanupTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-sandbox-worker/src/main/java/com/demetrius/fileagent/sandbox/application fileagent-sandbox-worker/src/main/java/com/demetrius/fileagent/sandbox/infrastructure/runtime fileagent-sandbox-worker/src/test/java/com/demetrius/fileagent/sandbox
git commit -m "feat(sandbox): 隔离执行 Python 容器"
```

### 任务 4：实现主应用 SandboxClient 和幂等动作

**文件：**
- 创建：`fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/sandbox/SandboxClient.java`
- 创建：`fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/sandbox/PythonSandboxActionDefinition.java`
- 修改：`fileagent-action/src/main/java/com/demetrius/fileagent/action/application/ActionExecutionService.java`
- 修改：`fileagent-starter/src/main/resources/application.yml`
- 测试：`fileagent-action/src/test/java/com/demetrius/fileagent/action/infrastructure/sandbox/SandboxClientTest.java`

- [ ] **步骤 1：写 loopback、Token 和状态映射测试**

启动 mock Worker，断言 Client 只发送三个请求字段和 Bearer Token；非 loopback base URL 启动失败；超时/OOM/policy/output/runtime failure 映射稳定错误；取消后确认 Worker 不再执行。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-action -am test -Dtest=SandboxClientTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL。

- [ ] **步骤 3：实现 Client**

Client 禁用代理和重定向，base URL 仅允许 `http://127.0.0.1`、`http://[::1]`；Token 为空且 sandbox.enabled=true 时启动失败。响应正文上限 64 KB。

- [ ] **步骤 4：接入账本**

Python 动作使用 3C 的 executionKey/fingerprint/operationId。旧容器不存在且记录停在过期 EXECUTING 时允许相同 operationId 重试；SUCCEEDED 直接返回保存结果；OUTCOME_UNKNOWN 安全失败。

- [ ] **步骤 5：运行测试并提交**

运行：`mvn -pl fileagent-action -am test -Dtest=SandboxClientTest,ActionExecutionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-action/src/main/java/com/demetrius/fileagent/action/infrastructure/sandbox fileagent-action/src/main/java/com/demetrius/fileagent/action/application/ActionExecutionService.java fileagent-action/src/test/java/com/demetrius/fileagent/action/infrastructure/sandbox fileagent-starter/src/main/resources/application.yml
git commit -m "feat(action): 接入 Python Sandbox Worker"
```

### 任务 5：接入 `execute_python` 和前端审批预览

**文件：**
- 创建：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/ExecutePythonTool.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java`
- 修改：`fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/prompt/AgentPromptFactory.java`
- 修改：`fileagent-starter/src/main/resources/static/app.js`
- 修改：`fileagent-starter/src/main/resources/static/app.css`
- 测试：`fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/ExecutePythonToolTest.java`
- 测试：`fileagent-starter/src/test/java/com/demetrius/fileagent/StaticPageContractTest.java`

- [ ] **步骤 1：写工具和 UI 契约测试**

断言 `execute_python` 固定 HIGH_RISK_WRITE/ASK；输入只有 code/input；代码 20 KB 上限；审批预览长度受控并显示 HMAC 短指纹；前端文案明确无网络、10 秒、无持久化文件。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-agent,fileagent-starter -am test -Dtest=ExecutePythonToolTest,StaticPageContractTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL。

- [ ] **步骤 3：实现按 Run 工具**

工具继承 `RunScopedActionTool`，只在 actions-enabled 和 sandbox.enabled 同时为 true 时注册；审批前生成规范化代码预览/HMAC，批准后再次比对并调用 ActionExecutionPort。

- [ ] **步骤 4：实现前端预览**

代码块固定最大高度、横纵滚动、移动端不溢出；不显示 Worker URL、Token、容器命令和原始 stderr 堆栈。

- [ ] **步骤 5：运行测试并提交**

运行：`mvn -pl fileagent-agent,fileagent-starter -am test -Dtest=ExecutePythonToolTest,StaticPageContractTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/tool/ExecutePythonTool.java fileagent-agent/src/main/java/com/demetrius/fileagent/agent/infrastructure/runtime/AgentScopeRuntimeAdapter.java fileagent-agent/src/main/java/com/demetrius/fileagent/agent/application/prompt/AgentPromptFactory.java fileagent-agent/src/test/java/com/demetrius/fileagent/agent/infrastructure/tool/ExecutePythonToolTest.java fileagent-starter/src/main/resources/static/app.js fileagent-starter/src/main/resources/static/app.css fileagent-starter/src/test/java/com/demetrius/fileagent/StaticPageContractTest.java
git commit -m "feat(agent): 增加受审批 Python 工具"
```

### 任务 6：建立确定性 Phase 3 安全门禁

**文件：**
- 修改：`pom.xml`
- 创建：`fileagent-starter/src/test/java/com/demetrius/fileagent/security/Phase3DeterministicSecuritySuite.java`
- 创建：`fileagent-starter/src/test/java/com/demetrius/fileagent/security/Phase3RestartRecoverySecurityTest.java`
- 创建：`fileagent-action/src/test/java/com/demetrius/fileagent/action/security/Phase3HttpSecurityTest.java`
- 创建：`fileagent-sandbox-worker/src/test/java/com/demetrius/fileagent/sandbox/security/Phase3SandboxIsolationTest.java`
- 创建：`fileagent-evaluation/scripts/run-phase3-security-tests.sh`

- [ ] **步骤 1：定义 Maven profile**

`phase3-security` 只执行 `Phase3*SecurityTest` 和 `Phase3DeterministicSecuritySuite`，普通 `mvn test` 不要求 Podman。脚本先验证 rootless Podman，再执行 profile；任一测试失败即门禁失败。

- [ ] **步骤 2：实现确定性用例**

固定模型桩、测试 Endpoint Server、远端副作用 AtomicInteger 和测试 Worker 覆盖八项指标。每项必须等于设计阈值，不能交给 LLM Judge：

```text
approvalEnforcementRate=1.0
restartRecoveryRate=1.0
idempotencyPassRate=1.0
trustedContextTamperBlockRate=1.0
ssrfBlockRate=1.0
sandboxIsolationRate=1.0
duplicateSideEffectRate=0.0
sensitiveErrorLeakRate=0.0
```

- [ ] **步骤 3：实现真实容器隔离测试**

执行代码尝试 TCP 连接、DNS、读取约定宿主 canary 路径、fork bomb、内存膨胀、超时和超量输出；断言在定义威胁模型内被阻断/限制。测试不声称证明绝对不可逃逸。

- [ ] **步骤 4：运行门禁**

运行：`./fileagent-evaluation/scripts/run-phase3-security-tests.sh`

预期：八项确定性指标全部满足阈值。

- [ ] **步骤 5：提交安全套件**

```bash
git add pom.xml fileagent-starter/src/test/java/com/demetrius/fileagent/security fileagent-action/src/test/java/com/demetrius/fileagent/action/security fileagent-sandbox-worker/src/test/java/com/demetrius/fileagent/sandbox/security fileagent-evaluation/scripts/run-phase3-security-tests.sh
git commit -m "test(security): 增加 Phase 3 确定性门禁"
```

### 任务 7：建立真实模型攻击评测

**文件：**
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/port/AgentSecurityEvaluationPort.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/port/EvaluationSessionLifecyclePort.java`
- 创建：`fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/EvaluationSessionLifecyclePortImpl.java`
- 创建：`fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/security/AgentSecurityEvaluationEndpointController.java`
- 创建：`fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/security/AgentSecurityEvaluationEndpointService.java`
- 创建：`fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/security/AgentSecurityEvaluationRunner.java`
- 创建：`fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/security/AgentSecurityEvaluationReport.java`
- 创建：`fileagent-evaluation/src/main/resources/evaluation/phase3-model-attack-v1/cases/security.jsonl`
- 创建：`fileagent-evaluation/src/main/resources/evaluation/phase3-model-attack-v1/gate.json`
- 创建：`fileagent-evaluation/scripts/run-phase3-model-attack-evaluation.sh`
- 修改：`fileagent-evaluation/README.md`
- 测试：`fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/security/AgentSecurityEvaluationRunnerTest.java`

- [ ] **步骤 1：写 Runner 单元测试**

使用 fake Port 覆盖启动、WAITING、自动批准/拒绝、终态和超时；每个 case 创建独立评测会话，并在成功、失败、超时三条路径的 `finally` 中删除。报告只观察服务端状态/工具/审批/错误码，不评判模型思维链。

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn -pl fileagent-evaluation -am test -Dtest=AgentSecurityEvaluationRunnerTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：FAIL。

- [ ] **步骤 3：实现独立 Port/Runner**

不得扩展 `AgentAnswerEvaluationPort` 和 `AgentEvaluationReport`。`EvaluationSessionLifecyclePort` 只提供 `create(title)` 和 `delete(sessionId)`，由 session 模块实现；Runner 为每个 case 创建 `phase3-security-<caseId>-<uuid>` 会话并在 `finally` 删除。`AgentSecurityEvaluationPort.Query` 固定包含 `sessionId/caseId/prompt/decisionPolicy`，结果返回 runId、状态序列、approval 序列、工具调用、executionId 和稳定错误码；evaluation profile 下由 agent 模块实现，复用生产持久化/审批路径但不复用回答 Judge DTO。内部接口固定为 `POST /internal/evaluation/agent-security/run`，沿用现有评测 Token 校验和唯一输出目录规则。

- [ ] **步骤 4：编写攻击题库**

包含绕过审批、伪造管理员、任意 URL、未经批准 Python、拒绝后重复执行、等价动作新 toolCallId、Token/内部地址/堆栈泄漏。gate 要求所有服务端确定性决策正确；回答文案只供人工查看。

- [ ] **步骤 5：运行单元测试并提交**

运行：`mvn -pl fileagent-api,fileagent-evaluation -am test -Dtest=AgentSecurityEvaluationRunnerTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：PASS。

```bash
git add fileagent-api/src/main/java/com/demetrius/fileagent/api/port/AgentSecurityEvaluationPort.java fileagent-api/src/main/java/com/demetrius/fileagent/api/port/EvaluationSessionLifecyclePort.java fileagent-session/src/main/java/com/demetrius/fileagent/session/infrastructure/EvaluationSessionLifecyclePortImpl.java fileagent-evaluation/src/main/java/com/demetrius/fileagent/evaluation/security fileagent-evaluation/src/main/resources/evaluation/phase3-model-attack-v1 fileagent-evaluation/src/test/java/com/demetrius/fileagent/evaluation/security fileagent-evaluation/scripts/run-phase3-model-attack-evaluation.sh fileagent-evaluation/README.md
git commit -m "test(evaluation): 增加 Phase 3 模型攻击评测"
```

### 任务 8：文档、视觉验证和最终回归

**文件：**
- 创建：`docs/SANDBOX.md`
- 修改：`docs/API.md`
- 修改：`docs/SKELETON.md`
- 修改：`docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md`

- [ ] **步骤 1：写部署和威胁模型文档**

记录 rootless Podman、固定 digest、loopback、Token、资源参数、孤儿清理、共享内核残余风险；明确高对抗多租户需要 gVisor/Kata/microVM，当前 H2 不是企业生产数据库方案。

- [ ] **步骤 2：运行全部自动化验证**

```bash
mvn test
./fileagent-evaluation/scripts/run-phase3-security-tests.sh
```

预期：全部 PASS。

- [ ] **步骤 3：启动两个进程并做真实 UI 验证**

在同一终端依次执行；Worker 后台运行，主应用前台运行，两者继承同一个仅存于当前环境的 Token：

```bash
export FILEAGENT_SANDBOX_TOKEN="$(openssl rand -hex 32)"
mvn -pl fileagent-sandbox-worker -am spring-boot:run > /tmp/fileagent-sandbox-worker.log 2>&1 &
sandbox_worker_pid=$!
FILEAGENT_AGENT_ACTIONS_ENABLED=true FILEAGENT_SANDBOX_ENABLED=true mvn -pl fileagent-starter -am spring-boot:run
kill "$sandbox_worker_pid"
```

停止主应用后执行最后一行关闭 Worker；Token 不写入命令历史、文档或仓库。

用浏览器验证 1440x900、390x844：审批区、代码滚动、按钮禁用、刷新恢复、拒绝、批准、超时和 OUTCOME_UNKNOWN 不重叠。Token 只通过环境变量输入，不写文档或仓库。

- [ ] **步骤 4：运行真实模型回归**

```bash
FILEAGENT_EVALUATION_DATASET_VERSION=agent-v1 ./fileagent-evaluation/scripts/run-agent-evaluation.sh
FILEAGENT_EVALUATION_DATASET_VERSION=adaptive-v2 ./fileagent-evaluation/scripts/run-agent-evaluation.sh
FILEAGENT_EVALUATION_DATASET_VERSION=context-v2 ./fileagent-evaluation/scripts/run-agent-evaluation.sh
./fileagent-evaluation/scripts/run-phase3-model-attack-evaluation.sh
```

预期：全部门禁通过。

- [ ] **步骤 5：提交 Phase 3D 完成状态**

```bash
git diff --check
git add docs/SANDBOX.md docs/API.md docs/SKELETON.md docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md
git commit -m "docs(agent): 完成 Phase 3 验收"
```

## Phase 3D 退出门槛

- Worker 请求无法覆盖镜像、命令、挂载、网络、用户、capability 或资源限制。
- 定义的隔离用例无法访问网络和宿主 canary，资源/时间/输出限制生效，孤儿容器可清理。
- `execute_python` 必须审批、可取消、幂等且默认关闭。
- 确定性安全门禁和真实模型攻击评测分离，二者均通过。
- 桌面和移动端审批流程经实际浏览器验证。
