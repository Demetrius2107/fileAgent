package com.demetrius.fileagent.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationEndpointPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EvaluationEndpointProperties.class);

    @Test
    void shouldNotRegisterPropertiesWhenEndpointIsDisabledByDefault() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(EvaluationEndpointProperties.class));
    }

    @Test
    void shouldRegisterPropertiesWhenEndpointIsEnabledWithToken() {
        contextRunner
                .withPropertyValues(
                        "fileagent.evaluation.endpoint.enabled=true",
                        "fileagent.evaluation.endpoint.token=secret")
                .run(context -> assertThat(context)
                        .hasSingleBean(EvaluationEndpointProperties.class));
    }

    @Test
    void shouldRequireTokenWhenEndpointIsEnabled() {
        EvaluationEndpointProperties properties = new EvaluationEndpointProperties();

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FILEAGENT_EVALUATION_TOKEN");

        properties.setToken("secret");
        assertThatNoException().isThrownBy(properties::afterPropertiesSet);
    }
}
