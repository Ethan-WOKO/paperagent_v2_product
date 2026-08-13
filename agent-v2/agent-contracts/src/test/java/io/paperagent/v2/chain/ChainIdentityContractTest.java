package io.paperagent.v2.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChainIdentityContractTest {
    private static final String SHA = "0".repeat(64);

    @Test
    void commandTaskAndInstructionCarryFrozenMinimumIdentity() {
        ChainIdentity.Command command = new ChainIdentity.Command(
                1L, 2L, "request", ChainInstructionRelation.INITIAL, null, null, null, SHA);
        ChainIdentity.Task task = new ChainIdentity.Task(
                "task", "command", "request", 3L, 1L, 2L, 4L, SHA,
                5L, "version", null, "instruction");
        ChainIdentity.Instruction instruction = new ChainIdentity.Instruction(
                "instruction", 2L, "task", "command", 4L, SHA, "MESSAGE:message", SHA);
        assertEquals(command.clientRequestId(), task.rootClientRequestId());
        assertEquals(task.sourceInstructionId(), instruction.instructionId());
        assertThrows(IllegalArgumentException.class, () -> new ChainIdentity.Instruction(
                "instruction", 2L, "task", "command", 4L, null, "MESSAGE:message", SHA));
    }

    @Test
    void commandKindsAndIdentityDigestsRejectAmbiguousMinimumShapes() {
        assertThrows(IllegalArgumentException.class, () -> new ChainIdentity.Command(
                1L, 2L, "request", ChainInstructionRelation.INITIAL, "task", null, null, SHA));
        assertThrows(IllegalArgumentException.class, () -> new ChainIdentity.Command(
                1L, 2L, "request", ChainInstructionRelation.SUPPLEMENT, null, null, null, SHA));
        assertThrows(IllegalArgumentException.class, () -> new ChainIdentity.Command(
                1L, 2L, "request", ChainInstructionRelation.ANSWER_TO_PENDING_ITEM,
                "task", "root", null, SHA));
        assertThrows(IllegalArgumentException.class, () -> new ChainIdentity.Command(
                1L, 2L, "request", ChainInstructionRelation.INITIAL, null, null, null, "ABC"));
    }

    @Test
    void proposalIdentityRejectsRoleKindMismatch() {
        assertThrows(IllegalArgumentException.class, () -> new ChainIdentity.Proposal(
                "proposal", "invocation", "context", ChainRole.EXECUTOR,
                ChainProposalKind.PLANNER_DIRECT_ROUTE, SHA, List.of(), null));
    }

    @Test
    void modelProposalCannotRewriteFrozenSourceOrBodyRefs() {
        ChainIdentity.Proposal identity = new ChainIdentity.Proposal(
                "proposal", "invocation", "context", ChainRole.EXECUTOR,
                ChainProposalKind.EXECUTOR_STEP_RESULT, ChainValues.sha256("{}"),
                List.of("source-a"), "content-a");
        assertThrows(IllegalArgumentException.class, () -> new ModelProposal(
                "1", identity, ChainWorkState.EXECUTING,
                List.of("source-b"), "CONTENT", "content-a", "{}"));
        assertThrows(IllegalArgumentException.class, () -> new ModelProposal(
                "1", identity, ChainWorkState.EXECUTING,
                List.of("source-a"), "CONTENT", "content-b", "{}"));
    }

    @Test
    void pendingGapValidationIsConditionalAndStillPendingHasOnlyTwoSuccessors() {
        GapValidation stillPending = new GapValidation("gap",
                List.of(new GapValidation.Check("condition", false, "fact")),
                GapValidation.Outcome.STILL_PENDING);
        PlannerPayload.DirectRoute direct = new PlannerPayload.DirectRoute(
                "reason", "spec", List.of(), List.of(), false, false, false, false, stillPending);
        ProviderRoleOutput output = new ProviderRoleOutput("1", "DIRECT_ROUTE", direct);
        assertThrows(IllegalArgumentException.class,
                () -> output.validateFor(ChainRole.PLANNER, ChainWorkState.VALIDATING_PENDING_ITEM, "gap"));
        assertThrows(IllegalArgumentException.class,
                () -> output.validateFor(ChainRole.PLANNER, ChainWorkState.PLANNING, null));
    }

    @Test
    void runtimePolicyAndFinalizationRetryRulesAreCentralized() {
        assertEquals("chain-runtime-policy-v1", ChainRuntimePolicy.V1.policyVersion());
        assertEquals(3, ChainRuntimePolicy.V1.providerAttemptsTotal());
        assertEquals(2, ChainRuntimePolicy.V1.modelInvocationsPerContextTotal());
        assertEquals(2, ChainRuntimePolicy.V1.protocolRepairAttemptsTotal());
        assertEquals(3, ChainRuntimePolicy.V1.deliveryAttemptsTotal());
        assertEquals(2, ChainRuntimePolicy.V1.finalizationMechanicalAttemptsTotal());
        assertThrows(IllegalArgumentException.class, () -> new ChainFinalization.CheckResult(
                "check", "readiness", 1, SHA, SHA, ChainRuntimePolicy.V1.policyVersion(),
                ChainFinalization.Outcome.FAILED, ChainFinalization.FailureHandling.RETRYABLE,
                ChainFinalization.ErrorCode.STALE_VERSION_FENCE));
    }

    @Test
    void readinessContractCarriesTheFinalStepBinding() {
        assertEquals("finalStepId", Arrays.stream(
                ChainFinalization.Readiness.class.getRecordComponents())
                .map(component -> component.getName())
                .filter("finalStepId"::equals)
                .findFirst().orElseThrow());
    }

    @Test
    void transitionReadinessAndDeliveryFreezeDeterministicLegalIdentities() {
        ChainIdentity.Transition transition = new ChainIdentity.Transition(
                ChainTransitionType.FINALIZATION, "task", "decision", SHA);
        assertEquals("transition.", transition.transitionId().substring(0, "transition.".length()));
        assertEquals(75, transition.transitionId().length());

        new ChainIdentity.Readiness(
                transition.transitionId(), "task-frame", "revision", SHA,
                null, ChainIdentity.NONE, "workspace", ChainIdentity.NONE,
                null, null, SHA, "instruction", "version");
        assertThrows(IllegalArgumentException.class, () -> new ChainIdentity.Readiness(
                transition.transitionId(), "task-frame", "revision", SHA,
                "artifact", ChainIdentity.NONE, "workspace", ChainIdentity.NONE,
                null, null, SHA, "instruction", "version"));

        new ChainIdentity.Delivery("delivery", "route", null, null, null, null, 0);
        assertThrows(IllegalArgumentException.class, () -> new ChainIdentity.Delivery(
                "delivery", "route", "outcome", null, null, null, 1));
    }

    @Test
    void applicabilityEightColumnIdentityRejectsNonAsciiSystemKeys() {
        assertThrows(IllegalArgumentException.class, () -> new ChainApplicability.Identity(
                "accepted", ChainApplicability.SourceType.ACCEPT_STEP, "decision", "task-frame",
                "plan", "revision", "候选", "instruction"));
    }
}
