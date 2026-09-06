package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.api.enums.ParseStatus;
import com.demetrius.fileagent.document.domain.RagFileEntity;
import com.demetrius.fileagent.document.domain.RagFileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RagFileRepositoryImpl} 去重派生查询与删除的 H2 落库验证。
 * Spring Data 派生方法名只在启动时校验，这里保证查询语义与去重规则一致。
 * 引导配置见 {@link com.demetrius.fileagent.document.RagFileJpaTestBootstrap}。
 *
 * @author Demetrius
 */
@DataJpaTest
@Import(RagFileRepositoryImpl.class)
class RagFileRepositoryDedupTest {

    @Autowired
    private RagFileRepository ragFileRepository;

    @Test
    void existsShouldHitOnlyWhenNameTagAndShaAllMatch() {
        ragFileRepository.save(entity("员工知识库", "制度", "sha-1", ParseStatus.SUCCESS));

        assertThat(ragFileRepository.existsByRagNameAndKnowledgeTagAndSha256AndStatus(
                "员工知识库", "制度", "sha-1", ParseStatus.SUCCESS)).isTrue();
        assertThat(ragFileRepository.existsByRagNameAndKnowledgeTagAndSha256AndStatus(
                "其他知识库", "制度", "sha-1", ParseStatus.SUCCESS)).isFalse();
        assertThat(ragFileRepository.existsByRagNameAndKnowledgeTagAndSha256AndStatus(
                "员工知识库", "其他标签", "sha-1", ParseStatus.SUCCESS)).isFalse();
        assertThat(ragFileRepository.existsByRagNameAndKnowledgeTagAndSha256AndStatus(
                "员工知识库", "制度", "sha-2", ParseStatus.SUCCESS)).isFalse();
    }

    @Test
    void existsShouldIgnoreFailedUploadsSoSameContentCanBeRetried() {
        ragFileRepository.save(entity("员工知识库", "制度", "sha-1", ParseStatus.FAILED));

        assertThat(ragFileRepository.existsByRagNameAndKnowledgeTagAndSha256AndStatus(
                "员工知识库", "制度", "sha-1", ParseStatus.SUCCESS)).isFalse();
    }

    @Test
    void deleteShouldRemoveRecordSoFindByIdBecomesEmpty() {
        RagFileEntity entity = ragFileRepository.save(entity("员工知识库", "制度", "sha-1", ParseStatus.SUCCESS));
        Long id = entity.getId();
        assertThat(id).isNotNull();

        ragFileRepository.delete(entity);

        assertThat(ragFileRepository.findById(id)).isEmpty();
    }

    private RagFileEntity entity(String ragName, String tag, String sha256, ParseStatus status) {
        RagFileEntity entity = new RagFileEntity();
        entity.setRagName(ragName);
        entity.setKnowledgeTag(tag);
        entity.setFilename("manual.txt");
        entity.setSha256(sha256);
        entity.setStatus(status);
        return entity;
    }
}
