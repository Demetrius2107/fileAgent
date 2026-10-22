# 注册式 HTTP 动作配置

Phase 3C 提供 `call_registered_http_api`。模型只能选择服务端预注册的 `endpointId` 并提交符合 JSON Schema 的业务参数，不能提交 URL、HTTP Method、Header、凭证、操作者身份或资源权限。

## 本机完整验收（action-mock）

项目内置了一个默认关闭的测试报销服务和两个预注册动作；它们只产生测试草稿，不连接真实报销系统。使用已有模型配置和 Elasticsearch，无需单独部署 Mock 服务。

1. 在 IDEA 的 FileAgent 运行配置中，将 Active profiles 设置为 `action-mock`，并在环境变量中配置 `FILEAGENT_ACTION_MOCK_TOKEN`，例如 `local-demo-token`。Token 只用于测试，不能写入仓库或使用真实业务凭证。
2. 启动 Elasticsearch 和 FileAgent，打开原有工作台并切换到 Agent 模式。Profile 已在 [application-action-mock.yml](../fileagent-starter/src/main/resources/application-action-mock.yml) 配好 Agent 开关、资源范围、Endpoint 和 Schema，无需再手动 export 其他动作配置。
3. 输入“创建一条金额 100 元、备注为出差测试的报销草稿”。Agent 根据模型输出选择动作，正常情况下会弹出一次审批，显示动作摘要和参数；`bankAccount` 是可选的测试字段，传入时审批预览应显示 `[REDACTED]`。
4. 点击拒绝，确认没有新草稿。重新发送请求并点击批准，确认出现工具执行完成事件；日志中 `endpointId=mock-create-expense-draft`、`status=SUCCEEDED`、`resultCode=HTTP_201` 表示实际 HTTP 创建成功。重复提交同一审批不会再次创建草稿。
5. 使用返回的 `draftId` 提问“查询测试报销草稿 <draftId>”，读取动作按既有只读策略执行，不需要创建审批。模型是否选择正确动作仍取决于实际模型输出，不能仅凭回答推断动作已执行。

默认回调地址是 `http://127.0.0.1:8080`。如果主应用改了端口，设置 `FILEAGENT_ACTION_MOCK_BASE_URL` 为相同的本机地址和端口；部署到受控开发环境时可设置为指向该实例的公网 HTTPS 地址。非 Mock Endpoint 仍禁止本机和内网地址。日志不会输出 Token 或账户原文。

可以连接项目的 H2 文件库（默认 `jdbc:h2:file:./storage/db/fileagent;AUTO_SERVER=TRUE`，用户名 `sa`，空密码；相对路径以服务工作目录为准），执行：

```sql
SELECT draft_id, amount, memo, created_at FROM action_mock_expense_draft ORDER BY created_at DESC;
SELECT execution_id, status, result_code, external_request_id FROM action_tool_execution ORDER BY updated_at DESC;
```

`action_mock_expense_draft` 不保存账户原文；相同幂等键和请求返回同一草稿，参数不同时返回冲突。该 Mock API 自身只校验 Token 和幂等，持有 Token 的客户端可以直接调用它，审批由 Agent 动作链路负责。因此 `action-mock` 仅适用于本机或受控开发环境，不能在生产环境启用，也不能作为真实财务服务。

## 1. 默认状态与启用顺序

两个开关默认都是 `false`，必须同时开启且存在当前资源范围可用的 Endpoint 才会向 Agent 注册工具。使用上述 `action-mock` Profile 时不需要重复配置以下示例变量：

```bash
export FILEAGENT_AGENT_ACTIONS_ENABLED=true
export FILEAGENT_ACTION_HTTP_ENABLED=true
export FILEAGENT_AGENT_AUTHORIZED_ACTION_RESOURCE_SCOPES=expense:read,expense:draft
```

资源范围为空时拒绝全部外部动作。建议先完成 Endpoint 配置和测试，再开启开关；关闭任一开关都会停止向新 Run 暴露该工具。

当前阶段仍是本地单操作者模式，`actorId` 由服务端固定为 `local-operator`。在多用户身份、租户隔离和正式鉴权完成前，不应把副作用 Agent 暴露到局域网或公网。

## 2. 只读 GET 示例

下面的地址是配置模板，部署时替换为实际公开 HTTPS 服务。只读动作不外发模型自由文本时可由策略自动执行；仍会校验资源范围、Schema、DNS/IP 和响应限制。

```yaml
fileagent:
  action:
    http:
      enabled: ${FILEAGENT_ACTION_HTTP_ENABLED:false}
      endpoints:
        - endpoint-id: query-expense-status
          display-name: 查询报销单状态
          definition-version: definition-v1
          endpoint-revision: endpoint-v1
          policy-version: policy-v1
          method: GET
          base-url: https://api.example.com
          path-template: /v1/expenses/{expenseId}
          request-schema:
            type: object
            additionalProperties: false
            properties:
              expenseId:
                type: string
                maxLength: 64
                x-location: path
              includeHistory:
                type: boolean
                x-location: query
            required: [expenseId]
          risk-level: READ_ONLY
          data-egress-policy:
            model-writable-fields: [expenseId, includeHistory]
            externalizes-model-free-text: false
          resource-scope: expense:read
          safe-summary: 查询指定报销单的处理状态
          expected-impact: 只读取外部系统数据，不修改业务状态
          connect-timeout: 3s
          read-timeout: 8s
          call-timeout: 10s
          max-response-bytes: 65536
          allowed-content-types: [application/json]
```

