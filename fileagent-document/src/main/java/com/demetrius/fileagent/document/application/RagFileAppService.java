package com.demetrius.fileagent.document.application;

import com.demetrius.fileagent.api.dto.RagFileSummary;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库文件应用服务（用例契约）：去重校验 → 原件落盘 → 解析分块 → 向量索引 → 落库记录。
 */
public interface RagFileAppService {

    /**
     * 上传知识库文件并建立向量索引。
     * <p>同一 name + tag 内按 sha256 去重：命中索引成功的相同内容直接拒绝。
     *
     * @param name  知识库名称
     * @param tag   知识标签（写入 chunk 元数据，检索时按标签过滤）
     * @param files 待索引的文件列表
     */
    void storeRagFile(String name, String tag, List<MultipartFile> files);

    /** 全部知识文件概要（按创建时间倒序） */
    List<RagFileSummary> list();

    /**
     * 删除知识库文件：按「查记录 → 删 ES 索引 → 删原件 → 删记录」顺序执行。
     * <p>必须先删 ES 索引：记录先没了索引还在，会继续被召回且再无 fileId 可清理。
     * ES 索引与原件删除均幂等，中途失败可直接重试本接口。
     *
     * @param id 知识库文件 id
     */
    void deleteRagFile(Long id);
}
