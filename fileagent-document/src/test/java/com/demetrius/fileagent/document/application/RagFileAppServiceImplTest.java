package com.demetrius.fileagent.document.application;

import com.demetrius.fileagent.api.dto.RagFileSummary;
import com.demetrius.fileagent.api.dto.ContentLocation;
import com.demetrius.fileagent.api.dto.DocumentAssetPage;
import com.demetrius.fileagent.api.enums.DocumentAssetType;
import com.demetrius.fileagent.api.enums.ParseStatus;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.document.domain.DocumentAssetEntity;
import com.demetrius.fileagent.document.domain.DocumentAssetRepository;
import com.demetrius.fileagent.document.domain.DocumentRepository;
import com.demetrius.fileagent.document.domain.KnowledgeChunk;
import com.demetrius.fileagent.document.domain.KnowledgeIndexRepository;
import com.demetrius.fileagent.document.domain.ParsedChunk;
import com.demetrius.fileagent.document.domain.RagFileEntity;
import com.demetrius.fileagent.document.domain.RagFileRepository;
import com.demetrius.fileagent.document.infrastructure.DocumentParser;
import com.demetrius.fileagent.document.infrastructure.DocumentParserRegistry;
import com.demetrius.fileagent.document.infrastructure.PdfPageAssetRenderer;
import com.demetrius.fileagent.document.infrastructure.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

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
import static org.mockito.Mockito.inOrder;
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

    @Mock
    private StorageService storageService;

    @Mock
    private DocumentAssetRepository documentAssetRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private PdfPageAssetRenderer pdfPageAssetRenderer;

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
        stubDedupMiss();
        stubStore();
        stubResolve();
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
        stubDedupMiss();
        stubStore();

        assertThatThrownBy(() -> ragFileAppService.storeRagFile("员工知识库", "制度",
                List.of(new MockMultipartFile("files", "virus.exe", null, "内容".getBytes()))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持");

        verify(parserRegistry, never()).findParser(anyString());
        verify(knowledgeIndexRepository).deleteByFileId(100L);
    }

    @Test
    void storeRagFileShouldRejectDuplicateContentInSameNameAndTag() {
        when(storageService.sha256(any(MultipartFile.class))).thenReturn("sha-256-value");
        when(ragFileRepository.existsByRagNameAndKnowledgeTagAndSha256AndStatus(
                "员工知识库", "制度", "sha-256-value", ParseStatus.SUCCESS)).thenReturn(true);

        assertThatThrownBy(() -> ragFileAppService.storeRagFile("员工知识库", "制度",
                List.of(new MockMultipartFile("files", "manual.txt", null, "内容".getBytes()))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内容相同");

        verify(ragFileRepository, never()).save(any(RagFileEntity.class));
        verify(storageService, never()).store(any());
        verify(knowledgeIndexRepository, never()).saveAll(any());
    }

    @Test
    void storeRagFileShouldPersistOriginalAndBackfillStorageFields() {
        stubSuccessSave();
        stubDedupMiss();
        stubStore();
        stubResolve();
        DocumentParser parser = mock(DocumentParser.class);
        when(parser.parseChunks(any(Path.class), anyString()))
                .thenReturn(List.of(ParsedChunk.text("知识片段")));
        when(parserRegistry.findParser(anyString())).thenReturn(Optional.of(parser));

        ragFileAppService.storeRagFile("员工知识库", "制度",
                List.of(new MockMultipartFile("files", "manual.txt", null, "内容".getBytes())));

        verify(storageService).resolve("2026/09/06/manual.txt");
        ArgumentCaptor<RagFileEntity> captor = ArgumentCaptor.forClass(RagFileEntity.class);
        verify(ragFileRepository, atLeast(3)).save(captor.capture());
        RagFileEntity backfilled = captor.getAllValues().get(1);
        assertThat(backfilled.getStoragePath()).isEqualTo("2026/09/06/manual.txt");
        assertThat(backfilled.getSha256()).isEqualTo("sha-256-value");
    }

    @Test
    void storeRagFileShouldWriteDeterministicChunksAndKeepGenericMetadata() {
        stubSuccessSave();
        stubDedupMiss();
        stubStore();
        stubResolve();
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
                .containsEntry("contentType", "TABLE")
                .containsEntry("contentUnitId", "100:0")
                .containsEntry("parserVersion", "document-parser-v1")
                .doesNotContainKeys("person", "year");
    }

    @Test
    void storeRagFileShouldPersistPdfPageAssetAndAssociateChildChunk() {
        stubSuccessSave();
        stubDedupMiss();
        stubStore();
        stubResolve();
        DocumentParser parser = mock(DocumentParser.class);
        when(parser.parseChunks(any(Path.class), anyString())).thenReturn(List.of(
                new ParsedChunk("第一页正文", Map.of("sourceType", "pdf", "page", 1))));
        when(parserRegistry.findParser("application/pdf")).thenReturn(Optional.of(parser));
        PdfPageAssetRenderer.RenderedPageAsset pageAsset = new PdfPageAssetRenderer.RenderedPageAsset(
                "asset-1", 1, "assets/100/v1/asset-1.png", "image/png", "asset-sha", 2048,
                1200, 1600, ContentLocation.pdfPage(1), "pdf-page-renderer-v1");
        when(pdfPageAssetRenderer.render(any(Path.class), anyString(), any(), any(Integer.class)))
                .thenReturn(List.of(pageAsset));
        when(documentAssetRepository.save(any(DocumentAssetEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ragFileAppService.storeRagFile("员工知识库", "制度",
                List.of(new MockMultipartFile("files", "manual.pdf", null, "pdf".getBytes())));

        ArgumentCaptor<DocumentAssetEntity> assetCaptor = ArgumentCaptor.forClass(DocumentAssetEntity.class);
        verify(documentAssetRepository).save(assetCaptor.capture());
        DocumentAssetEntity persisted = assetCaptor.getValue();
        assertThat(persisted.getId()).isEqualTo("asset-1");
        assertThat(persisted.getFileId()).isEqualTo(100L);
        assertThat(persisted.getDocumentVersion()).isEqualTo(1);
        assertThat(persisted.getAssetType()).isEqualTo(DocumentAssetType.PAGE);
        assertThat(persisted.getAssetIndex()).isEqualTo(1);
        assertThat(persisted.getLocationJson()).contains("\"page\":1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeIndexRepository).saveAll(chunksCaptor.capture());
        assertThat(chunksCaptor.getValue().getFirst().metadata())
                .containsEntry("contentUnitId", "100:0")
                .containsEntry("contentType", "TEXT")
                .containsEntry("assetId", "asset-1")
                .containsEntry("parserVersion", "document-parser-v1");
        assertThat(chunksCaptor.getValue().getFirst().metadata().get("location"))
                .isEqualTo(Map.of("page", 1));
    }

    @Test
    void storeRagFileShouldCreateOneParentChunkForExplicitChildGroup() {
        stubSuccessSave();
        stubDedupMiss();
        stubStore();
        stubResolve();
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
                .containsEntry("contentType", "TABLE")
                .doesNotContainKeys("parentId", "contentUnitId", "assetId", "location", "rowIndex", "page");
    }

    @Test
    void storeRagFileShouldCleanupIndexAndMarkFailedWhenIndexingFails() {
        stubSuccessSave();
        stubDedupMiss();
        stubStore();
        stubResolve();
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
    void storeRagFileShouldCleanupPersistedAssetsWhenIndexingFails() {
        stubSuccessSave();
        stubDedupMiss();
        stubStore();
        stubResolve();
        DocumentParser parser = mock(DocumentParser.class);
        when(parser.parseChunks(any(Path.class), anyString())).thenReturn(List.of(
                new ParsedChunk("第一页正文", Map.of("sourceType", "pdf", "page", 1))));
        when(parserRegistry.findParser("application/pdf")).thenReturn(Optional.of(parser));
        when(pdfPageAssetRenderer.render(any(Path.class), anyString(), any(), any(Integer.class)))
                .thenReturn(List.of(new PdfPageAssetRenderer.RenderedPageAsset(
                        "asset-1", 1, "assets/100/v1/asset-1.png", "image/png", "asset-sha", 2048,
                        1200, 1600, ContentLocation.pdfPage(1), "pdf-page-renderer-v1")));
        when(documentAssetRepository.save(any(DocumentAssetEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(documentAssetRepository.findOwned(100L, 1, "asset-1"))
                .thenAnswer(invocation -> Optional.of(assetEntity(
                        "asset-1", "assets/100/v1/asset-1.png")));
        doThrow(new IllegalStateException("ES unavailable"))
                .when(knowledgeIndexRepository).saveAll(any());

        assertThatThrownBy(() -> ragFileAppService.storeRagFile("员工知识库", "制度",
                List.of(new MockMultipartFile("files", "manual.pdf", null, "pdf".getBytes()))))
                .isInstanceOf(BizException.class);

        verify(storageService).delete("assets/100/v1/asset-1.png");
        verify(documentAssetRepository).delete(any(DocumentAssetEntity.class));
    }

    @Test
    void deleteRagFileShouldDeleteIndexThenOriginalThenRecord() {
        RagFileEntity entity = entity(7L, "员工知识库", "制度", "手册.pdf",
                ParseStatus.SUCCESS, 12, LocalDateTime.of(2026, 9, 1, 9, 0));
        entity.setStoragePath("2026/09/01/手册.pdf");
        when(ragFileRepository.findById(7L)).thenReturn(Optional.of(entity));

        ragFileAppService.deleteRagFile(7L);

        InOrder order = inOrder(knowledgeIndexRepository, storageService, ragFileRepository);
        order.verify(knowledgeIndexRepository).deleteByFileId(7L);
        order.verify(storageService).delete("2026/09/01/手册.pdf");
        order.verify(ragFileRepository).delete(entity);
    }

    @Test
    void deleteRagFileShouldDeleteAssetsBeforeOriginalAndRecord() {
        RagFileEntity entity = entity(7L, "员工知识库", "制度", "手册.pdf",
                ParseStatus.SUCCESS, 12, LocalDateTime.of(2026, 9, 1, 9, 0));
        entity.setStoragePath("originals/manual.pdf");
        DocumentAssetEntity asset = new DocumentAssetEntity();
        asset.setId("asset-1");
        asset.setStorageKey("assets/7/v1/asset-1.png");
        when(ragFileRepository.findById(7L)).thenReturn(Optional.of(entity));
        when(documentAssetRepository.findAllByFileId(7L)).thenReturn(List.of(asset));

        ragFileAppService.deleteRagFile(7L);

        InOrder order = inOrder(knowledgeIndexRepository, storageService,
                documentAssetRepository, ragFileRepository);
        order.verify(knowledgeIndexRepository).deleteByFileId(7L);
        order.verify(storageService).delete("assets/7/v1/asset-1.png");
        order.verify(documentAssetRepository).delete(asset);
        order.verify(storageService).delete("originals/manual.pdf");
        order.verify(ragFileRepository).delete(entity);
    }

    @Test
    void deleteRagFileShouldKeepOriginalStillReferencedByAnotherRecord() {
        RagFileEntity entity = entity(7L, "员工知识库", "制度", "手册.pdf",
                ParseStatus.SUCCESS, 12, LocalDateTime.of(2026, 9, 1, 9, 0));
        entity.setStoragePath("legacy/shared/manual.pdf");
        when(ragFileRepository.findById(7L)).thenReturn(Optional.of(entity));
        when(ragFileRepository.existsByStoragePathAndIdNot("legacy/shared/manual.pdf", 7L)).thenReturn(true);

        ragFileAppService.deleteRagFile(7L);

        verify(storageService, never()).delete("legacy/shared/manual.pdf");
        verify(ragFileRepository).delete(entity);
    }

    @Test
    void deleteRagFileShouldAbortBeforeFileAndRecordWhenIndexDeletionFails() {
        RagFileEntity entity = entity(7L, "员工知识库", "制度", "手册.pdf",
                ParseStatus.SUCCESS, 12, LocalDateTime.of(2026, 9, 1, 9, 0));
        entity.setStoragePath("2026/09/01/手册.pdf");
        when(ragFileRepository.findById(7L)).thenReturn(Optional.of(entity));
        doThrow(new BizException("Elasticsearch 清理知识索引失败"))
                .when(knowledgeIndexRepository).deleteByFileId(7L);

        assertThatThrownBy(() -> ragFileAppService.deleteRagFile(7L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Elasticsearch");

        verify(storageService, never()).delete(anyString());
        verify(ragFileRepository, never()).delete(any(RagFileEntity.class));
    }

    @Test
    void deleteRagFileShouldRejectMissingRecord() {
        when(ragFileRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ragFileAppService.deleteRagFile(9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");

        verify(knowledgeIndexRepository, never()).deleteByFileId(any());
        verify(ragFileRepository, never()).delete(any(RagFileEntity.class));
    }

    @Test
    void deleteRagFileShouldRejectWhileIndexing() {
        RagFileEntity entity = entity(7L, "员工知识库", "制度", "手册.pdf",
                ParseStatus.PARSING, 0, LocalDateTime.of(2026, 9, 1, 9, 0));
        when(ragFileRepository.findById(7L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> ragFileAppService.deleteRagFile(7L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("索引中");

        verify(knowledgeIndexRepository, never()).deleteByFileId(any());
        verify(ragFileRepository, never()).delete(any(RagFileEntity.class));
    }

    @Test
    void deleteRagFileShouldSkipOriginalDeletionWhenStoragePathBlank() {
        RagFileEntity entity = entity(7L, "员工知识库", "制度", "manual.txt",
                ParseStatus.SUCCESS, 3, LocalDateTime.of(2026, 9, 1, 9, 0));
        when(ragFileRepository.findById(7L)).thenReturn(Optional.of(entity));

        ragFileAppService.deleteRagFile(7L);

        verify(storageService, never()).delete(anyString());
        verify(knowledgeIndexRepository).deleteByFileId(7L);
        verify(ragFileRepository).delete(entity);
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
        assertThat(result.get(0).documentVersion()).isEqualTo(1);
    }

    @Test
    void listAssetsShouldUseExclusiveCursorAndFetchOneExtraItem() {
        RagFileEntity file = entity(7L, "员工知识库", "制度", "手册.pdf",
                ParseStatus.SUCCESS, 21, LocalDateTime.of(2026, 9, 1, 9, 0));
        file.setDocumentVersion(3);
        when(ragFileRepository.findById(7L)).thenReturn(Optional.of(file));
        List<DocumentAssetEntity> assets = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(index -> completeAsset("asset-" + index, 7L, 3, index))
                .toList();
        when(documentAssetRepository.findPage(7L, 3, -1, 21)).thenReturn(assets);
        when(documentAssetRepository.findPage(7L, 3, 20, 21)).thenReturn(List.of(assets.getLast()));

        DocumentAssetPage firstPage = ragFileAppService.listAssets(7L, 3, -1, 20);
        DocumentAssetPage secondPage = ragFileAppService.listAssets(7L, 3, 20, 20);

        assertThat(firstPage.items()).hasSize(20);
        assertThat(firstPage.items()).extracting(item -> item.assetIndex())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList());
        assertThat(firstPage.nextAssetIndex()).isEqualTo(20);
        assertThat(secondPage.items()).extracting(item -> item.assetIndex()).containsExactly(21);
        assertThat(secondPage.nextAssetIndex()).isNull();
        verify(documentAssetRepository).findPage(7L, 3, -1, 21);
        verify(documentAssetRepository).findPage(7L, 3, 20, 21);
    }

    @Test
    void loadAssetShouldOpenOwnedAssetAfterSizeValidation() throws Exception {
        RagFileEntity file = entity(7L, "员工知识库", "制度", "手册.pdf",
                ParseStatus.SUCCESS, 2, LocalDateTime.of(2026, 9, 1, 9, 0));
        file.setDocumentVersion(3);
        DocumentAssetEntity asset = completeAsset("asset-1", 7L, 3, 1);
        byte[] content = new byte[2048];
        when(ragFileRepository.findById(7L)).thenReturn(Optional.of(file));
        when(documentAssetRepository.findOwned(7L, 3, "asset-1")).thenReturn(Optional.of(asset));
        when(storageService.size(asset.getStorageKey())).thenReturn(2048L);
        when(storageService.open(asset.getStorageKey()))
                .thenReturn(new java.io.ByteArrayInputStream(content));

        RagFileAppService.DocumentAssetContent result = ragFileAppService.loadAsset(7L, 3, "asset-1");
        try (var inputStream = result.inputStream()) {
            assertThat(result.mimeType()).isEqualTo("image/png");
            assertThat(result.byteSize()).isEqualTo(2048L);
            assertThat(inputStream.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void loadAssetShouldRejectFileOrVersionMismatchBeforeOpeningStorage() {
        RagFileEntity file = entity(7L, "员工知识库", "制度", "手册.pdf",
                ParseStatus.SUCCESS, 2, LocalDateTime.of(2026, 9, 1, 9, 0));
        file.setDocumentVersion(3);
        when(ragFileRepository.findById(7L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> ragFileAppService.loadAsset(7L, 2, "asset-1"))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(404);

        verify(documentAssetRepository, never()).findOwned(any(), any(Integer.class), anyString());
        verify(storageService, never()).open(anyString());
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

    /** 去重未命中：指纹计算 + 仓储查重均放行（所有走到 storeOne 的用例都需要）。 */
    private void stubDedupMiss() {
        when(storageService.sha256(any(MultipartFile.class))).thenReturn("sha-256-value");
        when(ragFileRepository.existsByRagNameAndKnowledgeTagAndSha256AndStatus(
                anyString(), anyString(), anyString(), any(ParseStatus.class))).thenReturn(false);
    }

    /** 原件落盘桩（去重之后、格式校验之前发生，未知扩展名用例也会用到）。 */
    private void stubStore() {
        when(storageService.store(any(MultipartFile.class)))
                .thenReturn(new StorageService.StoredFile("2026/09/06/manual.txt", "sha-256-value"));
    }

    /** 落盘原件按相对路径读回（仅真正走到解析的用例需要）。 */
    private void stubResolve() {
        when(storageService.resolve(anyString())).thenReturn(Path.of("stored/manual.txt"));
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
        entity.setDocumentVersion(1);
        return entity;
    }

    private DocumentAssetEntity assetEntity(String id, String storageKey) {
        DocumentAssetEntity asset = new DocumentAssetEntity();
        asset.setId(id);
        asset.setStorageKey(storageKey);
        return asset;
    }

    private DocumentAssetEntity completeAsset(String id, Long fileId, int version, int index) {
        DocumentAssetEntity asset = assetEntity(id, "assets/" + fileId + "/v" + version + "/" + id + ".png");
        asset.setFileId(fileId);
        asset.setDocumentVersion(version);
        asset.setAssetType(DocumentAssetType.PAGE);
        asset.setAssetIndex(index);
        asset.setLocationJson("{\"page\":" + index + ",\"boundingBox\":{\"x\":0,\"y\":0,\"width\":1,\"height\":1}}");
        asset.setMimeType("image/png");
        asset.setWidth(1200);
        asset.setHeight(1600);
        asset.setByteSize(2048L);
        return asset;
    }

}
