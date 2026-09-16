package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.application.tool.AgentToolContext;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort.KnowledgeHit;
import com.demetrius.fileagent.common.exception.BizException;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * {@code search_docs} 只读工具：复用现有 BM25/KNN/RRF/rerank 混合检索链路。
 * <p>
 * query 去首尾空格后 1..200 字，最多返回 5 条命中；命中的 chunkId 写入本次
 * Run 的证据白名单；单条片段按上限截断。
 */
public class SearchDocsTool extends ToolBase {

    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_HITS = 5;

    public SearchDocsTool() {
        super(ToolBase.builder()
                .name("search_docs")
                .description("在全局知识库中检索与问题相关的文档片段，返回命中片段的正文、来源文件名与 chunkId。")
                .inputSchema(inputSchema())
                .readOnly(true)
                .concurrencySafe(true));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        AgentToolContext context = context(param);
        String query = param.getInput().get("query") == null ? null : String.valueOf(param.getInput().get("query"));
        return Mono.fromCallable(() -> execute(context, query));
    }

    public ToolResultBlock execute(AgentToolContext context, String query) {
        if (query == null || query.isBlank()) {
            throw new BizException("search_docs 检索关键词不能为空");
        }
        String trimmed = query.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new BizException("search_docs 检索关键词不能超过 " + MAX_QUERY_LENGTH + " 字");
        }
        List<KnowledgeHit> hits = context.knowledgeSearchPort()
                .search(KnowledgeSearchPort.SearchQuery.of(trimmed)).stream()
                .limit(MAX_HITS)
                .toList();
        hits.forEach(hit -> context.run().addAllowedChunk(hit.chunkId()));
        return ToolResultBlock.text(render(hits, context.singleToolResultCharacters()));
    }

    private AgentToolContext context(ToolCallParam param) {
        if (param.getRuntimeContext() == null) {
            throw new BizException("search_docs 缺少运行上下文");
        }
        return param.getRuntimeContext().get(AgentToolContext.class);
    }

    private String render(List<KnowledgeHit> hits, int maxChars) {
        if (hits.isEmpty()) {
            return "未检索到相关文档片段。";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeHit hit = hits.get(i);
            sb.append("[").append(i + 1).append("] chunkId=").append(hit.chunkId())
                    .append(" 来源=").append(hit.filename())
                    .append(" 分数=").append(hit.score()).append('\n')
                    .append(truncate(hit.content(), maxChars)).append("\n\n");
        }
        return sb.toString();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…(截断)";
    }

    private static Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "检索关键词（1-200 字）")),
                "required", List.of("query"));
    }
}
