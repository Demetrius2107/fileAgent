package com.demetrius.fileagent.document.application;

import com.demetrius.fileagent.api.dto.RagFileSummary;
import com.demetrius.fileagent.api.enums.ParseStatus;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.document.domain.RagFileEntity;
import com.demetrius.fileagent.document.domain.RagFileRepository;
import com.demetrius.fileagent.document.infrastructure.DocumentParser;
import com.demetrius.fileagent.document.infrastructure.DocumentParserRegistry;
import com.demetrius.fileagent.document.infrastructure.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识库文件应用服务实现。
 * <p>
 * 参照 {@code cn.bugstack.ai.domain.agent.service.rag.RagService#storeRagFile}：
 * 落盘原件 → 解析 → 分块 → 打知识标签元数据 → vectorStore.accept → 落库记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagFileAppServiceImpl implements RagFileAppService {

    private final DocumentParserRegistry parserRegistry;
    private final RagFileRepository ragFileRepository;
    private final SimpleVectorStore vectorStore;
    private final StorageService storageService;

    @Value("${fileagent.vector-store-path:./storage/vectorstore.json}")
    private String vectorStorePath;

    @Override
    public void storeRagFile(String name, String tag, List<MultipartFile> files) {
        if (!StringUtils.hasText(name) || !StringUtils.hasText(tag)) {
            throw new BizException("知识库名称(name)和知识标签(tag)不能为空");
        }
        if (files == null || files.isEmpty()) {
            throw new BizException("请至少上传一个文件");
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new BizException("存在空文件，请检查上传内容");
            }
            storeOne(name, tag, file);
        }
    }

    @Override
    public List<RagFileSummary> list() {
        return ragFileRepository.findAllOrderByCreatedAtDesc().stream()
                .map(entity -> new RagFileSummary(
                        entity.getId(),
                        entity.getRagName(),
                        entity.getKnowledgeTag(),
                        entity.getFilename(),
                        entity.getStatus(),
                        entity.getChunkCount(),
                        entity.getCreatedAt().toString()))
                .toList();
    }

    private void storeOne(String name, String tag, MultipartFile file) {
        RagFileEntity entity = new RagFileEntity();
        entity.setRagName(name);
        entity.setKnowledgeTag(tag);
        entity.setFilename(resolveFilename(file));
        entity.setFileSize(file.getSize());
        entity.setStatus(ParseStatus.PARSING);
        entity = ragFileRepository.save(entity);

        try {
            // 原件先落盘再解析；索引失败时整体回滚（删记录 + 删原件），列表只保留索引成功的文件
            StorageService.StoredFile stored = storageService.store(file);
            entity.setStoragePath(stored.relativePath());
            entity.setSha256(stored.sha256());

            String mimeType = resolveMimeType(file);
            DocumentParser parser = parserRegistry.findParser(mimeType)
                    .orElseThrow(() -> new BizException("暂不支持的文件格式: " + mimeType + "（当前支持 TXT/MD，PDF/Office 随 M2 解析器扩展）"));

            // 解析 + 分块（读取已落盘的原件）
            List<String> chunks = parser.parse(storageService.resolve(stored.relativePath()), mimeType);
            if (chunks.isEmpty()) {
                throw new BizException("文件内容为空，未能切分出有效 chunk: " + entity.getFilename());
            }

            // 打知识标签等元数据后写入向量库
            List<Document> documents = toDocuments(chunks, entity);
            vectorStore.accept(documents);
            persistVectorStore();

            entity.setChunkCount(documents.size());
            entity.setStatus(ParseStatus.SUCCESS);
            ragFileRepository.save(entity);
            log.info("知识库文件索引完成: name={}, tag={}, file={}, chunks={}, storagePath={}",
                    name, tag, entity.getFilename(), documents.size(), stored.relativePath());
        } catch (BizException e) {
            rollbackRecord(entity, e);
            throw e;
        } catch (Exception e) {
            log.error("知识库文件索引失败: {}", entity.getFilename(), e);
            BizException biz = new BizException("文件分块/向量化失败: " + e.getMessage());
            rollbackRecord(entity, biz);
            throw biz;
        }
    }

    /** chunk 文本 + 元数据 -> Spring AI Document */
    private List<Document> toDocuments(List<String> chunks, RagFileEntity entity) {
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String rawContent = chunks.get(i);
            Map<String, Object> metadata = new HashMap<>();
            // 与参照实现保持一致：知识标签写入 knowledge 元数据
            metadata.put("knowledge", entity.getKnowledgeTag());
            metadata.put("ragName", entity.getRagName());
            metadata.put("fileId", entity.getId());
            metadata.put("filename", entity.getFilename());
            metadata.put("chunkIndex", i);
            metadata.put("rawContent", rawContent);
            String embeddingText = "知识库: " + entity.getRagName() + "\n"
                    + "标签: " + entity.getKnowledgeTag() + "\n"
                    + "文件: " + entity.getFilename() + "\n"
                    + "内容: " + rawContent;
            documents.add(new Document(embeddingText, metadata));
        }
        return documents;
    }

    private void persistVectorStore() {
        try {
            Path path = Path.of(vectorStorePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.toAbsolutePath().getParent());
            }
            vectorStore.save(path.toFile());
        } catch (IOException e) {
            log.error("向量库落盘失败: {}", vectorStorePath, e);
            throw new BizException("向量库落盘失败: " + e.getMessage());
        }
    }

    /**
     * 索引失败回滚：删除本次上传的 rag_file 记录与已落盘的原件，列表只保留索引成功的文件。
     * 回滚删除失败时兜底标记 FAILED，避免记录悬在 PARSING。
     */
    private void rollbackRecord(RagFileEntity entity, RuntimeException e) {
        try {
            ragFileRepository.delete(entity);
            if (entity.getStoragePath() != null) {
                storageService.delete(entity.getStoragePath());
            }
            log.warn("知识库文件索引失败，已回滚记录与原件: file={}, reason={}", entity.getFilename(), e.getMessage());
        } catch (Exception ex) {
            log.error("回滚失败记录时出错，降级标记 FAILED: {}", entity.getFilename(), ex);
            try {
                entity.setStatus(ParseStatus.FAILED);
                ragFileRepository.save(entity);
            } catch (Exception suppressed) {
                log.warn("标记失败状态时出错: {}", entity.getFilename(), suppressed);
            }
        }
    }

    private String resolveMimeType(MultipartFile file) {
        String extension = fileExtension(file).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case ".txt" -> "text/plain";
            case ".md", ".markdown" -> "text/markdown";
            case ".pdf" -> "application/pdf";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".csv" -> "text/csv";
            default -> throw new BizException(
                    "不支持的文件格式: " + resolveFilename(file) + "（当前支持 TXT/MD/PDF/DOCX/XLSX/CSV）");
        };
    }

    private String resolveFilename(MultipartFile file) {
        String filename = file.getOriginalFilename();
        return StringUtils.hasText(filename) ? filename : "unnamed";
    }

    private String fileExtension(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.'));
    }
}
