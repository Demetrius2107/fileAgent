package com.demetrius.fileagent.document.domain;

import com.demetrius.fileagent.api.dto.BoundingBox;
import com.demetrius.fileagent.api.dto.ContentLocation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多模态来源位置值对象的边界约束测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
class ContentLocationTest {

    @Test
    void boundingBoxShouldAcceptNormalizedRegion() {
        BoundingBox box = new BoundingBox(0.1, 0.2, 0.3, 0.4);

        assertThat(box.right()).isCloseTo(0.4, within(1e-12));
        assertThat(box.bottom()).isCloseTo(0.6, within(1e-12));
    }

    @Test
    void boundingBoxShouldRejectValuesOutsideSourcePlane() {
        assertThatThrownBy(() -> new BoundingBox(-0.1, 0.0, 0.2, 0.2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x");
        assertThatThrownBy(() -> new BoundingBox(0.9, 0.0, 0.2, 0.2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("右边界");
        assertThatThrownBy(() -> new BoundingBox(0.0, 0.0, Double.NaN, 0.2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有限数值");
    }

    @Test
    void pdfPageShouldKeepRealOneBasedPageAndFullPageRegion() {
        ContentLocation location = ContentLocation.pdfPage(3);

        assertThat(location.page()).isEqualTo(3);
        assertThat(location.boundingBox()).isEqualTo(BoundingBox.fullPlane());
        assertThat(location.sheetName()).isNull();
        assertThat(location.slide()).isNull();
    }

    @Test
    void contentLocationShouldRejectNonPositivePageAndSlide() {
        assertThatThrownBy(() -> ContentLocation.pdfPage(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page");
        assertThatThrownBy(() -> new ContentLocation(null, null, null, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slide");
    }
}
