# Phase 2C 设计：结构化引用与引用验证

- 日期：2026-10-14
- 分支：`feat/m3-verifiable-citations`
- 需求来源：`docs/AGENTIC-RAG-ENTERPRISE-ROADMAP.md` §5.3 Phase 2C

## 1. 目标与范围

让每个关键事实可以定位到固定版本的原文件区域，并在回答后验证证据支持关系。本阶段落地 C1～C4（数据契约、解析与索引、引用映射、引用核验与落库）；评测数据集（citation-v1）与真实评测验收为后续独立批次。

## 2. 关键决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 引用产出方式 | 后端映射（路线 A）：模型保持 `[来源：文件名]` 文本标记，服务端映射到实际进入上下文的 chunk | 不约束模型自由文本，验证确定性强，与流式渲染兼容 |
| 核验失败行为 | 只标注状态（`QUOTE_MISMATCH` 等），不阻断回答交付 | 核验是事后审计，不应吞掉已生成的回答 |
| 文档版本语义 | `RagFileEntity.documentVersion` 从 1 起按同 name+tag+filename 替换上传递增；引用记录自带原文摘录（quote），删除旧文件后历史报告仍可追溯 | 现有"删除重传"是唯一更新路径；版本号只前进不回收，不伪造 |
| 旧索引兼容 | `page`/`documentVersion` 缺失时读为 null；启动时 `putMapping` 仅补 mapping 声明，不回填数据 | roadmap 明确"不伪造定位信息" |
| bbox | 本阶段不实现 | 验收门槛只要求定位到"固定版本的原文件区域"，chunkId+page 已满足；PDF 坐标留待需要时扩展 |

## 3. 三账分离

| 账 | 记录位置 | 内容 |
|---|---|---|
| 检索候选 | `AgentRun.RetrievalExecution.candidateChunkIds` | 每轮 search_docs 的合并池候选 |
| 最终上下文 | `AgentRun.ContextEvidence`（新增） | 实际渲染给模型的 chunk（检索摘要 + 精读全文，同 chunkId 保留更完整版本） |
| 最终引用 | `AgentRun.finalCitations` → `agent_run.citations_json` | 回答声明的来源映射到上下文证据后的结构化引用 + 核验结论 |

核心规则：**最终引用 ⊆ 最终上下文**。回答声明了从未进入上下文的来源 → `NOT_IN_CONTEXT`（伪造/缺失引用）。

## 4. 实现要点

- `api/dto/AgentCitation`：`fileId/documentVersion/chunkId/filename/page/sheetName/sectionId/chunkIndex/quote/quoteHash/status`；`CitationVerificationStatus` 四态枚举。
- `KnowledgeHit`/`KnowledgeChunkContext` 追加 `page`、`documentVersion`（带兼容旧构造）。
- `AgentRunEvent` 追加 `citations` 字段（仅 `sources` 事件携带；Phase 3A 的 Citation/Evidence 字段只追加不覆盖）。
- `PdfDocumentParser.parseChunks`：每页一个 chunk，metadata 带 `page`；空页跳过但页码保持真实值。
- `RagFileAppServiceImpl`：上传时计算 `nextDocumentVersion` 并写入 chunk metadata。
- ES `toSource` 写入 `page`/`documentVersion` 顶层字段；`ensureParentChildMapping` 补 mapping（旧文档字段缺失读 null）。
- `AgentCitationService`：来源标记正则抽取 → 映射上下文证据（单文件最多 3 chunk）→ 批量重读索引核验（quote 规范化 200 字符 + SHA-256）；核验基础设施异常按 `CHUNK_MISSING` 处理，不伪造核验通过。
- `AgentScopeRuntimeAdapter.complete()`：成功收尾前核验并写入 `finalCitations`，`sources` 事件携带结构化引用；核验异常不阻断收尾。
- `AgentRunFinalizationService`：`citations_json` 与助手消息、FINALIZATION Step、Run 成功状态同事务落库（序列化失败降级为空数组 + 告警，不回滚回答）。
- 前端 `app.js`：来源行下追加引用徽章（页码/sheet/片段/版本 + 核验状态），点击复用文档预览弹窗查看原文。

## 5. 已知边界与后续

- 旧索引（Phase 2C 前上传的文件）无 `page`/`documentVersion`，引用定位降级到 chunk 级；重传后自动补全。
- 被彻底删除的旧版本文件无法在界面上回看原文，历史追溯依赖 `citations_json` 中记录的版本号与 quote 摘录。
- Word/txt 无页码定位（`page=null`），Excel 用 sheet 定位。
- 评测缺口：`citation-v1` 数据集（含"文档更新后不引旧版本"、伪造引用场景）与对应指标（缺失引用率、伪造引用率、证据不支持率、版本一致性）未建，属后续批次。
- quote 为证据 chunk 开头 200 字符的规范化摘录，非模型实际引用句子——逐结论级 quote 需结构化输出，留待后续演进。
