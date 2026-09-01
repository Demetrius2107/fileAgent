package com.demetrius.fileagent.document.application;

import com.demetrius.fileagent.api.dto.RagFileSummary;
import com.demetrius.fileagent.api.enums.ParseStatus;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.document.domain.KnowledgeChunk;
import com.demetrius.fileagent.document.domain.KnowledgeIndexRepository;
import com.demetrius.fileagent.document.domain.ParsedChunk;
import com.demetrius.fileagent.document.domain.RagFileEntity;
import com.demetrius.fileagent.document.domain.RagFileRepository;
import com.demetrius.fileagent.document.infrastructure.DocumentParser;
import com.demetrius.fileagent.document.infrastructure.DocumentParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagFileAppServiceImpl} 用例测试。
 *
 * @author raosaijie
 */
@ExtendWith(MockitoExtension.class)
class RagFileAppServiceImplTest {

    @Mock
    private DocumentParserRegistry parserRegistry;

    @Mock
    private RagFileRepository ragFileRepository;

    @Mock
    private KnowledgeIndexRepository knowledgeIndexRepository;

    @InjectMocks
    private RagFileAppServiceImpl ragFileAppService;

    @ParameterizedTest
    @CsvSource({
            "manual.txt,              text/plain",
            "manual.md,               text/markdown",
            "manual.markdown,         text/markdown",
            "manual.pdf,              application/pdf",
            "manual.docx,             application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "manual.xlsx,             application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "manual.csv,              text/csv",
    })
    void storeRagFileShouldRouteByExtensionToExactMime(String filename, String expectedMime) {
        stubSuccessSave();
        DocumentParser parser = mock(DocumentParser.class);
        when(parser.parseChunks(any(Path.class), anyString()))
                .thenReturn(List.of(ParsedChunk.text("知识片段")));
        when(parserRegistry.findParser(anyString())).thenReturn(Optional.of(parser));

        ragFileAppService.storeRagFile("员工知识库", "制度",
                List.of(new MockMultipartFile("files", filename, null, "内容".getBytes())));

        ArgumentCaptor<String> mimeCaptor = ArgumentCaptor.forClass(String.class);
        verify(parserRegistry).findParser(mimeCaptor.capture());
        assertThat(mimeCaptor.getValue()).isEqualTo(expectedMime);
    }

    @Test
    void storeRagFileShouldRejectUnknownExtensionWithBizError() {
        stubSuccessSave();

        assertThatThrownBy(() -> ragFileAppService.storeRagFile("员工知识库", "制度",
                List.of(new MockMultipartFile("files", "virus.exe", null, "内容".getBytes()))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持");

        verify(parserRegistry, never()).findParser(anyString());
        verify(knowledgeIndexRepository).deleteByFileId(100L);
    }

    @Test
    void storeRagFileShouldWriteDeterministicChunksAndKeepGenericMetadata() {
        stubSuccessSave();
        DocumentParser parser = mock(DocumentParser.class);
        ParsedChunk chunk = new ParsedChunk(
                "[目标表] 姓名: 张三 | 目标: 完成系统升级 | 权重: 40%",
                Map.of("sourceType", "xlsx", "sheetName", "目标表",
                        "rowIndex", 2, "sectionId", "sheet-0-section-0"));
        when(parser.parseChunks(any(Path.class), anyString())).thenReturn(List.of(chunk));
        when(parserRegistry.findParser(anyString())).thenReturn(Optional.of(parser));

        ragFileAppService.storeRagFile("年度计划", "目标",
                List.of(new MockMultipartFile("files", "2026-张三.xlsx", null, "内容".getBytes())));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeIndexRepository).saveAll(captor.capture());
        KnowledgeChunk indexed = captor.getValue().getFirst();
        assertThat(indexed.chunkId()).isEqualTo("100:0");
        assertThat(indexed.fileId()).isEqualTo(100L);
        assertThat(indexed.ragName()).isEqualTo("年度计划");
        assertThat(indexed.knowledgeTag()).isEqualTo("目标");
        assertThat(indexed.filename()).isEqualTo("2026-张三.xlsx");
        assertThat(indexed.metadata())
                .containsEntry("sourceType", "xlsx")
                .containsEntry("sheetName", "目标表")
                .containsEntry("rowIndex", 2)
                .containsEntry("sectionId", "sheet-0-section-0")
                .doesNotContainKeys("person", "year", "contentType");
    }

    @Test
    void storeRagFileShouldCreateOneParentChunkForExplicitChildGroup() {
        stubSuccessSave();
        DocumentParser parser = mock(DocumentParser.class);
        ParsedChunk first = new ParsedChunk("目标一", Map.of(
                "sourceType", "xlsx", "sheetName", "OKR", "rowIndex", 2,
                "sectionId", "sheet-0-section-0", "parentId", "sheet-0-section-0"));
        ParsedChunk second = new ParsedChunk("目标二", Map.of(
                "sourceType", "xlsx", "sheetName", "OKR", "rowIndex", 3,
                "sectionId", "sheet-0-section-0", "parentId", "sheet-0-section-0"));
        when(parser.parseChunks(any(Path.class), anyString())).thenReturn(List.of(first, second));
        when(parserRegistry.findParser(anyString())).thenReturn(Optional.of(parser));

        ragFileAppService.storeRagFile("年度计划", "目标",
                List.of(new MockMultipartFile("files", "2026.xlsx", null, "内容".getBytes())));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeIndexRepository).saveAll(captor.capture());
        List<KnowledgeChunk> indexed = captor.getValue();
        assertThat(indexed).hasSize(3);
        assertThat(indexed.get(0).metadata())
                .containsEntry("chunkType", "CHILD")
                .containsEntry("parentId", "100:parent:0");
        assertThat(indexed.get(1).metadata())
                .containsEntry("parentId", "100:parent:0");
        assertThat(indexed.get(2).chunkId()).isEqualTo("100:parent:0");
        assertThat(indexed.get(2).content()).isEqualTo("目标一\n目标二");
        assertThat(indexed.get(2).metadata())
                .containsEntry("chunkType", "PARENT")
                .doesNotContainKey("parentId");
    }

