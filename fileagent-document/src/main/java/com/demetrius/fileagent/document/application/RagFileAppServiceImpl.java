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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库文件应用服务实现。
 * <p>
 * 负责文件解析、通用分块与知识索引写入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagFileAppServiceImpl implements RagFileAppService {

    private final DocumentParserRegistry parserRegistry;
    private final RagFileRepository ragFileRepository;

    private final KnowledgeIndexRepository knowledgeIndexRepository;

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
            .filter(ragFileEntity -> ragFileEntity.getStatus() == ParseStatus.SUCCESS)
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

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("fileagent-rag-", fileExtension(file));
            file.transferTo(tempFile);

            String mimeType = resolveMimeType(file);
            DocumentParser parser = parserRegistry.findParser(mimeType)
                    .orElseThrow(() -> new BizException("暂不支持的文件格式: " + mimeType + "（当前支持 TXT/MD，PDF/Office 随 M2 解析器扩展）"));

            List<ParsedChunk> chunks = parser.parseChunks(tempFile, mimeType);
            if (chunks.isEmpty()) {
                throw new BizException("文件内容为空，未能切分出有效 chunk: " + entity.getFilename());
            }

            List<KnowledgeChunk> knowledgeChunks = toKnowledgeChunks(chunks, entity);
            knowledgeIndexRepository.saveAll(knowledgeChunks);

            entity.setChunkCount(chunks.size());
            entity.setStatus(ParseStatus.SUCCESS);
            ragFileRepository.save(entity);
            log.info("知识库文件索引完成: name={}, tag={}, file={}, chunks={}",
                    name, tag, entity.getFilename(), knowledgeChunks.size());
        } catch (BizException e) {
            cleanupIndex(entity.getId());
            markFailed(entity, e);
            throw e;
        } catch (Exception e) {
            log.error("知识库文件索引失败: {}", entity.getFilename(), e);
            cleanupIndex(entity.getId());
            BizException biz = new BizException("文件分块/索引失败: " + e.getMessage());
            markFailed(entity, biz);
            throw biz;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("临时文件清理失败: {}", tempFile, e);
                }
            }
        }
    }

    private List<KnowledgeChunk> toKnowledgeChunks(List<ParsedChunk> chunks, RagFileEntity entity) {
        Map<String, List<Integer>> parentGroups = new LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            Object parentId = chunks.get(i).metadata().get("parentId");
            if (parentId != null && StringUtils.hasText(String.valueOf(parentId))) {
                parentGroups.computeIfAbsent(String.valueOf(parentId), ignored -> new ArrayList<>())
                        .add(i);
            }
        }
        Map<String, String> physicalParentIds = new LinkedHashMap<>();
        int parentIndex = 0;
        for (String logicalParentId : parentGroups.keySet()) {
            physicalParentIds.put(logicalParentId, entity.getId() + ":parent:" + parentIndex++);
        }

        ArrayList<KnowledgeChunk> knowledgeChunks = new ArrayList<>(
                chunks.size() + parentGroups.size());
        for (int i = 0; i < chunks.size(); i++) {
            ParsedChunk chunk = chunks.get(i);
            Map<String, Object> metadata = new LinkedHashMap<>(chunk.metadata());
            metadata.put("chunkType", "CHILD");
            Object logicalParentId = metadata.get("parentId");
            if (logicalParentId != null) {
                String physicalParentId = physicalParentIds.get(String.valueOf(logicalParentId));
                if (physicalParentId == null) {
                    metadata.remove("parentId");
                } else {
                    metadata.put("parentId", physicalParentId);
                }
            }
            knowledgeChunks.add(new KnowledgeChunk(
                    entity.getId() + ":" + i,
                    entity.getId(),
                    entity.getRagName(),
                    entity.getKnowledgeTag(),
                    entity.getFilename(),
                    chunk.content(),
                    i,
                    metadata));
        }
        for (Map.Entry<String, List<Integer>> group : parentGroups.entrySet()) {
            int firstChildIndex = group.getValue().getFirst();
            ParsedChunk firstChild = chunks.get(firstChildIndex);
            Map<String, Object> metadata = new LinkedHashMap<>(firstChild.metadata());
            metadata.remove("parentId");
            metadata.put("chunkType", "PARENT");
            String parentContent = group.getValue().stream()
                    .map(chunks::get)
                    .map(ParsedChunk::content)
                    .collect(Collectors.joining("\n"));
            knowledgeChunks.add(new KnowledgeChunk(
                    physicalParentIds.get(group.getKey()),
                    entity.getId(),
                    entity.getRagName(),
                    entity.getKnowledgeTag(),
                    entity.getFilename(),
                    parentContent,
                    firstChildIndex,
                    metadata));
        }
        return List.copyOf(knowledgeChunks);
    }

    private void cleanupIndex(Long fileId) {
        try {
            knowledgeIndexRepository.deleteByFileId(fileId);
        } catch (Exception e) {
            log.warn("清理失败知识索引时出错: fileId={}", fileId, e);
        }
    }

    private void markFailed(RagFileEntity entity, RuntimeException e) {
        try {
            entity.setStatus(ParseStatus.FAILED);
            ragFileRepository.save(entity);
        } catch (Exception ex) {
            log.warn("标记失败状态时出错: {}", entity.getFilename(), ex);
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
