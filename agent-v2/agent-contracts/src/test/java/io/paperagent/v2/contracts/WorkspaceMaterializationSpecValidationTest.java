package io.paperagent.v2.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class WorkspaceMaterializationSpecValidationTest {
    private static final WorkspaceId WORKSPACE_ID = new WorkspaceId("workspace-opaque-sentinel");
    private static final ProjectVersionRef SOURCE =
            new ProjectVersionRef("project-opaque-sentinel", "version-opaque-sentinel");
    private static final WorkspaceMaterializationLimits LIMITS =
            new WorkspaceMaterializationLimits(101_001, 202_002, 303);

    @Test
    void acceptsPositiveZeroAndMaximumLimits() {
        WorkspaceMaterializationLimits positive =
                new WorkspaceMaterializationLimits(1, 2, 3);
        WorkspaceMaterializationLimits zero =
                new WorkspaceMaterializationLimits(0, 0, 0);
        WorkspaceMaterializationLimits maximum =
                new WorkspaceMaterializationLimits(Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);

        assertEquals(1, positive.maxFileBytes());
        assertEquals(2, positive.maxAggregateBytes());
        assertEquals(3, positive.maxFiles());
        assertEquals(0, zero.maxFileBytes());
        assertEquals(0, zero.maxAggregateBytes());
        assertEquals(0, zero.maxFiles());
        assertEquals(Long.MAX_VALUE, maximum.maxFileBytes());
        assertEquals(Long.MAX_VALUE, maximum.maxAggregateBytes());
        assertEquals(Integer.MAX_VALUE, maximum.maxFiles());
    }

    @Test
    void allowsFileLimitGreaterThanAggregateLimit() {
        WorkspaceMaterializationLimits limits =
                new WorkspaceMaterializationLimits(10, 1, 1);

        assertEquals(10, limits.maxFileBytes());
        assertEquals(1, limits.maxAggregateBytes());
    }

    @Test
    void rejectsEachNegativeLimitWithExactCodeAndPath() {
        assertViolation(
                () -> new WorkspaceMaterializationLimits(-1, 0, 0),
                ViolationCode.INVALID_WORKSPACE_LIMIT,
                "workspaceMaterializationLimits.maxFileBytes");
        assertViolation(
                () -> new WorkspaceMaterializationLimits(0, -1, 0),
                ViolationCode.INVALID_WORKSPACE_LIMIT,
                "workspaceMaterializationLimits.maxAggregateBytes");
        assertViolation(
                () -> new WorkspaceMaterializationLimits(0, 0, -1),
                ViolationCode.INVALID_WORKSPACE_LIMIT,
                "workspaceMaterializationLimits.maxFiles");
    }

    @Test
    void rejectsEachNullSpecComponentWithExactCodeAndPath() {
        assertViolation(
                () -> new WorkspaceMaterializationSpec(null, SOURCE, LIMITS),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "workspaceMaterializationSpec.workspaceId");
        assertViolation(
                () -> new WorkspaceMaterializationSpec(WORKSPACE_ID, null, LIMITS),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "workspaceMaterializationSpec.sourceProjectVersion");
        assertViolation(
                () -> new WorkspaceMaterializationSpec(WORKSPACE_ID, SOURCE, null),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "workspaceMaterializationSpec.limits");
    }

    @Test
    void nestedTypedValuesRetainExistingValidationContracts() {
        assertViolation(
                () -> new WorkspaceMaterializationSpec(
                        new WorkspaceId("invalid workspace id"),
                        SOURCE,
                        LIMITS),
                ViolationCode.INVALID_ID,
                "workspaceId");
        assertViolation(
                () -> new WorkspaceMaterializationSpec(
                        WORKSPACE_ID,
                        new ProjectVersionRef("invalid project id", "version-valid"),
                        LIMITS),
                ViolationCode.INVALID_ID,
                "projectVersion.projectId");
        assertViolation(
                () -> new WorkspaceMaterializationSpec(
                        WORKSPACE_ID,
                        new ProjectVersionRef("project-valid", "invalid version id"),
                        LIMITS),
                ViolationCode.INVALID_ID,
                "projectVersion.versionId");
    }

    @Test
    void exactComponentsHaveStableValueIdentity() {
        WorkspaceMaterializationSpec first =
                new WorkspaceMaterializationSpec(WORKSPACE_ID, SOURCE, LIMITS);
        WorkspaceMaterializationSpec replay =
                new WorkspaceMaterializationSpec(
                        new WorkspaceId(WORKSPACE_ID.value()),
                        new ProjectVersionRef(SOURCE.projectId(), SOURCE.versionId()),
                        new WorkspaceMaterializationLimits(
                                LIMITS.maxFileBytes(),
                                LIMITS.maxAggregateBytes(),
                                LIMITS.maxFiles()));

        assertEquals(first, replay);
        assertEquals(first.hashCode(), replay.hashCode());
    }

    @Test
    void everyComponentParticipatesInRetryIdentity() {
        WorkspaceMaterializationSpec baseline =
                new WorkspaceMaterializationSpec(WORKSPACE_ID, SOURCE, LIMITS);

        assertNotEquals(baseline, spec(
                new WorkspaceId("workspace-other"), SOURCE, LIMITS));
        assertNotEquals(baseline, spec(
                WORKSPACE_ID,
                new ProjectVersionRef("project-other", SOURCE.versionId()),
                LIMITS));
        assertNotEquals(baseline, spec(
                WORKSPACE_ID,
                new ProjectVersionRef(SOURCE.projectId(), "version-other"),
                LIMITS));
        assertNotEquals(baseline, spec(
                WORKSPACE_ID,
                SOURCE,
                new WorkspaceMaterializationLimits(
                        LIMITS.maxFileBytes() + 1,
                        LIMITS.maxAggregateBytes(),
                        LIMITS.maxFiles())));
        assertNotEquals(baseline, spec(
                WORKSPACE_ID,
                SOURCE,
                new WorkspaceMaterializationLimits(
                        LIMITS.maxFileBytes(),
                        LIMITS.maxAggregateBytes() + 1,
                        LIMITS.maxFiles())));
        assertNotEquals(baseline, spec(
                WORKSPACE_ID,
                SOURCE,
                new WorkspaceMaterializationLimits(
                        LIMITS.maxFileBytes(),
                        LIMITS.maxAggregateBytes(),
                        LIMITS.maxFiles() + 1)));
    }

    @Test
    void recordSurfacesAreExact() {
        assertRecordSurface(
                WorkspaceMaterializationLimits.class,
                List.of("maxFileBytes", "maxAggregateBytes", "maxFiles"),
                List.of(long.class, long.class, int.class));
        assertRecordSurface(
                WorkspaceMaterializationSpec.class,
                List.of("workspaceId", "sourceProjectVersion", "limits"),
                List.of(
                        WorkspaceId.class,
                        ProjectVersionRef.class,
                        WorkspaceMaterializationLimits.class));
    }

    @Test
    void textSurfacesAreOpaque() {
        WorkspaceMaterializationSpec spec =
                new WorkspaceMaterializationSpec(WORKSPACE_ID, SOURCE, LIMITS);

        assertEquals(
                "WorkspaceMaterializationSpec[workspaceId=<provided>, "
                        + "sourceProjectVersion=<provided>, limits=<provided>]",
                spec.toString());
        assertEquals(
                "WorkspaceMaterializationLimits[maxFileBytes=<provided>, "
                        + "maxAggregateBytes=<provided>, maxFiles=<provided>]",
                LIMITS.toString());
        assertOpaque(spec.toString());
        assertOpaque(LIMITS.toString());
    }

    @Test
    void validationFailureMessagesAndCausesAreOpaque() {
        ContractViolationException missing =
                ContractFixtures.violation(
                        () -> new WorkspaceMaterializationSpec(WORKSPACE_ID, SOURCE, null));
        ContractViolationException invalidLimit =
                ContractFixtures.violation(
                        () -> new WorkspaceMaterializationLimits(-1, 202_002, 303));

        assertThrowableTreeOpaque(missing);
        assertThrowableTreeOpaque(invalidLimit);
    }

    private static WorkspaceMaterializationSpec spec(
            WorkspaceId workspaceId,
            ProjectVersionRef source,
            WorkspaceMaterializationLimits limits) {
        return new WorkspaceMaterializationSpec(workspaceId, source, limits);
    }

    private static void assertViolation(
            Runnable action,
            ViolationCode expectedCode,
            String expectedPath) {
        ContractViolationException exception = ContractFixtures.violation(action);
        assertEquals(expectedCode, exception.primaryCode());
        assertEquals(1, exception.violations().size());
        assertEquals(expectedPath, exception.violations().get(0).path());
    }

    private static void assertRecordSurface(
            Class<?> type,
            List<String> expectedNames,
            List<Class<?>> expectedTypes) {
        assertTrue(type.isRecord());
        RecordComponent[] components = type.getRecordComponents();
        assertEquals(expectedNames, Arrays.stream(components).map(RecordComponent::getName).toList());
        assertEquals(expectedTypes, Arrays.stream(components).map(RecordComponent::getType).toList());
    }

    private static void assertOpaque(String text) {
        for (String sentinel : sentinels()) {
            assertFalse(text.contains(sentinel), sentinel);
        }
    }

    private static void assertThrowableTreeOpaque(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            assertOpaque(String.valueOf(current.getMessage()));
            assertOpaque(current.toString());
        }
    }

    private static Set<String> sentinels() {
        return Set.of(
                "workspace-opaque-sentinel",
                "project-opaque-sentinel",
                "version-opaque-sentinel",
                "101001",
                "202002",
                "303");
    }

}
