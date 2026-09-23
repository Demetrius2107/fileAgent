package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.application.tool.AgentToolContext;
import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.agent.domain.service.AdaptiveRetrievalPolicy;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.enums.RetrievalQueryType;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort.KnowledgeHit;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort.SearchOptions;
import com.demetrius.fileagent.common.exception.BizException;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code search_docs} 只读工具：复用现有 BM25/KNN/RRF/rerank 混合检索链路。
 * <p>
 * Phase 1 路径（Adaptive 关闭）：query 去首尾空格后 1..200 字，最多返回 5 条命中。
 * 结构化路径（Adaptive 开启）：模型声明 queryType 与子查询列表，服务端按档位映射检索参数，
 * 逐条串行检索后按规格合并去重；命中的 chunkId 写入本次 Run 的证据白名单。
 */
@Slf4j
public class SearchDocsTool extends ToolBase {

    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_HITS = 5;
    private static final String DESCRIPTION =
            "在全局知识库中检索与问题相关的文档片段，返回命中片段的正文、来源文件名与 chunkId。";
    private static final String STRUCTURED_DESCRIPTION =
            "在全局知识库中按声明的查询类型检索文档片段，返回命中片段的正文、来源文件名、chunkId 与子查询来源；无需检索时不要调用本工具。";
    private static final String UNAVAILABLE_TEXT = "知识库检索暂时不可用，当前运行将结束。";

