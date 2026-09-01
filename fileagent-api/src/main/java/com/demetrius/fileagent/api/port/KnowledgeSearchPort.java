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

    /** 通用检索条件。 */
    record SearchQuery(String text, String answerMode,
                       String ragName, String knowledgeTag, Long fileId) {

        public static SearchQuery single(String text) {
            return new SearchQuery(text, "SINGLE", null, null, null);
        }

        public boolean listAll() {
            return "LIST_ALL".equalsIgnoreCase(answerMode);
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
            int chunkIndex,
            double score) {
    }
}
