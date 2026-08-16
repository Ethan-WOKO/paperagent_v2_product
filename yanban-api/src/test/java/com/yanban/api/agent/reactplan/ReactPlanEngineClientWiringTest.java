package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ReactPlanEngineClientWiringTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Wiring.class)
            .withPropertyValues(
                    "yanban.agent.reactplan.enabled=true",
                    "yanban.agent.reactplan.engine-service-token=" + "t".repeat(32));

    @Test
    void selectsTheProductConstructorWhenTheRuntimeIsEnabled() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).hasSingleBean(ReactPlanEngineClient.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReactPlanRuntimeProperties.class)
    @Import(ReactPlanEngineClient.class)
    static class Wiring {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
