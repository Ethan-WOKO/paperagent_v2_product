package com.yanban.api.agent.v2.chain.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.ReflectorPayload;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainValidationConclusion;
import io.paperagent.v2.chain.validation.ChainValidationBundleRuntime;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.TaskFrameId;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductChainExecutorProgressionIdentityTest {
    @Test
    void executorReadsTheExactFormalReplannedRevision() {
        ProductChainStepAuthorityAdapter authority = mock(
                ProductChainStepAuthorityAdapter.class);
        ChainPersistenceRecords.PlanBindingRecord binding = mock(
                ChainPersistenceRecords.PlanBindingRecord.class);
        PlanRevision revision = mock(PlanRevision.class);
        when(binding.taskId()).thenReturn("task-1");
        when(binding.taskFrameId()).thenReturn("frame-1");
        when(binding.planRevisionId()).thenReturn("revision-2");
        when(binding.planRevisionNumber()).thenReturn(2L);
        when(revision.id()).thenReturn(new PlanRevisionId("revision-2"));
        when(revision.number()).thenReturn(2L);
        when(revision.taskFrameId()).thenReturn(new TaskFrameId("frame-1"));
        when(authority.findPlanRevision("task-1", "revision-2"))
                .thenReturn(Optional.of(revision));

        assertEquals(revision, ProductChainExecutorProgression
                .formalPlanRevision(authority, binding));
    }

    @Test
    void contextIdentityTracksFormalCandidateAndActionAuthority() {
        String first = context("candidate-a", "attempt=1; actionId=action-a");

        assertEquals(first, context("candidate-a", "attempt=1; actionId=action-a"));
        assertNotEquals(first, context("candidate-a", "attempt=2; actionId=action-b"));
        assertNotEquals(first, context("candidate-b", "attempt=1; actionId=action-a"));
    }

    @Test
    void committedCandidateAdvancesToStepResultOnlyForItsActiveStep() {
        ChainPersistenceRecords.WorkspaceCandidateRecord candidate = mock(
                ChainPersistenceRecords.WorkspaceCandidateRecord.class);
        ChainPersistenceRecords.ActionBindingRecord action = mock(
                ChainPersistenceRecords.ActionBindingRecord.class);
        ChainPersistenceRecords.PlanBindingRecord binding = mock(
                ChainPersistenceRecords.PlanBindingRecord.class);
        io.paperagent.v2.chain.step.ChainStepAuthorityPort.StepEvent activation =
                mock(io.paperagent.v2.chain.step.ChainStepAuthorityPort
                        .StepEvent.class);
        io.paperagent.v2.chain.step.ChainStepAuthorityPort.StepEventCommand command =
                mock(io.paperagent.v2.chain.step.ChainStepAuthorityPort
                        .StepEventCommand.class);

        when(candidate.taskId()).thenReturn("task-1");
        when(candidate.actionId()).thenReturn("action-1");
        when(candidate.workspaceId()).thenReturn("workspace-1");
        when(candidate.versionFenceSha256()).thenReturn("f".repeat(64));
        when(action.taskId()).thenReturn("task-1");
        when(action.actionId()).thenReturn("action-1");
        when(action.instructionId()).thenReturn("instruction-1");
        when(action.taskFrameId()).thenReturn("frame-1");
        when(action.planId()).thenReturn("plan-1");
        when(action.planRevisionId()).thenReturn("revision-2");
        when(action.stepId()).thenReturn("step-fix");
        when(action.activationEventId()).thenReturn("activation-2");
        when(action.workspaceId()).thenReturn("workspace-1");
        when(action.versionFenceSha256()).thenReturn("f".repeat(64));
        when(binding.taskId()).thenReturn("task-1");
        when(binding.instructionId()).thenReturn("instruction-1");
        when(binding.taskFrameId()).thenReturn("frame-1");
        when(binding.planId()).thenReturn("plan-1");
        when(binding.planRevisionId()).thenReturn("revision-2");
        when(activation.command()).thenReturn(command);
        when(command.stepId()).thenReturn("step-fix");
        when(command.activationEventId()).thenReturn("activation-2");

        assertTrue(ProductChainExecutorProgression
                .candidateBelongsToActiveStep(
                        candidate, action, binding, activation));

        when(command.stepId()).thenReturn("step-after-fix");
        assertFalse(ProductChainExecutorProgression
                .candidateBelongsToActiveStep(
                        candidate, action, binding, activation));
    }

    @Test
    void workspaceChangeMustUseTheFrozenCurrentCandidateReference() {
        String currentFingerprint = "c".repeat(64);
        ProviderRoleOutput current = workspaceChange(currentFingerprint);
        ProviderRoleOutput stale = workspaceChange("d".repeat(64));
        ProviderRoleOutput workspaceCandidateId =
                workspaceChange("workspace-candidate.current");

        assertDoesNotThrow(() -> ProductChainExecutorProgression
                .validateExecutorCandidateBase(
                        current, currentFingerprint));
        assertThrows(IllegalArgumentException.class,
                () -> ProductChainExecutorProgression
                        .validateExecutorCandidateBase(
                                stale, currentFingerprint));
        assertThrows(IllegalArgumentException.class,
                () -> ProductChainExecutorProgression
                        .validateExecutorCandidateBase(
                        workspaceCandidateId, currentFingerprint));
    }

    @Test
    void postExecutionReportingAcceptsOnlyStepResult() {
        ProviderRoleOutput workspaceChange = workspaceChange("NONE");
        ExecutorPayload.StepResult stepResult = new ExecutorPayload.StepResult(
                List.of(new ProposalFields.RequirementCoverage(
                        "compile and run", ProposalFields.RequirementStatus.SATISFIED,
                        List.of("receipt-1"))),
                "compiled and ran", List.of(), null,
                List.of("receipt-1"), List.of(), List.of(), List.of(), null);
        ProviderRoleOutput reporting = new ProviderRoleOutput(
                "1", stepResult.kind().wireName(), stepResult);

        assertDoesNotThrow(() -> ProductChainExecutorProgression
                .requireStepResultOutput(reporting));
        assertThrows(IllegalArgumentException.class,
                () -> ProductChainExecutorProgression
                        .requireStepResultOutput(workspaceChange));
    }

    @Test
    void stepResultValidationSourcesExactlyCoverTheActiveStepBindings() {
        ExecutorPayload.StepResult bound = new ExecutorPayload.StepResult(
                List.of(new ProposalFields.RequirementCoverage(
                        "read file", ProposalFields.RequirementStatus.SATISFIED,
                        List.of("receipt-1"))),
                "read complete", List.of(), null, List.of("receipt-1"),
                List.of(new ProposalFields.ValidationSource(
                        "validation-read", "receipt-1")),
                List.of(), List.of("receipt-1"), List.of(), null);
        ProviderRoleOutput output = new ProviderRoleOutput(
                "1", bound.kind().wireName(), bound);

        assertDoesNotThrow(() -> ProductChainExecutorProgression
                .validateExecutorStepResultValidationBindings(
                        output, List.of("validation-read")));
        assertThrows(IllegalArgumentException.class,
                () -> ProductChainExecutorProgression
                        .validateExecutorStepResultValidationBindings(
                                output, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ProductChainExecutorProgression
                        .validateExecutorStepResultValidationBindings(
                                output, List.of("validation-other")));
    }

    @Test
    void acceptedProposalRecoveryHasExplicitReadAndConsumeBoundaries()
            throws Exception {
        Method recover = ProductChainExecutorProgression.class.getMethod(
                "recoverAcceptedProposal", String.class, String.class);
        Method consume = ProductChainExecutorProgression.class.getMethod(
                "consumeAcceptedProposal", String.class, String.class,
                java.time.Instant.class);

        assertEquals(io.paperagent.v2.chain.model.ChainModelProtocolOutcome
                        .ProposalReady.class,
                recover.getReturnType());
        assertEquals(ProductChainExecutorPump.OfficialSuccessor.class,
                consume.getReturnType());
        assertTrue(java.lang.reflect.Modifier.isPublic(
                recover.getModifiers()));
    }

    @Test
    void publishRequirementIsIndependentFromWorkspaceWritePermission() {
        var required = io.paperagent.v2.contracts.TaskRequirements.explicit(
                List.of(), io.paperagent.v2.contracts.PublishRequirement.REQUIRED);
        var notRequired = io.paperagent.v2.contracts.TaskRequirements.explicit(
                List.of(), io.paperagent.v2.contracts.PublishRequirement.NOT_REQUIRED);

        for (boolean ignoredWriteWorkspacePermission : List.of(false, true)) {
            assertTrue(ProductChainExecutorProgression
                    .taskFrameRequiresPublish(required));
            assertEquals(false, ProductChainExecutorProgression
                    .taskFrameRequiresPublish(notRequired));
        }
        assertThrows(IllegalStateException.class, () ->
                ProductChainExecutorProgression.taskFrameRequiresPublish(
                        io.paperagent.v2.contracts.TaskRequirements
                                .legacyUnspecified()));
    }

    @Test
    void reflectorBindsFrozenRequirementsBeforeBundleExists() {
        ProviderRoleOutput required = finalAcceptance(
                ProposalFields.AssessmentStatus.BOUND, "frame-1");
        assertDoesNotThrow(() -> ProductChainExecutorProgression
                .validateReflectorFinalizationAuthority(
                        required, true, true, false, "frame-1"));
        assertThrows(IllegalArgumentException.class, () ->
                ProductChainExecutorProgression
                        .validateReflectorFinalizationAuthority(
                                finalAcceptance(
                                        ProposalFields.AssessmentStatus.BOUND,
                                        "validation-set-final"),
                                true, true, false, "frame-1"));
        assertDoesNotThrow(() -> ProductChainExecutorProgression
                .validateReflectorFinalizationAuthority(
                        finalAcceptance(
                                ProposalFields.AssessmentStatus.NOT_REQUIRED,
                                null),
                        true, false, false, "frame-1"));
    }

    @Test
    void finalStepCannotBeAcceptedWithoutFinalizationReadiness() {
        var combined = (ReflectorPayload.AcceptStepAndReadyToFinalize)
                finalAcceptance(ProposalFields.AssessmentStatus.NOT_REQUIRED,
                        null).payload();
        ProviderRoleOutput plainAcceptance = new ProviderRoleOutput(
                "1", combined.acceptance().kind().wireName(),
                combined.acceptance());

        assertThrows(IllegalArgumentException.class, () ->
                ProductChainExecutorProgression
                        .validateReflectorFinalizationAuthority(
                                plainAcceptance, true, false, false,
                                "frame-1"));
        assertDoesNotThrow(() -> ProductChainExecutorProgression
                .validateReflectorFinalizationAuthority(
                        plainAcceptance, false, false, false,
                        "frame-1"));
    }

    @Test
    void reflectorCandidateReferenceIsCheckedBeforeProposalAcceptance() {
        ProviderRoleOutput noCandidate = finalAcceptance(
                ProposalFields.AssessmentStatus.NOT_REQUIRED, null);
        assertDoesNotThrow(() -> ProductChainExecutorProgression
                .validateReflectorCandidateReference(noCandidate, "NONE"));
        assertThrows(IllegalArgumentException.class, () ->
                ProductChainExecutorProgression
                        .validateReflectorCandidateReference(
                                noCandidate, "workspace-candidate-1"));

        ProviderRoleOutput changedCandidate = finalAcceptance(
                ProposalFields.AssessmentStatus.NOT_REQUIRED, null,
                "workspace-candidate-1");
        assertDoesNotThrow(() -> ProductChainExecutorProgression
                .validateReflectorCandidateReference(
                        changedCandidate, "workspace-candidate-1"));
        assertThrows(IllegalArgumentException.class, () ->
                ProductChainExecutorProgression
                        .validateReflectorCandidateReference(
                                changedCandidate, "NONE"));
    }

    @Test
    void readinessCarriesBundleIdentityOrExplicitNone() {
        var record = new ChainPersistenceRecords.ValidationBundleRecord(
                "validation-bundle-1", "task-1", "bundle-event-1",
                "frame-1", "plan-1", "revision-1", 1,
                "instruction-1", "step-2", "1".repeat(64),
                "2".repeat(64), "3".repeat(64),
                ChainValidationConclusion.PASSED, "bundle-key",
                Instant.parse("2026-08-09T00:00:00Z"));
        var identity = ProductChainExecutorProgression
                .readinessValidationBundle(
                        new ChainValidationBundleRuntime.Committed(
                                record, List.of(), false));
        assertEquals("validation-bundle-1", identity.validationId());
        assertEquals("1".repeat(64), identity.requestDigest());
        assertEquals("2".repeat(64), identity.receiptSetDigest());

        var none = ProductChainExecutorProgression
                .readinessValidationBundle(
                        new ChainValidationBundleRuntime.NotRequired());
        assertEquals(io.paperagent.v2.chain.ChainIdentity.NONE,
                none.validationId());
        assertEquals(null, none.requestDigest());
        assertEquals(null, none.receiptSetDigest());
    }

    private static String context(String candidate, String actions) {
        return ProductChainExecutorProgression.executorContextId(
                "task-1", "activation-1", "STEP_EXECUTION", "same instruction",
                candidate, actions);
    }

    private static ProviderRoleOutput workspaceChange(String baseCandidateRef) {
        ExecutorPayload.WorkspaceChange payload =
                new ExecutorPayload.WorkspaceChange(
                        baseCandidateRef, List.of("src/File.java"),
                        "{\"replacements\":[{\"path\":\"src/File.java\","
                                + "\"text\":\"class File {}\"}]}",
                        "apply the requested change", List.of("source updated"),
                        List.of(), null);
        return new ProviderRoleOutput("1", payload.kind().wireName(), payload);
    }

    private static ProviderRoleOutput finalAcceptance(
            ProposalFields.AssessmentStatus validationStatus,
            String validationAuthority) {
        return finalAcceptance(validationStatus, validationAuthority, "NONE");
    }

    private static ProviderRoleOutput finalAcceptance(
            ProposalFields.AssessmentStatus validationStatus,
            String validationAuthority,
            String candidateRef) {
        var review = new ProposalFields.ReviewCommon(
                "final", List.of("candidate-result-1"), "accepted",
                List.of("receipt-1"), List.of());
        var acceptance = new ReflectorPayload.AcceptStep(
                review, "candidate-result-1", List.of(
                new ProposalFields.RequirementCoverage(
                        "done", ProposalFields.RequirementStatus.SATISFIED,
                        List.of("receipt-1"))), List.of("receipt-1"),
                "frame-1", "revision-1", "step-1", candidateRef, List.of());
        var notRequired = new ProposalFields.AuthorityAssessment(
                ProposalFields.AssessmentStatus.NOT_REQUIRED, null,
                "not required");
        var validation = validationStatus
                == ProposalFields.AssessmentStatus.BOUND
                ? new ProposalFields.AuthorityAssessment(
                validationStatus, validationAuthority, null)
                : new ProposalFields.AuthorityAssessment(
                validationStatus, null, "not required");
        var finalization = new ProposalFields.FinalizationAssessment(
                List.of(new ProposalFields.RequirementCoverage(
                        "done", ProposalFields.RequirementStatus.SATISFIED,
                        List.of("receipt-1"))), notRequired, notRequired,
                validation, notRequired, List.of(), List.of());
        var payload = new ReflectorPayload.AcceptStepAndReadyToFinalize(
                review, acceptance, finalization);
        return new ProviderRoleOutput(
                "1", payload.kind().wireName(), payload);
    }
}
