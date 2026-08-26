package com.demetrius.fileagent.document.application;

import com.demetrius.fileagent.api.dto.RagFileSummary;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库文件应用服务（用例契约）：上传 → 分块 → 向量化 → 写入向量库 → 落库记录。
 */
public interface RagFileAppService {

    /**
     * 上传知识库文件并建立向量索引。
     *
     * @param name  知识库名称
     * @param tag   知识标签（写入 chunk 元数据，检索时按标签过滤）
     * @param files 待索引的文件列表
     */
    void storeRagFile(String name, String tag, List<MultipartFile> files);

    /** 全部知识文件概要（按创建时间倒序） */
    List<RagFileSummary> list();
}
