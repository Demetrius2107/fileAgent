package com.demetrius.fileagent.document.domain;

import com.demetrius.fileagent.api.enums.ParseStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 上传的文档（含解析状态与存储位置）
 * <p>
 * 归属文档域。对会话仅持有 {@code sessionId} 值引用，跨域取会话信息走 fileagent-api 的 port，
 * 不直接依赖 session 域实体（DDD 有界上下文解耦）。
 */
@Getter
@Setter
@Entity
@Table(name = "document")
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话 id（值引用，跨域不建实体关系） */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    private String filename;

    private String mimeType;

    private Long size;

    /** 内容指纹，用于去重 */
    private String sha256;

    /** 本地存储相对路径 */
    private String storagePath;

    /** 解析切分出的 chunk 数量，解析成功后回填 */
    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    /** 内容级元数据：文档标题（解析时抽取） */
    private String title;

    /** 内容级元数据：作者（解析时抽取） */
    private String author;

    /** 内容级元数据：页数（PDF/Word 解析时抽取） */
    @Column(name = "page_count")
    private Integer pageCount;

    /** 内容级元数据：工作表数（Excel 解析时抽取） */
    @Column(name = "sheet_count")
    private Integer sheetCount;

    /** 解析状态 */
    @Enumerated(EnumType.STRING)
    private ParseStatus parseStatus = ParseStatus.PENDING;

    private LocalDateTime createdAt = LocalDateTime.now();
}
