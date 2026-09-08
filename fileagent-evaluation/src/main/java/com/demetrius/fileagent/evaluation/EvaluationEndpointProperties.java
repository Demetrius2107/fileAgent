package com.demetrius.fileagent.evaluation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 内部评测接口配置。
 *
 * @author raosaijie
 */
@Getter
@Setter
@Component
@ConditionalOnProperty(prefix = "fileagent.evaluation.endpoint", name = "enabled", havingValue = "true")
@ConfigurationProperties(prefix = "fileagent.evaluation.endpoint")
public class EvaluationEndpointProperties implements InitializingBean {

    private String token;

    @Override
    public void afterPropertiesSet() {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "启用内部评测接口时必须配置 FILEAGENT_EVALUATION_TOKEN");
        }
    }
}
