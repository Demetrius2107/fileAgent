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

    private static final int MAX_CHUNKS = 3;

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
        if (chunkIds == null || chunkIds.isEmpty() || chunkIds.size() > MAX_CHUNKS) {
            throw new BizException("read_document_context 每次只能读取 1~" + MAX_CHUNKS + " 个 chunk");
        }
        for (String id : chunkIds) {
            if (!context.run().isAllowedChunk(id)) {
                throw new BizException("chunk " + id + " 未在本次检索结果中，拒绝读取");
            }
        }
        List<KnowledgeContextPort.KnowledgeChunkContext> chunks = context.knowledgeContextPort().read(chunkIds);
        return ToolResultBlock.text(render(chunks, context.singleToolResultCharacters()));
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

    private String render(List<KnowledgeContextPort.KnowledgeChunkContext> chunks, int maxChars) {
        if (chunks.isEmpty()) {
            return "未读取到任何片段。";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeContextPort.KnowledgeChunkContext chunk : chunks) {
            sb.append("chunkId=").append(chunk.chunkId())
                    .append(" 来源=").append(chunk.filename())
                    .append(" sheet=").append(chunk.sheetName())
                    .append(" section=").append(chunk.sectionId())
                    .append(" chunkIndex=").append(chunk.chunkIndex()).append('\n')
                    .append(truncate(chunk.content(), maxChars)).append("\n\n");
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
                        "chunkIds", Map.of(
                                "type", "array",
                                "description", "要读取的 chunkId 列表（1-3 个）",
                                "items", Map.of("type", "string"))),
                "required", List.of("chunkIds"));
    }
}
