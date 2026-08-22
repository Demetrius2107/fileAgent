package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.document.domain.DocumentEntity;
import com.demetrius.fileagent.document.domain.DocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 文档领域仓储的 JPA 实现：把领域契约委托给 Spring Data，仅做转发不写业务。
 * 领域契约见 {@link com.demetrius.fileagent.document.domain.DocumentRepository}。
 *
 * @author Demetrius
 * @since 0.1.0
 * @date 2026-08-22
 */
@Repository
@RequiredArgsConstructor
public class DocumentRepositoryImpl implements DocumentRepository {

    private final DocumentJpaRepository jpa;

    /**
     * 保存文档（新增或更新），返回带主键的持久化实体。
     *
     * @param document 待保存文档
     * @return 持久化后的文档
     */
    @Override
    public DocumentEntity save(DocumentEntity document) {
        return jpa.save(document);
    }

    /**
     * 按主键查询文档。
     *
     * @param id 文档主键
     * @return 命中则返回文档，否则返回空
     */
    @Override
    public Optional<DocumentEntity> findById(Long id) {
        return jpa.findById(id);
    }

    /**
     * 查询某会话下的全部文档（F1.1 列表接口用）。
     *
     * @param sessionId 会话 id
     * @return 该会话的文档列表，可能为空
     */
    @Override
    public List<DocumentEntity> findBySessionId(Long sessionId) {
        return jpa.findBySessionId(sessionId);
    }

    /**
     * 按内容指纹查询文档（F1.4 去重：sha256 命中则复用既有记录）。
     *
     * @param sha256 文件内容 sha256
     * @return 命中则返回既有文档，否则返回空
     */
    @Override
    public Optional<DocumentEntity> findBySha256(String sha256) {
        return jpa.findBySha256(sha256);
    }

    /**
     * 判断文档是否存在。
     *
     * @param id 文档主键
     * @return 存在返回 true
     */
    @Override
    public boolean existsById(Long id) {
        return jpa.existsById(id);
    }

    /**
     * 按主键删除文档。
     *
     * @param id 文档主键
     */
    @Override
    public void deleteById(Long id) {
        jpa.deleteById(id);
    }
}
