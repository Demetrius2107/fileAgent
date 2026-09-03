package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.api.port.KnowledgeSearchPort.KnowledgeHit;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 通过百炼 qwen3-rerank 接口对混合召回结果进行语义重排。
 *
 * @author raosaijie
 */
@Slf4j
@Component
public class DashScopeKnowledgeReranker {

    private final RestClient restClient;
    private final RerankerProperties properties;

    public DashScopeKnowledgeReranker(RestClient.Builder restClientBuilder,
                                     RerankerProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    public List<KnowledgeHit> rerank(String query, List<KnowledgeHit> candidates) {
        if (!properties.isEnabled() || candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        if (!StringUtils.hasText(properties.getBaseUrl())
                || !StringUtils.hasText(properties.getApiKey())) {
            log.warn("Reranker 已启用但缺少 baseUrl 或 apiKey，降级为 RRF 排序");
            return candidates;
        }
        int candidateCount = Math.min(properties.getCandidateTopK(), candidates.size());
        if (candidateCount <= 0 || properties.getTopN() <= 0) {
            log.warn("Reranker candidateTopK/topN 配置无效，降级为 RRF 排序");
            return candidates;
        }
        double minScore = properties.getMinRelevanceScore();
        if (!Double.isFinite(minScore) || minScore < 0.0 || minScore > 1.0) {
            log.warn("Reranker minRelevanceScore 配置无效，降级为 RRF 排序: {}", minScore);
            return candidates;
        }
        List<KnowledgeHit> selected = candidates.subList(0, candidateCount);
        RerankRequest request = new RerankRequest(
                properties.getModel(), query, selected.stream().map(this::rerankText).toList(),
                Math.min(properties.getTopN(), selected.size()));
        log.debug("Reranker 开始: query={}, candidates={}, selected={}, topN={}, minScore={}",
                query, candidates.size(), selected.size(), request.topN(), minScore);
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        try {
            RerankResponse response = restClient.post()
                    .uri(properties.getBaseUrl())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(request)
                    .retrieve()
                    .body(RerankResponse.class);
            stopWatch.stop();
            if (response == null || response.results() == null || response.results().isEmpty()) {
                log.warn("Reranker 未返回排序结果，降级为 RRF 排序: elapsedMs={}",
                        stopWatch.getTotalTimeMillis());
                return candidates;
            }
            List<KnowledgeHit> reranked = new ArrayList<>(response.results().size());
            int validResultCount = 0;
            for (int rank = 0; rank < response.results().size(); rank++) {
                RerankResult result = response.results().get(rank);
                if (result.index() < 0 || result.index() >= selected.size()) {
                    continue;
                }
                validResultCount++;
                KnowledgeHit hit = selected.get(result.index());
                boolean kept = result.relevanceScore() >= minScore;
                log.debug("Reranker 结果: rank={}, chunkId={}, file={}, score={}, kept={}",
                        rank + 1, hit.chunkId(), hit.filename(), result.relevanceScore(), kept);
                if (kept) {
                    reranked.add(new KnowledgeHit(
                            hit.chunkId(), hit.fileId(), hit.content(), hit.filename(), hit.sheetName(),
                            hit.sectionId(), hit.parentId(), hit.chunkIndex(), result.relevanceScore()));
                }
            }
            if (validResultCount == 0) {
                log.warn("Reranker 返回结果均无法映射，降级为 RRF 排序: returned={}, elapsedMs={}",
                        response.results().size(), stopWatch.getTotalTimeMillis());
                return candidates;
            }
            log.debug("Reranker 完成: returned={}, kept={}, filtered={}, elapsedMs={}",
                    validResultCount, reranked.size(), validResultCount - reranked.size(),
                    stopWatch.getTotalTimeMillis());
            return List.copyOf(reranked);
        } catch (Exception e) {
            if (stopWatch.isRunning()) {
                stopWatch.stop();
            }
            log.warn("Reranker 调用失败，降级为 RRF 排序: elapsedMs={}, reason={}",
                    stopWatch.getTotalTimeMillis(), e.getMessage());
            return candidates;
        }
    }

    private String rerankText(KnowledgeHit hit) {
        StringBuilder text = new StringBuilder();
        if (StringUtils.hasText(hit.filename())) {
            text.append(hit.filename()).append('\n');
        }
        if (StringUtils.hasText(hit.sheetName())) {
            text.append(hit.sheetName()).append('\n');
        }
        return text.append(hit.content() == null ? "" : hit.content()).toString();
    }

    private record RerankRequest(
            String model,
            String query,
            List<String> documents,
            @JsonProperty("top_n") int topN) {
    }

    private record RerankResponse(List<RerankResult> results) {
    }

    private record RerankResult(
            int index,
            @JsonProperty("relevance_score") double relevanceScore) {
    }
}
