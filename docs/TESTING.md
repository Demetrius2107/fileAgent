# 测试规范 (TESTING.md)

> 目标：核心逻辑有测试，测试能稳定跑、能传达意图。先写有价值的测试，不追求覆盖率数字。

## 1. 框架与约定

- JUnit 5 + Mockito（Spring Boot 自带 `spring-boot-starter-test`）。
- 测试目录：`src/test/java/com/demetrius/fileagent/...`，包结构与生产类一致。
- 命名：类 `XxxTest`；方法 `should_<期望> when_<条件>` 或中文描述，如 `should_returnChunks_when_parseMarkdown`。

## 2. 分层测试策略

| 层 | 类型 | 范围 |
|---|---|---|
| parser | 纯单元 | 给文件→断言 Chunk 内容/数量 |
| service | 单元(Mock) | Mock repo/vectorStore，验编排逻辑 |
| controller | MockMvc | 断言 HTTP 状态/JSON 结构 |
| 集成 | `@SpringBootTest` | 端到端：上传→问答（M1 至少一条 happy path） |

## 3. 原则

- 每个测试只验一个行为；用 `given/when/then` 三段式。
- 断言要精确（字段值），避免 `assertNotNull` 一把梭。
- 测试数据自包含，不依赖 DB 残留状态。
- 集成测试若调用真实 LLM，打 `@Disabled` 或用 mock，避免 CI 花钱/不稳。
- 不写"永远通过"的空测试。

## 4. 测试资源

- 测试用样例文件放 `src/test/resources/fixtures/`（如 `sample.md`）。
- 测试不写 `storage/` 真实目录；用临时目录或 mock。

## 5. 跑测试

```bash
mvn test              # 全部
mvn test -Dtest=DocumentServiceTest   # 单类
mvn verify            # 含集成（如有）
```

提交前确保 `mvn test` 通过；新增功能必须带测试。
