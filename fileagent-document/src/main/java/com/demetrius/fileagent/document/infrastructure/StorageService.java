package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

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
     * <p>流程：清洗文件名 → 生成不可碰撞的服务端对象键 → 写文件并计算 sha256。
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
        String relativePath = "originals/" + LocalDate.now().format(DATE_DIR)
                + "/" + UUID.randomUUID() + "/" + safeName;
        Path target = resolve(relativePath);
        MessageDigest digest = newSha256Digest();
        try {
            Files.createDirectories(target.getParent());
            verifyRealContainment(target.getParent());
            try (InputStream in = file.getInputStream();
                 OutputStream raw = Files.newOutputStream(
                         target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 DigestOutputStream out = new DigestOutputStream(raw, digest)) {
                in.transferTo(out);
            }
        } catch (IOException e) {
            deleteQuietly(target);
            log.error("落盘失败: {}", target, e);
            throw new BizException("文件存储失败: " + e.getMessage());
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        log.debug("文件落盘: {} (sha256={})", relativePath, sha256);
        return new StoredFile(relativePath, sha256);
    }

    /**
     * 将视觉资产写入由服务端归属信息生成的对象键，先写临时对象再移动到最终位置。
     *
     * @param fileId 所属知识库文件 ID
     * @param documentVersion 文档版本
     * @param assetId 资产 UUID
     * @param extension 受控文件扩展名
     * @param writer 资产编码器
     * @return 资产存储键、摘要与实际字节数
     */
    public StoredAsset storeAsset(Long fileId, int documentVersion, String assetId,
                                  String extension, AssetWriter writer) {
        validateAssetIdentity(fileId, documentVersion, assetId, extension, writer);
        String storageKey = "assets/" + fileId + "/v" + documentVersion
                + "/" + assetId + "." + extension;
        Path target = resolve(storageKey);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        MessageDigest digest = newSha256Digest();
        try {
            Files.createDirectories(target.getParent());
            verifyRealContainment(target.getParent());
            try (OutputStream raw = Files.newOutputStream(
                    temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 DigestOutputStream out = new DigestOutputStream(raw, digest)) {
                writer.write(out);
            }
            moveNewObject(temporary, target);
            return new StoredAsset(storageKey, HexFormat.of().formatHex(digest.digest()), Files.size(target));
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(temporary);
            if (exception instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException("视觉资产存储失败: " + exception.getMessage());
        }
    }

    /**
     * 仅计算上传内容指纹，不落盘。上传去重在写盘前据此拦截重复文件。
     *
     * @param file 上传文件（不允许为空）
     * @return 内容 sha256 十六进制串
     * @throws BizException 读取上传内容失败时
     */
    public String sha256(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        try (InputStream in = file.getInputStream()) {
            return sha256(in);
        } catch (IOException e) {
            throw new BizException("读取上传文件失败: " + e.getMessage());
        }
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
        if (relativePath == null || relativePath.isBlank() || Paths.get(relativePath).isAbsolute()) {
            throw new BizException("非法存储路径: " + relativePath);
        }
        Path abs = storageRoot.resolve(relativePath).normalize();
        if (!abs.startsWith(storageRoot)) {
            throw new BizException("非法存储路径: " + relativePath);
        }
        verifyRealContainment(abs);
        return abs;
    }

    /** 打开受控对象流；调用方负责关闭。 */
    public InputStream open(String relativePath) {
        Path target = requireExisting(relativePath);
        try {
            return Files.newInputStream(target, StandardOpenOption.READ);
        } catch (IOException exception) {
            throw new BizException("读取存储对象失败: " + exception.getMessage());
        }
    }

    /** 返回受控对象的实际字节数。 */
    public long size(String relativePath) {
        try {
            return Files.size(requireExisting(relativePath));
        } catch (IOException exception) {
            throw new BizException("读取存储对象大小失败: " + exception.getMessage());
        }
    }

    /**
     * 删除已落盘的原件（索引失败回滚用）。文件不存在时静默忽略。
     *
     * @param relativePath 存储时返回的相对路径
     * @throws BizException 路径越界（非法相对路径）时
     */
    public void delete(String relativePath) {
        Path target = resolve(relativePath);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new BizException("删除已存储文件失败: " + target.getFileName());
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
        MessageDigest digest = newSha256Digest();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            digest.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private Path requireExisting(String relativePath) {
        Path target = resolve(relativePath);
        if (!Files.isRegularFile(target)) {
            throw new BizException("存储对象不存在: " + relativePath);
        }
        verifyRealContainment(target);
        return target;
    }

    /** 已存在节点的真实路径必须仍位于真实存储根目录下，避免符号链接绕过。 */
    private void verifyRealContainment(Path target) {
        try {
            Files.createDirectories(storageRoot);
            Path rootReal = storageRoot.toRealPath();
            Path existing = target;
            while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null || !existing.toRealPath().startsWith(rootReal)) {
                throw new BizException("非法存储路径: " + target);
            }
        } catch (IOException exception) {
            throw new BizException("校验存储路径失败: " + exception.getMessage());
        }
    }

    private void validateAssetIdentity(Long fileId, int documentVersion, String assetId,
                                       String extension, AssetWriter writer) {
        if (fileId == null || fileId <= 0 || documentVersion <= 0 || writer == null) {
            throw new BizException("视觉资产归属参数非法");
        }
        try {
            UUID.fromString(assetId);
        } catch (RuntimeException exception) {
            throw new BizException("视觉资产 ID 非法");
        }
        if (!"png".equals(extension)) {
            throw new BizException("暂不支持的视觉资产格式: " + extension);
        }
    }

    private void moveNewObject(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target);
        }
    }

    private void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            log.warn("清理未完成的存储对象失败: {}", target, exception);
        }
    }

    /** 落盘结果：相对路径 + 内容指纹。 */
    public record StoredFile(String relativePath, String sha256) {
    }

    /** 视觉资产落盘结果。 */
    public record StoredAsset(String storageKey, String sha256, long byteSize) {
    }

    /** 向受控资产对象写入编码结果。 */
    @FunctionalInterface
    public interface AssetWriter {
        void write(OutputStream outputStream) throws IOException;
    }
}
