package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.api.dto.ContentLocation;
import com.demetrius.fileagent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PDF 页面视觉资产生成器；逐页渲染并受页数、像素和累计字节硬限制约束。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfPageAssetRenderer {

    private static final String PDF_MIME = "application/pdf";
    private static final String PNG_MIME = "image/png";
    private static final String PARSER_VERSION = "pdf-page-renderer-v1";

    private final StorageService storageService;
    private final MultimodalProperties properties;

    /**
     * 将 PDF 的每个真实页面渲染成 PNG；任一页失败时清理本轮已经生成的页面对象。
     */
    public List<RenderedPageAsset> render(Path file, String mimeType, Long fileId, int documentVersion) {
        if (!properties.isEnabled() || !PDF_MIME.equalsIgnoreCase(mimeType)) {
            return List.of();
        }
        validateConfiguration();
        List<RenderedPageAsset> assets = new ArrayList<>();
        try (PDDocument document = PDDocument.load(file.toFile())) {
            if (document.isEncrypted()) {
                throw new BizException("加密 PDF 无法生成页面图片");
            }
            int pageCount = document.getNumberOfPages();
            if (pageCount > properties.getMaxPages()) {
                throw new BizException("PDF 页数超过页面图片限制: " + pageCount);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            long totalBytes = 0;
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                PDPage page = document.getPage(pageIndex);
                validateEstimatedPixels(page, pageIndex + 1);
                BufferedImage image = renderer.renderImageWithDPI(
                        pageIndex, properties.getRenderDpi(), ImageType.RGB);
                try {
                    validateActualPixels(image, pageIndex + 1);
                    long remainingBytes = properties.getMaxTotalAssetBytes() - totalBytes;
                    if (remainingBytes <= 0) {
                        throw new BizException("PDF 页面资产总字节超过限制");
                    }
                    String assetId = UUID.randomUUID().toString();
                    StorageService.StoredAsset stored = storageService.storeAsset(
                            fileId, documentVersion, assetId, "png",
                            output -> writePng(image, output, remainingBytes));
                    totalBytes += stored.byteSize();
                    assets.add(new RenderedPageAsset(
                            assetId,
                            pageIndex + 1,
                            stored.storageKey(),
                            PNG_MIME,
                            stored.sha256(),
                            stored.byteSize(),
                            image.getWidth(),
                            image.getHeight(),
                            ContentLocation.pdfPage(pageIndex + 1),
                            PARSER_VERSION));
                } finally {
                    image.flush();
                }
            }
            return List.copyOf(assets);
        } catch (BizException exception) {
            cleanup(assets);
            throw exception;
        } catch (IOException exception) {
            cleanup(assets);
            throw new BizException("PDF 页面图片生成失败: " + exception.getMessage());
        } catch (RuntimeException exception) {
            cleanup(assets);
            throw exception;
        }
    }

    private void validateConfiguration() {
        if (properties.getRenderDpi() <= 0
                || properties.getMaxPages() <= 0
                || properties.getMaxPagePixels() <= 0
                || properties.getMaxTotalAssetBytes() <= 0) {
            throw new IllegalStateException("fileagent.multimodal 页面渲染限制必须为正数");
        }
    }

    private void validateEstimatedPixels(PDPage page, int pageNumber) {
        PDRectangle box = page.getCropBox();
        double scale = properties.getRenderDpi() / 72.0;
        long width = Math.max(1, (long) Math.ceil(box.getWidth() * scale));
        long height = Math.max(1, (long) Math.ceil(box.getHeight() * scale));
        if (exceedsPixelLimit(width, height)) {
            throw new BizException("PDF 第 " + pageNumber + " 页预计像素超过限制");
        }
    }

    private void validateActualPixels(BufferedImage image, int pageNumber) {
        if (exceedsPixelLimit(image.getWidth(), image.getHeight())) {
            throw new BizException("PDF 第 " + pageNumber + " 页实际像素超过限制");
        }
    }

    private boolean exceedsPixelLimit(long width, long height) {
        return width > properties.getMaxPagePixels() / height;
    }

    private void writePng(BufferedImage image, OutputStream output, long maxBytes) throws IOException {
        LimitedOutputStream limited = new LimitedOutputStream(output, maxBytes);
        if (!ImageIO.write(image, "png", limited)) {
            throw new IOException("运行环境没有 PNG 编码器");
        }
        limited.flush();
    }

    private void cleanup(List<RenderedPageAsset> assets) {
        for (RenderedPageAsset asset : assets) {
            try {
                storageService.delete(asset.storageKey());
            } catch (RuntimeException exception) {
                log.warn("清理未完成的 PDF 页面资产失败: assetId={}, storageKey={}",
                        asset.assetId(), asset.storageKey(), exception);
            }
        }
    }

    /** PDF 页面渲染并写入本地存储后的受控结果。 */
    public record RenderedPageAsset(
            String assetId,
            int page,
            String storageKey,
            String mimeType,
            String sha256,
            long byteSize,
            int width,
            int height,
            ContentLocation location,
            String parserVersion) {
    }

    /** 达到累计输出上限即停止编码，避免先把整张 PNG 聚合到内存再检查。 */
    private static final class LimitedOutputStream extends FilterOutputStream {

        private final long maxBytes;
        private long written;

        private LimitedOutputStream(OutputStream output, long maxBytes) {
            super(output);
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            out.write(value);
            written++;
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            ensureCapacity(length);
            out.write(values, offset, length);
            written += length;
        }

        private void ensureCapacity(int bytes) throws IOException {
            if (bytes < 0 || written > maxBytes - bytes) {
                throw new IOException("PDF 页面资产总字节超过限制");
            }
        }
    }
}
