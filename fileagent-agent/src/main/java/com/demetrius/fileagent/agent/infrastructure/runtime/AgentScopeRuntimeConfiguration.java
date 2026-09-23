package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.agent.domain.service.AdaptiveRetrievalPolicy;
import com.demetrius.fileagent.agent.infrastructure.config.AdaptiveRetrievalProperties;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.agent.infrastructure.tool.ListKnowledgeFilesTool;
import com.demetrius.fileagent.agent.infrastructure.tool.ReadDocumentContextTool;
import com.demetrius.fileagent.agent.infrastructure.tool.SearchDocsTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 运行时基础设施配置：注册固定的只读工具集与事件映射器。
 * <p>
 * 工具的名称、描述与 JSON Schema 由代码固定，不作为反射扫描任意 Spring Bean。
 * 档位策略在装配期完成启动校验，校验失败直接阻断启动。
 */
@Configuration
public class AgentScopeRuntimeConfiguration {

    @Bean
    AgentScopeEventMapper agentScopeEventMapper() {
        return new AgentScopeEventMapper();
    }

    @Bean
    AdaptiveRetrievalPolicy adaptiveRetrievalPolicy(AgentProperties agentProperties,
                                                    AdaptiveRetrievalProperties adaptiveRetrievalProperties) {
        agentProperties.validate();
        adaptiveRetrievalProperties.validate(agentProperties.getToolTimeout());
        return new AdaptiveRetrievalPolicy(adaptiveRetrievalProperties.toTiers());
    }

    @Bean
    SearchDocsTool searchDocsTool(AgentProperties agentProperties,
                                  AdaptiveRetrievalPolicy adaptiveRetrievalPolicy) {
        return new SearchDocsTool(agentProperties, adaptiveRetrievalPolicy);
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
