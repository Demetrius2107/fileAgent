package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.application.tool.AgentToolContext;
import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.common.exception.BizException;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * {@code read_document_context} 只读工具：读取已授权 chunk 的正文与来源元数据。
 * <p>
 * 一次 1..3 个 ID，全部必须存在于本次 Run 的 {@code search_docs} 白名单，
 * 否则拒绝读取——即使模型编造 chunkId 也无法读取未检索内容。
 */
public class ReadDocumentContextTool extends ToolBase {

    public ReadDocumentContextTool() {
        super(ToolBase.builder()
                .name("read_document_context")
                .description("读取已检索到的文档片段的完整正文与来源信息。只能读取本次运行中 search_docs 返回过的 chunk。")
                .inputSchema(inputSchema())
                .readOnly(true)
                .concurrencySafe(true));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        AgentToolContext context = context(param);
        List<String> chunkIds = chunkIds(param.getInput().get("chunkIds"));
        return Mono.fromCallable(() -> execute(context, chunkIds));
    }

    public ToolResultBlock execute(AgentToolContext context, List<String> chunkIds) {
        int maxChunks = context.budget().readMaxChunks();
        if (chunkIds == null || chunkIds.isEmpty() || chunkIds.size() > maxChunks) {
            throw new BizException("read_document_context 每次只能读取 1~" + maxChunks + " 个 chunk");
        }
        for (String id : chunkIds) {
            if (!context.run().isAllowedChunk(id)) {
                throw new BizException("chunk " + id + " 未在本次检索结果中，拒绝读取");
            }
        }
        if (context.run().shouldStopToolExpansion(context.budget())) {
            context.run().recordToolResult(0);
            return ToolResultBlock.text(ToolOutputBudget.BUDGET_EXHAUSTED_MESSAGE);
        }
        List<KnowledgeContextPort.KnowledgeChunkContext> chunks = context.knowledgeContextPort().read(chunkIds);
        RenderedContext rendered = render(chunks, context);
        context.run().recordToolResult(rendered.count());
        context.run().recordDocumentReadCharacters(rendered.contentCharacters());
        context.run().recordToolResultCharacters(ToolOutputBudget.length(rendered.text()));
        return ToolResultBlock.text(rendered.text());
    }

    private AgentToolContext context(ToolCallParam param) {
        if (param.getRuntimeContext() == null) {
            throw new BizException("read_document_context 缺少运行上下文");
        }
        return param.getRuntimeContext().get(AgentToolContext.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> chunkIds(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private RenderedContext render(List<KnowledgeContextPort.KnowledgeChunkContext> chunks,
                                   AgentToolContext context) {
        if (chunks.isEmpty()) {
            return new RenderedContext(0, 0, "未读取到任何片段。");
        }
        int maxChars = Math.min(context.singleToolResultCharacters(),
                context.run().remainingToolResultCharacters(context.budget()));
        StringBuilder sb = new StringBuilder();
        int contentCharacters = 0;
        int count = 0;
        for (KnowledgeContextPort.KnowledgeChunkContext chunk : chunks) {
            String prefix = "chunkId=" + chunk.chunkId()
                    + " 来源=" + chunk.filename()
                    + " sheet=" + chunk.sheetName()
                    + " section=" + chunk.sectionId()
                    + " chunkIndex=" + chunk.chunkIndex() + "\n";
            int before = ToolOutputBudget.length(sb.toString());
            int remaining = maxChars - before;
            if (remaining <= 0 || !ToolOutputBudget.append(sb, prefix, chunk.content(), "\n\n", maxChars)) {
                break;
            }
            int available = remaining - ToolOutputBudget.length(prefix) - 2;
            int bodyLength = ToolOutputBudget.length(chunk.content());
            if (bodyLength > Math.max(0, available)) {
                bodyLength = Math.max(0, available - ToolOutputBudget.length("…(截断)"));
            }
            contentCharacters += Math.min(bodyLength, ToolOutputBudget.length(chunk.content()));
            count++;
        }
        return new RenderedContext(count, contentCharacters,
                sb.isEmpty() ? "未读取到任何片段。" : sb.toString());
    }

    private static Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "chunkIds", Map.of(
                                "type", "array",
                                "description", "要读取的 chunkId 列表（1-3 个）",
                                "items", Map.of("type", "string"))),
                "required", List.of("chunkIds"));
    }

    private record RenderedContext(int count, int contentCharacters, String text) {
    }
}
