# 贡献指南 (CONTRIBUTING.md)

> 面向两位协作者（Demetrius 与搭档）的协作规范。AI 代理请读 `AGENTS.md`。

## 1. 协作模型

- 仓库：GitHub · `Demetrius2107/fileAgent`（已创建）
- 分支：`main` 受保护，仅允许 PR 合入
- 开发流程：`main` → `feat/` 分支 → PR → review → squash merge 回 `main`

## 2. 起步

```bash
git clone https://github.com/Demetrius2107/fileAgent.git
cd fileAgent
# JDK 21 必需
java -version   # 应为 21.x
mvn compile     # 验证骨架可编译
```

> 若本机还是 JDK 8，先升级 JDK 21 并把 `JAVA_HOME` 指过去。

## 3. 分支命名

```
feat/<里程碑>-<功能>    # 如 feat/m1-upload
fix/<问题简述>          # 如 fix/parse-npe
docs/<内容>            # 如 docs/api-update
```

## 4. 提交信息（Conventional Commits）

```
<type>(<scope>): <subject>

type: feat | fix | docs | refactor | test | chore
scope: chat | document | parse | session | repo | pom | model | api | infra
```

示例：
- `feat(parse): 支持 xlsx 多 sheet 解析`
- `fix(document): 上传文件 sha256 重复时复用索引`
- `docs(api): 更新 chat 接口 SSE 示例`

## 5. PR 规范

- 一个 PR 只做一件事，描述里写清「改动 + 动机 + 测试情况」。
- 至少 1 人 review 通过后才合并。
- 合入方式：squash merge（保持 main 历史干净）。
- 合入前确认：无密钥入库、无 `storage/` 被误加、`mvn test` 通过。

## 6. 里程碑拆解（建议）

| 里程碑 | 建议拆的任务 |
|---|---|
| M1 | 上传/解析/索引 / 检索/LLM/动作/落库 |
| M2 | PDF/Office 解析 / export+chart 执行器+SSE |
| M3 | ReAct 编排+工具注册 / 沙箱+外部 API |
| M4 | 会话/历史文档检索 / 业务库连接器 |

每完成一个里程碑打 tag：`git tag m1 && git push origin m1`。
