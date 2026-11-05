package com.demetrius.fileagent.api.dto;

import java.util.List;

/**
 * 文档视觉资产游标分页；下一游标为空表示已经读取完毕。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
public record DocumentAssetPage(
        List<DocumentAssetSummary> items,
        Integer nextAssetIndex) {

    public DocumentAssetPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
