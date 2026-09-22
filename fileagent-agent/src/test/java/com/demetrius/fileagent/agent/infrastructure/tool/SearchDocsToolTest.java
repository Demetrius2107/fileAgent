package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.application.tool.AgentToolContext;
import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.agent.domain.service.AdaptiveRetrievalPolicy;
import com.demetrius.fileagent.agent.infrastructure.config.AdaptiveRetrievalProperties;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.enums.RetrievalQueryType;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.common.exception.BizException;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchDocsToolTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    private AgentToolContext context(AgentRun run, KnowledgeSearchPort port) {
        return context(run, port, KnowledgeScope.global());
    }

    private AgentToolContext context(AgentRun run, KnowledgeSearchPort port, KnowledgeScope scope) {
        return new AgentToolContext(run, port, null, null, scope, 4000);
    }

    private AgentRun startedRun() {
        AgentRun run = AgentRun.pending("r", 1L, "t");
        run.start(NOW);
        return run;
    }

    private AdaptiveRetrievalPolicy defaultPolicy() {
        return new AdaptiveRetrievalPolicy(new AdaptiveRetrievalProperties().toTiers());
    }

    private SearchDocsTool tool() {
        return new SearchDocsTool(new AgentProperties(), defaultPolicy());
    }

    private SearchDocsTool adaptiveTool() {
        AgentProperties properties = new AgentProperties();
        properties.setAdaptiveRetrievalEnabled(true);
        return new SearchDocsTool(properties, defaultPolicy());
    }

    private KnowledgeSearchPort.KnowledgeHit hit(String chunkId, double score) {
        return new KnowledgeSearchPort.KnowledgeHit(
                chunkId, 1L, "正文-" + chunkId, "a.pdf", null, null, null, 0, score);
    }

    private KnowledgeSearchPort.SearchResult searchResult(List<KnowledgeSearchPort.KnowledgeHit> hits) {
        return new KnowledgeSearchPort.SearchResult(hits, hits, null, false, null);
    }

    private String text(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().getFirst()).getText();
    }

    @Test
    void executeShouldSearchAndAddHitsToWhitelist() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.search(any(KnowledgeSearchPort.SearchQuery.class))).thenReturn(List.of(
                new KnowledgeSearchPort.KnowledgeHit("c-1", 1L, "正文内容", "a.pdf", null, null, null, 0, 0.9)));
        AgentRun run = startedRun();
        SearchDocsTool tool = tool();

        ToolResultBlock result = tool.execute(context(run, port), "  关键词  ");

        assertThat(run.isAllowedChunk("c-1")).isTrue();
        assertThat(text(result)).contains("c-1", "a.pdf", "正文内容");
    }

    @Test
    void executeShouldRejectBlankQuery() {
        SearchDocsTool tool = tool();
        AgentRun run = startedRun();

        assertThatThrownBy(() -> tool.execute(context(run, mock(KnowledgeSearchPort.class)), "   "))
                .isInstanceOf(BizException.class);
    }

    @Test
    void executeShouldRejectOverlongQuery() {
        SearchDocsTool tool = tool();
        AgentRun run = startedRun();

        assertThatThrownBy(() -> tool.execute(context(run, mock(KnowledgeSearchPort.class)), "a".repeat(201)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void executeShouldUseTrustedScopeInsteadOfModelSuppliedRange() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.search(any(KnowledgeSearchPort.SearchQuery.class))).thenReturn(List.of());
        AgentRun run = startedRun();
        SearchDocsTool tool = tool();

        tool.execute(context(run, port, new KnowledgeScope("人事制度", "年假")), "年假天数");

        verify(port).search(eq(new KnowledgeSearchPort.SearchQuery(
                "年假天数", "人事制度", "年假", null)));
    }

    @Test
    void executeShouldKeepRunRunningWhenSearchReturnsNoHits() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.search(any(KnowledgeSearchPort.SearchQuery.class))).thenReturn(List.of());
        AgentRun run = startedRun();
        SearchDocsTool tool = tool();

        ToolResultBlock result = tool.execute(context(run, port), "不存在的制度");

        assertThat(text(result)).contains("未检索到相关文档片段");
        assertThat(run.status()).isEqualTo(com.demetrius.fileagent.api.enums.AgentRunStatus.RUNNING);
        assertThat(run.pendingToolFailureCode()).isNull();
    }

    @Test
    void executeShouldRecordControlledFailureWhenKnowledgeSearchThrows() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.search(any(KnowledgeSearchPort.SearchQuery.class)))
                .thenThrow(new IllegalStateException("provider token detail"));
        AgentRun run = startedRun();
        SearchDocsTool tool = tool();

        ToolResultBlock result = tool.execute(context(run, port), "年度目标");

        assertThat(run.pendingToolFailureCode()).isEqualTo(AgentRun.KNOWLEDGE_SEARCH_FAILURE_CODE);
        assertThat(text(result)).contains("知识库检索暂时不可用")
                .doesNotContain("provider token detail");
    }

    @Test
    void schemaShouldSwitchByAdaptiveFlag() {
        SearchDocsTool legacyTool = tool();
        SearchDocsTool structuredTool = adaptiveTool();

        assertThat(String.valueOf(legacyTool.getParameters())).doesNotContain("queryType");
        assertThat(String.valueOf(structuredTool.getParameters()))
                .contains("queryType")
                .contains("MULTI_QUERY")
                .contains("queries");
    }

    @Test
    void structuredExecuteShouldRejectNoneQueryType() {
        SearchDocsTool tool = adaptiveTool();
        AgentRun run = startedRun();

        assertThatThrownBy(() -> tool.executeStructured(
                context(run, mock(KnowledgeSearchPort.class)), RetrievalQueryType.NONE, List.of("年假制度")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void structuredExecuteShouldRejectOverlongSubQuery() {
        SearchDocsTool tool = adaptiveTool();
        AgentRun run = startedRun();

        assertThatThrownBy(() -> tool.executeStructured(
                context(run, mock(KnowledgeSearchPort.class)), RetrievalQueryType.MULTI_HOP,
                List.of("a".repeat(201), "病假规定")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void structuredExecuteShouldRejectSubQueriesBeyondTypeMaximum() {
        SearchDocsTool tool = adaptiveTool();
        AgentRun run = startedRun();

        assertThatThrownBy(() -> tool.executeStructured(
                context(run, mock(KnowledgeSearchPort.class)), RetrievalQueryType.SINGLE_HOP,
                List.of("年假制度", "病假规定")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tool.executeStructured(
                context(run, mock(KnowledgeSearchPort.class)), RetrievalQueryType.TIME_SENSITIVE,
                List.of("年假制度", "病假规定", "加班规定")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void structuredExecuteShouldRejectSubQueriesBelowTypeMinimum() {
        SearchDocsTool tool = adaptiveTool();
        AgentRun run = startedRun();

        assertThatThrownBy(() -> tool.executeStructured(
                context(run, mock(KnowledgeSearchPort.class)), RetrievalQueryType.MULTI_QUERY,
                List.of("年假制度")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void structuredExecuteShouldRejectDuplicateSubQueries() {
        SearchDocsTool tool = adaptiveTool();
        AgentRun run = startedRun();

        assertThatThrownBy(() -> tool.executeStructured(
                context(run, mock(KnowledgeSearchPort.class)), RetrievalQueryType.MULTI_HOP,
                List.of("年假制度", " 年假制度 ")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void structuredExecuteShouldRunSubQueriesSeriallyInInputOrder() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.searchDetailed(any(KnowledgeSearchPort.SearchQuery.class)))
                .thenReturn(searchResult(List.of(hit("m-1", 0.9))));
        AgentRun run = startedRun();
        AdaptiveRetrievalPolicy policy = defaultPolicy();
        SearchDocsTool tool = new SearchDocsTool(new AgentProperties(), policy);

        tool.executeStructured(context(run, port, new KnowledgeScope("人事制度", "年假")),
                RetrievalQueryType.MULTI_HOP, List.of("年假制度", "病假规定"));

        InOrder inOrder = inOrder(port);
        inOrder.verify(port).searchDetailed(new KnowledgeSearchPort.SearchQuery(
                "年假制度", "人事制度", "年假", null, policy.optionsFor(RetrievalQueryType.MULTI_HOP)));
        inOrder.verify(port).searchDetailed(new KnowledgeSearchPort.SearchQuery(
                "病假规定", "人事制度", "年假", null, policy.optionsFor(RetrievalQueryType.MULTI_HOP)));
    }

    @Test
    void structuredExecuteShouldAllowSequentialMultiHopButRequireTwoParallelQueries() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.searchDetailed(any(KnowledgeSearchPort.SearchQuery.class)))
                .thenReturn(searchResult(List.of(hit("m-1", 0.9))));
        AgentRun run = startedRun();
        SearchDocsTool tool = adaptiveTool();

        tool.executeStructured(context(run, port), RetrievalQueryType.MULTI_HOP,
                List.of("找到所依据的制度"));

        assertThat(run.retrievalExecutions()).singleElement().satisfies(execution -> {
            assertThat(execution.queryType()).isEqualTo(RetrievalQueryType.MULTI_HOP);
            assertThat(execution.plannedQueryCount()).isEqualTo(1);
        });
        assertThatThrownBy(() -> tool.executeStructured(context(run, port),
                RetrievalQueryType.MULTI_QUERY, List.of("年假")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void structuredExecuteShouldDedupeByChunkIdAndKeepSourceQueries() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.searchDetailed(any(KnowledgeSearchPort.SearchQuery.class)))
                .thenReturn(searchResult(List.of(hit("m-1", 0.9), hit("m-2", 0.5))))
                .thenReturn(searchResult(List.of(hit("m-2", 0.8), hit("m-3", 0.4))));
        AgentRun run = startedRun();
        SearchDocsTool tool = adaptiveTool();

        ToolResultBlock block = tool.executeStructured(context(run, port),
                RetrievalQueryType.MULTI_QUERY, List.of("年假制度", "病假规定"));

        String rendered = text(block);
        assertThat(rendered).contains("chunkId=m-2");
        assertThat(rendered.indexOf("chunkId=m-2")).isEqualTo(rendered.lastIndexOf("chunkId=m-2"));
        assertThat(rendered).contains("查询=[2, 1]");
        assertThat(run.allowedChunkIds()).containsExactlyInAnyOrder("m-1", "m-2", "m-3");
        assertThat(run.lastToolResultCount()).isEqualTo(3);
    }

    @Test
    void structuredExecuteShouldTruncateToTierFinalCap() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.searchDetailed(any(KnowledgeSearchPort.SearchQuery.class)))
                .thenReturn(searchResult(List.of(
                        hit("m-1", 0.9), hit("m-2", 0.8), hit("m-3", 0.7),
                        hit("m-4", 0.6), hit("m-5", 0.5), hit("m-6", 0.4))))
                .thenReturn(searchResult(List.of(
                        hit("m-5", 0.95), hit("m-6", 0.94), hit("n-1", 0.93),
                        hit("n-2", 0.92), hit("n-3", 0.91), hit("n-4", 0.90))));
        AgentRun run = startedRun();
        SearchDocsTool tool = adaptiveTool();

        tool.executeStructured(context(run, port), RetrievalQueryType.MULTI_HOP,
                List.of("年假制度", "病假规定"));

        assertThat(run.lastToolResultCount()).isEqualTo(8);
        assertThat(run.allowedChunkIds()).hasSize(8)
                .containsExactlyInAnyOrder("m-1", "m-2", "m-5", "m-6", "n-1", "n-2", "n-3", "n-4");
    }

    @Test
    void structuredExecuteShouldSummarizeMissingSubQueries() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.searchDetailed(any(KnowledgeSearchPort.SearchQuery.class)))
                .thenReturn(searchResult(List.of(hit("m-1", 0.9))))
                .thenReturn(searchResult(List.of()));
        AgentRun run = startedRun();
        SearchDocsTool tool = adaptiveTool();

        ToolResultBlock block = tool.executeStructured(context(run, port),
                RetrievalQueryType.MULTI_HOP, List.of("年假制度", "病假规定"));

        assertThat(text(block)).contains("chunkId=m-1", "缺失子查询", "病假规定");
        assertThat(run.status()).isEqualTo(com.demetrius.fileagent.api.enums.AgentRunStatus.RUNNING);
        assertThat(run.pendingToolFailureCode()).isNull();
        assertThat(run.lastToolResultCount()).isEqualTo(1);
    }

    @Test
    void structuredExecuteShouldKeepRunRunningWhenAllSubQueriesMiss() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.searchDetailed(any(KnowledgeSearchPort.SearchQuery.class)))
                .thenReturn(searchResult(List.of()));
        AgentRun run = startedRun();
        SearchDocsTool tool = adaptiveTool();

        ToolResultBlock block = tool.executeStructured(context(run, port),
                RetrievalQueryType.MULTI_HOP, List.of("年假制度", "病假规定"));

        assertThat(text(block)).contains("未检索到相关文档片段", "缺失子查询", "年假制度", "病假规定");
        assertThat(run.status()).isEqualTo(com.demetrius.fileagent.api.enums.AgentRunStatus.RUNNING);
        assertThat(run.lastToolResultCount()).isEqualTo(0);
    }

    @Test
    void structuredExecuteShouldCancelRemainingSubQueriesOnInfraFailure() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.searchDetailed(any(KnowledgeSearchPort.SearchQuery.class)))
                .thenThrow(new IllegalStateException("provider token detail"));
        AgentRun run = startedRun();
        SearchDocsTool tool = adaptiveTool();

        ToolResultBlock block = tool.executeStructured(context(run, port),
                RetrievalQueryType.MULTI_HOP, List.of("年假制度", "病假规定"));

        verify(port, times(1)).searchDetailed(any(KnowledgeSearchPort.SearchQuery.class));
        assertThat(run.pendingToolFailureCode()).isEqualTo(AgentRun.KNOWLEDGE_SEARCH_FAILURE_CODE);
        assertThat(text(block)).contains("知识库检索暂时不可用")
                .doesNotContain("provider token detail");
        assertThat(run.lastToolResultCount()).isEqualTo(0);
    }

    @Test
    void structuredExecuteShouldStopAtEffectiveToolTimeout() {
        AgentProperties properties = new AgentProperties();
        properties.setToolTimeout(Duration.ofMillis(30));
        SearchDocsTool tool = new SearchDocsTool(properties, defaultPolicy());
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.searchDetailed(any(KnowledgeSearchPort.SearchQuery.class))).thenAnswer(invocation -> {
            Thread.sleep(200);
            return searchResult(List.of(hit("m-1", 0.9)));
        });
        AgentRun run = startedRun();

        ToolResultBlock block = tool.executeStructured(context(run, port),
                RetrievalQueryType.MULTI_HOP, List.of("年假制度", "病假规定"));

        verify(port, times(1)).searchDetailed(any(KnowledgeSearchPort.SearchQuery.class));
        assertThat(run.pendingToolFailureCode()).isEqualTo(AgentRun.KNOWLEDGE_SEARCH_FAILURE_CODE);
        assertThat(text(block)).contains("知识库检索暂时不可用");
    }

    @Test
    void structuredCallShouldRecordRetrievalExecutionForLegalRound() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.searchDetailed(any(KnowledgeSearchPort.SearchQuery.class)))
                .thenReturn(searchResult(List.of(hit("m-1", 0.9), hit("m-2", 0.5))))
                .thenReturn(searchResult(List.of(hit("m-2", 0.8), hit("m-3", 0.4))));
        AgentRun run = startedRun();
        SearchDocsTool tool = adaptiveTool();

        tool.callStructured(context(run, port),
                Map.of("queryType", "MULTI_HOP", "queries", List.of("年假制度", "病假规定")));

        assertThat(run.retrievalRoundCount()).isEqualTo(1);
        assertThat(run.invalidPlanCount()).isZero();
        AgentRun.RetrievalExecution execution = run.retrievalExecutions().getFirst();
        assertThat(execution.queryType()).isEqualTo(RetrievalQueryType.MULTI_HOP);
        assertThat(execution.strategyId()).isEqualTo("MULTI_HOP");
        assertThat(execution.plannedQueryCount()).isEqualTo(2);
        assertThat(execution.executedQueries()).isEqualTo(2);
        assertThat(execution.perQueryHitCount()).containsExactly(2, 2);
        assertThat(execution.candidateChunkIds()).containsExactly("m-1", "m-2", "m-3");
        assertThat(execution.finalChunkIds()).containsExactly("m-1", "m-2", "m-3");
        assertThat(execution.rerankRequested()).isTrue();
        assertThat(execution.rerankApplied()).isFalse();
        assertThat(execution.fallbackCode()).isNull();
    }

    @Test
    void structuredCallShouldDistinguishCandidateAndFinalChunkIds() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.searchDetailed(any(KnowledgeSearchPort.SearchQuery.class)))
                .thenReturn(searchResult(List.of(
                        hit("m-1", 0.9), hit("m-2", 0.8), hit("m-3", 0.7),
                        hit("m-4", 0.6), hit("m-5", 0.5), hit("m-6", 0.4))))
                .thenReturn(searchResult(List.of(
                        hit("m-5", 0.95), hit("m-6", 0.94), hit("n-1", 0.93),
                        hit("n-2", 0.92), hit("n-3", 0.91), hit("n-4", 0.90))));
        AgentRun run = startedRun();
        SearchDocsTool tool = adaptiveTool();

        tool.callStructured(context(run, port),
                Map.of("queryType", "MULTI_HOP", "queries", List.of("年假制度", "病假规定")));

        AgentRun.RetrievalExecution execution = run.retrievalExecutions().getFirst();
        assertThat(execution.candidateChunkIds()).hasSize(10);
        assertThat(execution.finalChunkIds()).hasSize(8)
                .isSubsetOf(execution.candidateChunkIds());
    }

    @Test
    void structuredCallShouldRecordRerankDegradationFallback() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        KnowledgeSearchPort.SearchResult degraded = new KnowledgeSearchPort.SearchResult(
                List.of(hit("m-1", 0.9)), List.of(hit("m-1", 0.9)), null, false, "RERANK_FAILED");
        when(port.searchDetailed(any(KnowledgeSearchPort.SearchQuery.class))).thenReturn(degraded);
        AgentRun run = startedRun();
        SearchDocsTool tool = adaptiveTool();

        tool.callStructured(context(run, port),
                Map.of("queryType", "MULTI_HOP", "queries", List.of("年假制度", "病假规定")));

        AgentRun.RetrievalExecution execution = run.retrievalExecutions().getFirst();
        assertThat(execution.rerankRequested()).isTrue();
        assertThat(execution.rerankApplied()).isFalse();
        assertThat(execution.fallbackCode()).isEqualTo("RERANK_FAILED");
    }

    @Test
    void structuredCallShouldRecordInvalidPlanAndTerminateOnSecond() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        AgentRun run = startedRun();
        SearchDocsTool tool = adaptiveTool();
        Map<String, Object> invalidInput = Map.of(
                "queryType", "SINGLE_HOP", "queries", List.of("年假制度", "病假规定"));

        ToolResultBlock first = tool.callStructured(context(run, port), invalidInput);

        assertThat(text(first)).contains("检索计划无效", "子查询数量不能超过");
        assertThat(run.invalidPlanCount()).isEqualTo(1);
        assertThat(run.isRunning()).isTrue();
        verify(port, never()).searchDetailed(any(KnowledgeSearchPort.SearchQuery.class));

        ToolResultBlock second = tool.callStructured(context(run, port), invalidInput);

        assertThat(run.status()).isEqualTo(com.demetrius.fileagent.api.enums.AgentRunStatus.FAILED);
        assertThat(run.failureCode()).isEqualTo(AgentRun.AGENT_RETRIEVAL_PLAN_INVALID);
        assertThat(run.pendingToolFailureCode()).isEqualTo(AgentRun.AGENT_RETRIEVAL_PLAN_INVALID);
        assertThat(text(second)).contains("检索计划无效");
    }

    @Test
    void structuredCallShouldRefuseThirdRetrievalRound() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        AgentRun run = startedRun();
        run.recordRetrievalExecution(new AgentRun.RetrievalExecution(
                RetrievalQueryType.MULTI_HOP, "MULTI_HOP", 2, 2,
                List.of(1, 1), List.of("a"), List.of("a"), true, false, null));
        run.recordRetrievalExecution(new AgentRun.RetrievalExecution(
                RetrievalQueryType.MULTI_HOP, "MULTI_HOP", 2, 2,
                List.of(1, 1), List.of("b"), List.of("b"), true, false, null));
        SearchDocsTool tool = adaptiveTool();

        ToolResultBlock block = tool.callStructured(context(run, port),
                Map.of("queryType", "MULTI_HOP", "queries", List.of("年假制度", "病假规定")));

        assertThat(text(block)).contains("最多 2 轮检索");
        assertThat(run.retrievalRoundCount()).isEqualTo(2);
        assertThat(run.isRunning()).isTrue();
        verify(port, never()).searchDetailed(any(KnowledgeSearchPort.SearchQuery.class));
    }
}
