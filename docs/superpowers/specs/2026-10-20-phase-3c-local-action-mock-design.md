# Phase 3C 本地 HTTP 动作 Mock 设计

## 1. 背景与目标

Phase 3C 已实现注册式 HTTP 动作的参数约束、审批、幂等账本和出站安全控制，但默认没有实际注册的业务 Endpoint，文档中的 `api.example.com` 也只是占位地址。开发者无法在本机从工作台完整验证“模型选择动作 -> 审批 -> HTTP 请求 -> 远端幂等响应”的链路。

本设计在 FileAgent 主应用内提供一个默认关闭的测试目标服务，并以一个受版本控制的 Spring Profile 提供全部演示动作配置。目标是让开发者只需启用 `action-mock` Profile、配置一个本地 Token，即可在 `http://127.0.0.1:8080` 完成 Phase 3C 手工验收；不新增独立部署服务，也不放宽真实外部 HTTP 动作的 SSRF 防护。

## 2. 范围

本次包含：

- 新增 `application-action-mock.yml`，放入本地 Mock 所需的 Agent 开关、资源范围、Endpoint 定义、Schema 和超时配置。
- 在 `fileagent-action` 中新增默认关闭的 Mock Controller、应用服务、H2 持久化实体和 Repository。
- 为 Mock 写接口实现 Bearer Token 校验和远端幂等语义。
- 仅对显式标记的 Mock Endpoint 提供受限的 `127.0.0.1` HTTP 回调能力。
- 向模型安全展示本次 Run 已授权的 Endpoint 目录，支持自然语言选择 Mock 动作。
- 增加单元、Web/JPA 和 Agent 动作链路测试，并补齐部署与验收文档。

本次不包含：

- 动态管理真实生产 Endpoint 的后台接口。
- 全局 `allow-localhost`、内网地址白名单或任意本机 URL 调用能力。
- 真实报销系统集成、真实财务数据或真实账户信息。
- 把副作用动作接入既有 RAG 评测 Runner；它仍只评测真实模型和知识工具。

## 3. 启动方式

默认 `application.yml` 保持现有安全默认值：Agent 动作关闭、HTTP Endpoint 列表为空、Mock Controller 不装配。

开发者在 IDEA 运行配置中加入 Spring Profile `action-mock`。该 Profile 文件自动完成非敏感设置；唯一新增环境变量为：

```bash
export FILEAGENT_ACTION_MOCK_TOKEN='local-test-token'
```

本机地址默认是 `http://127.0.0.1:8080`。如需通过已部署实例验收，可以覆盖为公网 HTTPS 地址：

```bash
export FILEAGENT_ACTION_MOCK_BASE_URL='https://fileagent-dev.example.com'
```

配置文件只保存环境变量名称和占位符，不保存 Token 或真实凭证值。

## 4. YAML 配置

新增 `fileagent-starter/src/main/resources/application-action-mock.yml`，其关键结构如下：

```yaml
fileagent:
  agent:
    enabled: true
    actions-enabled: true
    authorized-action-resource-scopes: mock:expense:read,mock:expense:draft
  action:
    mock:
      enabled: true
      base-url: ${FILEAGENT_ACTION_MOCK_BASE_URL:http://127.0.0.1:8080}
      token: ${FILEAGENT_ACTION_MOCK_TOKEN:}
    http:
      enabled: true
      endpoints:
        - endpoint-id: mock-create-expense-draft
          mock-endpoint: true
          method: POST
          base-url: ${FILEAGENT_ACTION_MOCK_BASE_URL:http://127.0.0.1:8080}
          path-template: /internal/action-mock/v1/expense-drafts
          risk-level: LOW_RISK_WRITE
          resource-scope: mock:expense:draft
          credential-ref: FILEAGENT_ACTION_MOCK_TOKEN
          idempotency-header: Idempotency-Key
        - endpoint-id: mock-get-expense-draft
          mock-endpoint: true
          method: GET
          base-url: ${FILEAGENT_ACTION_MOCK_BASE_URL:http://127.0.0.1:8080}
          path-template: /internal/action-mock/v1/expense-drafts/{draftId}
          risk-level: READ_ONLY
          resource-scope: mock:expense:read
```

两个 Endpoint 的完整 `request-schema`、`data-egress-policy`、版本字段、响应限制和说明字段也必须写入该 YAML。写动作包含 `amount`、`memo`、`bankAccount` 三个 body 参数；其中 `bankAccount` 标记为敏感字段，审批预览和日志不得显示原值。读动作只接受 path 参数 `draftId`。

Profile 关闭时，上述 Endpoint 不会加载，也不会注册给模型。

## 5. 内置 Mock API

Mock API 归属 `fileagent-action` 的 `interfaces -> application -> domain -> infrastructure` 分层，不将业务代码放入 `fileagent-starter`。

| 路由 | 行为 |
|---|---|
| `POST /internal/action-mock/v1/expense-drafts` | 创建或重放测试报销草稿 |
| `GET /internal/action-mock/v1/expense-drafts/{draftId}` | 查询已创建的测试草稿 |

Controller 仅在 `fileagent.action.mock.enabled=true` 时装配。两个路由均要求 `Authorization: Bearer <FILEAGENT_ACTION_MOCK_TOKEN>`；Token 缺失、错误或 Mock 开启但 Token 为空时，分别返回明确的认证失败或启动失败，不允许匿名使用。

