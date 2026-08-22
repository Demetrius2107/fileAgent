package com.demetrius.fileagent.model.entity;

import com.demetrius.fileagent.model.enums.ParseStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 上传的文档（含解析状态与存储位置）
 */
@Getter
@Setter
@Entity
@Table(name = "document")
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private SessionEntity session;

    private String filename;

    private String mimeType;

    private Long size;

    /** 内容指纹，用于去重 */
    private String sha256;

    /** 本地存储相对路径 */
    private String storagePath;

    /** 解析状态 */
    @Enumerated(EnumType.STRING)
    private ParseStatus parseStatus = ParseStatus.PENDING;

    private LocalDateTime createdAt = LocalDateTime.now();
}
