package io.paperagent.v2.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepActivationRepositoryBoundaryTest {
    @Test
    void referenceImplementationIsUniqueAndDoesNotComposeOrdinaryWriters()
            throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<Path> production;
        try (var paths = Files.walk(sourceRoot)) {
            production = paths.filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
        long implementations = 0;
        for (Path path : production) {
            String source = Files.readString(path);
            String normalized = source.replaceAll("\\s+", " ");
            if (normalized.contains(
                    "implements StepActivationRepository")) {
                implementations++;
                assertTrue(path.getFileName().toString()
                        .equals("InMemoryStepActivationRepository.java"));
            }
        }
        assertEquals(1, implementations);

        String activationSource = Files.readString(sourceRoot.resolve(
                Path.of(
                        "io",
                        "paperagent",
                        "v2",
                        "persistence",
                        "InMemoryStepActivationRepository.java")));
        String authoritySource = Files.readString(sourceRoot.resolve(
                Path.of(
                        "io",
                        "paperagent",
                        "v2",
                        "persistence",
                        "InMemoryExecutionMutationAuthority.java")));
        String normalizedAuthority =
                authoritySource.replaceAll("\\s+", " ");
        for (String forbidden : List.of(
                "PlanRepository",
                "EventRepository",
                "CheckpointRepository",
                ".append(",
                ".save(",
                "java.io.",
                "java.net.",
                "java.nio.file.",
                "java.lang.Process",
                "java.lang.Thread",
                "System.getenv",
                "System.getProperty",
                "org.junit.",
                "src/test",
                "getDeclared",
                "setAccessible",
                "Random",
                "UUID")) {
            assertFalse(activationSource.contains(forbidden), forbidden);
            assertFalse(authoritySource.contains(forbidden), forbidden);
        }
        assertTrue(activationSource.contains("state.observeLeaseTime()"));
        assertTrue(activationSource.contains(
                "InMemoryPlanExecutionContextAuthority.inspect"));
        assertFalse(activationSource.contains("Clock"));
        assertFalse(authoritySource.contains("observeLeaseTime"));
        assertFalse(authoritySource.contains("Clock"));
        assertTrue(normalizedAuthority.contains("current.version() < 2"));
        assertFalse(normalizedAuthority.contains("current.version() == 2"));
        assertFalse(normalizedAuthority.contains("current.version() != 2"));
    }

    @Test
    void internalAuthorityTypesStayPackagePrivateAndOutsidePublicFacts() {
        for (Class<?> type : List.of(
                InMemoryState.ExecutionStartMarker.class,
                InMemoryState.ExecutionMutationHead.class,
                InMemoryState.ExecutionMutationLink.class,
                InMemoryState.ExecutionMutationMarkerIdentity.class,
                InMemoryState.StepActivationMarker.class,
                InMemoryState.PlanExecutionContextReservationMarker.class,
                InMemoryState.PlanExecutionContextConfirmationMarker.class,
                InMemoryState.WorkspaceOwner.class,
                InMemoryExecutionMutationAuthority.class,
                InMemoryExecutionMutationAuthority.AuthoritativeSource.class,
                InMemoryPlanExecutionContextAuthority.class,
                InMemoryPlanExecutionContextAuthority.ContextCut.class)) {
            assertFalse(java.lang.reflect.Modifier.isPublic(
                    type.getModifiers()));
        }
        for (var method :
                InMemoryExecutionMutationAuthority.class.getDeclaredMethods()) {
            assertFalse(java.lang.reflect.Modifier.isPublic(
                    method.getModifiers()));
        }
        assertTrue(java.lang.reflect.Modifier.isPrivate(
                InMemoryExecutionMutationAuthority.class
                        .getDeclaredConstructors()[0]
                        .getModifiers()));
        assertEquals(
                List.of(
                        "taskFrame",
                        "plan",
                        "checkpoint",
                        "eventHeadSequence",
                        "head",
                        "eventStream",
                        "links",
                        "activationMarkers"),
                java.util.Arrays.stream(
                                InMemoryExecutionMutationAuthority
                                        .AuthoritativeSource.class
                                        .getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        String resultFields = java.util.Arrays.stream(
                        PersistedStepActivation.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .reduce("", (left, right) -> left + " " + right);
        assertFalse(resultFields.contains("ExecutionMutation"));
        assertFalse(resultFields.contains("StepActivationRequest"));
    }
}
