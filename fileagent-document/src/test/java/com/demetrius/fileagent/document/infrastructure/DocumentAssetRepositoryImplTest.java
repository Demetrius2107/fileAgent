package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.api.enums.DocumentAssetType;
import com.demetrius.fileagent.document.domain.DocumentAssetEntity;
import com.demetrius.fileagent.document.domain.DocumentAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 视觉资产归属校验、稳定分页与删除语义的 H2 持久化测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-11-05
 */
@DataJpaTest
@Import(DocumentAssetRepositoryImpl.class)
class DocumentAssetRepositoryImplTest {

    @Autowired
    private DocumentAssetRepository repository;

    @Test
    void findPageShouldUseExclusiveCursorAndStableIndexOrder() {
        repository.save(asset("asset-3", 10L, 2, 3));
        repository.save(asset("asset-1", 10L, 2, 1));
        repository.save(asset("asset-2", 10L, 2, 2));
        repository.save(asset("other-version", 10L, 1, 1));
        repository.save(asset("other-file", 11L, 2, 1));

        List<DocumentAssetEntity> firstPage = repository.findPage(10L, 2, -1, 2);
        List<DocumentAssetEntity> secondPage = repository.findPage(10L, 2, 2, 2);

        assertThat(firstPage).extracting(DocumentAssetEntity::getId)
                .containsExactly("asset-1", "asset-2");
        assertThat(secondPage).extracting(DocumentAssetEntity::getId)
                .containsExactly("asset-3");
    }

    @Test
    void findOwnedShouldRequireFileVersionAndAssetIdTogether() {
        repository.save(asset("page-asset", 10L, 2, 1));

        assertThat(repository.findOwned(10L, 2, "page-asset")).isPresent();
        assertThat(repository.findOwned(11L, 2, "page-asset")).isEmpty();
        assertThat(repository.findOwned(10L, 1, "page-asset")).isEmpty();
    }

    @Test
    void deleteShouldRemoveOnlySpecifiedAsset() {
        DocumentAssetEntity first = repository.save(asset("first", 10L, 2, 1));
        repository.save(asset("second", 10L, 2, 2));

        repository.delete(first);

        assertThat(repository.findOwned(10L, 2, "first")).isEmpty();
        assertThat(repository.findOwned(10L, 2, "second")).isPresent();
    }

    private DocumentAssetEntity asset(String id, Long fileId, int documentVersion, int assetIndex) {
        DocumentAssetEntity asset = new DocumentAssetEntity();
        asset.setId(id);
        asset.setFileId(fileId);
        asset.setDocumentVersion(documentVersion);
        asset.setAssetType(DocumentAssetType.PAGE);
        asset.setAssetIndex(assetIndex);
        asset.setStorageKey("assets/" + fileId + "/v" + documentVersion + "/" + id + ".png");
        asset.setMimeType("image/png");
        asset.setSha256("sha-" + id);
        asset.setByteSize(100L);
        asset.setWidth(100);
        asset.setHeight(200);
        asset.setLocationJson("{\"page\":" + assetIndex + "}");
        asset.setParserVersion("pdf-page-renderer-v1");
        asset.setCreatedAt(LocalDateTime.of(2026, 11, 5, 10, 0));
        return asset;
    }
}