## 3. 幂等写入示例

写动作必须配置远端真实支持的幂等 Header。FileAgent 会把稳定的 `operationId` 写入该 Header；如果远端只是接收 Header 却不按它去重，就不能把该接口注册为可安全写入的 Endpoint。

```yaml
fileagent:
  action:
    http:
      enabled: ${FILEAGENT_ACTION_HTTP_ENABLED:false}
      endpoints:
        - endpoint-id: create-expense-draft
          display-name: 创建报销草稿
          definition-version: definition-v1
          endpoint-revision: endpoint-v1
          policy-version: policy-v1
          method: POST
          base-url: https://api.example.com
          path-template: /v1/expense-drafts
          request-schema:
            type: object
            additionalProperties: false
            properties:
              amount:
                type: number
                minimum: 0
                x-location: body
              memo:
                type: string
                maxLength: 200
                x-location: body
              bankAccount:
                type: string
                maxLength: 64
                sensitive: true
                x-location: body
            required: [amount, memo, bankAccount]
          risk-level: LOW_RISK_WRITE
          data-egress-policy:
            model-writable-fields: [amount, memo, bankAccount]
            externalizes-model-free-text: true
          resource-scope: expense:draft
          safe-summary: 创建一条待提交的报销草稿
          expected-impact: 外部报销系统将新增一条可撤销草稿
          credential-ref: FILEAGENT_EXPENSE_API_TOKEN
          idempotency-header: Idempotency-Key
          connect-timeout: 3s
          read-timeout: 8s
          call-timeout: 10s
          max-response-bytes: 65536
          allowed-content-types: [application/json]
```

凭证值只放在运行环境中：

```bash
export FILEAGENT_EXPENSE_API_TOKEN='由部署平台注入的真实 Token'
```

`credential-ref` 保存的是环境变量名称，不是 Token。当前执行器使用 `Authorization: Bearer <value>`；不要把密钥写入 YAML、请求 Schema、Prompt 或模型参数。

## 4. 字段规则

| 配置 | 规则 |
|---|---|
| `endpoint-id` | 模型可见的稳定标识；修改业务含义时创建新标识或升级定义版本 |
| `definition-version` | 工具业务定义版本；审批等待期间变化会使旧批准失效 |
| `endpoint-revision` | URL、路径、请求映射等端点版本；变化后旧批准不能执行 |
| `policy-version` | 权限策略版本；变化后旧批准不能执行 |
| `request-schema` | 必须是 `type: object` 且 `additionalProperties: false`；禁止 `$ref` |
| `x-location` | `path`、`query` 或 `body`，默认 `body`；路径字段必须对应完整 `{placeholder}` segment |
| `sensitive` | FileAgent 自定义字段；为 `true` 时审批预览显示 `[REDACTED]` |
| `model-writable-fields` | 模型允许提交的字段白名单，必须全部存在于 Schema |
| `externalizes-model-free-text` | 是否把模型自由文本发往外部；只读动作设为 `true` 时也必须审批 |
| `resource-scope` | 必须同时出现在 `FILEAGENT_AGENT_AUTHORIZED_ACTION_RESOURCE_SCOPES` 中 |
| `credential-ref` | 环境变量名称；真实 Endpoint 配置凭证时必须使用 HTTPS，本机 Mock 是受限例外 |
| `idempotency-header` | 所有非 `READ_ONLY` 动作必填，远端必须按该值保证业务幂等 |

字符串字段必须配置 `maxLength`，单次参数最多 64 KB、最多 64 个字段、最多 8 层；注册 Schema 最多 32 KB。响应最多 256 KB，Endpoint 可配置更小上限。

## 5. 网络与失败语义

- 生产 Endpoint 只允许 HTTPS；禁止 userinfo、query 和 fragment 出现在 `base-url`。本机 Mock 仅在显式启用且固定 ID、方法、路径、风险、范围和地址均匹配时可用 `http://127.0.0.1`，不能用于普通 Endpoint。
- 每次连接都重新解析 DNS；解析结果中只要包含本机、内网、链路本地、元数据、组播、保留地址或 IPv4-mapped IPv6，就整体拒绝。
- 禁用系统代理、HTTP/HTTPS 重定向和 OkHttp 自动重试。
- 路径和查询参数结构化编码，拒绝绝对 URL、`//host`、反斜线、CRLF、`.`/`..` 和双重编码绕过。
- 响应 Content-Type、Header 数量、Header 大小和解压后正文大小均受限。
- 写请求已开始发送后发生超时、重定向、超量响应或本地记账失败时返回 `OUTCOME_UNKNOWN`。页面显示“外部操作可能已执行”和 `executionId`，必须人工核对，系统和模型都不得自动重试或声称成功。

## 6. 上线检查

1. 远端团队确认幂等 Header 的真实去重语义和保留周期。
2. 为 Endpoint 分配最小 `resource-scope`，不要使用宽泛通配权限。
3. 确认所有模型可写字段均有类型、长度/范围和 `x-location`。
4. 用测试环境验证批准、拒绝、过期、重复点击、网络超时和 `OUTCOME_UNKNOWN`。
5. 检查日志和审批预览中没有 Token、Authorization、敏感参数或外部响应正文。
6. 验证关闭任一开关后，新 Run 看不到 `call_registered_http_api`。
