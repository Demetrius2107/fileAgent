package com.demetrius.fileagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MvcAsyncConfiguration} 的 MVC 异步执行器装配测试。
 *
 * @author raosaijie
 */
class MvcAsyncConfigurationTest {

    @Test
    void shouldUseDedicatedBoundedExecutorForMvcAsyncResponses() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MvcAsyncConfiguration.class)) {
            MvcAsyncConfiguration configuration = context.getBean(MvcAsyncConfiguration.class);
            ThreadPoolTaskExecutor executor = context.getBean("mvcAsyncTaskExecutor", ThreadPoolTaskExecutor.class);
            InspectableAsyncSupportConfigurer asyncConfigurer = new InspectableAsyncSupportConfigurer();

            configuration.configureAsyncSupport(asyncConfigurer);

            assertThat(asyncConfigurer.taskExecutor()).isSameAs(executor);
            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(16);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("mvc-async-");
        }
    }

    private static final class InspectableAsyncSupportConfigurer extends AsyncSupportConfigurer {

        private AsyncTaskExecutor taskExecutor() {
            return getTaskExecutor();
        }
    }
}
