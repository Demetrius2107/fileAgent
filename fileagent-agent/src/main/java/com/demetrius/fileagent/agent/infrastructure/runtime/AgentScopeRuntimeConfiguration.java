package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.agent.infrastructure.tool.ListKnowledgeFilesTool;
import com.demetrius.fileagent.agent.infrastructure.tool.ReadDocumentContextTool;
import com.demetrius.fileagent.agent.infrastructure.tool.SearchDocsTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 运行时基础设施配置：注册固定的只读工具集与事件映射器。
 * <p>
 * 工具的名称、描述与 JSON Schema 由代码固定，不作为反射扫描任意 Spring Bean。
 */
@Configuration
public class AgentScopeRuntimeConfiguration {

    @Bean
    AgentScopeEventMapper agentScopeEventMapper() {
        return new AgentScopeEventMapper();
    }

    @Bean
    SearchDocsTool searchDocsTool() {
        return new SearchDocsTool();
    }

    @Bean
    ListKnowledgeFilesTool listKnowledgeFilesTool() {
        return new ListKnowledgeFilesTool();
    }

    @Bean
    ReadDocumentContextTool readDocumentContextTool() {
        return new ReadDocumentContextTool();
    }
}
