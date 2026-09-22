package com.demetrius.fileagent.agent.domain.service;

import com.demetrius.fileagent.api.enums.RetrievalQueryType;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.common.exception.BizException;

import java.util.Map;

/**
 * 自适应检索策略域服务（Phase 2A）：把检索类型映射为固定策略档位。
 * <p>
 * 档位由服务端配置固定，Agent 只声明 queryType，不直接控制检索参数；
 * {@code NONE} 仅用于 Run 级意图，不允许进入检索档位。
 */
public class AdaptiveRetrievalPolicy {

    /** 单个查询类型的检索档位：两路 TopK/候选数、RRF 权重、最终命中数与重排/父扩展开关。 */
    public record Tier(
            int bm25TopK,
            int knnTopK,
            int knnCandidates,
            double bm25Weight,
            double knnWeight,
            int finalTopK,
            boolean rerankEnabled,
            boolean parentExpansionEnabled) {
    }

    private final Map<RetrievalQueryType, Tier> tiers;

    public AdaptiveRetrievalPolicy(Map<RetrievalQueryType, Tier> tiers) {
        this.tiers = Map.copyOf(tiers);
    }

    public KnowledgeSearchPort.SearchOptions optionsFor(RetrievalQueryType queryType) {
        Tier tier = tierFor(queryType);
        return new KnowledgeSearchPort.SearchOptions(
                queryType.name(),
                tier.bm25TopK(),
                tier.knnTopK(),
                tier.knnCandidates(),
                tier.bm25Weight(),
                tier.knnWeight(),
                tier.finalTopK(),
                tier.rerankEnabled(),
                tier.parentExpansionEnabled());
    }

    /** 并列问题与比较至少需要两路；多跳允许逐轮查找依赖证据。 */
    public int minSubQueries(RetrievalQueryType queryType) {
        return switch (queryType) {
            case MULTI_QUERY, COMPARISON -> 2;
            case SINGLE_HOP, MULTI_HOP, AGGREGATION, TIME_SENSITIVE -> 1;
            case NONE -> throw new BizException("NONE 不允许作为 search_docs 检索类型");
        };
    }

    /** 各类型允许的最大子查询数：控制单轮检索成本与工具超时预算。 */
    public int maxSubQueries(RetrievalQueryType queryType) {
        return switch (queryType) {
            case SINGLE_HOP -> 1;
            case MULTI_QUERY, MULTI_HOP, COMPARISON, AGGREGATION -> 3;
            case TIME_SENSITIVE -> 2;
            case NONE -> throw new BizException("NONE 不允许作为 search_docs 检索类型");
        };
    }

    /** 工具层最终命中上限：结构化检索按档位截断，替代 Phase 1 的固定 5 条。 */
    public int finalHitCap(RetrievalQueryType queryType) {
        return tierFor(queryType).finalTopK();
    }

    private Tier tierFor(RetrievalQueryType queryType) {
        if (queryType == null) {
            throw new BizException("未知检索类型: null");
        }
        Tier tier = tiers.get(queryType);
        if (tier == null) {
            throw new BizException("未知检索类型: " + queryType);
        }
        return tier;
    }
}
