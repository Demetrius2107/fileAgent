package com.demetrius.fileagent;

import com.demetrius.fileagent.action.interfaces.MockExpenseDraftController;
import com.demetrius.fileagent.agent.infrastructure.tool.AgentActionToolFactory;
import com.demetrius.fileagent.document.infrastructure.ElasticsearchKnowledgeIndexInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认不开启动作 Profile 时，不装配 Mock 接口和 HTTP 动作工具。
 *
 * @author raosaijie
 */
@SpringBootTest(properties = {
        "fileagent.action.mock.enabled=false",
        "fileagent.action.http.enabled=false",
        "fileagent.agent.actions-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:action_mock_disabled;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "fileagent.secret-key-path=target/action-mock-disabled.key"
})
class ActionMockDisabledIntegrationTest {

    @MockitoBean
    private ElasticsearchKnowledgeIndexInitializer indexInitializer;

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldNotRegisterMockControllerOrActionFactory() {
        assertThat(context.getBeansOfType(MockExpenseDraftController.class)).isEmpty();
        assertThat(context.getBeansOfType(AgentActionToolFactory.class)).isEmpty();
    }
}
