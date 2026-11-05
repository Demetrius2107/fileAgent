package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.document.domain.DocumentAssetEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 文档视觉资产的 Spring Data 仓储，派生查询始终包含文件和版本归属。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
public interface DocumentAssetJpaRepository extends JpaRepository<DocumentAssetEntity, String> {

    List<DocumentAssetEntity> findByFileIdAndDocumentVersionAndAssetIndexGreaterThanOrderByAssetIndexAscIdAsc(
            Long fileId, Integer documentVersion, Integer afterAssetIndex, Pageable pageable);

    Optional<DocumentAssetEntity> findByIdAndFileIdAndDocumentVersion(
            String id, Long fileId, Integer documentVersion);

    List<DocumentAssetEntity> findByFileIdOrderByAssetIndexAscIdAsc(Long fileId);
}