    @Test
    void storeRagFileShouldCleanupIndexAndMarkFailedWhenIndexingFails() {
        stubSuccessSave();
        DocumentParser parser = mock(DocumentParser.class);
        when(parser.parseChunks(any(Path.class), anyString()))
                .thenReturn(List.of(ParsedChunk.text("知识片段")));
        when(parserRegistry.findParser(anyString())).thenReturn(Optional.of(parser));
        doThrow(new IllegalStateException("ES unavailable"))
                .when(knowledgeIndexRepository).saveAll(any());

        assertThatThrownBy(() -> ragFileAppService.storeRagFile("员工知识库", "制度",
                List.of(new MockMultipartFile("files", "manual.txt", null, "内容".getBytes()))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("文件分块/索引失败");

        verify(knowledgeIndexRepository).deleteByFileId(100L);
        ArgumentCaptor<RagFileEntity> captor = ArgumentCaptor.forClass(RagFileEntity.class);
        verify(ragFileRepository, atLeast(2)).save(captor.capture());
        assertThat(captor.getAllValues().getLast().getStatus()).isEqualTo(ParseStatus.FAILED);
    }

    @Test
    void listShouldOnlyMapSuccessfulEntitiesToSummaries() {
        RagFileEntity first = entity(1L, "员工知识库", "制度", "手册.pdf",
                ParseStatus.SUCCESS, 12, LocalDateTime.of(2026, 8, 26, 9, 0));
        RagFileEntity second = entity(2L, "项目知识库", "方案", "计划.docx",
                ParseStatus.PARSING, 0, LocalDateTime.of(2026, 8, 25, 9, 0));
        when(ragFileRepository.findAllOrderByCreatedAtDesc()).thenReturn(List.of(first, second));

        List<RagFileSummary> result = ragFileAppService.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(0).ragName()).isEqualTo("员工知识库");
        assertThat(result.get(0).knowledgeTag()).isEqualTo("制度");
        assertThat(result.get(0).filename()).isEqualTo("手册.pdf");
        assertThat(result.get(0).status()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(result.get(0).chunkCount()).isEqualTo(12);
        assertThat(result.get(0).createdAt()).isEqualTo("2026-08-26T09:00");
    }

    private void stubSuccessSave() {
        when(ragFileRepository.save(any(RagFileEntity.class)))
                .thenAnswer(invocation -> {
                    RagFileEntity entity = invocation.getArgument(0);
                    if (entity.getId() == null) {
                        entity.setId(100L);
                    }
                    return entity;
                });
    }

    private RagFileEntity entity(Long id, String ragName, String tag, String filename,
                                 ParseStatus status, Integer chunkCount, LocalDateTime createdAt) {
        RagFileEntity entity = new RagFileEntity();
        entity.setId(id);
        entity.setRagName(ragName);
        entity.setKnowledgeTag(tag);
        entity.setFilename(filename);
        entity.setStatus(status);
        entity.setChunkCount(chunkCount);
        entity.setCreatedAt(createdAt);
        return entity;
    }
}
