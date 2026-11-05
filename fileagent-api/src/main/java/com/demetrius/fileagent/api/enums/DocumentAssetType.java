package com.demetrius.fileagent.api.enums;

/**
 * 文档视觉资产类型，用于区分整页、局部区域和文档内嵌图片。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
public enum DocumentAssetType {

    /** PDF 或演示文稿的完整页面图。 */
    PAGE,

    /** 从来源页面裁剪出的局部区域。 */
    REGION,

    /** Office 文档中直接嵌入的原始图片。 */
    EMBEDDED_IMAGE
}
