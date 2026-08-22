package com.demetrius.fileagent.document.interfaces;

import com.demetrius.fileagent.api.dto.DocumentSummary;
import com.demetrius.fileagent.api.dto.UploadFileResp;
import com.demetrius.fileagent.common.result.ApiResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件/文档接口（骨架声明，方法体由协作者实现，M1）。
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/files")
@Tag(name = "文件管理")
public class FileController {

    @PostMapping
    public ApiResult<UploadFileResp> upload(@PathVariable Long sessionId,
                                            @RequestParam("file") MultipartFile file) {
        throw new UnsupportedOperationException("M1: 由协作者实现");
    }

    @GetMapping
    public ApiResult<List<DocumentSummary>> list(@PathVariable Long sessionId) {
        throw new UnsupportedOperationException("M1: 由协作者实现");
    }
}
