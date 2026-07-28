package com.yanban.api.agent.v2.boundary;

import com.yanban.api.agent.v2.execution.AuthenticatedAgentTurnStepTurnCommand;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProviderStepTurnBoundaryTest {
    private static final List<String> FORBIDDEN_LEGACY_TYPES = List.of(
            "PlanAgentService",
            "PlanningAgentPlanner",
            "CompletionVerifier",
            "PlanStepVerifier",
            "Candidate");

    @Test
    void productStepTurnSourcesDoNotCallForbiddenLegacyAgentTypes()
            throws IOException {
        String sources = readJavaSources(
                Path.of("src/main/java/com/yanban/api/agent/v2/execution"));
        String adapterSources = readJavaSources(
                Path.of("../yanban-agent-v2-adapter/src/main/java/"
                        + "com/yanban/agent/v2/adapter/provider"));

        for (String forbidden : FORBIDDEN_LEGACY_TYPES) {
            assertFalse(sources.contains(forbidden), forbidden);
            assertFalse(adapterSources.contains(forbidden), forbidden);
        }
    }

    @Test
    void v2CoreRetainsOneWayProductDependency() throws IOException {
        String sources = readJavaSources(Path.of("../agent-v2"));
        assertFalse(sources.contains("import com.yanban"));
        assertFalse(sources.contains("package com.yanban"));
    }

    @Test
    void commandCarriesOnlyCallerOwnedRecoveryAttemptAndNoController() {
        assertArrayEquals(
                new String[]{"recoveryAttempt"},
                Arrays.stream(
                                AuthenticatedAgentTurnStepTurnCommand.class
                                        .getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
        assertFalse(AuthenticatedAgentTurnStepTurnCommand.class
                .isAnnotationPresent(Controller.class));
        assertFalse(AuthenticatedAgentTurnStepTurnCommand.class
                .isAnnotationPresent(RestController.class));
    }

    private static String readJavaSources(Path root) throws IOException {
        StringBuilder combined = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path path : paths
                    .filter(candidate -> candidate.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                combined.append(Files.readString(path));
            }
        }
        return combined.toString();
    }
}