### 5.1 写入与幂等

`POST` 必须提供 `Idempotency-Key`。Mock 使用 H2 表保存：`draftId`、`idempotencyKey`、请求 HMAC、金额、备注、创建时间和更新时间；不保存原始 `bankAccount`。

- 首次收到合法 Key 与请求：创建一条草稿，返回 `201`、`draftId`、`idempotentReplay=false` 和 `X-Request-Id`。
- 相同 Key 与相同请求 HMAC：返回原草稿，`200`、`idempotentReplay=true`，不创建第二条记录。
- 相同 Key 与不同请求 HMAC：返回 `409`，不修改既有草稿。
- 并发首次请求通过数据库唯一约束收敛到同一条记录。

响应使用项目统一 `ApiResult` 包装，不回显 Bearer Token、`bankAccount` 或请求 HMAC。

## 6. 受限本机回调

现有 `BlockedAddressPolicy` 对所有真实 HTTP 动作继续阻断回环、内网、链路本地和保留地址。不得增加全局 `allow-localhost` 或允许任意本机 Endpoint 的配置。

新增的 `mock-endpoint=true` 只在以下条件同时满足时允许非 HTTPS 的 `http://127.0.0.1:<port>`：

1. `fileagent.action.mock.enabled=true`。
2. Endpoint 的 `base-url` 与 `fileagent.action.mock.base-url` 规范化后完全一致。
3. URL host 为字面量 `127.0.0.1`，路径位于 `/internal/action-mock/v1/`。
4. Endpoint 使用上述固定 Mock 路由之一。

执行器对这类 Endpoint 使用专用 DNS 策略，只返回 `127.0.0.1`，不会将任何其他内网、localhost 别名或 DNS 解析结果放行。覆盖为公网 HTTPS URL 时，Mock Endpoint 回归普通 HTTPS 与公网 DNS 校验。非 Mock Endpoint 的校验逻辑完全不变。

## 7. 模型动作目录

当前 `ActionExecutionPort` 只有按 ID 读取定义的能力，模型无法知道已配置的 Endpoint。本次扩展为提供“按资源范围列出安全动作定义”的 Port 方法。

动作工具工厂根据当前 Run 的授权 scope 获取定义；只把 `endpointId`、显示名称、安全摘要、预计影响、风险等级和去除 `sensitive`/`x-location` 后的参数 Schema 写入 `call_registered_http_api` 的工具描述。

不会进入模型上下文的信息包括：`baseUrl`、HTTP method、路径模板、Header 名称、凭证引用、Token、请求 HMAC 和任何执行账本数据。没有已授权 Endpoint 时不注册该副作用工具。

这样用户在工作台输入“创建一条金额 100 元、备注为测试的报销草稿”时，模型能看到 `mock-create-expense-draft` 的用途与参数，不需要用户手写 Endpoint ID。

## 8. 手工验收流程

1. 在 IDEA 选择 `action-mock` Profile，并设置 `FILEAGENT_ACTION_MOCK_TOKEN`。
2. 正常启动 Elasticsearch、FileAgent 和现有模型配置。
3. 打开工作台，启用 Agent 模式，输入创建测试报销草稿的自然语言请求。
4. 观察 `call_registered_http_api` 进入 `approval.required`，审批卡显示脱敏参数。
5. 选择拒绝：Run 结束，Mock H2 表没有新草稿。
6. 重新发起同类请求并批准：页面收到成功结果与 `draftId`，Mock 表新增一条草稿。
7. 对同一次审批重复提交、SSE 重连或恢复执行：Mock 表仍只有一条对应草稿，且返回同一 `draftId`。
8. 发起读取请求验证 `mock-get-expense-draft`；它是只读动作，可按既有策略自动执行。

## 9. 自动化验证

- 配置测试：Profile 开启时注册两个 Mock Endpoint；关闭时不装配 Controller 或副作用工具。
- 注册表/DNS 测试：仅固定 Mock 配置允许 `127.0.0.1`，篡改 host、路径、Profile 或普通 Endpoint 后均被拒绝。
- Mock Web/JPA 测试：Token 校验、首次创建、相同 Key 重放、Key 冲突、敏感字段不回显、重启后记录可查询。
- Agent 工具测试：模型目录只包含授权 Endpoint，且不含 URL、Token、Header、敏感元数据；无授权 scope 时工具不注册。
- 端到端运行时测试：确定性测试模型发起 `mock-create-expense-draft`；审批前 Mock 收到零次请求，批准后一次，重复批准与恢复不产生第二次写入。
- 回归：运行既有 Phase 3C 单元测试和全量 Maven 测试；既有 Agent/RAG 数据集继续单独运行，不把动作结果混入检索质量指标。

## 10. 文档与运维

更新 `docs/HTTP-ACTION-CONFIG.md`，说明 `action-mock` 仅用于本机/开发验收、Token 配置和公网 URL 覆盖方式；更新 `docs/API.md` 记录默认关闭的 Mock API；更新 `docs/SKELETON.md` 记录 Mock 适配器归属。

生产环境不得启用 `action-mock` Profile。真实 Endpoint 仍必须使用受信任 YAML、HTTPS、最小资源范围和远端幂等 Header。
