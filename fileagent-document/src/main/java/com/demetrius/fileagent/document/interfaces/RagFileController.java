package com.demetrius.fileagent.document.interfaces;

import com.demetrius.fileagent.api.dto.RagFileSummary;
import com.demetrius.fileagent.common.result.ApiResult;
import com.demetrius.fileagent.document.application.RagFileAppService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
}
