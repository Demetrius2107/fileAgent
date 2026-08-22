package com.demetrius.fileagent.document.interfaces;

import com.demetrius.fileagent.api.dto.DocumentSummary;
import com.demetrius.fileagent.api.dto.UploadFileResp;
import com.demetrius.fileagent.common.result.ApiResult;
import com.demetrius.fileagent.document.application.DocumentAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件/文档 REST 接口：单文件/批量上传与会话内文档列表查询（F1.1）。
 * 所有结果统一以 {@link ApiResult} 包装。
 *
 * @author Demetrius
 * @since 0.1.0
 * @date 2026-08-22
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/files")
@Tag(name = "文件管理")
@RequiredArgsConstructor
public class FileController {

    private final DocumentAppService documentAppService;

    /**
     * 上传单个文件（F1.1）。
     *
     * @param sessionId 目标会话 id
     * @param file       上传文件
     * @return 上传结果
     */
    @PostMapping
    @Operation(summary = "上传单个文件")
    public ApiResult<UploadFileResp> upload(@PathVariable Long sessionId,
                                            @RequestParam("file") MultipartFile file) {
        return ApiResult.ok(documentAppService.upload(sessionId, file));
    }

    /**
     * 批量上传文件（F1.1 多文件）。逐个上传，单个失败不影响其它文件。
     *
     * @param sessionId 目标会话 id
     * @param files      待上传文件数组
     * @return 各文件的上传结果，顺序与入参一致
     */
    @PostMapping("/batch")
    @Operation(summary = "批量上传文件")
    public ApiResult<List<UploadFileResp>> uploadBatch(@PathVariable Long sessionId,
                                                       @RequestParam("files") MultipartFile[] files) {
        List<UploadFileResp> result = new ArrayList<>(files.length);
        for (MultipartFile file : files) {
            result.add(documentAppService.upload(sessionId, file));
        }
        return ApiResult.ok(result);
    }

    /**
     * 查询某会话下的文档列表（F1.1）。
     *
     * @param sessionId 会话 id
     * @return 文档概要列表
     */
    @GetMapping
    @Operation(summary = "会话内文档列表")
    public ApiResult<List<DocumentSummary>> list(@PathVariable Long sessionId) {
        return ApiResult.ok(documentAppService.listBySession(sessionId));
    }
}
