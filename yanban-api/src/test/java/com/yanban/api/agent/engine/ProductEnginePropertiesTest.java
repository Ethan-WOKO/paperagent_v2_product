package com.yanban.api.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProductEnginePropertiesTest {
    @Test
    void defaultsToLegacyWithoutExternalSecrets() {
        ProductEngineProperties properties = new ProductEngineProperties();
        assertThat(properties.selectedMode()).isEqualTo(ProductEngineMode.LEGACY);
        assertThat(properties.isExternalConfigurationSafe()).isTrue();
    }

    @Test
    void externalModesFailClosedWithoutDeploymentCredential() {
        ProductEngineProperties properties = new ProductEngineProperties();
        properties.setMode("dsh");
        assertThat(properties.isExternalConfigurationSafe()).isFalse();
        properties.setServiceToken("a-service-token-with-24-characters");
        assertThat(properties.isExternalConfigurationSafe()).isTrue();
        properties.setDshBaseUrl("https://engine.example/arbitrary/path?target=other");
        assertThat(properties.isExternalConfigurationSafe()).isFalse();
        properties.setMode("unknown");
        assertThatThrownBy(properties::selectedMode).isInstanceOf(IllegalStateException.class);
    }
}
