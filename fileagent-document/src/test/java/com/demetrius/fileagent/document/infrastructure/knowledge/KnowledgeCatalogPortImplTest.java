package com.demetrius.fileagent.document.infrastructure.knowledge;

import com.demetrius.fileagent.api.enums.ParseStatus;
import com.demetrius.fileagent.api.port.KnowledgeCatalogPort;
import com.demetrius.fileagent.document.domain.RagFileEntity;
import com.demetrius.fileagent.document.domain.RagFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeCatalogPortImplTest {

    private RagFileRepository repository;
    private KnowledgeCatalogPortImpl port;

    @BeforeEach
    void setUp() {
        repository = mock(RagFileRepository.class);
        port = new KnowledgeCatalogPortImpl(repository);
    }

    @Test
    void listShouldReturnOnlySuccessfulFiles() {
        RagFileEntity ok = file(1L, "rag-a", "policy", "a.pdf", ParseStatus.SUCCESS, 5);
        RagFileEntity failed = file(2L, "rag-a", "policy", "b.pdf", ParseStatus.FAILED, 3);
        when(repository.findAllOrderByCreatedAtDesc()).thenReturn(List.of(ok, failed));

        List<KnowledgeCatalogPort.KnowledgeFile> files =
                port.list(new KnowledgeCatalogPort.Query("rag-a", "policy", 20));

        assertThat(files).hasSize(1);
        assertThat(files.getFirst().fileId()).isEqualTo(1L);
        assertThat(files.getFirst().chunkCount()).isEqualTo(5);
        assertThat(files.getFirst().status()).isEqualTo(ParseStatus.SUCCESS);
    }

    @Test
    void listShouldApplyScopeAndCapResultAtTwenty() {
        when(repository.findAllOrderByCreatedAtDesc()).thenReturn(IntStream.range(0, 30)
                .mapToObj(i -> file((long) i, i % 2 == 0 ? "rag-a" : "rag-b", "tag", "f" + i, ParseStatus.SUCCESS, 1))
                .toList());

        List<KnowledgeCatalogPort.KnowledgeFile> files =
                port.list(new KnowledgeCatalogPort.Query("rag-a", null, 20));

        assertThat(files).hasSizeLessThanOrEqualTo(20);
        assertThat(files).allSatisfy(f -> assertThat(f.ragName()).isEqualTo("rag-a"));
    }

    @Test
    void listShouldNotLeakStoragePath() {
        RagFileEntity ok = file(1L, "rag-a", "policy", "a.pdf", ParseStatus.SUCCESS, 1);
        ok.setStoragePath("/secret/path/a.pdf");
        when(repository.findAllOrderByCreatedAtDesc()).thenReturn(List.of(ok));

        List<KnowledgeCatalogPort.KnowledgeFile> files =
                port.list(new KnowledgeCatalogPort.Query("rag-a", "policy", 20));

        assertThat(files.getFirst().filename()).isEqualTo("a.pdf");
    }

    private RagFileEntity file(Long id, String rag, String tag, String name, ParseStatus status, int chunks) {
        RagFileEntity e = new RagFileEntity();
        e.setId(id);
        e.setRagName(rag);
        e.setKnowledgeTag(tag);
        e.setFilename(name);
        e.setStatus(status);
        e.setChunkCount(chunks);
        return e;
    }
}
