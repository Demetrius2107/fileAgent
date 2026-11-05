package com.demetrius.fileagent.document.application;

import com.demetrius.fileagent.api.enums.ParseStatus;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.document.domain.DocumentEntity;
import com.demetrius.fileagent.document.domain.DocumentRepository;
import com.demetrius.fileagent.document.infrastructure.ParserRegistry;
import com.demetrius.fileagent.document.infrastructure.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话附件上传编排测试，重点验证唯一键存储后的去重清理约束。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
class DocumentAppServiceImplTest {

    @Test
    void duplicateUploadShouldDeleteNewUnreferencedStoredCopy() {
        StorageService storageService = mock(StorageService.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        ParserRegistry parserRegistry = mock(ParserRegistry.class);
        SessionQueryPort sessionQueryPort = mock(SessionQueryPort.class);
        DocumentAppServiceImpl service = new DocumentAppServiceImpl(
                storageService, documentRepository, parserRegistry, sessionQueryPort);
        MockMultipartFile file = new MockMultipartFile(
                "file", "manual.pdf", "application/pdf", "same-content".getBytes());
        StorageService.StoredFile stored = new StorageService.StoredFile(
                "originals/2026/11/05/new/manual.pdf", "same-sha");
        DocumentEntity existing = new DocumentEntity();
        existing.setId(9L);
        existing.setFilename("manual.pdf");
        existing.setMimeType("application/pdf");
        existing.setParseStatus(ParseStatus.SUCCESS);

        when(sessionQueryPort.exists(1L)).thenReturn(true);
        when(storageService.store(file)).thenReturn(stored);
        when(documentRepository.findBySha256("same-sha")).thenReturn(Optional.of(existing));

        service.upload(1L, file);

        verify(storageService).delete(stored.relativePath());
    }
}
