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

    /** 知识命中（不暴露向量库 Document 结构） */
    record KnowledgeHit(String content, String filename) {
    }
}
