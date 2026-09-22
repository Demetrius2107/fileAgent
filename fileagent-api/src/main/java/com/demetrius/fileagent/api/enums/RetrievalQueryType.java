package com.demetrius.fileagent.api.enums;

/**
 * Agent 检索计划类型。
 * <p>
 * Phase 2A：Agent 通过 {@code search_docs} 的结构化入参声明查询类型，服务端据此映射
 * 固定策略档位，Agent 不直接控制检索参数。{@code NONE} 仅用于 Run 级意图（无需检索），
 * 不允许作为 {@code search_docs} 的参数传入。
 */
public enum RetrievalQueryType {
    NONE,
    SINGLE_HOP,
    MULTI_QUERY,
    MULTI_HOP,
    COMPARISON,
    AGGREGATION,
    TIME_SENSITIVE
}
