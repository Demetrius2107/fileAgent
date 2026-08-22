package com.demetrius.fileagent.document.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块器：字符级滑窗切分，支持重叠。
 * <p>
 * 说明：Spring AI 2.0 的 {@code TokenTextSplitter} 已不再支持 chunkOverlap，
 * 且 {@code fileagent.chunk-size/chunk-overlap}（800/120）配置语义为字符数，
 * 因此这里用字符滑窗实现，保持与配置一致（参照实现中 TokenTextSplitter 的
 * 调用点即本类）。
 */
@Component
public class TextChunker {

    private final int chunkSize;
    private final int chunkOverlap;

    public TextChunker(@Value("${fileagent.chunk-size:800}") int chunkSize,
                       @Value("${fileagent.chunk-overlap:120}") int chunkOverlap) {
        this.chunkSize = Math.max(chunkSize, 1);
        this.chunkOverlap = Math.max(chunkOverlap, 0);
    }

    /**
     * 将文本切分为若干 chunk，相邻 chunk 之间重叠 {@code chunkOverlap} 个字符。
     */
    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return chunks;
        }
        String normalized = text.replace("\u0000", "");
        int step = Math.max(chunkSize - chunkOverlap, 1);
        for (int start = 0; start < normalized.length(); start += step) {
            int end = Math.min(start + chunkSize, normalized.length());
            String piece = normalized.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
        }
        return chunks;
    }
}
