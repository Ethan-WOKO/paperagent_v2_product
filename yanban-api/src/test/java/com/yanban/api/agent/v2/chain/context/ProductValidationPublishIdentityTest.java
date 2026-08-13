package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductValidationPublishIdentityTest {
    private static final Instant NOW = Instant.parse("2026-08-09T08:00:00Z");

    @Test
    void executorMayObserveWorkspaceCandidateBeforeStepResultExists() {
        assertNull(ProductValidationPublishIdentity.candidate(
                building(ChainRole.EXECUTOR, null), null,
                revision(step("step-1", false)), List.of()));
    }

    @Test
    void missingCandidateStepResultRemainsBlockedOutsideIntermediateExecutorCut() {
        assertThrows(ChainContextException.class,
                () -> ProductValidationPublishIdentity.candidate(
                        building(ChainRole.REFLECTOR, null), null,
                        revision(step("step-1", false)), List.of()));
        assertThrows(ChainContextException.class,
                () -> ProductValidationPublishIdentity.candidate(
                        building(ChainRole.EXECUTOR, "validation-1"),
                        null, revision(step("step-1", false)), List.of()));
    }

    @Test
    void laterValidationStepInheritsExactCandidateFromChangingPredecessor() {
        var candidate = candidate("change", "change-activation");
        var revision = revision(
                step("change", true),
                step("prepare", false, "change"),
                step("step-1", false, "prepare"));

        assertSame(candidate, ProductValidationPublishIdentity.candidate(
                building(ChainRole.REFLECTOR, null), null,
                revision, List.of(candidate)));
    }

    @Test
    void plannerRevisionWithoutActiveStepKeepsOneExactFrozenCandidate() {
        var candidate = candidate("change", "change-activation");

        assertSame(candidate, ProductValidationPublishIdentity.candidate(
                buildingWithoutStep(ChainRole.PLANNER), null,
                revision(step("change", true)), List.of(candidate)));
    }

    @Test
    void plannerRevisionWithoutActiveStepRejectsAmbiguousCandidates() {
        var first = candidate("change-a", "activation-a");
        var second = candidate("change-b", "activation-b");

        assertThrows(ChainContextException.class,
                () -> ProductValidationPublishIdentity.candidate(
                        buildingWithoutStep(ChainRole.PLANNER), null,
                        revision(step("change-a", true),
                                step("change-b", true)),
                        List.of(first, second)));
    }

    @Test
    void unrelatedCandidateCannotBeInherited() {
        var candidate = candidate("change", "change-activation");
        var revision = revision(
                step("change", true),
                step("step-1", false));

        assertThrows(ChainContextException.class,
                () -> ProductValidationPublishIdentity.candidate(
                        building(ChainRole.REFLECTOR, null), null,
                        revision, List.of(candidate)));
    }

    @Test
    void predecessorCandidateWithDifferentFingerprintCannotBeInherited() {
        var candidate = candidate("change", "change-activation");
        when(candidate.candidateFingerprint()).thenReturn("5".repeat(64));
        var revision = revision(
                step("change", true),
                step("step-1", false, "change"));

        assertThrows(ChainContextException.class,
                () -> ProductValidationPublishIdentity.candidate(
                        building(ChainRole.REFLECTOR, null), null,
                        revision, List.of(candidate)));
    }

    @Test
    void multipleMatchingPredecessorCandidatesRemainBlocked() {
        var first = candidate("change-a", "activation-a");
        var second = candidate("change-b", "activation-b");
        var revision = revision(
                step("change-a", true), step("change-b", true),
                step("step-1", false, "change-a", "change-b"));

        assertThrows(ChainContextException.class,
                () -> ProductValidationPublishIdentity.candidate(
                        building(ChainRole.REFLECTOR, null), null,
                        revision, List.of(first, second)));
    }

    @Test
    void answerContextKeepsFrozenInputVersionAfterProjectPublication() {
        var building = building(ChainRole.ANSWER, "validation-1");
        var readiness = mock(
                ChainPersistenceRecords.FinalizationReadinessRecord.class);
        var outcome = mock(ChainPersistenceRecords.TaskOutcomeRecord.class);
        when(readiness.validationId()).thenReturn("validation-1");
        when(readiness.candidateKey()).thenReturn("2".repeat(64));
        when(readiness.projectVersion()).thenReturn("1".repeat(64));
        when(outcome.instructionId()).thenReturn("instruction-1");
        when(outcome.taskFrameId()).thenReturn("frame-1");
        when(outcome.finalPlanId()).thenReturn("plan-1");
        when(outcome.finalPlanRevisionId()).thenReturn("revision-1");
        when(outcome.validationId()).thenReturn("validation-1");
        when(outcome.candidateKey()).thenReturn("2".repeat(64));
        when(outcome.publishedProjectVersion()).thenReturn("9".repeat(64));

        ProductValidationPublishIdentity.verifyOutcome(
                building, outcome, readiness, null);
    }

    @Test
    void answerContextCannotReplaceFrozenInputVersionWithPublishedVersion() {
        var building = building(
                ChainRole.ANSWER, "validation-1", "9".repeat(64));
        var readiness = mock(
                ChainPersistenceRecords.FinalizationReadinessRecord.class);
        var outcome = mock(ChainPersistenceRecords.TaskOutcomeRecord.class);
        when(readiness.validationId()).thenReturn("validation-1");
        when(readiness.candidateKey()).thenReturn("2".repeat(64));
        when(readiness.projectVersion()).thenReturn("1".repeat(64));
        when(outcome.instructionId()).thenReturn("instruction-1");
        when(outcome.taskFrameId()).thenReturn("frame-1");
        when(outcome.finalPlanId()).thenReturn("plan-1");
        when(outcome.finalPlanRevisionId()).thenReturn("revision-1");
        when(outcome.validationId()).thenReturn("validation-1");
        when(outcome.candidateKey()).thenReturn("2".repeat(64));
        when(outcome.publishedProjectVersion()).thenReturn("9".repeat(64));

        var publishedVersionContext = building;
        assertThrows(ChainContextException.class, () ->
                ProductValidationPublishIdentity.verifyOutcome(
                        publishedVersionContext, outcome, readiness, null));
    }

    private static ChainPersistenceRecords.ContextRevisionRecord building(
            ChainRole role, String validationId) {
        return building(role, validationId, "1".repeat(64));
    }

    private static ChainPersistenceRecords.ContextRevisionRecord building(
            ChainRole role, String validationId, String projectVersion) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context-1", "task-1", null, role,
                role == ChainRole.EXECUTOR ? ChainWorkState.EXECUTING
                        : ChainWorkState.AWAITING_REVIEW,
                "TEST", "instruction-1", "frame-1", "plan-1",
                "revision-1", 1L, "step-1", "activation-1",
                82L, projectVersion, "workspace-1", 134L,
                "2".repeat(64), validationId,
                validationId == null ? null : "3".repeat(64),
                validationId == null ? null : "4".repeat(64),
                "projectors-v1", "pages-v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.BUILDING, 0,
                null, null, null, null, null, NOW, null);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord
            buildingWithoutStep(ChainRole role) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context-1", "task-1", null, role,
                ChainWorkState.PLANNING, "PLAN_REVISION", "instruction-1",
                "frame-1", "plan-1", "revision-1", 1L, null, null,
                82L, "1".repeat(64), "workspace-1", 134L,
                "2".repeat(64), null, null, null,
                "projectors-v1", "pages-v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.BUILDING, 0,
                null, null, null, null, null, NOW, null);
    }

    private static PlanRevision revision(PlanStep... steps) {
        var revision = mock(PlanRevision.class);
        when(revision.steps()).thenReturn(List.of(steps));
        return revision;
    }

    private static PlanStep step(
            String id, boolean mayChangeCandidate, String... dependencies) {
        var step = mock(PlanStep.class);
        when(step.id()).thenReturn(new PlanStepId(id));
        when(step.mayChangeCandidate()).thenReturn(mayChangeCandidate);
        when(step.dependencies()).thenReturn(java.util.Arrays.stream(dependencies)
                .map(PlanStepId::new).collect(java.util.stream.Collectors.toSet()));
        return step;
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord candidate(
            String stepId, String activationId) {
        var candidate = mock(
                ChainPersistenceRecords.CandidateStepResultRecord.class);
        when(candidate.taskId()).thenReturn("task-1");
        when(candidate.instructionId()).thenReturn("instruction-1");
        when(candidate.taskFrameId()).thenReturn("frame-1");
        when(candidate.planId()).thenReturn("plan-1");
        when(candidate.planRevisionId()).thenReturn("revision-1");
        when(candidate.planRevisionNumber()).thenReturn(1L);
        when(candidate.stepId()).thenReturn(stepId);
        when(candidate.activationEventId()).thenReturn(activationId);
        when(candidate.artifactId()).thenReturn(134L);
        when(candidate.candidateFingerprint()).thenReturn("2".repeat(64));
        return candidate;
    }
}
