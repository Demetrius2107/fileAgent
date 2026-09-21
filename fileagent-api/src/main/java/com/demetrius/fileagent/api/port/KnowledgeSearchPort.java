package com.demetrius.fileagent.api.port;

import java.util.List;

/**
 * 全局知识检索端口（由 fileagent-document 的 infrastructure 实现）。
 * Chat 域检索知识片段必须走本接口，禁止直接依赖向量库或 document 域实体。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
public interface KnowledgeSearchPort {

    /**
     * 按查询词检索全局知识片段。
     *
     * @param query 用户问题
     * @return 命中片段列表（正文 + 来源文件名）
     */
    List<KnowledgeHit> search(String query);

    /**
     * 按文本与结构化条件检索知识片段。默认实现兼容仅支持语义检索的适配器。
     */
    default List<KnowledgeHit> search(SearchQuery query) {
        return search(query.text());
    }

    /**
     * 按文本与结构化条件检索并返回完整检索结果（候选 + 最终命中 + 实际生效参数）。
     * 默认实现把现有检索结果同时作为候选与最终命中，不携带策略信息。
     */
    default SearchResult searchDetailed(SearchQuery query) {
        List<KnowledgeHit> hits = search(query);
        return new SearchResult(hits, hits, null, false, null);
    }

    /** 通用检索条件。 */
    record SearchQuery(String text, String ragName, String knowledgeTag, Long fileId,
                       SearchOptions options) {

        /** 兼容无策略参数的调用方：四参构造，options 为空（沿用全局固定参数）。 */
        public SearchQuery(String text, String ragName, String knowledgeTag, Long fileId) {
            this(text, ragName, knowledgeTag, fileId, null);
        }

        public static SearchQuery of(String text) {
            return new SearchQuery(text, null, null, null);
        }
    }

    /**
     * 自适应检索策略参数（Phase 2A）：由服务端策略档位生成，Agent 不直接控制；
     * 为空表示沿用全局固定参数（Phase 1 行为）。
     */
    record SearchOptions(
            String strategyId,
            int bm25TopK,
            int knnTopK,
            int knnCandidates,
            double bm25Weight,
            double knnWeight,
            int finalTopK,
            boolean rerankEnabled,
            boolean parentExpansionEnabled) {
    }

    /** 检索结果：候选列表、最终命中、实际生效参数与降级码。 */
    record SearchResult(
            List<KnowledgeHit> candidates,
            List<KnowledgeHit> finalHits,
            SearchOptions appliedOptions,
            boolean rerankApplied,
            String fallbackCode) {
        public SearchResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            finalHits = finalHits == null ? List.of() : List.copyOf(finalHits);
        }
    }

    /** 知识命中，不暴露 Elasticsearch SDK 类型。 */
    record KnowledgeHit(
            String chunkId,
            Long fileId,
            String content,
            String filename,
            String sheetName,
            String sectionId,
            String parentId,
            int chunkIndex,
            double score) {
    }
}
