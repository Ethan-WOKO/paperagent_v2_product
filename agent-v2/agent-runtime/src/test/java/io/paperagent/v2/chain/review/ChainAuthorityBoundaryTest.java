package io.paperagent.v2.chain.review;

import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainActionBindingWriter;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainFinalizationCheckWriter;
import io.paperagent.v2.chain.ChainPermissionDecision;
import io.paperagent.v2.chain.ChainPermissionDecisionWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainReviewDecisionWriter;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTaskOutcomeWriter;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.ReflectorPayload;
import io.paperagent.v2.chain.state.ChainPermissionDecisionRuntime;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.chain.step.ChainActionRuntime;
import io.paperagent.v2.chain.step.ChainStepException;
import io.paperagent.v2.chain.step.ChainStepTestStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainAuthorityBoundaryTest {
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String CANDIDATE = "b".repeat(64);
    private static final String WORKSPACE_CANDIDATE =
            "workspace-candidate-1";

    @Test
    void actionRuntimeSolelyBindsWorkspaceChangesAndFreezesBodyDigest() {
        String firstBody = "{\"patch\":\"first\"}";
        String secondBody = "{\"patch\":\"second\"}";
        ChainPersistenceRecords.ActionBindingRecord first =
                ChainStepTestStore.commitWorkspaceChangeForBoundary(
                        firstBody, "task-1", "invocation-workspace",
                        ChainContentKind.WORKSPACE_CHANGE_BODY);
        ChainPersistenceRecords.ActionBindingRecord second =
                ChainStepTestStore.commitWorkspaceChangeForBoundary(
                        secondBody, "task-1", "invocation-workspace",
                        ChainContentKind.WORKSPACE_CHANGE_BODY);

        String payloadDigest = sha256("{\"change\":\"workspace\"}");
        assertEquals(sha256(payloadDigest + "\0" + sha256(firstBody)),
                first.actionSignatureSha256());
        assertEquals(sha256(payloadDigest + "\0" + sha256(secondBody)),
                second.actionSignatureSha256());
        assertNotEquals(first.actionSignatureSha256(),
                second.actionSignatureSha256());
        assertNotEquals(first.actionId(), second.actionId(),
                "different change bodies cannot reuse an Action identity");

        assertThrows(ChainStepException.class,
                () -> ChainStepTestStore.commitWorkspaceChangeForBoundary(
                        firstBody, "task-other", "invocation-workspace",
                        ChainContentKind.WORKSPACE_CHANGE_BODY));
        assertThrows(ChainStepException.class,
                () -> ChainStepTestStore.commitWorkspaceChangeForBoundary(
                        firstBody, "task-1", "invocation-other",
                        ChainContentKind.WORKSPACE_CHANGE_BODY));
        assertThrows(ChainStepException.class,
                () -> ChainStepTestStore.commitWorkspaceChangeForBoundary(
                        firstBody, "task-1", "invocation-workspace",
                        ChainContentKind.ANSWER_BODY));

        long actionWriters = java.util.Arrays.stream(
                        ChainActionRuntime.class.getDeclaredFields())
                .filter(field -> ChainActionBindingWriter.class
                        .isAssignableFrom(field.getType()))
                .count();
        assertEquals(1L, actionWriters);
        assertFalse(java.util.Arrays.stream(
                        ChainEffectRuntime.class.getDeclaredFields())
                .anyMatch(field -> ChainActionBindingWriter.class
                        .isAssignableFrom(field.getType())),
                "effect execution must consume, never create, ActionBinding");
    }

    @Test
    void actionRuntimeAcceptsWriterOwnedAuditTimeWithoutWeakeningIdentity() {
        Instant persistedAt = NOW.plusSeconds(17);
        ChainPersistenceRecords.ActionBindingRecord action =
                ChainStepTestStore.commitWorkspaceChangeForBoundary(
                        "{\"patch\":\"authority-time\"}", "task-1",
                        "invocation-workspace",
                        ChainContentKind.WORKSPACE_CHANGE_BODY, persistedAt);

        assertEquals(persistedAt, action.createdAt());
    }

    @Test
    void reviewCommitsOnlyDecisionAndHandsCandidateAcceptanceToItsFormalSuccessors() {
        Store store = new Store();
        store.candidates.add(candidate());
        store.workspaceCandidates.add(workspaceCandidate());
        ReflectorPayload.AcceptStep payload = acceptedPayload(
                WORKSPACE_CANDIDATE);
        store.sources.put("proposal-accept", source("proposal-accept", payload,
                ChainProposalState.ACCEPTED, null, null));
        List<String> bindings = new ArrayList<>();
        ChainReviewRuntime runtime = runtime(store, bindings);

        ChainReviewRuntime.CommitResult result = runtime.commit(
                new ChainReviewRuntime.CommitRequest(
                        "task-1", "proposal-accept", "event-review",
                        "CANDIDATE_STEP_RESULT", "candidate-result-1", NOW));

        assertEquals(ChainProposalKind.REFLECTOR_ACCEPT_STEP,
                result.decision().decisionKind());
        assertEquals(ChainReviewRuntime.SuccessorRequirement.ACCEPTED_RESULT_AND_STEP,
                result.successorRequirement());
        assertEquals(1, store.decisions.size());
        assertTrue(store.acceptedResults.isEmpty(),
                "Review runtime must not accept its own candidate result");
        assertTrue(store.pendingItems.isEmpty(),
                "Review runtime must not create a PendingItem for ACCEPT_STEP");
        assertEquals(List.of("REVIEW_DECISION"), store.authorityEventTypes);
        assertEquals(List.of("proposal-accept:REVIEW_DECISION:"
                + result.decision().reviewDecisionId()), bindings);
        assertTrue(runtime.nextModelRole("task-1").isEmpty(),
                "next Executor requires formal accepted-result/Step successors");
    }

    @Test
    void permissionAndReadinessReviewKindsCannotCrossTheirAuthorityBoundaries() {
        Store store = new Store();
        ProposalFields.ReviewCommon review = review("review-object");
        ReflectorPayload.NeedPermission permission = new ReflectorPayload.NeedPermission(
                review, "PROJECT_WRITE", "project:write", "edit project",
                "read-only alternative", ChainRole.PLANNER, "NEW_INTAKE");
        store.sources.put("proposal-permission", source(
                "proposal-permission", permission, ChainProposalState.ACCEPTED, null, null));
        ChainReviewRuntime runtime = runtime(store, new ArrayList<>());

        ChainReviewRuntime.CommitResult permissionResult = runtime.commit(
                new ChainReviewRuntime.CommitRequest(
                        "task-1", "proposal-permission", "event-permission-review",
                        "STEP", "review-object", NOW));
        assertEquals(ChainReviewRuntime.SuccessorRequirement.PERMISSION_PENDING_ITEM,
                permissionResult.successorRequirement());
        assertTrue(store.pendingItems.isEmpty());
        assertTrue(store.permissionDecisions.isEmpty(),
                "only product Permission runtime may grant or deny permission");
        assertTrue(runtime.nextModelRole("task-1").isEmpty(),
                "Answer waits for a formal PendingItem, not an in-memory review payload");

        ReflectorPayload.ReadyToFinalize ready = new ReflectorPayload.ReadyToFinalize(
                review, finalization());
        store.sources.put("proposal-ready", source(
                "proposal-ready", ready, ChainProposalState.ACCEPTED, null, null));
        ChainReviewRuntime.CommitResult readyResult = runtime.commit(
                new ChainReviewRuntime.CommitRequest(
                        "task-1", "proposal-ready", "event-ready-review",
                        "STEP", "review-object", NOW));
        assertEquals(ChainReviewRuntime.SuccessorRequirement.STEP_READINESS,
                readyResult.successorRequirement());
        assertTrue(store.readiness.isEmpty(),
                "Review runtime cannot treat READY_TO_FINALIZE as formal readiness");
        assertTrue(store.finalizationChecks.isEmpty(),
                "Review runtime cannot submit a FinalizationCheck");
        assertFalse(java.util.Arrays.stream(ChainReviewRuntime.class.getDeclaredFields())
                        .anyMatch(field -> ChainFinalizationCheckWriter.class
                                .isAssignableFrom(field.getType())),
                "Review runtime must not hold the FinalizationCheck writer");
    }

    @Test
    void nextRoleComesFromCommittedReviewDecisionAndCandidateBindingIsFenced() {
        Store store = new Store();
        ReflectorPayload.ContinueStep continueStep = new ReflectorPayload.ContinueStep(
                review("review-object"), List.of("condition"), List.of("error-1"),
                "same step only");
        store.sources.put("proposal-continue", source(
                "proposal-continue", continueStep, ChainProposalState.ACCEPTED, null, null));
        ChainReviewRuntime runtime = runtime(store, new ArrayList<>());
        assertTrue(runtime.nextModelRole("task-1").isEmpty());

        runtime.commit(new ChainReviewRuntime.CommitRequest(
                "task-1", "proposal-continue", "event-continue",
                "STEP", "review-object", NOW));
        ChainReviewRuntime.ModelRoleDirective directive =
                runtime.nextModelRole("task-1").orElseThrow();
        assertEquals(ChainRole.EXECUTOR, directive.role());
        assertEquals(ChainWorkState.EXECUTING, directive.workState());

        Store mismatched = new Store();
        mismatched.candidates.add(candidate());
        mismatched.workspaceCandidates.add(workspaceCandidate());
        ReflectorPayload.AcceptStep forgedCandidate = acceptedPayload(
                "workspace-candidate-forged");
        mismatched.sources.put("proposal-forged", source(
                "proposal-forged", forgedCandidate,
                ChainProposalState.ACCEPTED, null, null));
        assertThrows(IllegalStateException.class, () -> runtime(mismatched, new ArrayList<>())
                .commit(new ChainReviewRuntime.CommitRequest(
                        "task-1", "proposal-forged", "event-forged",
                        "CANDIDATE_STEP_RESULT", "candidate-result-1", NOW)));
        assertTrue(mismatched.decisions.isEmpty());

        Store rejected = new Store();
        rejected.sources.put("proposal-rejected", source(
                "proposal-rejected", continueStep,
                ChainProposalState.REJECTED, null, null));
        assertThrows(IllegalStateException.class, () -> runtime(rejected, new ArrayList<>())
                .commit(new ChainReviewRuntime.CommitRequest(
                        "task-1", "proposal-rejected", "event-rejected",
                        "STEP", "review-object", NOW)));
        assertTrue(rejected.decisions.isEmpty());

        Store crossRequest = new Store();
        crossRequest.sources.put("requested-proposal", source(
                "different-proposal", continueStep,
                ChainProposalState.ACCEPTED, null, null));
        assertThrows(IllegalStateException.class, () -> runtime(crossRequest, new ArrayList<>())
                .commit(new ChainReviewRuntime.CommitRequest(
                        "task-1", "requested-proposal", "event-cross-proposal",
                        "STEP", "review-object", NOW)));
        assertTrue(crossRequest.decisions.isEmpty());

        ChainPersistenceRecords.ModelProposalRecord taskOneProposal =
                new ChainPersistenceRecords.ModelProposalRecord(
                        "proposal-cross-task", "task-1", "invocation-cross", 1,
                        ChainRole.REFLECTOR, continueStep.kind(), canonical("{}"),
                        canonical("[]"), null, null, NOW);
        ChainPersistenceRecords.ProposalStateEventRecord taskTwoState =
                new ChainPersistenceRecords.ProposalStateEventRecord(
                        "proposal-cross-task", 1, "task-2", "state-cross",
                        ChainProposalState.ACCEPTED, null, null, NOW);
        assertThrows(IllegalArgumentException.class, () ->
                new ChainReviewRuntime.FormalReviewProposal(
                        taskOneProposal, taskTwoState, continueStep, HASH));
    }

    @Test
    void permissionAuthorityAcceptsOnlyVerifiedProductFactsAndReplaysExactly() {
        Store store = new Store();
        ChainPermissionDecisionRuntime runtime = new ChainPermissionDecisionRuntime(
                store,
                request -> {
                    if (!"PRODUCT_ACL".equals(request.authority().authorityType())
                            || !"grant-1".equals(request.authority().authorityRef())
                            || !"project:write".equals(request.permissionScope())) {
                        throw new IllegalStateException("no verified product permission authority");
                    }
                });
        ChainPermissionDecisionRuntime.ProductDecisionRequest granted = permissionRequest(
                "PRODUCT_ACL", "grant-1", ChainPermissionDecision.GRANTED, "user granted");

        ChainPermissionDecisionRuntime.CommitResult first = runtime.commit(granted);
        ChainPermissionDecisionRuntime.CommitResult replay = runtime.commit(granted);
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.decision(), replay.decision());
        assertEquals(1, store.permissionDecisionFacts.size());

        assertThrows(IllegalStateException.class, () -> runtime.commit(permissionRequest(
                "MODEL_PROPOSAL", "proposal-permission", ChainPermissionDecision.GRANTED,
                "model cannot grant")));
        assertThrows(IllegalStateException.class, () -> runtime.commit(permissionRequest(
                "PRODUCT_ACL", "wrong-grant", ChainPermissionDecision.GRANTED,
                "wrong product source")));
        assertEquals(1, store.permissionDecisionFacts.size());

        assertThrows(IllegalStateException.class, () -> runtime.commit(permissionRequest(
                "PRODUCT_ACL", "grant-1", ChainPermissionDecision.DENIED,
                "changed replay contents")));
        assertEquals(1, store.permissionDecisionFacts.size());
    }

    @Test
    void taskOutcomeAuthorityUsesTypedFormalSourcesAndExactTerminalReplay() {
        List<OutcomeBoundaryCase> cases = List.of(
                new OutcomeBoundaryCase("completed", new ChainTaskOutcomeRuntime.Completed(
                        outcomeDraft("completed", "instruction-final"),
                        "transition-finalization-1")),
                new OutcomeBoundaryCase("failed", new ChainTaskOutcomeRuntime.Failed(
                        outcomeDraft("failed", "instruction-failed"),
                        "review-failure-1", "EXECUTION", "TOOL_FAILED")),
                new OutcomeBoundaryCase("cancelled", new ChainTaskOutcomeRuntime.Cancelled(
                        outcomeDraft("cancelled", "instruction-cancel"),
                        "instruction-cancel")),
                new OutcomeBoundaryCase("superseded", supersededCommand(
                        outcomeDraft("superseded", "instruction-old"),
                        "instruction-new")));

        for (OutcomeBoundaryCase boundary : cases) {
            Store store = new Store();
            ChainTaskOutcomeRuntime runtime = new ChainTaskOutcomeRuntime(
                    store, formalOutcomeVerifier());
            ChainTaskOutcomeRuntime.CommitResult first = runtime.commit(boundary.command());
            ChainTaskOutcomeRuntime.CommitResult replay = runtime.commit(boundary.command());
            assertFalse(first.replayed(), boundary.name());
            assertTrue(replay.replayed(), boundary.name());
            assertEquals(first.outcome(), replay.outcome(), boundary.name());
            assertEquals(1, store.taskOutcomeFacts.size(), boundary.name());
        }

        Store invalid = new Store();
        ChainTaskOutcomeRuntime runtime = new ChainTaskOutcomeRuntime(
                invalid, formalOutcomeVerifier());
        assertThrows(IllegalStateException.class, () -> runtime.commit(
                new ChainTaskOutcomeRuntime.Completed(
                        outcomeDraft("bad-completed", "instruction-final"),
                        "proposal-model-completed")));
        assertThrows(IllegalStateException.class, () -> runtime.commit(
                new ChainTaskOutcomeRuntime.Failed(
                        outcomeDraft("bad-failed", "instruction-failed"),
                        "proposal-model-failure", "EXECUTION", "MODEL_SAID_FAILED")));
        assertThrows(IllegalStateException.class, () -> runtime.commit(
                new ChainTaskOutcomeRuntime.Cancelled(
                        outcomeDraft("bad-cancel", "instruction-not-cancel"),
                        "instruction-cancel")));
        ChainTaskOutcomeRuntime.OutcomeDraft superseded =
                outcomeDraft("bad-superseded", "instruction-old");
        assertThrows(IllegalStateException.class, () -> runtime.commit(
                new ChainTaskOutcomeRuntime.Superseded(
                        superseded,
                        new ChainTaskOutcomeRuntime.OldBoundary(
                                "instruction-other", superseded.taskFrameId(),
                                superseded.finalPlanId(), superseded.finalPlanRevisionId(),
                                superseded.coverage(), superseded.acceptedSet()),
                        "instruction-new")));
        assertTrue(invalid.taskOutcomeFacts.isEmpty());

        Store replayMismatch = new Store();
        ChainTaskOutcomeRuntime exactRuntime = new ChainTaskOutcomeRuntime(
                replayMismatch, formalOutcomeVerifier());
        ChainTaskOutcomeRuntime.Completed exact = new ChainTaskOutcomeRuntime.Completed(
                outcomeDraft("exact", "instruction-final"),
                "transition-finalization-1");
        exactRuntime.commit(exact);
        ChainTaskOutcomeRuntime.OutcomeDraft changed = new ChainTaskOutcomeRuntime.OutcomeDraft(
                exact.draft().taskId(), exact.draft().eventId(), exact.draft().sourceCommandId(),
                exact.draft().instructionId(), exact.draft().taskFrameId(),
                exact.draft().finalPlanId(), exact.draft().finalPlanRevisionId(),
                exact.draft().coverage(), exact.draft().acceptedSet(),
                exact.draft().finalArtifactId(), exact.draft().candidateKey(),
                exact.draft().finalizationReadinessId(),
                exact.draft().finalizationCheckId(),
                exact.draft().validationId(),
                exact.draft().validationRequestDigest(),
                exact.draft().validationReceiptDigest(),
                exact.draft().publishRequirement(),
                exact.draft().publishRequirementDigest(),
                exact.draft().publishOperationId(),
                exact.draft().publishedProjectVersion(), exact.draft().publishedRevisionId(),
                exact.draft().publishReceiptId(), canonical("[\"changed\"]"),
                exact.draft().limitations(), exact.draft().risks(), exact.draft().createdAt());
        assertThrows(IllegalStateException.class, () -> exactRuntime.commit(
                new ChainTaskOutcomeRuntime.Completed(
                        changed, "transition-finalization-1")));
    }

    @Test
    void newCompletedOutcomeCannotWriteWithoutExactFinalizationRoot() {
        Store store = new Store();
        ChainTaskOutcomeRuntime runtime = new ChainTaskOutcomeRuntime(
                store, formalOutcomeVerifier());
        ChainTaskOutcomeRuntime.OutcomeDraft withoutRoot = outcomeDraft(
                "completed-without-root", "instruction-final");

        assertThrows(IllegalStateException.class, () -> runtime.commit(
                new ChainTaskOutcomeRuntime.Completed(
                        withoutRoot, "transition-finalization-1")));
        assertTrue(store.taskOutcomeFacts.isEmpty());
    }

    private static ChainPermissionDecisionRuntime.ProductDecisionRequest permissionRequest(
            String authorityType,
            String authorityRef,
            ChainPermissionDecision decision,
            String reason) {
        return new ChainPermissionDecisionRuntime.ProductDecisionRequest(
                "task-1", "gap-permission-1", "event-permission-decision",
                "project:write",
                new ChainPermissionDecisionRuntime.ProductAuthority(
                        authorityType, authorityRef),
                decision, reason, NOW);
    }

    private static ChainTaskOutcomeRuntime.OutcomeDraft outcomeDraft(
            String suffix, String instructionId) {
        boolean completed = suffix.equals("completed")
                || suffix.equals("exact")
                || suffix.equals("bad-completed");
        return new ChainTaskOutcomeRuntime.OutcomeDraft(
                "task-1", "event-outcome-" + suffix, "command-" + suffix,
                instructionId, null, null, null,
                canonical("[]"), canonical("[]"), null, ChainIdentity.NONE,
                completed ? "readiness-1" : null,
                completed ? "check-1" : null,
                ChainIdentity.NONE, null, null,
                completed ? io.paperagent.v2.chain.ChainPublishRequirement
                        .NOT_REQUIRED : null,
                completed ? HASH : null,
                null, null, null, null,
                canonical("[]"), canonical("[]"), canonical("[]"), NOW);
    }

    private static ChainTaskOutcomeRuntime.Superseded supersededCommand(
            ChainTaskOutcomeRuntime.OutcomeDraft draft,
            String newInstructionId) {
        return new ChainTaskOutcomeRuntime.Superseded(
                draft,
                new ChainTaskOutcomeRuntime.OldBoundary(
                        draft.instructionId(), draft.taskFrameId(), draft.finalPlanId(),
                        draft.finalPlanRevisionId(), draft.coverage(), draft.acceptedSet()),
                newInstructionId);
    }

    private static ChainTaskOutcomeRuntime.FormalSourceVerifier formalOutcomeVerifier() {
        return new ChainTaskOutcomeRuntime.FormalSourceVerifier() {
            @Override
            public void verifyCompleted(ChainTaskOutcomeRuntime.Completed command) {
                if (!"transition-finalization-1".equals(command.finalizationTransitionId())) {
                    throw new IllegalStateException("completion lacks a formal finalization transition");
                }
            }

            @Override
            public void verifyFailed(ChainTaskOutcomeRuntime.Failed command) {
                if (!"review-failure-1".equals(command.formalFailureSourceId())) {
                    throw new IllegalStateException("failure lacks a formal failure source");
                }
            }

            @Override
            public void verifyCancelled(ChainTaskOutcomeRuntime.Cancelled command) {
                if (!"instruction-cancel".equals(command.cancellationInstructionId())) {
                    throw new IllegalStateException("cancellation lacks an explicit instruction");
                }
            }

            @Override
            public void verifySuperseded(ChainTaskOutcomeRuntime.Superseded command) {
                if (!"instruction-new".equals(command.supersededByInstructionId())) {
                    throw new IllegalStateException("supersession lacks a new instruction");
                }
            }
        };
    }

    private record OutcomeBoundaryCase(
            String name, ChainTaskOutcomeRuntime.OutcomeCommand command) {
    }

    private static ChainReviewRuntime runtime(Store store, List<String> bindings) {
        return new ChainReviewRuntime(
                store,
                store,
                proposalId -> {
                    ChainReviewRuntime.FormalReviewProposal source = store.sources.get(proposalId);
                    if (source == null) throw new IllegalStateException("review source missing");
                    return source;
                },
                (taskId, proposalId, authorityType, authorityRef) ->
                        bindings.add(proposalId + ":" + authorityType + ":" + authorityRef));
    }

    private static ChainReviewRuntime.FormalReviewProposal source(
            String proposalId,
            ReflectorPayload payload,
            ChainProposalState state,
            String authorityType,
            String authorityRef) {
        ChainPersistenceRecords.ModelProposalRecord proposal =
                new ChainPersistenceRecords.ModelProposalRecord(
                        proposalId, "task-1", "invocation-" + proposalId, 1,
                        ChainRole.REFLECTOR, payload.kind(), canonical("{}"),
                        canonical("[]"), null, null, NOW);
        ChainPersistenceRecords.ProposalStateEventRecord current =
                new ChainPersistenceRecords.ProposalStateEventRecord(
                        proposalId,
                        state == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT ? 2 : 1,
                        "task-1", "state-" + proposalId, state,
                        authorityType, authorityRef, NOW);
        return new ChainReviewRuntime.FormalReviewProposal(
                proposal, current, payload, HASH);
    }

    private static ReflectorPayload.AcceptStep acceptedPayload(String candidateRef) {
        return new ReflectorPayload.AcceptStep(
                review("candidate-result-1"), "candidate-result-1",
                List.of(new ProposalFields.RequirementCoverage(
                        "condition", ProposalFields.RequirementStatus.SATISFIED,
                        List.of("fact-1"))),
                List.of("artifact-1", "receipt-1", candidateRef),
                "task-frame-1", "revision-1", "step-1", candidateRef, List.of());
    }

    private static ProposalFields.ReviewCommon review(String objectRef) {
        return new ProposalFields.ReviewCommon(
                "current step", List.of(objectRef), "formal facts satisfy decision",
                List.of("fact-1"), List.of());
    }

    private static ProposalFields.FinalizationAssessment finalization() {
        ProposalFields.RequirementCoverage coverage =
                new ProposalFields.RequirementCoverage(
                        "deliver", ProposalFields.RequirementStatus.SATISFIED,
                        List.of("fact-1"));
        return new ProposalFields.FinalizationAssessment(
                List.of(coverage), notRequired("no artifact"),
                notRequired("no candidate"), notRequired("no validation required"),
                notRequired("no publish required"), List.of("fact-1"), List.of());
    }

    private static ProposalFields.AuthorityAssessment notRequired(String reason) {
        return new ProposalFields.AuthorityAssessment(
                ProposalFields.AssessmentStatus.NOT_REQUIRED, null, reason);
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord candidate() {
        return new ChainPersistenceRecords.CandidateStepResultRecord(
                "candidate-result-1", "task-1", "event-candidate",
                "proposal-result", "content-result", "instruction-1",
                "task-frame-1", "plan-1", "revision-1", 1,
                "step-1", "activation-1", 1L, CANDIDATE, HASH,
                canonical("[]"), null, null, null,
                canonical("[]"), HASH, NOW);
    }

    private static ChainPersistenceRecords.WorkspaceCandidateRecord
            workspaceCandidate() {
        return new ChainPersistenceRecords.WorkspaceCandidateRecord(
                WORKSPACE_CANDIDATE, "task-1", "event-workspace-candidate",
                "action-1", "workspace-1", "project-version-1", 1L,
                CANDIDATE, HASH, HASH, NOW);
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(String json) {
        return new ChainPersistenceRecords.CanonicalJson(1, sha256(json), json);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class Store implements
            ChainWorkflowRepository,
            ChainReviewDecisionWriter,
            ChainPermissionDecisionWriter,
            ChainTaskOutcomeWriter {
        private final Map<String, ChainReviewRuntime.FormalReviewProposal> sources = new HashMap<>();
        private final List<ChainPersistenceRecords.CandidateStepResultRecord> candidates = new ArrayList<>();
        private final List<ChainPersistenceRecords.WorkspaceCandidateRecord>
                workspaceCandidates = new ArrayList<>();
        private final Map<String, ChainPersistenceRecords.ReviewDecisionRecord> decisions =
                new LinkedHashMap<>();
        private final List<ChainPersistenceRecords.AcceptedResultRecord> acceptedResults = new ArrayList<>();
        private final List<ChainPersistenceRecords.PendingItemRecord> pendingItems = new ArrayList<>();
        private final List<ChainPersistenceRecords.PermissionDecisionRecord> permissionDecisions = new ArrayList<>();
        private final List<ChainPersistenceRecords.FinalizationReadinessRecord> readiness = new ArrayList<>();
        private final List<ChainPersistenceRecords.FinalizationCheckRecord> finalizationChecks =
                new ArrayList<>();
        private final Map<String, ChainPersistenceRecords.PermissionDecisionRecord>
                permissionDecisionFacts = new LinkedHashMap<>();
        private final Map<String, ChainPersistenceRecords.TaskOutcomeRecord>
                taskOutcomeFacts = new LinkedHashMap<>();
        private final List<String> authorityEventTypes = new ArrayList<>();
        private long eventSequence;

        @Override
        public ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.ReviewDecisionRecord>
                appendReviewDecision(
                        ChainPersistenceRecords.AuthoritativeFact<
                                ChainPersistenceRecords.ReviewDecisionRecord> requested) {
            authorityEventTypes.add(requested.event().eventType());
            ChainPersistenceRecords.ReviewDecisionRecord existing = decisions.putIfAbsent(
                    requested.fact().reviewDecisionId(), requested.fact());
            ChainPersistenceRecords.ReviewDecisionRecord fact = existing == null
                    ? requested.fact() : existing;
            ChainPersistenceRecords.AuthorityEventRecord event =
                    new ChainPersistenceRecords.AuthorityEventRecord(
                            requested.event().eventId(), requested.event().taskId(), ++eventSequence,
                            requested.event().eventType(), requested.event().transitionId(),
                            requested.event().sourceIdentitySha256(), requested.event().committedAt());
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    event, fact, existing != null);
        }

        @Override
        public ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.PermissionDecisionRecord> appendPermissionDecision(
                        ChainPersistenceRecords.AuthoritativeFact<
                                ChainPersistenceRecords.PermissionDecisionRecord> requested) {
            ChainPersistenceRecords.PermissionDecisionRecord existing =
                    permissionDecisionFacts.putIfAbsent(
                            requested.fact().permissionDecisionId(), requested.fact());
            ChainPersistenceRecords.PermissionDecisionRecord fact = existing == null
                    ? requested.fact() : existing;
            return authorityResult(requested.event(), fact, existing != null);
        }

        @Override
        public ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.TaskOutcomeRecord> appendTaskOutcome(
                        ChainPersistenceRecords.AuthoritativeFact<
                                ChainPersistenceRecords.TaskOutcomeRecord> requested) {
            ChainPersistenceRecords.TaskOutcomeRecord existing = taskOutcomeFacts.putIfAbsent(
                    requested.fact().taskId(), requested.fact());
            ChainPersistenceRecords.TaskOutcomeRecord fact = existing == null
                    ? requested.fact() : existing;
            return authorityResult(requested.event(), fact, existing != null);
        }

        private <T extends ChainPersistenceRecords.TaskAuthorityFact>
                ChainPersistenceRecords.AuthoritativeAppendResult<T> authorityResult(
                        ChainPersistenceRecords.AuthorityEventRequest request,
                        T fact,
                        boolean replayed) {
            ChainPersistenceRecords.AuthorityEventRecord event =
                    new ChainPersistenceRecords.AuthorityEventRecord(
                            request.eventId(), request.taskId(), ++eventSequence,
                            request.eventType(), request.transitionId(),
                            request.sourceIdentitySha256(), request.committedAt());
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(event, fact, replayed);
        }

        @Override public List<ChainPersistenceRecords.CandidateStepResultRecord> findCandidateStepResults(String taskId) {
            return candidates.stream().filter(value -> value.taskId().equals(taskId)).toList();
        }
        @Override public List<ChainPersistenceRecords.ReviewDecisionRecord> findReviewDecisions(String taskId) {
            return decisions.values().stream().filter(value -> value.taskId().equals(taskId)).toList();
        }
        @Override public List<ChainPersistenceRecords.AcceptedResultRecord> findAcceptedResults(String taskId) {
            return List.copyOf(acceptedResults);
        }
        @Override public List<ChainPersistenceRecords.PendingItemRecord> findPendingItems(String taskId) {
            return List.copyOf(pendingItems);
        }
        @Override public List<ChainPersistenceRecords.PermissionDecisionRecord> findPermissionDecisions(String taskId) {
            return List.copyOf(permissionDecisions);
        }
        @Override public Optional<ChainPersistenceRecords.TransitionRecord> findTransition(String id) { return Optional.empty(); }
        @Override public List<ChainPersistenceRecords.TransitionStageRecord> findTransitionStages(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.TransitionRecord> findIncompleteTransitions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.RouteDecisionRecord> findRouteDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PlanBindingRecord> findPlanBindings(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ResultApplicabilityRecord> findApplicabilityDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PendingItemRecord> findOpenPendingItems(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PendingItemEventRecord> findPendingItemEvents(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findActionBindings(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findInFlightActions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.WorkspaceCandidateRecord> findWorkspaceCandidates(String id) {
            return workspaceCandidates.stream()
                    .filter(value -> value.taskId().equals(id)).toList();
        }
    }
}
