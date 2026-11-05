package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.common.exception.BizException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PDF 页面资产真实渲染、旋转处理和资源硬限制测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
class PdfPageAssetRendererTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final String PDF_MIME = "application/pdf";

    @TempDir
    Path tempDir;

    @Test
    void renderShouldCreateOnePngAssetPerRealPdfPageIncludingBlankAndRotatedPages() throws Exception {
        Path pdf = createPdf(3, true);
        MultimodalProperties properties = enabledProperties();
        PdfPageAssetRenderer renderer = new PdfPageAssetRenderer(
                new StorageService(tempDir.resolve("storage").toString()), properties);

        List<PdfPageAssetRenderer.RenderedPageAsset> assets = renderer.render(pdf, PDF_MIME, 12L, 3);

        assertThat(assets).hasSize(3);
        assertThat(assets).extracting(PdfPageAssetRenderer.RenderedPageAsset::page)
                .containsExactly(1, 2, 3);
        assertThat(assets).allSatisfy(asset -> {
            assertThat(asset.mimeType()).isEqualTo("image/png");
            assertThat(asset.width()).isPositive();
            assertThat(asset.height()).isPositive();
            assertThat(asset.byteSize()).isPositive();
            assertThat(asset.location().page()).isEqualTo(asset.page());
            assertThat(tempDir.resolve("storage").resolve(asset.storageKey())).exists();
        });
        assertThat(assets.get(1).width()).isGreaterThan(assets.get(1).height());
    }

    @Test
    void renderShouldReturnEmptyWhenFeatureIsDisabledOrFileIsNotPdf() throws Exception {
        Path pdf = createPdf(1, false);
        MultimodalProperties properties = enabledProperties();
        properties.setEnabled(false);
        PdfPageAssetRenderer renderer = new PdfPageAssetRenderer(
                new StorageService(tempDir.resolve("storage").toString()), properties);

        assertThat(renderer.render(pdf, PDF_MIME, 12L, 1)).isEmpty();
        properties.setEnabled(true);
        assertThat(renderer.render(pdf, "text/plain", 12L, 1)).isEmpty();
    }

    @Test
    void renderShouldRejectPdfAbovePageLimitBeforeWritingAssets() throws Exception {
        Path pdf = createPdf(2, false);
        MultimodalProperties properties = enabledProperties();
        properties.setMaxPages(1);
        Path storage = tempDir.resolve("storage");
        PdfPageAssetRenderer renderer = new PdfPageAssetRenderer(new StorageService(storage.toString()), properties);

        assertThatThrownBy(() -> renderer.render(pdf, PDF_MIME, 12L, 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("页数");
        assertThat(countRegularFiles(storage)).isZero();
    }

    @Test
    void renderShouldRejectPageAbovePixelLimitBeforeRendering() throws Exception {
        Path pdf = createPdf(1, false);
        MultimodalProperties properties = enabledProperties();
        properties.setMaxPagePixels(100);
        Path storage = tempDir.resolve("storage");
        PdfPageAssetRenderer renderer = new PdfPageAssetRenderer(new StorageService(storage.toString()), properties);

        assertThatThrownBy(() -> renderer.render(pdf, PDF_MIME, 12L, 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("像素");
        assertThat(countRegularFiles(storage)).isZero();
    }

    @Test
    void renderShouldDeletePartialAssetsWhenTotalBytesLimitIsExceeded() throws Exception {
        Path pdf = createPdf(2, false);
        MultimodalProperties properties = enabledProperties();
        properties.setMaxTotalAssetBytes(1);
        Path storage = tempDir.resolve("storage");
        PdfPageAssetRenderer renderer = new PdfPageAssetRenderer(new StorageService(storage.toString()), properties);

        assertThatThrownBy(() -> renderer.render(pdf, PDF_MIME, 12L, 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("资产");
        assertThat(countRegularFiles(storage)).isZero();
    }

    @Test
    void renderShouldReportCorruptedPdfAsControlledBusinessError() throws Exception {
        Path invalid = tempDir.resolve("invalid.pdf");
        Files.writeString(invalid, "not-a-pdf");
        PdfPageAssetRenderer renderer = new PdfPageAssetRenderer(
                new StorageService(tempDir.resolve("storage").toString()), enabledProperties());

        assertThatThrownBy(() -> renderer.render(invalid, PDF_MIME, 12L, 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("页面图片");
    }

    private Path createPdf(int pages, boolean rotateSecondPage) throws Exception {
        Path file = tempDir.resolve("sample-" + pages + ".pdf");
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < pages; page++) {
                PDPage pdPage = new PDPage(new PDRectangle(240, 320));
                if (rotateSecondPage && page == 1) {
                    pdPage.setRotation(90);
                }
                document.addPage(pdPage);
            }
            document.save(file.toFile());
        }
        return file;
    }

    private MultimodalProperties enabledProperties() {
        MultimodalProperties properties = new MultimodalProperties();
        properties.setEnabled(true);
        properties.setRenderDpi(72);
        properties.setMaxPages(10);
        properties.setMaxPagePixels(1_000_000);
        properties.setMaxTotalAssetBytes(10_000_000);
        return properties;
    }

    private long countRegularFiles(Path directory) throws Exception {
        if (Files.notExists(directory)) {
            return 0;
        }
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }
}
