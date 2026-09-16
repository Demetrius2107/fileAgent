package com.demetrius.fileagent.api.dto;

/**
 * Agent 知识检索范围（服务端可信构造）。
 * <p>
 * 两个字段均为 null 表示全局知识范围；模型与工具参数中禁止出现并信任
 * tenantId / userId / 角色 / ES DSL / 任意文件路径或 URL。
 */
public record KnowledgeScope(String ragName, String knowledgeTag) {

    /** 全局知识范围（Phase 1 默认）。 */
    public static KnowledgeScope global() {
        return new KnowledgeScope(null, null);
    }
}
