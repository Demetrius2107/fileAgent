# F1 上传模块 · 两人分工开发方案 (M1-UPLOAD-SPLIT)

> 适用里程碑：M1（闭环骨架）
> 目标：把 F1「文件接入模块」（F1.1–F1.4）拆成 **A / B 两条并行主线**，冲突最少、可独立验证、最后单点汇合。
> 阅读对象：Demetrius（A）与搭档（B）。
> 关联文档：`docs/PRD.md` §3.1（F1）、`docs/SKELETON.md` §2（分包）、`docs/TESTING.md`。

---

## 1. F1 需求 → 现有骨架映射

| 需求 | 说明 | 落在 document 域的类 |
|---|---|---|
| F1.1 | REST 上传接口，单/多文件 | `interfaces/FileController` + `application/DocumentAppService`（编排） |
| F1.2 | 格式支持：PDF/DOCX/XLSX/CSV/PPTX/TXT/MD/PNG/JPG | `infrastructure/ParserRegistry` + 各 `*Parser` 实现 |
| F1.3 | 落本地存储 + 登记元数据 | `infrastructure/StorageService` + `DocumentEntity` + Repository |
| F1.4 | sha256 去重，复用解析结果 | `StorageService`(算hash) + `DocumentRepository.findBySha256` |

**现状**：`DocumentAppService` / `DocumentParser` / `DocumentEntity` / `DocumentJpaRepository` / `FileController` 均已建好空壳（方法抛 `UnsupportedOperationException`），待两人填实现。

---

## 2. 核心原则

**按「包 / 类」切分，不按功能交叉切。**
存储侧与解析侧彼此无依赖，可完全并行；唯一的汇合点是 `application/DocumentAppServiceImpl`（编排）。

---

## 3. 分工方案

### 3.1 A —— 存储与元数据侧（F1.3 + F1.4 + F1.1 接口层）

| 文件 | 要做的事 |
|---|---|
| `infrastructure/StorageService.java`（新建） | 落盘到 `fileagent.storage-dir`，算 sha256，返回 `(storagePath, sha256)` |
| `infrastructure/DocumentJpaRepository`（已有接口） | 补 `findBySha256`（F1.4 去重用）、`findBySessionId` |
| `domain/DocumentRepository`（已有接口） | 补 `findBySha256` 领域契约 |
| `application/DocumentAppServiceImpl`（新建，**集成点**） | 编排：存储→sha256→查重→落库(PENDING)→调解析→更新状态→返回 |
| `interfaces/FileController`（已有空壳） | 实现 upload 端点：多文件循环、参数校验、包 `ApiResult` |

**A 独立可跑通**：存储 + 落库 + sha256 查重不依赖任何解析代码，先于 B 完成并可自测。

### 3.2 B —— 解析与索引侧（F1.2 + 建索引）

| 文件 | 要做的事 |
|---|---|
| `infrastructure/ParserRegistry.java`（新建） | 按 MIME 路由，收集各 `DocumentParser` |
| `infrastructure/TextParser.java` / `MarkdownParser.java`（新建） | F1.2 中的 TXT/MD（M1 先做这两个，PDF/Office 留 M2） |
| `infrastructure/VectorStoreService.java`（新建） | chunk → Embedding → SimpleVectorStore，供 chat 域 RAG |
| `infrastructure/DocumentParser`（已有接口） | 已定 `supports(mime)` + `parse(Path, mime)`，B 照契约实现 |

**B 依赖的只有约定**：输入 `(Path, mimeType)`、输出 `List<String>` chunks + 成功/失败。不碰数据库、不碰 Controller。

### 3.3 汇合点（F1.1 编排）：`DocumentAppServiceImpl`

唯一两人都要触碰的逻辑，**契约先行**，避免抢文件：

```
upload(sessionId, file):
  1. StorageService.store(file)                     → (storagePath, sha256)   [A]
  2. repo.findBySha256(sha256) → 命中则复用返回       [A]  ← F1.4
  3. 新建 DocumentEntity(PENDING) 落库               [A]
  4. ParserRegistry.parse(storagePath, mime)         → chunks                   [B]
  5. VectorStoreService.add(chunks)                 [B]  ← 供 F3 RAG
  6. 更新 parseStatus SUCCESS/FAILED，返回 UploadFileResp  [A]
```

**约定**：接口签名先由 A 一次性定稿并提交（`StorageService` / `DocumentAppService` / `DocumentParser` 三份接口），B 拿到接口即实现 Parser 系列，A 同时写存储 + 编排，互不阻塞。

---

## 4. 约定先行（开工前两人 30 分钟敲定）

1. **MIME 白名单**：各 `Parser.supports()` 自行认领，`ParserRegistry` 收集——避免 A 定清单 B 又改。
2. **chunk 元数据**：M1 简化，chunk 用 `List<String>` 即可（page/position 等 M2 再加）。
3. **异常契约**：解析失败抛 `BizException`，A 在编排 catch 置 `parseStatus=FAILED`。
4. **多文件返回**：`UploadFileResp` 单文件，Controller 层循环返回 `List<UploadFileResp>`。
5. **`ParseStatus` 流转**：`PENDING → PARSING → SUCCESS | FAILED`（枚举已定义，见 `api/enums/ParseStatus`）。

---

## 5. Git 协作细节（避冲突）

- 各开功能分支：A = `feat/m1-upload-storage`，B = `feat/m1-upload-parse`，均从 master 切。
- **顺序串行合入**：先合 A 的存储分支（编排在 A，master 先有 upload 主体），B 的解析分支再合。避免并行 merge 冲突。
- 两人可能共碰 `pom.xml`：仅 B 在 M2 加 tika/pdfbox 时；M1 不冲突。
- 合入前 `mvn test` + curl 手动验证一条链路。

---

## 6. 完成定义（双方验收标准）

```
POST /api/sessions/1/files (multipart "file")
→ 返回 UploadFileResp(parseStatus=PENDING→SUCCESS, chunkCount>0)
→ 再传同一文件 → 返回相同 documentId（sha256 命中，未重复解析）   ← F1.4 验收点
→ DB document 表有记录（filename/mime/size/sha256/storagePath）    ← F1.3 验收点
→ VectorStore 有新增 chunk，chat 域可检索                           ← B 验收点
```

---

## 7. 备注

- 本方案落于 master 分支 `docs/M1-UPLOAD-SPLIT.md`。
- 分工演示代码（parser 或 chat 之一）如需先跑通，可另行安排。
