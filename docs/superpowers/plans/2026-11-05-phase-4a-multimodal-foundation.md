# Phase 4A 多模态基础实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为全局知识库建立不会同名覆盖的本地原件存储、PDF 页面视觉资产、内容块与页面关联、受控资产 API 和前端页面预览。

**架构：** 保留 `ParsedChunk -> KnowledgeChunk -> Elasticsearch` 主链路，用 metadata 增量表达内容类型和资产关联；新增 `rag_document_asset` 只保存视觉资产元数据，图片字节继续放在 `fileagent.storage-dir`。上传成功前资产不可见，删除仍按 ES、资产、原件、文件记录的顺序收敛。

**技术栈：** Java 21、Spring Boot 4.1、Spring MVC、Spring Data JPA、H2、PDFBox、Elasticsearch、原生 JavaScript/CSS、JUnit 5、Mockito。

---

## 文件结构

### 新增文件

- `fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/ContentType.java`：内容单元类型。
- `fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/DocumentAssetType.java`：视觉资产类型。
- `fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/BoundingBox.java`：归一化区域坐标。
- `fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/ContentLocation.java`：跨格式来源位置。
- `fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/DocumentAssetSummary.java`：不暴露存储键的资产摘要。
- `fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/DocumentAssetPage.java`：资产游标分页结果。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/domain/DocumentAssetEntity.java`：资产元数据持久化实体。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/domain/DocumentAssetRepository.java`：领域仓储契约。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/DocumentAssetJpaRepository.java`：Spring Data 接口。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/DocumentAssetRepositoryImpl.java`：JPA 适配器。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/MultimodalProperties.java`：页面渲染硬限制。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/PdfPageAssetRenderer.java`：PDF 页面图片生成。
- `fileagent-document/src/test/java/com/demetrius/fileagent/document/domain/ContentLocationTest.java`：位置约束测试。
- `fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/DocumentAssetRepositoryImplTest.java`：资产持久化和归属测试。
- `fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/StorageServiceTest.java`：唯一存储键和兼容读取测试。
- `fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/PdfPageAssetRendererTest.java`：真实 PDF 页面渲染和限额测试。
- `fileagent-document/src/test/java/com/demetrius/fileagent/document/interfaces/RagFileAssetControllerTest.java`：资产 API 归属、版本和分页测试。

### 修改文件

- `fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/RagFileSummary.java`：追加真实 `documentVersion`。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/StorageService.java`：唯一原件键、资产对象流式读写及安全删除。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/application/RagFileAppService.java`：增加资产列表和读取用例。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/application/RagFileAppServiceImpl.java`：上传、页面资产、metadata、失败清理和删除编排。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/ElasticsearchKnowledgeIndexInitializer.java`：新增 keyword mapping。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/ElasticsearchKnowledgeIndexRepository.java`：新增顶层检索字段。
- `fileagent-document/src/main/java/com/demetrius/fileagent/document/interfaces/RagFileController.java`：资产分页及图片流式读取接口。
- `fileagent-starter/src/main/resources/application.yml`：默认关闭的多模态页面渲染配置。
- `fileagent-starter/src/main/resources/static/index.html`：文档窗口新增页面图片区域。
- `fileagent-starter/src/main/resources/static/app.js`：资产分页、预览及分块关联。
- `fileagent-starter/src/main/resources/static/app.css`：页面图片布局与移动端样式。
- `fileagent-starter/src/test/java/com/demetrius/fileagent/StaticPageContractTest.java`：前端结构契约。
- `docs/API.md`、`docs/SKELETON.md`、`docs/TESTING.md`、`docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md`：接口、依赖、测试与状态同步。

---

### 任务 1：内容位置契约与资产持久化

**文件：**
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/ContentType.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/enums/DocumentAssetType.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/BoundingBox.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/ContentLocation.java`
- 创建：`fileagent-document/src/main/java/com/demetrius/fileagent/document/domain/DocumentAssetEntity.java`
- 创建：`fileagent-document/src/main/java/com/demetrius/fileagent/document/domain/DocumentAssetRepository.java`
- 创建：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/DocumentAssetJpaRepository.java`
- 创建：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/DocumentAssetRepositoryImpl.java`
- 测试：`fileagent-document/src/test/java/com/demetrius/fileagent/document/domain/ContentLocationTest.java`
- 测试：`fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/DocumentAssetRepositoryImplTest.java`

