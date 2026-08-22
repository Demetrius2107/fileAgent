# CI/CD 流水线说明 (CICD.md)

> 持续集成与持续交付流水线，配置位于 `.github/`，由 **`ci-cd` 分支**维护。
> 本地机器是 JDK 8，跑不了 Spring Boot 3，CI 在 GitHub 的 Linux runner 上用 JDK 21 验证，正好补上本地无法编译的短板。

---

## 1. 分支策略

| 分支 | 作用 | CI 触发 |
|---|---|---|
| `master` | 主干，始终可运行 | PR 指向 master 时触发 CI |
| `ci-cd` | CI/CD 配置专属分支 | push 到 ci-cd 时触发 CI 自测 |
| `feat/*` `fix/*` | 功能分支 | 通过 PR 间接触发（合入 master 前） |

**合并路径**：`ci-cd` 分支上的工作流改动成熟后，通过 PR 合入 `master`，使 CI/CD 在主线上生效。

## 2. 工作流一览

| 文件 | 触发 | 做什么 |
|---|---|---|
| `.github/workflows/ci.yml` | push 到 master/ci-cd、PR 到 master、手动 | JDK 21 + `mvn verify`（编译+测试），失败上传测试报告 |
| `.github/workflows/cd.yml` | 打 `v*` 标签、手动 | 构建 `fileagent-starter` 可执行 jar，创建 GitHub Release |
| `.github/dependabot.yml` | 每周一自动 | Maven 依赖 + GitHub Actions 版本更新 PR |

## 3. CI 流水线细节（ci.yml）

```
checkout → setup JDK 21 (temurin, maven cache) → mvn -B -ntp verify → 失败时上传 surefire 报告
```

- `-B` 批处理模式（无进度条，日志干净）；`-ntp` 不打印时间戳。
- `mvn verify` 会走完编译 + 单元测试 + 集成测试（`@SpringBootTest`）。
- Spring AI milestone 仓库在 `pom.xml` 已配置，runner 可直接访问。
- 失败时把 `**/target/surefire-reports/` 作为 artifact 上传，保留 7 天，便于在 Actions 页面下载排查。

> 当前项目暂无测试类，`mvn verify` 等价于编译校验。协作者按 `docs/TESTING.md` 补测试后，CI 会自动跑起来。

## 4. CD 流水线细节（cd.yml）

```
打 v* 标签 → checkout → setup JDK 21 → mvn -pl fileagent-starter -am package -DskipTests → 创建 Release 上传 jar
```

**发版用法**：
```bash
git checkout master
git tag v0.1.0
git push origin v0.1.0
# CD 自动触发，几分钟后在 Releases 页面看到 jar 产物
```

- `-pl fileagent-starter -am`：只构建 starter 模块及其依赖模块，更快。
- `-DskipTests`：发版构建跳过测试（CI 已在合并前验过）。
- `generate_release_notes: true`：自动生成基于 commit 的变更说明。
- 产物：`fileagent-starter/target/fileagent-starter-0.1.0-SNAPSHOT.jar`。

## 5. Dependabot

- Maven 依赖（根 pom）每周一检查更新，最多开 5 个 PR，提交前缀 `chore(deps)`。
- GitHub Actions 版本同样每周一检查，提交前缀 `chore(ci)`。
- Spring AI 走 milestone 版本，Dependabot 会谨慎升级，PR 仍需人工确认。

## 6. 本地如何配合

- 本地 JDK 8 跑不了 `mvn`，所以**本地改完直接 push，让 CI 帮你验证编译**。
- 修 CI 配置时在 `ci-cd` 分支上改，push 后 CI 会自测（因为 ci.yml 监听 ci-cd 分支）。
- Actions 运行状态：`GitHub 仓库 → Actions 标签页`。

## 7. 后续演进

| 阶段 | 增加什么 |
|---|---|
| M2 | 加代码质量检查（spotless / checkstyle）步骤 |
| M3 | 加 Docker 镜像构建 + 推送 GHCR |
| M5 | 加部署到测试环境 / 蓝绿 |
