package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.application.tool.AgentToolContext;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * @author raosaijie
 */
public class GetDocumentOutlineTool extends ToolBase {

    private static final int MAX_OUTLINE_ENTRIES = 50;

    public GetDocumentOutlineTool() {
        super(ToolBase.builder()
                .name("get_document_outline")
                .description("读取已授权文件的分块目录和短预览，不返回完整正文。")
                .inputSchema(inputSchema())
                .readOnly(true)
                .concurrencySafe(true));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        AgentToolContext context = context(param);
        Long fileId = longValue(param.getInput().get("fileId"));
        int afterChunkIndex = intValue(param.getInput().get("afterChunkIndex"), -1);
        int limit = intValue(param.getInput().get("limit"), context.budget().outlineMaxEntries());
        return Mono.fromCallable(() -> execute(context, fileId, afterChunkIndex, limit));
    }

    public ToolResultBlock execute(AgentToolContext context, Long fileId,
                                   int afterChunkIndex, int limit) {
        if (fileId == null) {
            throw new BizException("get_document_outline 缺少 fileId");
        }
        if (!context.run().isAllowedFile(fileId)) {
            throw new BizException("文件 " + fileId + " 未授权，拒绝读取目录");
        }
        if (context.run().shouldStopToolExpansion(context.budget())) {
            return budgetExhausted(context);
        }
        int pageSize = Math.min(Math.max(0, limit),
                Math.min(MAX_OUTLINE_ENTRIES, context.budget().outlineMaxEntries()));
        KnowledgeContextPort.DocumentOutlinePage page = context.knowledgeContextPort()
                .outline(fileId, afterChunkIndex, pageSize);
        RenderedOutline rendered = render(page, context);
        rendered.items().forEach(item -> {
            context.run().addAllowedChunk(item.chunkId());
            context.run().addAllowedChunk(item.parentId());
        });
        context.run().recordToolResult(rendered.items().size());
        context.run().recordToolResultCharacters(ToolOutputBudget.length(rendered.text()));
        return ToolResultBlock.text(rendered.text());
    }

    private ToolResultBlock budgetExhausted(AgentToolContext context) {
        context.run().recordToolResult(0);
        return ToolResultBlock.text(ToolOutputBudget.BUDGET_EXHAUSTED_MESSAGE);
    }

    private RenderedOutline render(KnowledgeContextPort.DocumentOutlinePage page, AgentToolContext context) {
        int max = Math.min(context.singleToolResultCharacters(),
                context.run().remainingToolResultCharacters(context.budget()));
        StringBuilder result = new StringBuilder();
        List<KnowledgeContextPort.DocumentOutlineItem> rendered = new java.util.ArrayList<>();
        for (KnowledgeContextPort.DocumentOutlineItem item : page.items()) {
            String prefix = "chunkId=" + item.chunkId()
                    + " parentId=" + item.parentId()
                    + " 文件=" + item.filename()
                    + " sourceType=" + item.sourceType()
                    + " sheet=" + item.sheetName()
                    + " section=" + item.sectionId()
                    + " row=" + item.rowIndex()
                    + " chunkIndex=" + item.chunkIndex() + "\n";
            if (!ToolOutputBudget.append(result, prefix, item.preview(), "\n\n", max)) {
                break;
            }
            rendered.add(item);
        }
        return new RenderedOutline(List.copyOf(rendered),
                result.isEmpty() ? ToolOutputBudget.truncate("目录中没有可读取的分块。", max) : result.toString());
    }

    private AgentToolContext context(ToolCallParam param) {
        if (param.getRuntimeContext() == null) {
            throw new BizException("get_document_outline 缺少运行上下文");
        }
        return param.getRuntimeContext().get(AgentToolContext.class);
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "fileId", Map.of("type", "integer", "description", "已授权文件 ID"),
                        "afterChunkIndex", Map.of("type", "integer", "description", "排他 chunkIndex 游标，首次为 -1"),
                        "limit", Map.of("type", "integer", "description", "本页条数，最多 50")),
                "required", List.of("fileId"));
    }

    private record RenderedOutline(List<KnowledgeContextPort.DocumentOutlineItem> items, String text) {
    }
}
