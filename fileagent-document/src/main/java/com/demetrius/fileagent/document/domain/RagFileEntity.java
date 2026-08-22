package com.demetrius.fileagent.document.domain;

import com.demetrius.fileagent.api.enums.ParseStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识库文件（上传 → 分块 → 向量索引 的落库记录）。
 * <p>
 * 与 {@link DocumentEntity}（会话内文档）区分：知识库文件按 name（知识库名称）
 * + tag（知识标签）组织，跨会话共享，供 chat 域按标签检索注入上下文。
 */
@Getter
@Setter
@Entity
@Table(name = "rag_file")
public class RagFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 知识库名称（上传接口的 name 参数） */
    @Column(name = "rag_name", nullable = false)
    private String ragName;

    /** 知识标签（上传接口的 tag 参数），写入 chunk 元数据用于检索过滤 */
    @Column(name = "knowledge_tag", nullable = false)
    private String knowledgeTag;

    @Column(name = "filename", nullable = false)
    private String filename;

    @Column(name = "file_size")
    private Long fileSize;

    /** 切分出的 chunk 数量 */
    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    /** 索引状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ParseStatus status = ParseStatus.PENDING;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
