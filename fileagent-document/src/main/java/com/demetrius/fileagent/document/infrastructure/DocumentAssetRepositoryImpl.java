package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.document.domain.DocumentAssetEntity;
import com.demetrius.fileagent.document.domain.DocumentAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 文档视觉资产仓储的 JPA 适配器；分页游标采用排他的资产序号。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
@Repository
@RequiredArgsConstructor
public class DocumentAssetRepositoryImpl implements DocumentAssetRepository {

    private final DocumentAssetJpaRepository jpaRepository;

    @Override
    public DocumentAssetEntity save(DocumentAssetEntity asset) {
        return jpaRepository.save(asset);
    }

    @Override
    public List<DocumentAssetEntity> findPage(Long fileId, int documentVersion,
                                               int afterAssetIndex, int limit) {
        if (fileId == null || documentVersion <= 0 || limit <= 0) {
            return List.of();
        }
        return jpaRepository
                .findByFileIdAndDocumentVersionAndAssetIndexGreaterThanOrderByAssetIndexAscIdAsc(
                        fileId, documentVersion, afterAssetIndex, PageRequest.of(0, limit));
    }

    @Override
    public Optional<DocumentAssetEntity> findOwned(Long fileId, int documentVersion, String assetId) {
        if (fileId == null || documentVersion <= 0 || assetId == null || assetId.isBlank()) {
            return Optional.empty();
        }
        return jpaRepository.findByIdAndFileIdAndDocumentVersion(assetId, fileId, documentVersion);
    }

    @Override
    public List<DocumentAssetEntity> findAllByFileId(Long fileId) {
        return fileId == null ? List.of() : jpaRepository.findByFileIdOrderByAssetIndexAscIdAsc(fileId);
    }

    @Override
    public void delete(DocumentAssetEntity asset) {
        jpaRepository.delete(asset);
    }
}
