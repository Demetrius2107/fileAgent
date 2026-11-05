package com.demetrius.fileagent.document.interfaces;

import com.demetrius.fileagent.api.dto.DocumentAssetPage;
import com.demetrius.fileagent.api.dto.KnowledgeChunkView;
import com.demetrius.fileagent.api.dto.OriginalRagFile;
import com.demetrius.fileagent.api.dto.RagFileSummary;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.common.result.ApiResult;
import com.demetrius.fileagent.document.application.RagFileAppService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 知识库文件接口。
 * <p>
 * 参照 {@code cn.bugstack.ai.trigger.http.admin.AiClientRagOrderAdminController#uploadRagFile}：
 * 接收 name（知识库名称）、tag（知识标签）、files（文件列表），同步解析 → 分块 → 向量化入库。
 */
@Slf4j
@RestController
@RequestMapping("/api/rag-files")
@Tag(name = "知识库文件")
@RequiredArgsConstructor
public class RagFileController {

    private final RagFileAppService ragFileAppService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<Boolean> uploadRagFile(@RequestParam("name") String name,
                                            @RequestParam("tag") String tag,
                                            @RequestParam("files") List<MultipartFile> files) {
        log.info("上传知识库文件: name={}, tag={}, fileCount={}", name, tag, files == null ? 0 : files.size());
        ragFileAppService.storeRagFile(name, tag, files);
        return ApiResult.ok(true);
    }

    @GetMapping
    public ApiResult<List<RagFileSummary>> listRagFiles() {
        return ApiResult.ok(ragFileAppService.list());
    }

    @DeleteMapping("/{id}")
    public ApiResult<Boolean> deleteRagFile(@PathVariable Long id) {
        log.info("删除知识库文件: id={}", id);
        ragFileAppService.deleteRagFile(id);
        return ApiResult.ok(true);
    }

    /**
     * 原件预览/下载：inline 按正确 MIME 直出，浏览器渲染不了的格式（DOCX/XLSX 等）自行转下载。
     * 二进制内容不走 ApiResult 包装。
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long id) {
        OriginalRagFile original = ragFileAppService.loadOriginal(id);
        String encoded = URLEncoder.encode(original.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(original.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .body(original.content());
    }

    @GetMapping("/{id}/chunks")
    public ApiResult<List<KnowledgeChunkView>> chunks(@PathVariable Long id) {
        return ApiResult.ok(ragFileAppService.listChunks(id));
    }

    @GetMapping("/{id}/assets")
    public ApiResult<DocumentAssetPage> assets(
            @PathVariable Long id,
            @RequestParam(required = false) Integer documentVersion,
            @RequestParam(defaultValue = "-1") int afterAssetIndex,
            @RequestParam(defaultValue = "20") int limit) {
        if (documentVersion == null || documentVersion <= 0
                || afterAssetIndex < -1 || limit <= 0 || limit > 50) {
            throw new BizException("资产分页参数非法：documentVersion 必须为正数，afterAssetIndex 不小于 -1，limit 范围为 1-50");
        }
        return ApiResult.ok(ragFileAppService.listAssets(
                id, documentVersion, afterAssetIndex, limit));
    }

    /** 视觉资产二进制内容不使用 ApiResult 包装。 */
    @GetMapping("/{id}/assets/{assetId}/content")
    public ResponseEntity<Resource> assetContent(
            @PathVariable Long id,
            @PathVariable String assetId,
            @RequestParam(required = false) Integer documentVersion) {
        if (documentVersion == null || documentVersion <= 0) {
            throw new BizException("documentVersion 必须为正数");
        }
        RagFileAppService.DocumentAssetContent asset = ragFileAppService.loadAsset(
                id, documentVersion, assetId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.mimeType()))
                .contentLength(asset.byteSize())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(asset.inputStream()));
    }
}
