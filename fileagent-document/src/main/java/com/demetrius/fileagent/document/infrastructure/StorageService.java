package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 本地文件存储服务：负责文件落盘、sha256 指纹计算与路径安全校验。
 * 返回相对路径，便于后续迁移 OSS（只改本类，Entity 不动）。
 *
 * @author Demetrius
 * @since 0.1.0
 * @date 2026-08-22
 */
@Slf4j
@Component
public class StorageService {

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final Path storageRoot;

    public StorageService(@Value("${fileagent.storage-dir:./storage/files}") String storageDir) {
        this.storageRoot = Paths.get(storageDir).toAbsolutePath().normalize();
    }

    /**
     * 落盘并计算内容指纹。
     * <p>流程：清洗文件名 → 按日期分目录 → 写文件 → 计算 sha256。
     * 返回相对路径（非绝对路径），便于将来迁移 OSS 时只需替换本类。
     *
     * @param file 上传文件（不允许为空）
     * @return 相对路径 + 内容指纹
     * @throws BizException 文件为空 / 文件名非法 / 写入失败时
     */
    public StoredFile store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        String original = file.getOriginalFilename();
        String safeName = sanitize(original);
        String relativeDir = LocalDate.now().format(DATE_DIR);
        String relativePath = relativeDir + "/" + safeName;
        Path target = storageRoot.resolve(relativePath).normalize();

        // 防目录穿越：解析后必须仍在 storageRoot 内
        if (!target.startsWith(storageRoot)) {
            throw new BizException("非法文件名: " + original);
        }

        String sha256;
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                sha256 = sha256(in);
                Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("落盘失败: {}", target, e);
            throw new BizException("文件存储失败: " + e.getMessage());
        }
        log.debug("文件落盘: {} (sha256={})", relativePath, sha256);
        return new StoredFile(relativePath, sha256);
    }

    /**
     * 把存储的相对路径解析为绝对路径，供解析器读取文件内容。
     * 校验解析结果必须仍在存储根目录内，防止路径穿越。
     *
     * @param relativePath 存储时返回的相对路径
     * @return 对应磁盘绝对路径
     * @throws BizException 路径越界（非法相对路径）时
     */
    public Path resolve(String relativePath) {
        Path abs = storageRoot.resolve(relativePath).normalize();
        if (!abs.startsWith(storageRoot)) {
            throw new BizException("非法存储路径: " + relativePath);
        }
        return abs;
    }

    /**
     * 删除已落盘的原件（索引失败回滚用）。文件不存在时静默忽略。
     *
     * @param relativePath 存储时返回的相对路径
     * @throws BizException 路径越界（非法相对路径）时
     */
    public void delete(String relativePath) {
        Path target = storageRoot.resolve(relativePath).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new BizException("非法存储路径: " + relativePath);
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("删除已存储文件失败: {}", target, e);
        }
    }

    /** 仅取文件名部分，丢弃任何路径前缀，防穿越。空名用时间戳兜底。 */
    private String sanitize(String original) {
        if (original == null || original.isBlank()) {
            return "upload_" + System.currentTimeMillis();
        }
        String name = Paths.get(original).getFileName().toString();
        return name.isBlank() ? "upload_" + System.currentTimeMillis() : name;
    }

    /** JDK 原生 sha256，流式计算，不引入 commons-codec。 */
    private String sha256(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            digest.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 落盘结果：相对路径 + 内容指纹。 */
    public record StoredFile(String relativePath, String sha256) {
    }
}
