package com.yanban.api.agent.v2.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class V2ProductAvailabilityPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            V2ProductAvailabilityConfiguration.class);

    @Test
    void codeDefaultIsEnabledAndDocumentIsBoundedAndImmutable() {
        contextRunner.run(context -> {
            V2ProductAvailability availability =
                    context.getBean(V2ProductAvailability.class);

            assertThat(availability.document())
                    .isEqualTo(new V2ProductAvailabilityDocument(
                            1, true, List.of(
                                    "literature.search",
                                    "project.read-analysis",
                                    "project.candidate",
                                    "agent.turn")));
            assertThatThrownBy(() -> availability.document()
                    .capabilities().add("client.override"))
                    .isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    void explicitServerConfigurationDisablesEveryCapability() {
        contextRunner
                .withPropertyValues(
                        "yanban.agent.v2.product.enabled=false")
                .run(context -> {
                    V2ProductAvailability availability =
                            context.getBean(V2ProductAvailability.class);

                    assertThat(availability.document().enabled()).isFalse();
                    assertThatThrownBy(() -> availability.requireAvailable(
                            V2ProductAvailability.LITERATURE_SEARCH))
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    error -> {
                                        assertThat(error.getStatusCode())
                                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                                        assertThat(error.getReason()).isEqualTo(
                                                "V2 Agent capabilities are unavailable");
                                    });
                });
    }

    @Test
    void unsupportedCapabilityFailsClosedWithoutExposingConfiguration() {
        V2ProductAvailability availability =
                V2ProductAvailability.enabledByDefault();

        assertThatThrownBy(() -> availability.requireAvailable(
                "client.supplied"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported V2 capability");
    }
}