- [ ] **步骤 1：先写位置与仓储失败测试**

```java
assertThrows(IllegalArgumentException.class,
        () -> new BoundingBox(-0.1, 0, 0.2, 0.2));
assertEquals(List.of(page1), repository.findPage(fileId, version, -1, 20));
assertTrue(repository.findOwned(fileId, version, page1.getId()).isPresent());
assertTrue(repository.findOwned(otherFileId, version, page1.getId()).isEmpty());
```

- [ ] **步骤 2：运行定向测试并确认因类型不存在而失败**

运行：

```bash
mvn -pl fileagent-document -am test \
  -Dtest=ContentLocationTest,DocumentAssetRepositoryImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：FAIL，编译器报告 `BoundingBox` / `DocumentAssetEntity` 等类型不存在。

- [ ] **步骤 3：实现最小契约和实体**

`BoundingBox` 在紧凑构造中校验有限数值、`0 <= x,y < 1`、正宽高及右/下边界不超过 1。`ContentLocation` 提供 `pdfPage(int page)` 工厂。实体保存 UUID 字符串主键、`fileId/documentVersion/type/index/storageKey/mime/sha256/size/width/height/sourceAssetId/locationJson/parserVersion/createdAt`；所有实体字段写中文 Javadoc。

- [ ] **步骤 4：实现归属仓储并通过测试**

仓储方法固定为：

```java
DocumentAssetEntity save(DocumentAssetEntity asset);
List<DocumentAssetEntity> findPage(Long fileId, int version, int afterIndex, int limit);
Optional<DocumentAssetEntity> findOwned(Long fileId, int version, String assetId);
List<DocumentAssetEntity> findAllByFileId(Long fileId);
void delete(DocumentAssetEntity asset);
```

JPA 查询同时包含 `fileId + documentVersion + id`，分页按 `assetIndex,id` 稳定排序。

- [ ] **步骤 5：运行任务 1 测试**

预期：上述两个测试类 PASS，无 Hibernate Schema 错误。

---

### 任务 2：唯一原件键与受控资产对象存储

**文件：**
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/StorageService.java`
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/application/DocumentAppServiceImpl.java`
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/application/RagFileAppServiceImpl.java`
- 创建：`fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/StorageServiceTest.java`

- [ ] **步骤 1：先写同名文件与资产路径失败测试**

```java
StoredFile first = storage.store(file("same.pdf", "one"));
StoredFile second = storage.store(file("same.pdf", "two"));
assertNotEquals(first.relativePath(), second.relativePath());
assertArrayEquals("one".getBytes(UTF_8), Files.readAllBytes(storage.resolve(first.relativePath())));
assertThrows(BizException.class, () -> storage.resolve("../outside"));
```

增加资产写入、输入流读取、存在性和单对象删除测试；构造符号链接时验证 `toRealPath` 不允许逃逸，平台不支持符号链接时用 JUnit assumption 跳过该一项。

- [ ] **步骤 2：运行测试确认旧实现发生同名覆盖**

运行：

