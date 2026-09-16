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
        String effectiveRagName = context.scope().ragName() != null ? context.scope().ragName() : ragName;
        String effectiveTag = context.scope().knowledgeTag() != null ? context.scope().knowledgeTag() : knowledgeTag;
        List<KnowledgeCatalogPort.KnowledgeFile> files = context.knowledgeCatalogPort()
                .list(new KnowledgeCatalogPort.Query(effectiveRagName, effectiveTag, MAX_FILES));
        return ToolResultBlock.text(render(files));
    }

    private AgentToolContext context(ToolCallParam param) {
        if (param.getRuntimeContext() == null) {
            throw new BizException("list_knowledge_files 缺少运行上下文");
        }
        return param.getRuntimeContext().get(AgentToolContext.class);
    }

    private String render(List<KnowledgeCatalogPort.KnowledgeFile> files) {
        if (files.isEmpty()) {
            return "没有可检索的知识文件。";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeCatalogPort.KnowledgeFile file : files) {
            sb.append("fileId=").append(file.fileId())
                    .append(" 知识库=").append(file.ragName())
                    .append(" 标签=").append(file.knowledgeTag())
                    .append(" 文件=").append(file.filename())
                    .append(" 状态=").append(file.status())
                    .append(" 分片数=").append(file.chunkCount()).append('\n');
        }
        return sb.toString();
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
}
