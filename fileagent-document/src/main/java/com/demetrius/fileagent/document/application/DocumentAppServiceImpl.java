package com.demetrius.fileagent.document.application;

import com.demetrius.fileagent.api.dto.DocumentSummary;
import com.demetrius.fileagent.api.dto.UploadFileResp;
import com.demetrius.fileagent.api.enums.ParseStatus;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.document.domain.DocumentEntity;
import com.demetrius.fileagent.document.domain.DocumentRepository;
import com.demetrius.fileagent.document.infrastructure.ParserRegistry;
import com.demetrius.fileagent.document.infrastructure.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档应用服务实现：编排上传流程（校验会话 → 落盘+sha256 → 查重 → 落库 → 解析 → 状态流转）。
 * 跨域校验会话走 SessionQueryPort；解析与向量索引由 B 侧补充（留接入点）。
 *
 * @author Demetrius
 * @since 0.1.0
 * @date 2026-08-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentAppServiceImpl implements DocumentAppService {

    private final StorageService storageService;
    private final DocumentRepository documentRepository;
    private final ParserRegistry parserRegistry;
    private final SessionQueryPort sessionQueryPort;

    /**
     * 上传单个文件：落盘 → sha256 查重 → 落库 → 解析 → 更新状态。
     * <p>F1.4 去重语义：sha256 命中时直接返回既有记录、不重复解析，故 chunkCount 回 0。
     * 无对应解析器时保持 PENDING（A 侧可独立验证，B 侧 parser 注册后自动接上）。
     *
     * @param sessionId 目标会话 id
     * @param file       上传文件（不允许为空）
     * @return 上传结果（含 documentId / 解析状态 / 分块数）
     * @throws BizException 会话不存在 / 文件为空 / 存储失败时
     */
    @Override
    @Transactional
    public UploadFileResp upload(Long sessionId, MultipartFile file) {
        // 1. 校验会话存在（跨域走 port）
        if (!sessionQueryPort.exists(sessionId)) {
            throw new BizException(404, "会话不存在: " + sessionId);
        }

        // 2. 落盘 + 计算 sha256
        StorageService.StoredFile stored = storageService.store(file);

        // 3. F1.4 去重：sha256 命中则复用既有记录，不重复解析
        var existing = documentRepository.findBySha256(stored.sha256());
        if (existing.isPresent()) {
            DocumentEntity doc = existing.get();
            log.info("文件命中 sha256 去重，复用 documentId={}", doc.getId());
            return toResp(doc, 0);
        }

        // 4. 登记元数据，状态 PENDING
        DocumentEntity doc = new DocumentEntity();
        doc.setSessionId(sessionId);
        doc.setFilename(file.getOriginalFilename());
        doc.setMimeType(file.getContentType());
        doc.setSize(file.getSize());
        doc.setSha256(stored.sha256());
        doc.setStoragePath(stored.relativePath());
        doc.setParseStatus(ParseStatus.PENDING);
        documentRepository.save(doc);

        // 5. 解析（B 侧实现具体 parser；无 parser 时保持 PENDING，A 可独立验证）
        int chunkCount = 0;
        if (parserRegistry.supports(doc.getMimeType())) {
            doc.setParseStatus(ParseStatus.PARSING);
            documentRepository.save(doc);
            try {
                var chunks = parserRegistry.parse(storageService.resolve(doc.getStoragePath()), doc.getMimeType());
                chunkCount = chunks.size();
                doc.setParseStatus(ParseStatus.SUCCESS);
                // TODO(B): VectorStoreService.add(chunks, doc.getId()) —— F3.2 索引接入点
            } catch (Exception e) {
                log.error("解析失败 documentId={}", doc.getId(), e);
                doc.setParseStatus(ParseStatus.FAILED);
            }
            documentRepository.save(doc);
        } else {
            log.warn("暂无解析器支持 MIME={}，documentId={} 保持 PENDING", doc.getMimeType(), doc.getId());
        }

        return toResp(doc, chunkCount);
    }

    /**
     * 列出某会话下的全部文档概要（F1.1 列表接口）。
     *
     * @param sessionId 会话 id
     * @return 文档概要列表，可能为空
     */
    @Override
    public List<DocumentSummary> listBySession(Long sessionId) {
        return documentRepository.findBySessionId(sessionId).stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * 实体 → 上传响应 DTO。chunkCount 仅在解析 SUCCESS 时有值，其余状态返回 0。
     */
    private UploadFileResp toResp(DocumentEntity doc, int chunkCount) {
        return new UploadFileResp(
                doc.getId(),
                doc.getFilename(),
                doc.getSize(),
                doc.getMimeType(),
                doc.getParseStatus().name(),
                chunkCount
        );
    }

    private DocumentSummary toSummary(DocumentEntity doc) {
        return new DocumentSummary(
                doc.getId(),
                doc.getFilename(),
                doc.getParseStatus(),
                doc.getCreatedAt() == null ? null : doc.getCreatedAt().toString()
        );
    }
}
