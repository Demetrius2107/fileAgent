package com.demetrius.fileagent.api.dto;

/**
 * 跨文档格式的来源位置；无法可靠获得的定位字段保持为空，不用其他序号代替。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
public record ContentLocation(
        Integer page,
        String sheetName,
        String cellRange,
        Integer slide,
        String anchorId,
        BoundingBox boundingBox) {

    public ContentLocation {
        if (page != null && page <= 0) {
            throw new IllegalArgumentException("page 必须从 1 开始");
        }
        if (slide != null && slide <= 0) {
            throw new IllegalArgumentException("slide 必须从 1 开始");
        }
        sheetName = blankToNull(sheetName);
        cellRange = blankToNull(cellRange);
        anchorId = blankToNull(anchorId);
    }

    /** 构造覆盖指定 PDF 整页的位置。 */
    public static ContentLocation pdfPage(int page) {
        return new ContentLocation(page, null, null, null, null, BoundingBox.fullPlane());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
