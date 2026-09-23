package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.application.tool.AgentToolContext;
import com.demetrius.fileagent.api.port.KnowledgeCatalogPort;
import com.demetrius.fileagent.common.exception.BizException;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * {@code list_knowledge_files} 只读工具：列出可检索知识文件概要。
 * <p>
 * 范围只能收窄（按运行上下文允许的 scope 覆盖），不能通过参数扩大服务器下发的知识范围；
 * 最多 20 条，不返回存储路径或原始内容。
 */
public class ListKnowledgeFilesTool extends ToolBase {

    private static final int MAX_FILES = 20;

    public ListKnowledgeFilesTool() {
        super(ToolBase.builder()
                .name("list_knowledge_files")
                .description("列出可检索的知识文件概要（文件 ID、知识库名、标签、文件名、状态、分片数）。")
                .inputSchema(inputSchema())
                .readOnly(true)
                .concurrencySafe(true));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        AgentToolContext context = context(param);
        String ragName = stringValue(param.getInput().get("ragName"));
        String knowledgeTag = stringValue(param.getInput().get("knowledgeTag"));
        return Mono.fromCallable(() -> execute(context, ragName, knowledgeTag));
    }

    public ToolResultBlock execute(AgentToolContext context, String ragName, String knowledgeTag) {
        if (context.run().shouldStopToolExpansion(context.budget())) {
            context.run().recordToolResult(0);
            return ToolResultBlock.text(ToolOutputBudget.BUDGET_EXHAUSTED_MESSAGE);
        }
        String effectiveRagName = context.scope().ragName() != null ? context.scope().ragName() : ragName;
        String effectiveTag = context.scope().knowledgeTag() != null ? context.scope().knowledgeTag() : knowledgeTag;
        List<KnowledgeCatalogPort.KnowledgeFile> files = context.knowledgeCatalogPort()
                .list(new KnowledgeCatalogPort.Query(effectiveRagName, effectiveTag, MAX_FILES));
        RenderedFiles rendered = render(files, context);
        rendered.files().forEach(file -> context.run().addAllowedFile(file.fileId()));
        context.run().recordToolResult(rendered.files().size());
        context.run().recordToolResultCharacters(ToolOutputBudget.length(rendered.text()));
        return ToolResultBlock.text(rendered.text());
    }

    private AgentToolContext context(ToolCallParam param) {
        if (param.getRuntimeContext() == null) {
            throw new BizException("list_knowledge_files 缺少运行上下文");
        }
        return param.getRuntimeContext().get(AgentToolContext.class);
    }

    private RenderedFiles render(List<KnowledgeCatalogPort.KnowledgeFile> files, AgentToolContext context) {
        if (files.isEmpty()) {
            return new RenderedFiles(List.of(), "没有可检索的知识文件。");
        }
        int max = Math.min(context.singleToolResultCharacters(),
                context.run().remainingToolResultCharacters(context.budget()));
        StringBuilder sb = new StringBuilder();
        List<KnowledgeCatalogPort.KnowledgeFile> rendered = new java.util.ArrayList<>();
        for (KnowledgeCatalogPort.KnowledgeFile file : files) {
            String prefix = "fileId=" + file.fileId()
                    + " 知识库=" + file.ragName()
                    + " 标签=" + file.knowledgeTag()
                    + " 文件=" + file.filename()
                    + " 状态=" + file.status()
                    + " 分片数=" + file.chunkCount() + "\n";
            if (!ToolOutputBudget.append(sb, prefix, "", "", max)) {
                break;
            }
            rendered.add(file);
        }
        return new RenderedFiles(List.copyOf(rendered), sb.isEmpty()
                ? ToolOutputBudget.truncate("没有可检索的知识文件。", max)
                : sb.toString());
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "ragName", Map.of("type", "string", "description", "知识库名称（可选）"),
                        "knowledgeTag", Map.of("type", "string", "description", "知识标签（可选）")));
    }

    private record RenderedFiles(List<KnowledgeCatalogPort.KnowledgeFile> files, String text) {
    }
}
