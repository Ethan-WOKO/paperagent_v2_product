package com.yanban.api.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

class SandboxExecutionPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultsToDisabledAndConservativeLimits() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SandboxExecutionProperties properties = context.getBean(SandboxExecutionProperties.class);

            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.isRequiredAtStartup()).isFalse();
            assertThat(properties.getProvider()).isEqualTo("e2b");
            assertThat(properties.getBrokerUrl()).isEqualTo(URI.create("http://127.0.0.1:8091"));
            assertThat(properties.getMaxConcurrentRuns()).isEqualTo(1);
            assertThat(properties.getCpus()).isEqualTo(2);
            assertThat(properties.getMemoryLimit()).isEqualTo(DataSize.ofGigabytes(4));
            assertThat(properties.getExecutionTimeout()).isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.getMaxOutputSize()).isEqualTo(DataSize.ofMegabytes(20));
            assertThat(properties.isNetworkEnabled()).isFalse();
        });
    }

    @Test
    void bindsExplicitDeploymentOverrides() {
        contextRunner
                .withPropertyValues(
                        "yanban.sandbox.enabled=true",
                        "yanban.sandbox.broker-token=0123456789abcdef0123456789abcdef",
                        "yanban.sandbox.required-at-startup=true",
                        "yanban.sandbox.provider=e2b",
                        "yanban.sandbox.broker-url=https://sandbox-worker:8091",
                        "yanban.sandbox.max-concurrent-runs=1",
                        "yanban.sandbox.cpus=1",
                        "yanban.sandbox.memory-limit=3GB",
                        "yanban.sandbox.execution-timeout=8m",
                        "yanban.sandbox.max-output-size=8MB",
                        "yanban.sandbox.network-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SandboxExecutionProperties properties = context.getBean(SandboxExecutionProperties.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.isRequiredAtStartup()).isTrue();
                    assertThat(properties.getBrokerUrl()).isEqualTo(URI.create("https://sandbox-worker:8091"));
                    assertThat(properties.getMaxConcurrentRuns()).isEqualTo(1);
                    assertThat(properties.getCpus()).isEqualTo(1);
                    assertThat(properties.getMemoryLimit()).isEqualTo(DataSize.ofGigabytes(3));
                    assertThat(properties.getExecutionTimeout()).isEqualTo(Duration.ofMinutes(8));
                    assertThat(properties.getMaxOutputSize()).isEqualTo(DataSize.ofMegabytes(8));
                    assertThat(properties.isNetworkEnabled()).isFalse();
                });
    }

    @Test
    void rejectsUnsafeZeroResourceLimits() {
        contextRunner
                .withPropertyValues(
                        "yanban.sandbox.max-concurrent-runs=0",
                        "yanban.sandbox.cpus=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("yanban.sandbox");
                });
    }

    @Test
    void rejectsStartupRequirementWithoutEnableAndNetworkEnablement() {
        contextRunner.withPropertyValues("yanban.sandbox.required-at-startup=true")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("yanban.sandbox.network-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsDeploymentValuesAboveFrozenCeilings() {
        contextRunner.withPropertyValues("yanban.sandbox.max-concurrent-runs=2")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("yanban.sandbox.cpus=3")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("yanban.sandbox.memory-limit=5GB")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("yanban.sandbox.execution-timeout=16m")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("yanban.sandbox.max-output-size=21MB")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void acceptsOnlyThePrivateComposeEndpointForE2b() {
        contextRunner.withPropertyValues(
                        "yanban.sandbox.provider=e2b",
                        "yanban.sandbox.broker-url=http://sandbox-broker:8091")
                .run(context -> assertThat(context).hasNotFailed());
        contextRunner.withPropertyValues(
                        "yanban.sandbox.provider=docker-sbx",
                        "yanban.sandbox.broker-url=http://sandbox-broker:8091")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("yanban.sandbox.provider=unknown")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SandboxExecutionProperties.class)
    static class TestConfiguration {
    }
}
