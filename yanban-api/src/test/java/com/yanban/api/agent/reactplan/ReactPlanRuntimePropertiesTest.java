package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ReactPlanRuntimePropertiesTest {
    private final ReactPlanRuntimeProperties properties = new ReactPlanRuntimeProperties();

    @Test
    void acceptsLoopbackAndTheFixedComposeService() {
        assertSafe("http://127.0.0.1:8092");
        assertSafe("https://localhost:8092/");
        assertSafe("http://agent-engine-reactplan:8092");
    }

    @Test
    void rejectsOtherHostsPortsAndUriComponents() {
        assertUnsafe("http://engine:8092");
        assertUnsafe("https://agent-engine-reactplan:8092");
        assertUnsafe("http://agent-engine-reactplan:8080");
        assertUnsafe("http://user@agent-engine-reactplan:8092");
        assertUnsafe("http://agent-engine-reactplan:8092/tasks");
        assertUnsafe("http://agent-engine-reactplan:8092?target=other");
        assertUnsafe("http://agent-engine-reactplan:8092#fragment");
    }

    private void assertSafe(String origin) {
        properties.setEngineOrigin(URI.create(origin));
        assertThat(properties.isEngineOriginSafe()).isTrue();
    }

    private void assertUnsafe(String origin) {
        properties.setEngineOrigin(URI.create(origin));
        assertThat(properties.isEngineOriginSafe()).isFalse();
    }
}
