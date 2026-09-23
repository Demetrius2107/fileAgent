package com.demetrius.fileagent.document.infrastructure.knowledge;

import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.document.domain.KnowledgeChunk;
import com.demetrius.fileagent.document.domain.KnowledgeIndexRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeContextPortImplTest {

    @Test
    void readShouldMapChunkToContextWithoutLeakingMetadata() {
        KnowledgeIndexRepository repo = mock(KnowledgeIndexRepository.class);
        KnowledgeChunk chunk = new KnowledgeChunk("c-1", 7L, "rag-a", "policy", "a.pdf", "正文内容", 3,
                Map.of("sheetName", "目标", "sectionId", "s-1", "parentId", "p-1", "chunkType", "CHILD"));
        when(repo.findByChunkIds(List.of("c-1"))).thenReturn(List.of(chunk));
        KnowledgeContextPortImpl port = new KnowledgeContextPortImpl(repo);

        List<KnowledgeContextPort.KnowledgeChunkContext> result = port.read(List.of("c-1"));

        assertThat(result).hasSize(1);
        KnowledgeContextPort.KnowledgeChunkContext ctx = result.getFirst();
        assertThat(ctx.chunkId()).isEqualTo("c-1");
        assertThat(ctx.fileId()).isEqualTo(7L);
        assertThat(ctx.filename()).isEqualTo("a.pdf");
        assertThat(ctx.content()).isEqualTo("正文内容");
        assertThat(ctx.sheetName()).isEqualTo("目标");
        assertThat(ctx.sectionId()).isEqualTo("s-1");
        assertThat(ctx.chunkIndex()).isEqualTo(3);
    }

    @Test
    void readShouldReturnEmptyForEmptyInput() {
        KnowledgeIndexRepository repo = mock(KnowledgeIndexRepository.class);
        KnowledgeContextPortImpl port = new KnowledgeContextPortImpl(repo);

        assertThat(port.read(List.of())).isEmpty();
    }

    @Test
    void outlineShouldReturnChildChunksInExclusivePages() {
        KnowledgeIndexRepository repo = mock(KnowledgeIndexRepository.class);
        when(repo.findByFileId(7L)).thenReturn(List.of(
                chunk("parent", 7L, 0, "PARENT", Map.of()),
                chunk("child-2", 7L, 2, "CHILD", Map.of("parentId", "p-1")),
                chunk("child-0", 7L, 0, "CHILD", Map.of(
                        "sourceType", "xlsx", "sheetName", "目标", "sectionId", "s-1", "rowIndex", 3)),
                chunk("child-1", 7L, 1, "CHILD", Map.of())
        ));
        KnowledgeContextPortImpl port = new KnowledgeContextPortImpl(repo);

        KnowledgeContextPort.DocumentOutlinePage first = port.outline(7L, -1, 2);
        KnowledgeContextPort.DocumentOutlinePage second = port.outline(
                7L, first.items().getLast().chunkIndex(), 2);

        assertThat(first.items()).extracting(KnowledgeContextPort.DocumentOutlineItem::chunkId)
                .containsExactly("child-0", "child-1");
        assertThat(first.nextChunkIndex()).isEqualTo(1);
        assertThat(first.items().getFirst()).satisfies(item -> {
            assertThat(item.parentId()).isNull();
            assertThat(item.sourceType()).isEqualTo("xlsx");
            assertThat(item.sheetName()).isEqualTo("目标");
            assertThat(item.sectionId()).isEqualTo("s-1");
            assertThat(item.rowIndex()).isEqualTo(3);
        });
        assertThat(second.items()).extracting(KnowledgeContextPort.DocumentOutlineItem::chunkId)
                .containsExactly("child-2");
        assertThat(second.nextChunkIndex()).isNull();
    }

    @Test
    void outlineShouldLimitToFiftyAndUseCodePointSafePreview() {
        KnowledgeIndexRepository repo = mock(KnowledgeIndexRepository.class);
        String content = "😀".repeat(161);
        List<KnowledgeChunk> chunks = IntStream.range(0, 55)
                .mapToObj(index -> chunk("child-" + index, 7L, index, "CHILD",
                        Map.of("sectionId", "section-" + index, "contentMarker", content)))
                .map(chunk -> new KnowledgeChunk(chunk.chunkId(), chunk.fileId(), chunk.ragName(),
                        chunk.knowledgeTag(), chunk.filename(), content, chunk.chunkIndex(), chunk.metadata()))
                .toList();
        when(repo.findByFileId(7L)).thenReturn(chunks);
        KnowledgeContextPortImpl port = new KnowledgeContextPortImpl(repo);

        KnowledgeContextPort.DocumentOutlinePage page = port.outline(7L, -1, 100);

        assertThat(page.items()).hasSize(50);
        assertThat(page.items().getFirst().preview()).isEqualTo("😀".repeat(160));
        assertThat(page.items().getFirst().preview().codePoints()).hasSize(160);
        assertThat(page.nextChunkIndex()).isEqualTo(49);
    }

    @Test
    void outlineShouldNotInventSectionWhenMetadataIsMissing() {
        KnowledgeIndexRepository repo = mock(KnowledgeIndexRepository.class);
        when(repo.findByFileId(7L)).thenReturn(List.of(
                chunk("child-0", 7L, 0, "CHILD", Map.of())
        ));
        KnowledgeContextPortImpl port = new KnowledgeContextPortImpl(repo);

        KnowledgeContextPort.DocumentOutlineItem item = port.outline(7L, -1, 10).items().getFirst();

        assertThat(item.sectionId()).isNull();
        assertThat(item.sheetName()).isNull();
        assertThat(item.rowIndex()).isNull();
        assertThat(item.preview()).isEqualTo("正文内容");
    }

    private KnowledgeChunk chunk(String chunkId, Long fileId, int chunkIndex, String chunkType,
                                 Map<String, Object> metadata) {
        Map<String, Object> allMetadata = new java.util.LinkedHashMap<>(metadata);
        allMetadata.put("chunkType", chunkType);
        return new KnowledgeChunk(chunkId, fileId, "rag-a", "policy", "a.pdf",
                "正文内容", chunkIndex, allMetadata);
    }
}
