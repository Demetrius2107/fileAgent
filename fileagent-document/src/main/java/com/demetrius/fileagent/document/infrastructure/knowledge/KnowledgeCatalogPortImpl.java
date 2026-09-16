package com.demetrius.fileagent.document.infrastructure.knowledge;

import com.demetrius.fileagent.api.enums.ParseStatus;
import com.demetrius.fileagent.api.port.KnowledgeCatalogPort;
import com.demetrius.fileagent.document.domain.RagFileEntity;
import com.demetrius.fileagent.document.domain.RagFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * {@link KnowledgeCatalogPort} 实现：复用 {@link RagFileRepository} 返回可检索文件概要。
 * <p>
 * 只返回 {@link ParseStatus#SUCCESS} 的文件，按创建时间倒序，limit 强制收敛到 1..20。
 * 不暴露存储路径、sha256 或解析器细节。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeCatalogPortImpl implements KnowledgeCatalogPort {

    private final RagFileRepository ragFileRepository;

    @Override
    public List<KnowledgeFile> list(Query query) {
        int limit = Math.max(1, Math.min(20, query.limit()));
        return ragFileRepository.findAllOrderByCreatedAtDesc().stream()
                .filter(file -> file.getStatus() == ParseStatus.SUCCESS)
                .filter(file -> matches(file.getRagName(), query.ragName()))
                .filter(file -> matches(file.getKnowledgeTag(), query.knowledgeTag()))
                .limit(limit)
                .map(this::toKnowledgeFile)
                .toList();
    }

    private boolean matches(String actual, String expected) {
        return !StringUtils.hasText(expected) || expected.equals(actual);
    }

    private KnowledgeFile toKnowledgeFile(RagFileEntity file) {
        return new KnowledgeFile(
                file.getId(),
                file.getRagName(),
                file.getKnowledgeTag(),
                file.getFilename(),
                file.getStatus(),
                file.getChunkCount() == null ? 0 : file.getChunkCount());
    }
}
