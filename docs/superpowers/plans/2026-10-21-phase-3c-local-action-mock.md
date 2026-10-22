# Phase 3C 本地 HTTP 动作 Mock 实现计划

**目标：** 在 `action-mock` Profile 下，通过 FileAgent 内置测试接口验证真实 HTTP 调用、审批与幂等。

**架构：** 默认配置不变；Profile 提供两个受控 Endpoint。Mock 服务存储在 action 域，仅固定 Mock 路由可回调本机。Agent 只看到已授权动作的安全描述和参数 Schema。

**技术栈：** Java 21、Spring Boot、JPA/H2、AgentScope、OkHttp、JUnit。

## 任务 1：Mock API 与远端幂等

- [x] 在 `fileagent-action` 新增 Mock Controller、应用服务、草稿实体和 JPA Repository。启用时校验非空 Bearer Token；实体字段逐一写明含义。
- [x] `POST /internal/action-mock/v1/expense-drafts` 校验金额、备注、账户和 `Idempotency-Key`；在数据库唯一约束下，首次创建返回 201，同 Key 同参数返回 200 和同一草稿，不同参数返回 409。
- [x] `GET /internal/action-mock/v1/expense-drafts/{draftId}` 查询草稿；响应和日志不包含账户、Token 或请求 HMAC。
- [x] 使用 MockMvc、JPA 和并发测试覆盖认证、参数、幂等及数据库重读。

## 任务 2：配置与本机回调边界

- [x] 新增 `fileagent-starter/src/main/resources/application-action-mock.yml`，定义 Agent 开关、两个 Endpoint、授权范围及 Schema；Token 只引用环境变量。
- [x] 在 `RegisteredHttpActionProperties`、`RegisteredHttpActionRegistry` 和 `RegisteredHttpExecutor` 中，仅对 Mock 已启用、标记正确、地址与 Mock 配置一致且路由/方法完全匹配的 Endpoint 允许 `127.0.0.1`；其他 Endpoint 沿用原 HTTPS 和 DNS/IP 防护。
- [x] 测试普通 Endpoint、错误 host/端口/路由/method、禁用 Mock 和其他内网地址均不可借此放行；公网 HTTPS 继续沿用原有 DNS 校验。

## 任务 3：模型可见的安全目录

- [x] 为 `ActionExecutionPort` 增加按授权 scope 查询定义的方法，并由 action 注册表实现稳定排序与权限过滤。
- [x] 在 `CallRegisteredHttpApiTool` 的描述中提供 ID、名称、用途和去除内部元数据后的参数 Schema；不包含 URL、路径、Header、凭证或 Token。
- [x] 无已授权定义时不注册 HTTP 工具；仍在调用及审批恢复时重新执行原有授权和配置绑定校验。
- [x] 测试授权过滤、隐藏字段、Schema、空目录以及原有工具行为。

## 任务 4：端到端验证与文档

- [x] 测试真实 HTTP 工具、审批和 Mock 调用：批准前及拒绝后零次写入，批准后一次；重复批准、已完成账本重放和应用上下文重启后的审批恢复均不重复写入。
- [x] 更新 `docs/HTTP-ACTION-CONFIG.md`、`docs/API.md`、`docs/SKELETON.md`，提供 IDEA Profile、Token、示例提问及 H2 查询方法。
- [x] 使用项目 JDK 21 执行 action/agent 定向测试、starter 装配测试和全量 Maven 测试；真实模型的人工验收需在用户配置的运行环境执行。

固定 RAG 的 Retrieval 单条诊断只记录在 Phase 0 路线图，本计划不实现该功能。