```bash
mvn -pl fileagent-document -am test \
  -Dtest=StorageServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：FAIL，两个 `relativePath` 相同或缺少资产方法。

- [ ] **步骤 3：实现唯一键和资产对象方法**

原件路径改为 `originals/yyyy/MM/dd/{uuid}/{safeName}`，使用 `CREATE_NEW`。增加服务端生成的 `storeAsset(fileId, version, assetId, mimeType, writer)`，写入临时对象后原子移动至最终键；`open/size/deleteIfUnreferenced` 只接受已规范化的相对键并验证实际父路径仍在存储根目录内。

- [ ] **步骤 4：修复会话附件去重的孤儿副本**

`DocumentAppServiceImpl.upload()` 在 SHA-256 命中旧记录时删除本次刚保存的唯一副本；删除前只处理本次 `StoredFile.relativePath()`，不碰既有记录。

- [ ] **步骤 5：运行存储、上传回归测试**

运行 `StorageServiceTest` 和现有 `DocumentAppServiceImplTest`、`RagFileAppServiceImplTest`；预期全部 PASS。

---

### 任务 3：PDF 页面资产真实渲染与硬限制

**文件：**
- 创建：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/MultimodalProperties.java`
- 创建：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/PdfPageAssetRenderer.java`
- 创建：`fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/PdfPageAssetRendererTest.java`
- 修改：`fileagent-starter/src/main/resources/application.yml`

- [ ] **步骤 1：用 PDFBox 生成真实测试 PDF 并先写失败测试**

测试创建普通页、旋转页、空白页，断言渲染结果页码、宽高、PNG MIME、归一化整页位置和累计大小；另测 `max-pages`、`max-page-pixels`、`max-total-asset-bytes` 超限抛受控 `BizException`。

- [ ] **步骤 2：运行测试确认渲染器不存在而失败**

运行 `PdfPageAssetRendererTest`，预期 FAIL。

- [ ] **步骤 3：实现默认关闭配置和逐页渲染**

`MultimodalProperties` 使用 `@ConfigurationProperties(prefix="fileagent.multimodal")`，默认：关闭、120 DPI、100 页、单页 4,000,000 像素、总资产 64 MiB。渲染器关闭或非 PDF 时返回空列表；开启后先检查页数与预计像素，逐页使用 `PDFRenderer`，PNG 写入累计限流输出流，不在列表中保留所有 `BufferedImage`。

- [ ] **步骤 4：运行任务 3 测试**

预期：普通、旋转、空白及超限测试全部 PASS，测试临时目录没有残留半成品。

---

### 任务 4：上传、ES 映射、失败清理与删除接入

**文件：**
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/application/RagFileAppServiceImpl.java`
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/ElasticsearchKnowledgeIndexInitializer.java`
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/infrastructure/ElasticsearchKnowledgeIndexRepository.java`
- 修改测试：`fileagent-document/src/test/java/com/demetrius/fileagent/document/application/RagFileAppServiceImplTest.java`
- 修改测试：`fileagent-document/src/test/java/com/demetrius/fileagent/document/infrastructure/ElasticsearchKnowledgeIndexRepositoryTest.java`

- [ ] **步骤 1：先写页面关联与父块隔离测试**

PDF 第 1、2 页分别生成 CHILD；断言 metadata 包含 `contentUnitId/contentType=TEXT/assetId/page/parserVersion`，且关联页码正确。PARENT 不包含子块的 `contentUnitId/assetId/location`。关闭开关时仍能按原链路索引且不创建资产。

- [ ] **步骤 2：先写失败清理与删除顺序测试**

索引写入失败时断言资产对象和资产行被清理、原件保留、文件标记 FAILED；删除时断言 ES 在资产和原件之前处理，资产对象删除失败时保留元数据和文件记录供重试。

- [ ] **步骤 3：运行测试确认新增行为失败**

运行 `RagFileAppServiceImplTest` 和 ES repository 测试；预期 metadata/资产断言失败。

- [ ] **步骤 4：实现上传关联和父块 metadata 收敛**

页面渲染返回 `page -> assetId`；构建 CHILD 时写入统一字段。生成 PARENT 时只保留共享的文档级 metadata 和既有父块检索字段，明确移除精确资产及内容单元字段。ES 顶层新增四个 keyword 字段并兼容旧索引 `putMapping`。

- [ ] **步骤 5：实现清理和引用保护删除**

应用服务按资产记录逐项删除；对象删除成功后删资产行。原件路径仍被其他 `rag_file` 或 `document` 记录引用时只删当前记录。任何非幂等 IO 失败中止删除，保留可重试事实。

- [ ] **步骤 6：运行任务 4 和文本检索回归**

运行 `RagFileAppServiceImplTest,KnowledgeSearchPortImplTest,RrfFusionTest,ElasticsearchKnowledgeIndexRepositoryTest`；预期全部 PASS。

---

### 任务 5：资产 API、分页、版本与内容流式响应

**文件：**
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/DocumentAssetSummary.java`
- 创建：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/DocumentAssetPage.java`
- 修改：`fileagent-api/src/main/java/com/demetrius/fileagent/api/dto/RagFileSummary.java`
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/application/RagFileAppService.java`
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/application/RagFileAppServiceImpl.java`
- 修改：`fileagent-document/src/main/java/com/demetrius/fileagent/document/interfaces/RagFileController.java`
- 创建：`fileagent-document/src/test/java/com/demetrius/fileagent/document/interfaces/RagFileAssetControllerTest.java`

