package io.paperagent.v2.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecutionContextRepositoryBoundaryTest {
    @Test
    void implementationIsUniqueAndDoesNotCrossAdapterBoundaries()
            throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        long implementations = 0;
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths
                    .filter(value -> value.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(path);
                if (source.replaceAll("\\s+", " ").contains(
                        "implements PlanExecutionContextRepository")) {
                    implementations++;
                    assertEquals(
                            "InMemoryPlanExecutionContextRepository.java",
                            path.getFileName().toString());
                }
            }
        }
        assertEquals(1, implementations);

        for (String file : List.of(
                "InMemoryPlanExecutionContextRepository.java",
                "InMemoryPlanExecutionContextAuthority.java")) {
            String source = Files.readString(sourceRoot.resolve(Path.of(
                    "io", "paperagent", "v2", "persistence", file)));
            for (String forbidden : List.of(
                    "io.paperagent.v2.workspace",
                    "VerifiedWorkspaceMaterialization",
                    "WorkspacePort",
                    "io.paperagent.v2.runtime",
                    "java.io.",
                    "java.nio.file.",
                    "java.net.",
                    "System.getenv",
                    "System.getProperty",
                    "PlanRepository",
                    "EventRepository",
                    "CheckpointRepository",
                    ".append(",
                    ".save(")) {
                assertFalse(source.contains(forbidden),
                        file + ": " + forbidden);
            }
        }
    }

    @Test
    void internalAuthorityMarkersAndOwnersStayPackagePrivate() {
        for (Class<?> type : List.of(
                InMemoryPlanExecutionContextRepository.class,
                InMemoryPlanExecutionContextAuthority.class,
                InMemoryPlanExecutionContextAuthority.ContextCut.class,
                InMemoryState.PlanExecutionContextReservationMarker.class,
                InMemoryState.PlanExecutionContextConfirmationMarker.class,
                InMemoryState.WorkspaceOwner.class)) {
            assertFalse(java.lang.reflect.Modifier.isPublic(
                    type.getModifiers()));
        }
        for (var method :
                InMemoryPlanExecutionContextAuthority.class
                        .getDeclaredMethods()) {
            assertFalse(java.lang.reflect.Modifier.isPublic(
                    method.getModifiers()));
        }
        assertTrue(java.lang.reflect.Modifier.isPrivate(
                InMemoryPlanExecutionContextAuthority.class
                        .getDeclaredConstructors()[0]
                        .getModifiers()));
    }
}
