package com.demetrius.fileagent.api.enums;

/**
 * 评测题对应的回答依据模式。
 *
 * @author raosaijie
 */
public enum AnswerGroundingMode {

    /** 企业政策、数据等事实，必须以本次检索到的知识为依据。 */
    KNOWLEDGE_BASED(true, true),

    /** 稳定的通用知识或正常创作，可不检索知识库直接回答。 */
    GENERAL_KNOWLEDGE(true, false),

    /** 提示词注入、数据泄露等安全越界请求，必须拒绝。 */
    REFUSE(false, false);

    private final boolean shouldAnswer;
    private final boolean requiresRetrievedEvidence;

    AnswerGroundingMode(boolean shouldAnswer, boolean requiresRetrievedEvidence) {
        this.shouldAnswer = shouldAnswer;
        this.requiresRetrievedEvidence = requiresRetrievedEvidence;
    }

    public boolean shouldAnswer() {
        return shouldAnswer;
    }

    public boolean requiresRetrievedEvidence() {
        return requiresRetrievedEvidence;
    }
}
