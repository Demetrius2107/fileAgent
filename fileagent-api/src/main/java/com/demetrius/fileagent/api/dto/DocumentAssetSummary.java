package com.demetrius.fileagent.api.dto;

import com.demetrius.fileagent.api.enums.DocumentAssetType;

/**
 * 文档视觉资产摘要；只暴露展示事实，不返回内部存储键。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
public record DocumentAssetSummary(
        String assetId,
        Integer documentVersion,
        DocumentAssetType assetType,
        Integer assetIndex,
        ContentLocation location,
        String mimeType,
        Integer width,
        Integer height,
        Long byteSize) {
}
