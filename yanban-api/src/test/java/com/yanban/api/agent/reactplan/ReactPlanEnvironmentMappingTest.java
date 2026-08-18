package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.api.agent.reactplan.gateway.EngineGatewayProperties;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ReactPlanEnvironmentMappingTest {
    private static final String ENGINE_TOKEN = "e".repeat(40);
    private static final String GRANT_SECRET = "g".repeat(40);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void mapsDeploymentEnvironmentNamesIntoBackendProperties() {
        context.withSystemProperties(
                        "YANBAN_AGENT_REACTPLAN_ENABLED=true",
                        "YANBAN_AGENT_REACTPLAN_ENGINE_ORIGIN=http://127.0.0.1:8092",
                        "YANBAN_AGENT_REACTPLAN_ENGINE_SERVICE_TOKEN=" + ENGINE_TOKEN,
                        "YANBAN_AGENT_REACTPLAN_DEFAULT_PROVIDER=deepseek",
                        "YANBAN_AGENT_REACTPLAN_DEFAULT_MODEL=deepseek-reasoner",
                        "YANBAN_AGENT_ENGINE_GATEWAY_ENABLED=true",
                        "YANBAN_AGENT_ENGINE_GATEWAY_TASK_GRANT_SECRET=" + GRANT_SECRET,
                        "YANBAN_AGENT_ENGINE_GATEWAY_TASK_GRANT_TTL=7m",
                        "YANBAN_AGENT_ENGINE_GATEWAY_WORKSPACE_ROOT=data/test-engine-workspaces",
                        "YANBAN_AGENT_ENGINE_GATEWAY_MAX_READ_BYTES=2097152")
                .run(application -> {
                    assertThat(application).hasNotFailed();

                    ReactPlanRuntimeProperties runtime = application.getBean(
                            ReactPlanRuntimeProperties.class);
                    assertThat(runtime.isEnabled()).isTrue();
                    assertThat(runtime.getEngineOrigin()).isEqualTo(
                            URI.create("http://127.0.0.1:8092"));
                    assertThat(runtime.getEngineServiceToken()).isEqualTo(ENGINE_TOKEN);
                    assertThat(runtime.getDefaultProvider()).isEqualTo("deepseek");
                    assertThat(runtime.getDefaultModel()).isEqualTo("deepseek-reasoner");

                    EngineGatewayProperties gateway = application.getBean(
                            EngineGatewayProperties.class);
                    assertThat(gateway.isEnabled()).isTrue();
                    assertThat(gateway.getTaskGrantSecret()).isEqualTo(GRANT_SECRET);
                    assertThat(gateway.getTaskGrantTtl()).isEqualTo(Duration.ofMinutes(7));
                    assertThat(gateway.getWorkspaceRoot()).isEqualTo(
                            "data/test-engine-workspaces");
                    assertThat(gateway.getMaxReadBytes()).isEqualTo(2_097_152);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            ReactPlanRuntimeProperties.class,
            EngineGatewayProperties.class
    })
    static class PropertiesConfiguration { }
}
