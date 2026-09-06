package com.demetrius.fileagent.document.domain;

import com.demetrius.fileagent.api.enums.ParseStatus;

import java.util.List;
import java.util.Optional;

/**
 * 知识库文件仓储（领域契约，纯接口）。
 * 由 fileagent-document 的 infrastructure 层提供 Spring Data JPA 实现。
 */
public interface RagFileRepository {

    RagFileEntity save(RagFileEntity ragFile);

    Optional<RagFileEntity> findById(Long id);

    List<RagFileEntity> findByKnowledgeTag(String knowledgeTag);

    /** 全部知识文件，按创建时间倒序（最新上传在前） */
    List<RagFileEntity> findAllOrderByCreatedAtDesc();

    /** 同一知识库名称+标签下是否已存在该内容指纹且索引成功的文件（上传去重） */
    boolean existsByRagNameAndKnowledgeTagAndSha256AndStatus(String ragName, String knowledgeTag,
                                                             String sha256, ParseStatus status);

    /** 删除记录（索引失败回滚用） */
    void delete(RagFileEntity ragFile);
}