- [ ] **步骤 1：先写 Controller 失败测试**

覆盖：21 个资产分两页读取、游标排他、limit 最大 50、真实 `documentVersion`、旧文件空页、跨 fileId/版本读取返回 404、非法参数 400、成功图片带固定 MIME/长度/`nosniff` 且不返回 `storageKey`。

- [ ] **步骤 2：运行测试确认路由不存在而失败**

运行 `RagFileAssetControllerTest`，预期 FAIL（404 或方法不存在）。

- [ ] **步骤 3：实现应用用例与 API**

`listAssets(fileId, version, afterIndex, limit)` 返回最多 limit 项和下一游标；读取接口返回内部 `StoredAsset`，Controller 用 `InputStreamResource` 流式响应。业务层先校验 `rag_file` 为 SUCCESS 且版本匹配，再读取资产。

- [ ] **步骤 4：运行任务 5 测试和 Controller 回归**

预期资产 API 与现有 `RagFileControllerTest` 全部 PASS。

---

### 任务 6：前端页面图片、文档和完整回归

**文件：**
- 修改：`fileagent-starter/src/main/resources/static/index.html`
- 修改：`fileagent-starter/src/main/resources/static/app.js`
- 修改：`fileagent-starter/src/main/resources/static/app.css`
- 修改：`fileagent-starter/src/test/java/com/demetrius/fileagent/StaticPageContractTest.java`
- 修改：`docs/API.md`
- 修改：`docs/SKELETON.md`
- 修改：`docs/TESTING.md`
- 修改：`docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md`

- [ ] **步骤 1：先扩展前端契约测试**

断言页面图片区域、分页按钮、资产内容 URL、真实 `documentVersion` 和“暂无页面图片”文案存在；旧记录版本为空时不请求资产接口。

- [ ] **步骤 2：运行 `StaticPageContractTest` 确认失败**

预期：FAIL，缺少页面资产 DOM 和脚本契约。

- [ ] **步骤 3：实现分页预览和分块关联**

文档窗口增加页面图片页签/区域；一次请求 20 项，按需加载缩略图，支持继续加载和大图查看。分块显示内容类型、页码和关联资产按钮；移动端使用稳定网格尺寸，文本不能覆盖图片。

- [ ] **步骤 4：同步项目文档**

`docs/API.md` 写清接口、版本必填、分页、错误和二进制响应；`SKELETON.md` 登记新类和 PDFBox 用途；`TESTING.md` 记录 4A 命令与浏览器验收；路线图将 4A 标为核心实现完成、待部署验收。

- [ ] **步骤 5：运行目标模块测试**

```bash
mvn -pl fileagent-starter -am test \
  -Dtest=ContentLocationTest,DocumentAssetRepositoryImplTest,StorageServiceTest,PdfPageAssetRendererTest,RagFileAppServiceImplTest,ElasticsearchKnowledgeIndexRepositoryTest,RagFileAssetControllerTest,StaticPageContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期：全部 PASS。

- [ ] **步骤 6：运行静态和完整模块验证**

```bash
node --check fileagent-starter/src/main/resources/static/app.js
git diff --check
mvn -pl fileagent-starter -am test
```

预期：JS 语法、Git 空白检查及 Maven 相关模块测试全部通过。

- [ ] **步骤 7：运行实际浏览器验收**

启动主应用后，上传两页 PDF，在桌面和移动视口验证原件、页面图片分页、分块关联、刷新和删除。上传同名不同内容 PDF，确认两份原件与页面资产 URL 返回不同内容；关闭 `fileagent.multimodal.enabled` 后文本 RAG 行为不变。

验收依赖 Elasticsearch、模型和浏览器环境；若当前环境未提供，记录为待目标部署执行，不用 Mock 浏览器结果代替。

---

## 计划自检

- 规格中的内容单元、资产表、唯一原件、页面渲染、限额、上传清理、删除、API、前端和兼容规则均有对应任务。
- 类型命名统一使用 `ContentType`、`DocumentAssetType`、`BoundingBox`、`ContentLocation`、`DocumentAssetEntity`。
- 4A 不实现 OCR、VLM、Office 图片抽取、视觉引用或对象存储迁移。
- 计划不包含实现占位符；每项行为都有先失败、再实现、再验证的路径。
