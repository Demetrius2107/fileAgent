package com.demetrius.fileagent.api.dto;

import com.demetrius.fileagent.api.enums.CitationVerificationStatus;

/**
 * Agent 回答的结构化引用（Phase 2C）：把一条来源标记映射为固定版本的原文件区域。
 * <p>
 * 最终引用只能来自实际进入模型上下文的证据；{@code page}/{@code sheetName}/{@code sectionId}
 * 定位字段缺失时保持 null（旧索引不伪造定位信息），前端按 chunk 级回退展示。
 * {@code quote} 为证据原文摘录，{@code quoteHash} 是其规范化摘要，用于回答后核验。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-14
 */
public record AgentCitation(
        Long fileId,
        Integer documentVersion,
        String chunkId,
        String filename,
        Integer page,
        String sheetName,
        String sectionId,
        Integer chunkIndex,
        String quote,
        String quoteHash,
        CitationVerificationStatus status) {

    /** 答案声明了来源但该来源未进入上下文时的降级构造：只保留文件名与核验结论。 */
    public static AgentCitation notInContext(String filename) {
        return new AgentCitation(null, null, null, filename, null, null, null, null,
                null, null, CitationVerificationStatus.NOT_IN_CONTEXT);
    }
}