    private static final Comparator<ScoredEntry> MERGE_ORDER = Comparator
            .comparingDouble(ScoredEntry::normalizedScore).reversed()
            .thenComparingInt(ScoredEntry::queryIndex)
            .thenComparingInt(ScoredEntry::rankInQuery)
            .thenComparing(entry -> entry.hit().chunkId(), Comparator.nullsLast(Comparator.naturalOrder()));
    private static final Comparator<KnowledgeHit> HIT_ORDER = Comparator
            .comparingDouble(KnowledgeHit::score).reversed()
            .thenComparing(KnowledgeHit::fileId, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(KnowledgeHit::chunkIndex)
            .thenComparing(KnowledgeHit::chunkId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final AgentProperties agentProperties;
    private final AdaptiveRetrievalPolicy adaptiveRetrievalPolicy;

    public SearchDocsTool(AgentProperties agentProperties, AdaptiveRetrievalPolicy adaptiveRetrievalPolicy) {
        super(ToolBase.builder()
                .name("search_docs")
                .description(agentProperties.isAdaptiveRetrievalEnabled() ? STRUCTURED_DESCRIPTION : DESCRIPTION)
                .inputSchema(inputSchema(agentProperties.isAdaptiveRetrievalEnabled()))
                .readOnly(true)
                .concurrencySafe(true));
        this.agentProperties = agentProperties;
        this.adaptiveRetrievalPolicy = adaptiveRetrievalPolicy;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        AgentToolContext context = context(param);
        if (!agentProperties.isAdaptiveRetrievalEnabled()) {
            String query = param.getInput().get("query") == null ? null : String.valueOf(param.getInput().get("query"));
            return Mono.fromCallable(() -> searchByKeyword(context, query));
        }
        return Mono.fromCallable(() -> callStructured(context, param.getInput()));
    }

    /** Phase 1 关键词检索入口，保留既有方法名供调用方使用。 */
    public ToolResultBlock execute(AgentToolContext context, String query) {
        return searchByKeyword(context, query);
    }

    private ToolResultBlock searchByKeyword(AgentToolContext context, String query) {
        if (query == null || query.isBlank()) {
            throw new BizException("search_docs 检索关键词不能为空");
        }
        String trimmed = query.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new BizException("search_docs 检索关键词不能超过 " + MAX_QUERY_LENGTH + " 字");
        }
        if (context.run().shouldStopToolExpansion(context.budget())) {
            return budgetExhausted(context);
        }
        List<KnowledgeHit> hits;
        try {
            KnowledgeSearchPort.SearchQuery searchQuery = new KnowledgeSearchPort.SearchQuery(
                    trimmed, context.scope().ragName(), context.scope().knowledgeTag(), null);
            hits = context.knowledgeSearchPort().search(searchQuery).stream()
                    .limit(MAX_HITS)
                    .toList();
        } catch (RuntimeException exception) {
            log.warn("search_docs 检索失败 runId={}, queryLength={}",
                    context.run().runId(), trimmed.length(), exception);
            return controlledFailure(context);
        }
        RenderedHits rendered = render(deduplicateAndSort(hits).stream().limit(MAX_HITS).toList(), context);
        authorize(context, rendered.hits());
        context.run().recordToolResult(rendered.hits().size());
        context.run().recordSearchSnippetCharacters(rendered.contentCharacters());
        context.run().recordToolResultCharacters(ToolOutputBudget.length(rendered.text()));
        return ToolResultBlock.text(rendered.text());
    }

    /** 结构化入口：第三轮检索直接拒绝；校验失败递增非法计划计数并返回受控校验信息，允许模型修正一次。 */
    ToolResultBlock callStructured(AgentToolContext context, Map<String, Object> input) {
        if (context.run().retrievalRoundCount() >= AgentRun.MAX_RETRIEVAL_ROUNDS) {
            context.run().recordToolResult(0);
            return ToolResultBlock.text("单次运行最多 " + AgentRun.MAX_RETRIEVAL_ROUNDS
                    + " 轮检索，请基于已有证据回答；证据不完整时如实说明。");
        }
        try {
            return executeStructured(context, parseQueryType(input.get("queryType")),
                    parseQueries(input.get("queries")));
        } catch (BizException exception) {
            return invalidPlan(context, exception);
        }
    }

    private ToolResultBlock invalidPlan(AgentToolContext context, BizException exception) {
        context.run().recordInvalidPlan(Instant.now());
        return ToolResultBlock.text("检索计划无效，" + exception.getMessage() + "；请修正后重试一次。");
    }

    /**
     * 结构化检索：模型只声明查询类型与子查询，检索参数由服务端档位决定；
     * 有效工具超时 = toolTimeout × 子查询数（下限 1 条）。
     */
    public ToolResultBlock executeStructured(AgentToolContext context, RetrievalQueryType queryType,
                                             List<String> queries) {
        if (context.run().shouldStopToolExpansion(context.budget())) {
            return budgetExhausted(context);
        }
        List<String> ordered = validateStructured(queryType, queries);
        SearchOptions planned = adaptiveRetrievalPolicy.optionsFor(queryType);
        Instant deadline = Instant.now().plus(
                agentProperties.getToolTimeout().multipliedBy(Math.max(1, ordered.size())));
        List<ScoredEntry> collected = new ArrayList<>();
        List<String> missingQueries = new ArrayList<>();
        List<Integer> perQueryHitCounts = new ArrayList<>();
        List<KnowledgeSearchPort.SearchResult> executedResults = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            if (context.run().shouldStopToolExpansion(context.budget())) {
                return budgetExhausted(context);
            }
            if (i > 0 && Instant.now().isAfter(deadline)) {
                return controlledFailure(context);
            }
            String subQuery = ordered.get(i);
            try {
                KnowledgeSearchPort.SearchResult result = context.knowledgeSearchPort()
                        .searchDetailed(buildQuery(context, subQuery, planned));
                executedResults.add(result);
                perQueryHitCounts.add(result.finalHits().size());
                appendEntries(collected, missingQueries, subQuery, i, result.finalHits());
            } catch (RuntimeException exception) {
                log.warn("search_docs 子查询检索失败 runId={}, queryType={}, subQueryLength={}",
                        context.run().runId(), queryType, subQuery.length(), exception);
                return controlledFailure(context);
            }
        }
        List<ScoredEntry> candidates = merge(queryType, collected);
        List<ScoredEntry> merged = candidates.stream()
                .limit(adaptiveRetrievalPolicy.finalHitCap(queryType))
                .toList();
        RenderedScoredHits rendered = renderStructured(merged, missingQueries, context);
        rendered.hits().forEach(entry -> {
            context.run().addAllowedChunk(entry.hit().chunkId());
            context.run().addAllowedChunk(entry.hit().parentId());
            context.run().addAllowedFile(entry.hit().fileId());
            context.run().addRetrievedHit(entry.hit());
        });
        context.run().recordRetrievalExecution(new AgentRun.RetrievalExecution(
                queryType,
                planned.strategyId(),
                ordered.size(),
                executedResults.size(),
                perQueryHitCounts,
                chunkIds(candidates),
                chunkIds(rendered.hits()),
                planned.rerankEnabled(),
                planned.rerankEnabled() && executedResults.stream()
                        .allMatch(KnowledgeSearchPort.SearchResult::rerankApplied),
                executedResults.stream()
                        .map(KnowledgeSearchPort.SearchResult::fallbackCode)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null)));
        context.run().recordToolResult(rendered.hits().size());
        context.run().recordSearchSnippetCharacters(rendered.contentCharacters());
        context.run().recordToolResultCharacters(ToolOutputBudget.length(rendered.text()));
        return ToolResultBlock.text(rendered.text());
    }

    private List<String> validateStructured(RetrievalQueryType queryType, List<String> queries) {
        if (queryType == null) {
            throw new BizException("search_docs 缺少查询类型");
        }
        if (queryType == RetrievalQueryType.NONE) {
            throw new BizException("NONE 不允许作为 search_docs 参数，不检索时不要调用本工具");
        }
        if (queries == null || queries.isEmpty()) {
            throw new BizException("search_docs 子查询列表不能为空");
        }
        int min = adaptiveRetrievalPolicy.minSubQueries(queryType);
        int max = adaptiveRetrievalPolicy.maxSubQueries(queryType);
        if (queries.size() < min) {
            throw new BizException("search_docs 子查询数量不足，" + queryType + " 至少需要 " + min + " 条");
        }
        if (queries.size() > max) {
            throw new BizException("search_docs 子查询数量不能超过 " + max + " 条");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                throw new BizException("search_docs 子查询不能为空");
            }
            String trimmed = query.trim();
            if (trimmed.length() > MAX_QUERY_LENGTH) {
                throw new BizException("search_docs 子查询不能超过 " + MAX_QUERY_LENGTH + " 字");
            }
            if (!unique.add(trimmed)) {
                throw new BizException("search_docs 子查询重复: " + trimmed);
            }
        }
        return List.copyOf(unique);
    }

    private KnowledgeSearchPort.SearchQuery buildQuery(AgentToolContext context, String subQuery,
                                                       SearchOptions options) {
        return new KnowledgeSearchPort.SearchQuery(
                subQuery,
                context.scope().ragName(),
                context.scope().knowledgeTag(),
                null,
                options);
    }

    private void appendEntries(List<ScoredEntry> collected, List<String> missingQueries,
                               String subQuery, int queryIndex, List<KnowledgeHit> finalHits) {
        if (finalHits.isEmpty()) {
            missingQueries.add(subQuery);
            return;
        }
        double maxScore = finalHits.stream().mapToDouble(KnowledgeHit::score).max().orElse(0);
        for (int rank = 0; rank < finalHits.size(); rank++) {
            KnowledgeHit hit = finalHits.get(rank);
            double normalized = maxScore > 0 ? hit.score() / maxScore : 0;
            collected.add(new ScoredEntry(hit, queryIndex, rank, normalized, List.of(queryIndex + 1)));
        }
    }

    /** 并列查询和比较先保留每路 top-1；按归一化分排序后按 chunkId 去重（保留原始分最高版本并合并来源）。 */
    private List<ScoredEntry> merge(RetrievalQueryType queryType, List<ScoredEntry> collected) {
        List<ScoredEntry> pool = new ArrayList<>(collected);
        pool.sort(MERGE_ORDER);
        Map<String, ScoredEntry> merged = new LinkedHashMap<>();
        if (queryType == RetrievalQueryType.COMPARISON || queryType == RetrievalQueryType.MULTI_QUERY) {
            collected.stream()
                    .filter(entry -> entry.rankInQuery() == 0)
                    .forEach(entry -> merged.putIfAbsent(entry.hit().chunkId(), entry));
        }
        for (ScoredEntry entry : pool) {
            ScoredEntry existing = merged.get(entry.hit().chunkId());
            if (existing == null) {
                merged.put(entry.hit().chunkId(), entry);
            } else if (entry.hit().score() > existing.hit().score()) {
                merged.put(entry.hit().chunkId(), entry.mergeSources(existing.sourceIndices()));
            } else {
                merged.put(entry.hit().chunkId(), existing.withSource(entry.queryIndex() + 1));
            }
        }
        return List.copyOf(merged.values());
    }

    private List<String> chunkIds(List<ScoredEntry> entries) {
        return entries.stream().map(entry -> entry.hit().chunkId()).toList();
    }

    private RenderedScoredHits renderStructured(List<ScoredEntry> merged,
                                                List<String> missingQueries,
                                                AgentToolContext context) {
        int maxChars = Math.min(context.singleToolResultCharacters(),
                context.run().remainingToolResultCharacters(context.budget()));
        List<ScoredEntry> ordered = prioritizeScoredByFile(merged);
        StringBuilder sb = new StringBuilder();
        List<ScoredEntry> rendered = new ArrayList<>();
        int contentCharacters = 0;
        for (int i = 0; i < ordered.size(); i++) {
            ScoredEntry entry = ordered.get(i);
            String prefix = "[" + (i + 1) + "] chunkId=" + entry.hit().chunkId()
                    + " 来源=" + entry.hit().filename()
                    + " 分数=" + entry.hit().score()
                    + " 查询=" + entry.sourceIndices() + "\n";
            String content = ToolOutputBudget.truncate(entry.hit().content(), context.budget().searchSnippetCharacters());
            int before = ToolOutputBudget.length(sb.toString());
            int remaining = maxChars - before;
            if (remaining <= 0 || !ToolOutputBudget.append(sb, prefix, content, "\n\n", maxChars)) {
                break;
            }
            rendered.add(entry);
            contentCharacters += includedContentCharacters(content, prefix, remaining);
        }
        if (rendered.isEmpty() && ordered.isEmpty()) {
            ToolOutputBudget.append(sb, "未检索到相关文档片段。\n", "", "", maxChars);
        }
        if (!missingQueries.isEmpty()) {
            ToolOutputBudget.append(sb, "缺失子查询：\n", missingQueries.stream()
                    .map(query -> "- " + query).collect(java.util.stream.Collectors.joining("\n"))
                    + "\n", "", maxChars);
        }
        String text = sb.isEmpty() ? ToolOutputBudget.truncate("未检索到相关文档片段。", maxChars) : sb.toString();
        return new RenderedScoredHits(List.copyOf(rendered), contentCharacters, text);
    }

    private AgentToolContext context(ToolCallParam param) {
        if (param.getRuntimeContext() == null) {
            throw new BizException("search_docs 缺少运行上下文");
        }
        return param.getRuntimeContext().get(AgentToolContext.class);
    }

    private ToolResultBlock controlledFailure(AgentToolContext context) {
        context.run().recordToolFailure(AgentRun.KNOWLEDGE_SEARCH_FAILURE_CODE);
        context.run().recordToolResult(0);
        return ToolResultBlock.text(UNAVAILABLE_TEXT);
    }

    private RenderedHits render(List<KnowledgeHit> hits, AgentToolContext context) {
        int maxChars = Math.min(context.singleToolResultCharacters(),
                context.run().remainingToolResultCharacters(context.budget()));
        List<KnowledgeHit> ordered = prioritizeByFile(deduplicateAndSort(hits));
        StringBuilder sb = new StringBuilder();
        List<KnowledgeHit> rendered = new ArrayList<>();
        int contentCharacters = 0;
        for (int i = 0; i < ordered.size(); i++) {
            KnowledgeHit hit = ordered.get(i);
            String prefix = "[" + (i + 1) + "] chunkId=" + hit.chunkId()
                    + " 来源=" + hit.filename()
                    + " 分数=" + hit.score() + "\n";
            String content = ToolOutputBudget.truncate(hit.content(), context.budget().searchSnippetCharacters());
            int before = ToolOutputBudget.length(sb.toString());
            int remaining = maxChars - before;
            if (remaining <= 0 || !ToolOutputBudget.append(sb, prefix, content, "\n\n", maxChars)) {
                break;
            }
            rendered.add(hit);
            contentCharacters += includedContentCharacters(content, prefix, remaining);
        }
        String text = rendered.isEmpty()
                ? ToolOutputBudget.truncate("未检索到相关文档片段。", maxChars)
                : sb.toString();
        return new RenderedHits(List.copyOf(rendered), contentCharacters, text);
    }

    private List<KnowledgeHit> deduplicateAndSort(List<KnowledgeHit> hits) {
        Map<String, KnowledgeHit> unique = new LinkedHashMap<>();
        for (KnowledgeHit hit : hits == null ? List.<KnowledgeHit>of() : hits) {
            if (hit == null || hit.chunkId() == null) {
                continue;
            }
            KnowledgeHit previous = unique.get(hit.chunkId());
            if (previous == null || HIT_ORDER.compare(hit, previous) < 0) {
                unique.put(hit.chunkId(), hit);
            }
        }
        return unique.values().stream().sorted(HIT_ORDER).toList();
    }

    private List<KnowledgeHit> prioritizeByFile(List<KnowledgeHit> hits) {
        List<KnowledgeHit> firstPerFile = new ArrayList<>();
        List<KnowledgeHit> remaining = new ArrayList<>();
        Set<Long> files = new LinkedHashSet<>();
        for (KnowledgeHit hit : hits) {
            if (files.add(hit.fileId())) {
                firstPerFile.add(hit);
            } else {
                remaining.add(hit);
            }
        }
        firstPerFile.addAll(remaining);
        return firstPerFile;
    }

    private List<ScoredEntry> prioritizeScoredByFile(List<ScoredEntry> entries) {
        List<ScoredEntry> firstPerFile = new ArrayList<>();
        List<ScoredEntry> remaining = new ArrayList<>();
        Set<Long> files = new LinkedHashSet<>();
        for (ScoredEntry entry : entries) {
            if (files.add(entry.hit().fileId())) {
                firstPerFile.add(entry);
            } else {
                remaining.add(entry);
            }
        }
        firstPerFile.addAll(remaining);
        return firstPerFile;
    }

    private int includedContentCharacters(String content, String prefix, int remaining) {
        int available = remaining - ToolOutputBudget.length(prefix) - 2;
        if (available >= ToolOutputBudget.length(content)) {
            return ToolOutputBudget.length(content);
        }
        return Math.max(0, available - ToolOutputBudget.length("…(截断)"));
    }

    private void authorize(AgentToolContext context, List<KnowledgeHit> hits) {
        hits.forEach(hit -> {
            context.run().addAllowedChunk(hit.chunkId());
            context.run().addAllowedChunk(hit.parentId());
            context.run().addAllowedFile(hit.fileId());
            context.run().addRetrievedHit(hit);
        });
    }

    private ToolResultBlock budgetExhausted(AgentToolContext context) {
        context.run().recordToolResult(0);
        return ToolResultBlock.text(ToolOutputBudget.BUDGET_EXHAUSTED_MESSAGE);
    }

    private RetrievalQueryType parseQueryType(Object raw) {
        if (raw == null) {
            throw new BizException("search_docs 缺少查询类型");
        }
        try {
            return RetrievalQueryType.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException exception) {
            throw new BizException("未知检索类型: " + raw);
        }
    }

    private List<String> parseQueries(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (raw != null) {
            return List.of(String.valueOf(raw));
        }
        return List.of();
    }

    private static Map<String, Object> inputSchema(boolean adaptiveRetrieval) {
        if (!adaptiveRetrieval) {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string", "description", "检索关键词（1-200 字）")),
                    "required", List.of("query"));
        }
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "queryType", Map.of(
                                "type", "string",
                                "enum", List.of("SINGLE_HOP", "MULTI_QUERY", "MULTI_HOP", "COMPARISON", "AGGREGATION", "TIME_SENSITIVE"),
                                "description", "检索类型；NONE 仅表示无需检索，不允许作为本工具参数"),
                        "queries", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "minItems", 1,
                                "maxItems", 3,
                                "description", "子查询列表（每条 1-200 字）")),
                "required", List.of("queryType", "queries"));
    }

    private record RenderedHits(List<KnowledgeHit> hits, int contentCharacters, String text) {
    }

    private record RenderedScoredHits(List<ScoredEntry> hits, int contentCharacters, String text) {
    }

    /** 合并池条目：命中片段 + 来源查询下标 + 归一化分；去重时保留原始分最高版本并合并来源下标。 */
    private record ScoredEntry(KnowledgeHit hit, int queryIndex, int rankInQuery,
                               double normalizedScore, List<Integer> sourceIndices) {

        private ScoredEntry withSource(int sourceIndex) {
            return mergeSources(List.of(sourceIndex));
        }

        private ScoredEntry mergeSources(List<Integer> moreSources) {
            List<Integer> combined = new ArrayList<>(sourceIndices);
            for (Integer source : moreSources) {
                if (!combined.contains(source)) {
                    combined.add(source);
                }
            }
            return new ScoredEntry(hit, queryIndex, rankInQuery, normalizedScore, List.copyOf(combined));
        }
    }
}
