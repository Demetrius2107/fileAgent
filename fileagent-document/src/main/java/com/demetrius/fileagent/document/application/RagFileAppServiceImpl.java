package com.demetrius.fileagent.document.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.demetrius.fileagent.api.dto.ContentLocation;
import com.demetrius.fileagent.api.dto.DocumentAssetPage;
import com.demetrius.fileagent.api.dto.DocumentAssetSummary;
import com.demetrius.fileagent.api.dto.KnowledgeChunkView;
import com.demetrius.fileagent.api.dto.OriginalRagFile;
import com.demetrius.fileagent.api.dto.RagFileSummary;
import com.demetrius.fileagent.api.enums.ContentType;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
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
 * 负责内容去重校验、原件落盘、解析分块、知识索引写入与删除编排。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagFileAppServiceImpl implements RagFileAppService {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final String CONTENT_PARSER_VERSION = "document-parser-v1";

    private final DocumentParserRegistry parserRegistry;
    private final RagFileRepository ragFileRepository;

    private final KnowledgeIndexRepository knowledgeIndexRepository;

    private final StorageService storageService;

    private final DocumentAssetRepository documentAssetRepository;

    private final DocumentRepository documentRepository;

    private final PdfPageAssetRenderer pdfPageAssetRenderer;

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
                entity.getCreatedAt().toString(),
                entity.getDocumentVersion()))
            .toList();
    }

    @Override
    public void deleteRagFile(Long id) {
        RagFileEntity entity = ragFileRepository.findById(id)
                .orElseThrow(() -> new BizException("知识库文件不存在: " + id));
        if (entity.getStatus() == ParseStatus.PARSING) {
            throw new BizException("文件正在索引中，请等待索引完成后再删除: " + entity.getFilename());
        }
        // 删除顺序约束：必须先删 ES 索引再删记录——记录先没了索引还在，会继续被召回且再无 fileId 可清理。
        // 每个资产先删对象再删元数据，任何对象删除失败都保留数据库归属，便于重试和排障。
        knowledgeIndexRepository.deleteByFileId(id);
        for (DocumentAssetEntity asset : documentAssetRepository.findAllByFileId(id)) {
            storageService.delete(asset.getStorageKey());
            documentAssetRepository.delete(asset);
        }
        if (StringUtils.hasText(entity.getStoragePath()) && !isOriginalStillReferenced(entity)) {
            storageService.delete(entity.getStoragePath());
        }
        ragFileRepository.delete(entity);
        log.info("知识库文件删除完成: id={}, file={}", id, entity.getFilename());
    }

    @Override
    public OriginalRagFile loadOriginal(Long id) {
        RagFileEntity entity = ragFileRepository.findById(id)
                .orElseThrow(() -> new BizException("知识库文件不存在: " + id));
        if (!StringUtils.hasText(entity.getStoragePath())) {
            throw new BizException("该文件没有可用的原件记录: " + entity.getFilename());
        }
        // 路径安全由 StorageService.resolve 兜底（防目录穿越）
        Path path = storageService.resolve(entity.getStoragePath());
        try {
            byte[] content = Files.readAllBytes(path);
            return new OriginalRagFile(entity.getFilename(), resolveMimeType(entity.getFilename()), content);
        } catch (IOException e) {
            throw new BizException("读取文件原件失败: " + entity.getFilename());
        }
    }

    @Override
    public List<KnowledgeChunkView> listChunks(Long id) {
        ragFileRepository.findById(id)
                .orElseThrow(() -> new BizException("知识库文件不存在: " + id));
        return knowledgeIndexRepository.findByFileId(id).stream()
                .map(chunk -> new KnowledgeChunkView(
                        chunk.chunkId(),
                        chunk.chunkIndex(),
                        String.valueOf(chunk.metadata().getOrDefault("chunkType", "CHILD")),
                        chunk.content(),
                        chunk.metadata()))
                .toList();
    }

    @Override
    public DocumentAssetPage listAssets(Long fileId, int documentVersion,
                                        int afterAssetIndex, int limit) {
        validateAssetPage(documentVersion, afterAssetIndex, limit);
        requireSuccessfulFileVersion(fileId, documentVersion);
        List<DocumentAssetEntity> fetched = documentAssetRepository.findPage(
                fileId, documentVersion, afterAssetIndex, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<DocumentAssetSummary> items = fetched.stream()
                .limit(limit)
                .map(this::toAssetSummary)
                .toList();
        Integer nextAssetIndex = hasMore && !items.isEmpty()
                ? items.getLast().assetIndex()
                : null;
        return new DocumentAssetPage(items, nextAssetIndex);
    }

    @Override
    public DocumentAssetContent loadAsset(Long fileId, int documentVersion, String assetId) {
        if (!StringUtils.hasText(assetId) || documentVersion <= 0) {
            throw new BizException("文档资产参数非法");
        }
        requireSuccessfulFileVersion(fileId, documentVersion);
        DocumentAssetEntity asset = documentAssetRepository
                .findOwned(fileId, documentVersion, assetId)
                .orElseThrow(() -> new BizException(404, "文档资产不存在"));
        if (!"image/png".equals(asset.getMimeType())) {
            throw new BizException("不支持的文档资产类型: " + asset.getMimeType());
        }
        long actualSize = storageService.size(asset.getStorageKey());
        if (asset.getByteSize() == null || actualSize != asset.getByteSize()) {
            throw new BizException("文档资产大小校验失败: " + assetId);
        }
        InputStream inputStream = storageService.open(asset.getStorageKey());
        return new DocumentAssetContent(asset.getMimeType(), actualSize, inputStream);
    }

    private void storeOne(String name, String tag, MultipartFile file) {
        String filename = resolveFilename(file);
        // 内容指纹先行：同一 name+tag 内已索引成功的相同内容直接拦截，不落盘不落库
        String sha256 = storageService.sha256(file);
        if (ragFileRepository.existsByRagNameAndKnowledgeTagAndSha256AndStatus(name, tag, sha256, ParseStatus.SUCCESS)) {
            throw new BizException("同一知识库与标签下已存在内容相同的文件: " + filename + "，如需替换请先删除原文件");
        }

        RagFileEntity entity = new RagFileEntity();
        entity.setRagName(name);
        entity.setKnowledgeTag(tag);
        entity.setFilename(filename);
        entity.setFileSize(file.getSize());
        entity.setDocumentVersion(nextDocumentVersion(name, tag, filename));
        entity.setStatus(ParseStatus.PARSING);
        entity = ragFileRepository.save(entity);
        List<PdfPageAssetRenderer.RenderedPageAsset> renderedAssets = List.of();

        try {
            // 原件先落盘再解析：解析失败不丢文件，storage_path/sha256 回填记录
            StorageService.StoredFile stored = storageService.store(file);
            entity.setStoragePath(stored.relativePath());
            entity.setSha256(stored.sha256());
            ragFileRepository.save(entity);

            String mimeType = resolveMimeType(file);
            DocumentParser parser = parserRegistry.findParser(mimeType)
                    .orElseThrow(() -> new BizException("暂不支持的文件格式: " + mimeType + "（当前支持 TXT/MD，PDF/Office 随 M2 解析器扩展）"));

            Path storedPath = storageService.resolve(stored.relativePath());
            List<ParsedChunk> chunks = parser.parseChunks(storedPath, mimeType);
            if (chunks.isEmpty()) {
                throw new BizException("文件内容为空，未能切分出有效 chunk: " + entity.getFilename());
            }

            renderedAssets = pdfPageAssetRenderer.render(
                    storedPath, mimeType,
                    entity.getId(), entity.getDocumentVersion());
            persistAssets(renderedAssets, entity);

            Map<Integer, String> pageAssetIds = renderedAssets.stream().collect(Collectors.toMap(
                    PdfPageAssetRenderer.RenderedPageAsset::page,
                    PdfPageAssetRenderer.RenderedPageAsset::assetId));
            List<KnowledgeChunk> knowledgeChunks = toKnowledgeChunks(chunks, entity, pageAssetIds);
            knowledgeIndexRepository.saveAll(knowledgeChunks);

            entity.setChunkCount(chunks.size());
            entity.setStatus(ParseStatus.SUCCESS);
            ragFileRepository.save(entity);
            log.info("知识库文件索引完成: name={}, tag={}, file={}, chunks={}",
                    name, tag, entity.getFilename(), knowledgeChunks.size());
        } catch (BizException e) {
            cleanupIndex(entity.getId());
            cleanupAssets(entity, renderedAssets);
            markFailed(entity, e);
            throw e;
        } catch (Exception e) {
            log.error("知识库文件索引失败: {}", entity.getFilename(), e);
            cleanupIndex(entity.getId());
            cleanupAssets(entity, renderedAssets);
            BizException biz = new BizException("文件分块/索引失败: " + e.getMessage());
            markFailed(entity, biz);
            throw biz;
        }
    }

    /**
     * 同一知识库+标签+文件名的替换性上传递增文档版本；被删除的旧版本不再参与计数，
     * 历史引用仍按其生成时记录的版本号追溯（版本号只前进不回收）。
     */
    private int nextDocumentVersion(String name, String tag, String filename) {
        return ragFileRepository.findByRagNameAndKnowledgeTagAndFilename(name, tag, filename).stream()
                .mapToInt(existing -> existing.getDocumentVersion() == null ? 1 : existing.getDocumentVersion())
                .max()
                .orElse(0) + 1;
    }

    private List<KnowledgeChunk> toKnowledgeChunks(List<ParsedChunk> chunks, RagFileEntity entity,
                                                    Map<Integer, String> pageAssetIds) {
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
            metadata.put("documentVersion", entity.getDocumentVersion());
            metadata.put("contentUnitId", entity.getId() + ":" + i);
            metadata.put("contentType", resolveContentType(metadata).name());
            metadata.put("parserVersion", CONTENT_PARSER_VERSION);
            Map<String, Object> location = resolveContentLocation(metadata);
            if (!location.isEmpty()) {
                metadata.put("location", location);
            }
            Integer page = integerMetadata(metadata, "page");
            if (page != null && pageAssetIds.containsKey(page)) {
                metadata.put("assetId", pageAssetIds.get(page));
            }
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
            metadata.remove("contentUnitId");
            metadata.remove("assetId");
            metadata.remove("location");
            metadata.remove("rowIndex");
            metadata.remove("page");
            metadata.put("chunkType", "PARENT");
            metadata.put("documentVersion", entity.getDocumentVersion());
            metadata.put("contentType", resolveContentType(metadata).name());
            metadata.put("parserVersion", CONTENT_PARSER_VERSION);
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

    private void persistAssets(List<PdfPageAssetRenderer.RenderedPageAsset> renderedAssets,
                               RagFileEntity file) {
        for (PdfPageAssetRenderer.RenderedPageAsset rendered : renderedAssets) {
            DocumentAssetEntity asset = new DocumentAssetEntity();
            asset.setId(rendered.assetId());
            asset.setFileId(file.getId());
            asset.setDocumentVersion(file.getDocumentVersion());
            asset.setAssetType(DocumentAssetType.PAGE);
            asset.setAssetIndex(rendered.page());
            asset.setStorageKey(rendered.storageKey());
            asset.setMimeType(rendered.mimeType());
            asset.setSha256(rendered.sha256());
            asset.setByteSize(rendered.byteSize());
            asset.setWidth(rendered.width());
            asset.setHeight(rendered.height());
            asset.setLocationJson(writeLocation(rendered.location()));
            asset.setParserVersion(rendered.parserVersion());
            documentAssetRepository.save(asset);
        }
    }

    private void validateAssetPage(int documentVersion, int afterAssetIndex, int limit) {
        if (documentVersion <= 0 || afterAssetIndex < -1 || limit <= 0 || limit > 50) {
            throw new BizException("资产分页参数非法：documentVersion 必须为正数，afterAssetIndex 不小于 -1，limit 范围为 1-50");
        }
    }

    private RagFileEntity requireSuccessfulFileVersion(Long fileId, int documentVersion) {
        RagFileEntity file = ragFileRepository.findById(fileId)
                .orElseThrow(() -> new BizException(404, "知识库文件不存在: " + fileId));
        if (file.getStatus() != ParseStatus.SUCCESS
                || file.getDocumentVersion() == null
                || file.getDocumentVersion() != documentVersion) {
            throw new BizException(404, "知识库文件版本不存在");
        }
        return file;
    }

    private DocumentAssetSummary toAssetSummary(DocumentAssetEntity asset) {
        return new DocumentAssetSummary(
                asset.getId(),
                asset.getDocumentVersion(),
                asset.getAssetType(),
                asset.getAssetIndex(),
                readLocation(asset.getLocationJson()),
                asset.getMimeType(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getByteSize());
    }

    private ContentLocation readLocation(String locationJson) {
        try {
            return JSON_MAPPER.readValue(locationJson, ContentLocation.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("文档资产位置数据损坏", exception);
        }
    }

    private String writeLocation(ContentLocation location) {
        try {
            return JSON_MAPPER.writeValueAsString(location);
        } catch (JsonProcessingException exception) {
            throw new BizException("文档资产位置序列化失败: " + exception.getMessage());
        }
    }

    private ContentType resolveContentType(Map<String, Object> metadata) {
        String sourceType = String.valueOf(metadata.getOrDefault("sourceType", "text"));
        return switch (sourceType.toLowerCase(Locale.ROOT)) {
            case "xlsx", "csv" -> ContentType.TABLE;
            default -> ContentType.TEXT;
        };
    }

    private Map<String, Object> resolveContentLocation(Map<String, Object> metadata) {
        Map<String, Object> location = new LinkedHashMap<>();
        copyLocationValue(metadata, location, "page");
        copyLocationValue(metadata, location, "sheetName");
        copyLocationValue(metadata, location, "cellRange");
        copyLocationValue(metadata, location, "slide");
        copyLocationValue(metadata, location, "anchorId");
        return location;
    }

    private void copyLocationValue(Map<String, Object> metadata, Map<String, Object> location, String key) {
        Object value = metadata.get(key);
        if (value != null && (!(value instanceof String text) || StringUtils.hasText(text))) {
            location.put(key, value);
        }
    }

    private Integer integerMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? null : Integer.valueOf(String.valueOf(value));
    }

    private boolean isOriginalStillReferenced(RagFileEntity entity) {
        return ragFileRepository.existsByStoragePathAndIdNot(entity.getStoragePath(), entity.getId())
                || documentRepository.existsByStoragePath(entity.getStoragePath());
    }

    private void cleanupIndex(Long fileId) {
        try {
            knowledgeIndexRepository.deleteByFileId(fileId);
        } catch (Exception e) {
            log.warn("清理失败知识索引时出错: fileId={}", fileId, e);
        }
    }

    private void cleanupAssets(RagFileEntity file,
                               List<PdfPageAssetRenderer.RenderedPageAsset> renderedAssets) {
        for (PdfPageAssetRenderer.RenderedPageAsset rendered : renderedAssets) {
            try {
                storageService.delete(rendered.storageKey());
                documentAssetRepository.findOwned(
                                file.getId(), file.getDocumentVersion(), rendered.assetId())
                        .ifPresent(documentAssetRepository::delete);
            } catch (RuntimeException exception) {
                log.warn("清理失败的页面资产时出错: fileId={}, assetId={}",
                        file.getId(), rendered.assetId(), exception);
            }
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
        return resolveMimeType(resolveFilename(file));
    }

    private String resolveMimeType(String filename) {
        String extension = fileExtension(filename).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case ".txt" -> "text/plain";
            case ".md", ".markdown" -> "text/markdown";
            case ".pdf" -> "application/pdf";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".csv" -> "text/csv";
            default -> throw new BizException(
                    "不支持的文件格式: " + filename + "（当前支持 TXT/MD/PDF/DOCX/XLSX/CSV）");
        };
    }

    private String resolveFilename(MultipartFile file) {
        String filename = file.getOriginalFilename();
        return StringUtils.hasText(filename) ? filename : "unnamed";
    }

    private String fileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.'));
    }
}
