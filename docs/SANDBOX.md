# Python Sandbox（Phase 3D）

## 1. 当前能力与状态

当前实现的是一个默认关闭的 `execute_python` 高风险 Agent 工具。它不在主应用 JVM 中执行代码，而是把一次执行请求交给独立的 `fileagent-sandbox-worker` 进程；Worker 再用 rootless Podman 创建一次性 Python 容器。

- 当前仅支持 Python 3；没有接入 GraalVM、GraalJS 或 GraalPy。
- 模型只能提交 `code` 和 JSON `input`，不能指定镜像、命令、网络、挂载、Linux 用户、capability 或资源参数。
- 每次 Python 执行都属于高风险动作，需经 Agent 单次人工审批后才能执行。
- 默认关闭：不开启 Agent 动作、Sandbox 和 `sandbox:python` 资源范围时，模型不会看到 `execute_python` 工具。
- 当前实现不提供文件产物、动态安装依赖、Shell 包装器或任意网络访问接口；脚本只获得只读的 `input_data` JSON 变量，并以受控 stdout/stderr 结果返回。

本文件描述的是当前代码已有的部署和安全边界，不代表真实模型攻击评测或浏览器交互验收已经完成。

## 2. 组件与依赖方向

```text
Agent execute_python
        |
        v
fileagent-api: PythonSandboxExecutionPort
        |
        v
fileagent-action: PythonSandboxActionExecutionService / SandboxClient
        |  固定 loopback HTTP + Bearer Token
        v
fileagent-sandbox-worker（独立 Spring Boot 进程）
        |  固定 rootless Podman 参数
        v
一次性 Python 容器（固定本地 image ID）
```

`fileagent-sandbox-worker` 只依赖 `fileagent-api` 和 `fileagent-common`，不依赖 `fileagent-agent`、`fileagent-action` 或 `fileagent-starter`。主应用也不直接调用 Podman：`fileagent-action` 通过 API Port 和受限 HTTP Client 访问 Worker。这样即使主应用被模型输出影响，模型也没有直接拼接容器命令的路径。

## 3. 执行与审批链路

1. Agent 仅在以下条件同时满足时注册 `execute_python`：`fileagent.agent.actions-enabled=true`、`fileagent.sandbox.enabled=true`，且本地运行身份已授权 `sandbox:python`。
2. 工具只接受 `code` 与 `input` 两个字段。服务端对参数做规范化和 HMAC 绑定，审批中保存动作定义版本、参数指纹和最多 4,000 字符的预览；预览截断会明确标识，完整参数不进入浏览器展示。
3. 用户批准后，服务端再次校验动作定义、资源范围和审批绑定，再创建或取得副作用账本记录。
4. `SandboxClient` 只向固定的 `/internal/sandbox/executions` 路径发送 `executionId`、`code`、`inputJson` 三个字段。
5. Worker 在读取或解析请求体前校验 Bearer Token，然后启动一次性容器并通过 stdin 把代码和输入传给固定的 Python runner。
6. Worker 返回受控结果，主应用写入动作账本。通信失败、协议异常或结果无法可靠持久化时状态收敛为 `OUTCOME_UNKNOWN`，禁止自动重试脚本。

已批准的 Python 动作在应用重启恢复时会失败关闭为 `PYTHON_RESTART_REJECTED`，而不是重新调用 Worker。拒绝、审批过期、取消或旧审批绑定变化也不会执行脚本。

## 4. 部署前置条件

Sandbox Worker 与主应用应运行在同一主机，并使用同一份仅通过环境变量或密钥管理系统注入的 Token。不要把真实 Token 写进 `application.yml`、脚本、命令历史或仓库。

Worker 与主应用均按 Java 21 编译和运行。若机器默认 `java` 不是 JDK 21，启动 Worker 时必须显式使用 JDK 21 的 `bin/java`；仅设置 `JAVA_HOME` 不会自动替换已经位于 `PATH` 中的旧版 `java`。

### 4.1 rootless Podman

Worker 启动时会执行 `podman info --format '{{.Host.Security.Rootless}}'`。结果不是 `true` 时启动失败；不支持 rootful Podman 作为替代运行方式。

部署账户必须能在无特权模式下执行 Podman，并且不应把该账户授予宿主机管理权限、Docker socket 访问权限或可写入生产敏感目录的权限。

### 4.2 固定基础镜像 digest 与本地 image ID

`deploy/sandbox/Dockerfile` 的 `FROM` 使用 Python 基础镜像的内容 digest，目的是固定构建输入。它不是 Worker 运行时使用的值。

Worker 运行时要求 `FILEAGENT_SANDBOX_IMAGE_ID` 为本机已构建镜像的完整 `sha256:<64 位十六进制>` image ID。启动期会调用 `podman image inspect` 比对该 ID，且运行容器时使用该 ID 与 `--pull=never`；运行期不接受可变 tag，例如 `latest` 或 `python:3.x`。

构建后可按下面方式取得配置值。命令中的镜像名只是本机 tag，不能替代最终传给 Worker 的 image ID。

```bash
podman build --tag localhost/fileagent-python-sandbox:local \
  --file deploy/sandbox/Dockerfile deploy/sandbox

raw_image_id="$(podman image inspect --format '{{.Id}}' localhost/fileagent-python-sandbox:local)"
export FILEAGENT_SANDBOX_IMAGE_ID="sha256:${raw_image_id#sha256:}"
```

### 4.3 必要配置

Worker 的独立配置位于 `fileagent-sandbox-worker/src/main/resources/application.yml`：

