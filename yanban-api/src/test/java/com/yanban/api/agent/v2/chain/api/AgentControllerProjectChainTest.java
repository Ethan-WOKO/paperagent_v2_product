package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.AgentController;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.web.bind.annotation.PostMapping;

class AgentControllerProjectChainTest {
    @Test void exposesProjectTurnCommands() {
        Method method = java.util.Arrays.stream(AgentController.class.getDeclaredMethods())
                .filter(value -> value.getName().equals("startV2ProjectTurn"))
                .findFirst().orElseThrow();
        assertTrue(java.util.Arrays.stream(method.getAnnotationsByType(PostMapping.class))
                .flatMap(mapping -> java.util.Arrays.stream(mapping.value()))
                .anyMatch(path -> path.equals("/{sessionId}/v2/turns")));
    }
}
