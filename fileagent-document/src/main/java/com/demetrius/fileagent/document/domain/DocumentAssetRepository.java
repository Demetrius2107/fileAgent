package com.demetrius.fileagent.document.domain;

import java.util.List;
import java.util.Optional;

/**
 * 文档视觉资产仓储；所有单条读取都必须携带文件和版本归属条件。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
public interface DocumentAssetRepository {

    DocumentAssetEntity save(DocumentAssetEntity asset);

    List<DocumentAssetEntity> findPage(Long fileId, int documentVersion, int afterAssetIndex, int limit);

    Optional<DocumentAssetEntity> findOwned(Long fileId, int documentVersion, String assetId);

    List<DocumentAssetEntity> findAllByFileId(Long fileId);

    void delete(DocumentAssetEntity asset);
}
