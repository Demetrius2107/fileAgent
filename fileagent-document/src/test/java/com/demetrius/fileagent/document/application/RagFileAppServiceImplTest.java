package com.demetrius.fileagent.document.application;

import com.demetrius.fileagent.api.dto.RagFileSummary;
import com.demetrius.fileagent.api.enums.ParseStatus;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.document.domain.RagFileEntity;
import com.demetrius.fileagent.document.domain.RagFileRepository;
import com.demetrius.fileagent.document.infrastructure.DocumentParser;
import com.demetrius.fileagent.document.infrastructure.DocumentParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagFileAppServiceImpl} 用例测试：MIME 路由、未知格式拒绝与列表映射。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@ExtendWith(MockitoExtension.class)
class RagFileAppServiceImplTest {

    @Mock
    private DocumentParserRegistry parserRegistry;

    @Mock
    private RagFileRepository ragFileRepository;

    @Mock
    private org.springframework.ai.vectorstore.SimpleVectorStore vectorStore;

    @InjectMocks
    private RagFileAppServiceImpl ragFileAppService;

    @TempDir
    Path tempDir;

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
        when(parser.parse(any(Path.class), anyString())).thenReturn(List.of("知识片段"));
        when(parserRegistry.findParser(anyString())).thenReturn(Optional.of(parser));
        ReflectionTestUtils.setField(ragFileAppService, "vectorStorePath",
                tempDir.resolve("vectorstore.json").toString());

        ragFileAppService.storeRagFile("员工知识库", "制度",
                List.of(new MockMultipartFile("files", filename, null, "内容".getBytes())));

        ArgumentCaptor<String> mimeCaptor = ArgumentCaptor.forClass(String.class);
        verify(parserRegistry).findParser(mimeCaptor.capture());
        assertThat(mimeCaptor.getValue()).isEqualTo(expectedMime);
    }

    @Test
    void storeRagFileShouldRejectUnknownExtensionWithBizError() {
        stubSuccessSave();
        ReflectionTestUtils.setField(ragFileAppService, "vectorStorePath",
                tempDir.resolve("vectorstore.json").toString());

        assertThatThrownBy(() -> ragFileAppService.storeRagFile("员工知识库", "制度",
                List.of(new MockMultipartFile("files", "virus.exe", null, "内容".getBytes()))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持");

        verify(parserRegistry, never()).findParser(anyString());
        // 失败回滚：本次上传的记录不落库
        verify(ragFileRepository).delete(any(RagFileEntity.class));
    }

    @Test
    void storeRagFileShouldRollbackRecordWhenIndexingFails() {
        stubSuccessSave();
        DocumentParser parser = mock(DocumentParser.class);
        when(parser.parse(any(Path.class), anyString())).thenThrow(new RuntimeException("向量库故障"));
        when(parserRegistry.findParser(anyString())).thenReturn(Optional.of(parser));
        ReflectionTestUtils.setField(ragFileAppService, "vectorStorePath",
                tempDir.resolve("vectorstore.json").toString());

        assertThatThrownBy(() -> ragFileAppService.storeRagFile("员工知识库", "制度",
                List.of(new MockMultipartFile("files", "manual.txt", null, "内容".getBytes()))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("文件分块/向量化失败");

        // 索引失败：删除本次上传的记录，列表只保留索引成功的文件
        verify(ragFileRepository).delete(any(RagFileEntity.class));
    }

    @Test
    void storeRagFileShouldEmbedSemanticContextAndKeepRawContent() {
        stubSuccessSave();
        DocumentParser parser = mock(DocumentParser.class);
        String rawContent = "[OKR] Objective 1 | 快递产品线需求日常开发维护";
        when(parser.parse(any(Path.class), anyString())).thenReturn(List.of(rawContent));
        when(parserRegistry.findParser(anyString())).thenReturn(Optional.of(parser));
        ReflectionTestUtils.setField(ragFileAppService, "vectorStorePath",
                tempDir.resolve("vectorstore.json").toString());

        ragFileAppService.storeRagFile("个人年度目标", "OKR",
                List.of(new MockMultipartFile("files", "2025OKR-饶赛杰.xlsx", null, "内容".getBytes())));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).accept(captor.capture());
        Document document = captor.getValue().getFirst();
        assertThat(document.getText())
                .contains("知识库: 个人年度目标")
                .contains("标签: OKR")
                .contains("文件: 2025OKR-饶赛杰.xlsx")
                .contains("内容: " + rawContent);
        assertThat(document.getMetadata().get("rawContent")).isEqualTo(rawContent);
    }

    @Test
    void listShouldMapEntitiesToSummariesInRepositoryOrder() {
        RagFileEntity first = entity(1L, "员工知识库", "制度", "手册.pdf",
                ParseStatus.SUCCESS, 12, LocalDateTime.of(2026, 8, 26, 9, 0));
        RagFileEntity second = entity(2L, "项目知识库", "方案", "计划.docx",
                ParseStatus.PARSING, 0, LocalDateTime.of(2026, 8, 25, 9, 0));
        when(ragFileRepository.findAllOrderByCreatedAtDesc()).thenReturn(List.of(first, second));

        List<RagFileSummary> result = ragFileAppService.list();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(0).ragName()).isEqualTo("员工知识库");
        assertThat(result.get(0).knowledgeTag()).isEqualTo("制度");
        assertThat(result.get(0).filename()).isEqualTo("手册.pdf");
        assertThat(result.get(0).status()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(result.get(0).chunkCount()).isEqualTo(12);
        assertThat(result.get(0).createdAt()).isEqualTo("2026-08-26T09:00");
        assertThat(result.get(1).status()).isEqualTo(ParseStatus.PARSING);
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
