package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.AgentController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.web.bind.annotation.PostMapping;

class AgentControllerWorkspaceChatBoundaryTest {
    @Test void workspaceChatRemainsSeparateEndpoint() {
        java.lang.reflect.Method method = java.util.Arrays.stream(AgentController.class.getDeclaredMethods())
                .filter(value -> value.getName().equals("sendMessage")).findFirst().orElseThrow();
        assertTrue(java.util.Arrays.stream(method.getAnnotationsByType(PostMapping.class))
                .flatMap(mapping -> java.util.Arrays.stream(mapping.value()))
                .anyMatch(path -> path.contains("/messages")));
        assertTrue(java.util.Arrays.stream(method.getParameterTypes())
                .noneMatch(type -> type.getName().contains("V2NaturalLanguageTurn")));
    }
}
