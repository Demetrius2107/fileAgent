package com.demetrius.fileagent.document.application;

import com.demetrius.fileagent.api.dto.DocumentSummary;
import com.demetrius.fileagent.api.dto.UploadFileResp;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档应用服务（用例契约）。
 * 由协作者提供 {@code DocumentAppServiceImpl} 实现（M1）。
 */
public interface DocumentAppService {

    UploadFileResp upload(Long sessionId, MultipartFile file);

    List<DocumentSummary> listBySession(Long sessionId);
}
