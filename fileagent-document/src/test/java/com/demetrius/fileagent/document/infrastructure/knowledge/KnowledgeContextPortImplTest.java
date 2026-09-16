package com.demetrius.fileagent.document.infrastructure.knowledge;

import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.document.domain.KnowledgeChunk;
import com.demetrius.fileagent.document.domain.KnowledgeIndexRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
