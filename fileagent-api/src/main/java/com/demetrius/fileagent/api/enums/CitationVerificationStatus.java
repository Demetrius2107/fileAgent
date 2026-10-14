package com.demetrius.fileagent.api.enums;

/**
 * 单条结构化引用的回答后核验结论（Phase 2C）。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-14
 */
public enum CitationVerificationStatus {

    /** 引用来源存在于本次上下文证据，且引用原文仍在当前索引内容中命中。 */
    SUPPORTED,

    /** 引用来源存在于上下文证据，但当前索引内容已不包含引用原文（内容漂移或版本变更）。 */
    QUOTE_MISMATCH,

    /** 引用来源存在于上下文证据，但对应 chunk 已不在索引中（如文件被删除），无法核验。 */
    CHUNK_MISSING,

    /** 回答声明了该来源，但该来源从未进入本次上下文证据（缺失引用或伪造引用）。 */
    NOT_IN_CONTEXT
}