| 环境变量 | 作用 | 默认或要求 |
|---|---|---|
| `FILEAGENT_SANDBOX_TOKEN` | Worker Bearer Token，同时由主应用 Client 使用 | 必填，不能为空 |
| `FILEAGENT_SANDBOX_IMAGE_ID` | 本机构建后的固定 Podman image ID | 必填，`sha256:<64 位十六进制>` |
| `FILEAGENT_SANDBOX_PORT` | Worker 监听端口 | `8090` |
| `FILEAGENT_SANDBOX_BIND_ADDRESS` | Worker 监听地址 | `127.0.0.1`，非 loopback 会启动失败 |

主应用还需要显式开启如下配置；除 Token 外都可在 `fileagent-starter` 的 YAML 或部署环境中设置：

| 配置 | 含义 |
|---|---|
| `FILEAGENT_AGENT_ENABLED=true` | 开启 Agent 入口 |
| `FILEAGENT_AGENT_ACTIONS_ENABLED=true` | 允许注册受控副作用工具 |
| `FILEAGENT_AGENT_AUTHORIZED_ACTION_RESOURCE_SCOPES=sandbox:python` | 赋予本地运行身份 Python Sandbox 资源范围 |
| `FILEAGENT_SANDBOX_ENABLED=true` | 创建 Sandbox Client、执行服务和 `execute_python` 工具工厂 |
| `FILEAGENT_SANDBOX_BASE_URL=http://127.0.0.1:8090` | Worker 地址；仅允许 `127.0.0.1` 或 `::1` 的 HTTP 字面量地址 |
| `FILEAGENT_SANDBOX_CALL_TIMEOUT=15s` | 主应用等待 Worker 的调用上限 |
| `FILEAGENT_SANDBOX_TOKEN` | 与 Worker 完全相同的 Token，必填 |

主应用 Client 禁用代理、重定向和连接失败重试，并拒绝远端主机、`localhost` DNS 名、HTTPS、额外路径、查询参数和片段。Worker 也通过 Web Server 定制器强制绑定经过校验的 loopback 地址，不能靠外部 `server.address` 覆盖。

## 5. 固定容器边界

每次请求对应一个名为 `fileagent-sandbox-<UUID>` 的容器。Worker 的 Podman 参数由 Java 代码固定，不从请求、模型参数或审批参数中读取：

- `--network=none`
- `--http-proxy=false`（不向容器传递宿主机 `HTTP_PROXY`、`HTTPS_PROXY`、`NO_PROXY` 等代理环境变量）
- `--read-only`
- `--tmpfs /tmp:rw,noexec,nosuid,size=64m`
- `--cpus=1`
- `--memory=256m`
- `--pids-limit=64`
- `--cap-drop=ALL`
- `--security-opt=no-new-privileges`
- `--user=65532:65532`
- `--pull=never`

容器使用固定入口 `python3 -I -S /opt/fileagent/runner.py`。`-I -S` 降低 Python 启动环境的可变性，但它不是语言级安全沙箱；当前 runner 也没有 Python 模块或标准库白名单。脚本仍可使用镜像中已有的 Python 能力以及唯一可写的临时 `/tmp`，但不能通过请求获得宿主机挂载、网络或持久化产物。真正的隔离依赖 rootless Podman 的网络、挂载、身份与资源边界。

执行超时默认为 10 秒，超时后 Worker 先终止进程，再在最多 2 秒的二次等待上限内确认停止。Python runner 的 stdout/stderr 共用 64 KiB 输出预算；Worker 读取 Podman 子进程 stdout/stderr 时也分别设置 64 KiB 上限。代码最大 20 KiB，输入 JSON 最大 64 KiB，Worker 在 Jackson 解析前把原始请求体限制为 192 KiB。

## 6. 认证、清理和审计

- Worker 仅有内部接口 `POST /internal/sandbox/executions`，要求 `Authorization: Bearer <Token>`。Token 使用常量时间比较，未认证请求在读取请求体前被拒绝。
- 每次动作有独立 `executionId`，副作用账本使用执行键、操作指纹和操作 ID 去重。它们只用于审计和幂等收敛，不能把旧审批变成可重放授权。
- 执行结束后 Worker 固定调用 `podman rm -f` 清理本次容器。
- Worker 启动时及后续每分钟，会仅按 `com.demetrius.fileagent.execution-id` 标签和 `fileagent-sandbox-<UUID>` 名称枚举容器，删除超过 2 分钟的遗留容器；不会枚举或删除其他 Podman 容器。
- 脚本成功结果会加密保存到动作账本，供后续受控展示；不确定结果不自动补偿或重试。

## 7. 威胁模型与边界

这套设计旨在降低模型生成代码的网络访问、宿主机挂载、持久化写入、过量 CPU/内存/进程和任意容器参数注入风险。它不提供“绝对无法逃逸”的保证：rootless 容器仍与宿主机共享内核，内核漏洞、Podman/OCI 运行时漏洞、错误的宿主机权限配置、拥有同一用户权限的本地恶意进程，以及泄露 Token 都可能突破或绕过部分防护。

因此当前实现不应被视为高对抗、多租户或处理高敏感数据的最终隔离方案。此类场景应使用专用隔离节点，并评估 gVisor、Kata Containers 或 microVM，同时补充网络出口治理、宿主机加固、最小权限、镜像供应链签名、密钥轮换和独立审计。项目默认 H2 也只是本地开发数据存储，不是企业生产级的多租户审计数据库方案。

## 8. 验证边界

仓库提供 `fileagent-evaluation/scripts/run-phase3-security-tests.sh`，它会先检查 rootless Podman，再运行固定隔离、超时、输出限制、审批失败关闭和账本语义相关的安全测试。该脚本需要本机具备 Podman 和可构建的 Python 镜像。

确定性安全测试、真实模型攻击评测和浏览器审批流程验证是不同层次的验收项。前者通过不等同于后两者已完成；部署前仍应分别在目标环境执行真实模型攻击题集和桌面、移动端审批交互验证。
