# 代码规范 (CODE-STYLE.md)

> 适用范围：`src/main/java` 全部 Java 代码。目标：风格统一、分层清晰、可被 AI 代理与协作者一致遵守。

## 1. 命名

| 类型 | 规范 | 示例 |
|---|---|---|
| 包名 | 全小写 | `com.demetrius.fileagent.service` |
| 类/接口 | UpperCamelCase | `DocumentService` |
| 方法 | lowerCamelCase | `handleUpload` |
| 常量 | UPPER_SNAKE_CASE | `MAX_CHUNK_SIZE` |
| 枚举值 | UPPER_SNAKE_CASE | `PARSING` |
| 实体 | `XxxEntity` | `SessionEntity` |
| DTO | 语义名 + 无后缀/Resp/Req | `CreateSessionReq` `ChatResp` |
| Repository | `XxxRepository` | `SessionRepository` |
| 布尔方法 | `is/has/can` 前缀 | `isParsed()` |
| 数据库表 | 蛇形小写 | `chat_session` |

## 2. 分层铁律

- `controller`：只做参数校验 + 调 service + 包 `ApiResult`。**禁止**直接碰 repo/entity。
- `service`：业务编排；一个 service 专注一个领域（Session/Document/Chat）。
- `repo`：Spring Data 接口，只声明查询方法；复杂查询用 `@Query` 或规范名。
- `parser`：无状态工具类；`DocumentParser` 接口 + `ParserRegistry` 按 MIME 路由。
- `model`：实体/DTO/枚举，**不写业务方法**（实体可写 `addMessage` 这类领域行为，但禁止 IO/网络）。

## 3. Java 语法与库

- Java 21：优先用 record / sealed / 模式匹配；不用过时的 `Vector`、`Date`、`SimpleDateFormat`。
- Lombok：实体/服务用 `@Getter @Setter @Slf4j @RequiredArgsConstructor`；**禁止**过度使用 `@Data`（可变集合字段易出坑）。
- DTO 一律 `record`（不可变、自带 equals/hashCode/toString）。
- 日期用 `LocalDateTime` / `Instant`，交互边界用 ISO 字符串。
- 集合：接口类型声明（`List`/`Map`），不声明具体实现。
- 空值：用 `Optional` 或显式判空；禁止吞异常返回 null。

## 4. 异常处理

- 业务可预期错误 → 抛 `BizException(code, message)`。
- 全局统一在 `GlobalExceptionHandler` 兜底，**禁止** controller 里 try-catch 后返回裸 Map。
- 不捕获 `Exception` 后 `e.printStackTrace()`；要么抛出要么 `log.error`。
- 外部调用（LLM/HTTP/解析）超时要有明确错误信息，不要无限等待。

## 5. 并发与异步

- M1 同步即可；M2 解析/索引改异步时，用 `@Async` + 独立线程池，**禁止**裸 `new Thread`。
- 共享状态用 `ConcurrentHashMap` 或加锁；SimpleVectorStore 访问注意线程安全。

## 6. 配置与常量

- 业务可调参数放 `application.yml`，用 `@ConfigurationProperties`（如 `FileAgentProperties`）读取。
- 魔法数字/字符串抽常量；提示模板放 `resources/prompts/`。

## 7. 其他

- 缩进 4 空格；行宽 ≤ 120。
- 禁止 `System.out/System.err`，统一 `@Slf4j`。
- 一个类职责单一；超过 300 行考虑拆分。
- 提交前 `mvn compile` 保证可编译。
