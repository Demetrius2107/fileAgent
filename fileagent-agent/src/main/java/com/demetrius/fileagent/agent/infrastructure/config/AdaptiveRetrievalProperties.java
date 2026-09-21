package com.demetrius.fileagent.agent.infrastructure.config;

import com.demetrius.fileagent.agent.domain.service.AdaptiveRetrievalPolicy;
import com.demetrius.fileagent.api.enums.RetrievalQueryType;
import com.demetrius.fileagent.common.exception.BizException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * 自适应检索策略配置（前缀 {@code fileagent.agent.adaptive-retrieval}，Phase 2A）。
 * <p>
 * 档位由服务端固定，Agent 只声明 queryType；装配时经 {@link #validate} 启动期校验，
 * 校验失败直接阻断启动，不带病运行。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "fileagent.agent.adaptive-retrieval")
public class AdaptiveRetrievalProperties {

    /** Adaptive 生效时的 Run 总时长预算，须大于 toolTimeout 的 3 倍（3 条子查询的预算上限）。 */
    private Duration runTimeout = Duration.ofSeconds(90);

    private Tier singleHop = new Tier(20, 20, 100, 1.0, 1.0, 5, true, true);
    private Tier multiHop = new Tier(20, 20, 100, 1.0, 1.0, 8, true, false);
    private Tier comparison = new Tier(20, 20, 100, 1.0, 1.0, 8, true, false);
    private Tier aggregation = new Tier(50, 20, 100, 1.3, 0.7, 12, false, false);
    private Tier timeSensitive = new Tier(30, 20, 100, 1.3, 0.7, 8, true, false);

    /** 启动期校验：档位参数合法且 runTimeout 覆盖最大子查询数预算。 */
    public void validate(Duration baseToolTimeout) {
        validateTier("singleHop", singleHop);
        validateTier("multiHop", multiHop);
        validateTier("comparison", comparison);
        validateTier("aggregation", aggregation);
        validateTier("timeSensitive", timeSensitive);
        if (runTimeout == null || runTimeout.isZero() || runTimeout.isNegative()) {
            throw new BizException("fileagent.agent.adaptive-retrieval.runTimeout 必须为正数");
        }
        if (baseToolTimeout != null && runTimeout.compareTo(baseToolTimeout.multipliedBy(3)) <= 0) {
            throw new BizException("fileagent.agent.adaptive-retrieval.runTimeout 必须大于 toolTimeout 的 3 倍");
        }
    }

    /** 档位映射为不可变领域策略（infrastructure -> domain 单向桥接）。 */
    public Map<RetrievalQueryType, AdaptiveRetrievalPolicy.Tier> toTiers() {
        Map<RetrievalQueryType, AdaptiveRetrievalPolicy.Tier> tiers = new EnumMap<>(RetrievalQueryType.class);
        tiers.put(RetrievalQueryType.SINGLE_HOP, toPolicyTier(singleHop));
        tiers.put(RetrievalQueryType.MULTI_HOP, toPolicyTier(multiHop));
        tiers.put(RetrievalQueryType.COMPARISON, toPolicyTier(comparison));
        tiers.put(RetrievalQueryType.AGGREGATION, toPolicyTier(aggregation));
        tiers.put(RetrievalQueryType.TIME_SENSITIVE, toPolicyTier(timeSensitive));
        return tiers;
    }

    private void validateTier(String name, Tier tier) {
        if (tier == null) {
            throw new BizException("fileagent.agent.adaptive-retrieval." + name + " 不能为空");
        }
        if (tier.getBm25TopK() < 1 || tier.getKnnTopK() < 1) {
            throw new BizException("fileagent.agent.adaptive-retrieval." + name + " TopK 必须大于等于 1");
        }
        if (tier.getKnnCandidates() < tier.getKnnTopK()) {
            throw new BizException("fileagent.agent.adaptive-retrieval." + name + " knnCandidates 不能小于 knnTopK");
        }
        if (tier.getBm25Weight() <= 0 || tier.getBm25Weight() > 2.0
                || tier.getKnnWeight() <= 0 || tier.getKnnWeight() > 2.0) {
            throw new BizException("fileagent.agent.adaptive-retrieval." + name + " RRF 权重必须在 (0, 2] 区间");
        }
        if (tier.getFinalTopK() < 1) {
            throw new BizException("fileagent.agent.adaptive-retrieval." + name + " finalTopK 必须大于等于 1");
        }
    }

    private AdaptiveRetrievalPolicy.Tier toPolicyTier(Tier tier) {
        return new AdaptiveRetrievalPolicy.Tier(
                tier.getBm25TopK(),
                tier.getKnnTopK(),
                tier.getKnnCandidates(),
                tier.getBm25Weight(),
                tier.getKnnWeight(),
                tier.getFinalTopK(),
                tier.isRerankEnabled(),
                tier.isParentExpansionEnabled());
    }

    /** 单个查询类型的档位配置：支持配置绑定，默认值即规格档位。 */
    @Getter
    @Setter
    public static class Tier {
        private int bm25TopK;
        private int knnTopK;
        private int knnCandidates;
        private double bm25Weight;
        private double knnWeight;
        private int finalTopK;
        private boolean rerankEnabled;
        private boolean parentExpansionEnabled;

        public Tier() {
        }

        public Tier(int bm25TopK, int knnTopK, int knnCandidates, double bm25Weight, double knnWeight,
                    int finalTopK, boolean rerankEnabled, boolean parentExpansionEnabled) {
            this.bm25TopK = bm25TopK;
            this.knnTopK = knnTopK;
            this.knnCandidates = knnCandidates;
            this.bm25Weight = bm25Weight;
            this.knnWeight = knnWeight;
            this.finalTopK = finalTopK;
            this.rerankEnabled = rerankEnabled;
            this.parentExpansionEnabled = parentExpansionEnabled;
        }
    }
}
