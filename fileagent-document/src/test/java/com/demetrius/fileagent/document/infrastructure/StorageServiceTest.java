package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StorageService} 指纹计算、路径安全与删除幂等测试。
 * 基于临时目录，不触碰真实 storage/。
 *
 * @author Demetrius
 */
class StorageServiceTest {

    /** "abc" 的 SHA-256 标准测试向量 */
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @TempDir
    Path tempDir;

    @Test
    void sha256ShouldReturnKnownDigestForFixedContent() {
        MockMultipartFile file = new MockMultipartFile("files", "a.txt", null, "abc".getBytes());

        assertThat(newStorageService().sha256(file)).isEqualTo(ABC_SHA256);
    }

    @Test
    void sha256ShouldMatchStoreFingerprintForSameContent() {
        StorageService storageService = newStorageService();
        MockMultipartFile file = new MockMultipartFile("files", "a.txt", null, "abc".getBytes());

        StorageService.StoredFile stored = storageService.store(file);

        assertThat(storageService.sha256(file)).isEqualTo(stored.sha256());
    }

    @Test
    void sha256ShouldRejectEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("files", "a.txt", null, new byte[0]);

        assertThatThrownBy(() -> newStorageService().sha256(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("为空");
    }

    @Test
    void storeShouldKeepFileInsideRootWhenFilenameContainsTraversal() {
        MockMultipartFile file = new MockMultipartFile("files", "../../evil.txt", null, "abc".getBytes());

        StorageService.StoredFile stored = newStorageService().store(file);

        assertThat(newStorageService().resolve(stored.relativePath())).startsWith(tempDir.toAbsolutePath());
    }

    @Test
    void deleteShouldRemoveStoredFile() {
        StorageService storageService = newStorageService();
        StorageService.StoredFile stored = storageService.store(
                new MockMultipartFile("files", "a.txt", null, "abc".getBytes()));
        Path absolute = storageService.resolve(stored.relativePath());
        assertThat(absolute).exists();

        storageService.delete(stored.relativePath());

        assertThat(absolute).doesNotExist();
    }

    @Test
    void deleteShouldStaySilentWhenFileAlreadyMissing() {
        StorageService storageService = newStorageService();

        storageService.delete("2099/01/01/never-existed.txt");
        storageService.delete("2099/01/01/never-existed.txt");

        assertThat(storageService.resolve("2099/01/01/never-existed.txt")).doesNotExist();
    }

    @Test
    void deleteShouldRejectPathEscape() {
        assertThatThrownBy(() -> newStorageService().delete("../../evil.txt"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法存储路径");
    }

    private StorageService newStorageService() {
        return new StorageService(tempDir.toAbsolutePath().toString());
    }
}
