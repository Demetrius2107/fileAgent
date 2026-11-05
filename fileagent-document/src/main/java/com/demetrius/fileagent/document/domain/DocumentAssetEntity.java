package com.demetrius.fileagent.document.domain;

import com.demetrius.fileagent.api.enums.DocumentAssetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文档视觉资产元数据；图片字节保存在受控文件存储中，实体只保存归属、位置和完整性事实。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
@Getter
@Setter
@Entity
@Table(name = "rag_document_asset", uniqueConstraints = {
        @UniqueConstraint(name = "uk_rag_asset_file_version_index",
                columnNames = {"file_id", "document_version", "asset_index"})
})
public class DocumentAssetEntity {

    /** 不可预测的资产标识；对外暴露时仍需同时校验文件和版本归属。 */
    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    /** 所属知识库文件主键，用于阻止资产跨文件读取。 */
    @Column(name = "file_id", nullable = false, updatable = false)
    private Long fileId;

    /** 资产生成时的文档版本，防止历史引用自动跳到新版本。 */
    @Column(name = "document_version", nullable = false, updatable = false)
    private Integer documentVersion;

    /** 资产在文档中的业务类型，决定后续 OCR、裁剪和展示方式。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, updatable = false, length = 32)
    private DocumentAssetType assetType;

    /** 同一文件版本内的稳定展示序号，仅用于排序，不代替页码。 */
    @Column(name = "asset_index", nullable = false, updatable = false)
    private Integer assetIndex;

    /** 存储根目录下的相对对象键；数据库不保存宿主机绝对路径。 */
    @Column(name = "storage_key", nullable = false, updatable = false, length = 512)
    private String storageKey;

    /** 实际图片编码类型，读取接口据此返回固定 Content-Type。 */
    @Column(name = "mime_type", nullable = false, updatable = false, length = 100)
    private String mimeType;

    /** 图片字节的 SHA-256，用于检测存储内容损坏或错配。 */
    @Column(name = "sha256", nullable = false, updatable = false, length = 64)
    private String sha256;

    /** 资产字节数，用于读取前限额检查和审计。 */
    @Column(name = "byte_size", nullable = false, updatable = false)
    private Long byteSize;

    /** 图片实际像素宽度，用于前端稳定布局和模型预算。 */
    @Column(name = "width", nullable = false, updatable = false)
    private Integer width;

    /** 图片实际像素高度，用于前端稳定布局和模型预算。 */
    @Column(name = "height", nullable = false, updatable = false)
    private Integer height;

    /** 局部裁剪图的来源资产标识；完整页面资产保持为空。 */
    @Column(name = "source_asset_id", updatable = false, length = 36)
    private String sourceAssetId;

    /** 序列化后的可信来源位置；不保存任意反序列化类型。 */
    @Lob
    @Column(name = "location_json", nullable = false, updatable = false, columnDefinition = "CLOB")
    private String locationJson;

    /** 生成资产的解析器版本，便于重建和问题追溯。 */
    @Column(name = "parser_version", nullable = false, updatable = false, length = 100)
    private String parserVersion;

    /** 首次生成资产的时间，用于审计和孤儿资产排查。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void initializePersistentFacts() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
