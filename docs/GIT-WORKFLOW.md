# Git 工作流 (GIT-WORKFLOW.md)

> 两人协作的最小流程。目标：`main` 始终可编译可运行，改动可追溯、可回滚。

## 1. 分支模型

```
main              受保护，始终可运行
 └─ feat/m1-upload     功能分支，从 main 切出
 └─ fix/parse-npe
 └─ docs/api-update
```

- **禁止**直接往 `main` 提交。
- 分支名：`feat/<milestone>-<功能>` / `fix/<简述>` / `docs/<内容>` / `refactor/<内容>`。
- 分支生命周期要短，做完即合、即删。

## 2. 标准流程

```bash
# 1. 同步最新 main
git checkout main && git pull

# 2. 切功能分支
git checkout -b feat/m1-chat

# 3. 开发、小步提交
git add <相关文件>           # 不要 git add -A 夹带无关
git commit -m "feat(chat): 实现 RAG 问答"

# 4. 推送
git push -u origin feat/m1-chat

# 5. GitHub 上开 PR，至少 1 人 review，squash merge
```

## 3. 提交信息规范（Conventional Commits）

```
<type>(<scope>): <subject>

type:   feat | fix | docs | refactor | test | chore
scope:  session | document | chat | action | api | common | starter | pom | docs | infra
subject: 祈使句、中文、≤50 字
```

示例：
- `feat(parse): 支持 PDF 文本层抽取`
- `fix(document): sha256 重复时复用解析结果`
- `feat(chat): 实现 RAG 问答闭环`
- `docs(api): 新增 chat 接口 SSE 示例`
- `chore(pom): 升级 spring-ai 到 1.0.0-M3`

## 4. PR 检查清单

- [ ] 改动只与本任务相关（无无关文件）
- [ ] 无密钥 / `storage/` 被提交（`git diff --cached` 检查）
- [ ] `mvn compile` 通过
- [ ] 新增功能带了测试，`mvn test` 通过
- [ ] 接口/依赖变更已同步更新 `docs/API.md` / `docs/SKELETON.md`
- [ ] PR 描述写了「改动 + 动机 + 测试情况」

## 5. 合并方式

- **squash merge**：保持 `main` 历史干净，一个功能一个 commit。
- 合并后删本地与远程分支。

## 6. 冲突处理

- 先 `git fetch` + `git rebase origin/main`，本地解决冲突再推。
- 冲突解决不了时拉对方一起看，不强行覆盖。

## 7. 版本与里程碑

- 每完成一个里程碑打 tag：`git tag m1 -m "M1 闭环"` → `git push origin m1`。
- 重大版本用 `v0.1.0` 语义化版本号。

## 8. 禁止事项

- 禁止 `git push --force` 到 `main` 或他人分支。
- 禁止提交 `.env`、`application-local.yml`、`storage/`、IDE 配置。
- 禁止一次提交跨多个不相关功能。
