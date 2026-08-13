package com.yanban.api.agent.v2.chain.api;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.model.ChainProposalAdmissionService;
import io.paperagent.v2.chain.step.ChainActionRuntime;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductChainExecutorPumpTest {
    private static final Instant NOW = Instant.parse("2026-08-07T02:03:04Z");
    private static final String TASK = "task-1";
    private static final String HASH = "a".repeat(64);

    @Test
    void modelFailureDoesNotAdmitOrBindAnAction() {
        AtomicInteger admissions = new AtomicInteger();
        AtomicInteger actions = new AtomicInteger();
        ProductChainExecutorPump pump = new ProductChainExecutorPump(
                request -> {
                    admissions.incrementAndGet();
                    throw new AssertionError("failed model must not be admitted");
                },
                command -> {
                    actions.incrementAndGet();
                    throw new AssertionError("failed model must not bind an action");
                },
                request -> {
                    throw new AssertionError("failed model must not dispatch an effect");
                });

        ProductChainExecutorPump.Result result = pump.execute(
                TASK, new ChainModelProtocolOutcome.ModelCallFailed(
                        "invocation-1", "MODEL_PROVIDER_UNAVAILABLE", 2), NOW);

        assertEquals(ProductChainExecutorPump.Status.MODEL_FAILED, result.status());
        assertEquals(2, result.attempts());
        assertEquals(0, admissions.get());
        assertEquals(0, actions.get());
    }

    @Test
    void refusesNonToolProposalBeforeAdmission() {
        AtomicInteger admissions = new AtomicInteger();
        ProductChainExecutorPump pump = new ProductChainExecutorPump(
                request -> {
                    admissions.incrementAndGet();
                    throw new AssertionError("unsupported proposal must not be admitted");
                },
                command -> { throw new AssertionError("unsupported proposal bound an action"); },
                request -> { throw new AssertionError("unsupported proposal dispatched an effect"); });

        ChainPersistenceRecords.ModelProposalRecord proposal = proposal(
                "proposal-direct", ChainProposalKind.PLANNER_DIRECT_ROUTE,
                ChainRole.PLANNER);
        ProductChainExecutorPump.Result result = pump.execute(TASK,
                new ChainModelProtocolOutcome.ProposalReady(proposal, null, 1, false), NOW);

        assertEquals(ProductChainExecutorPump.Status.UNSUPPORTED_PROPOSAL, result.status());
        assertEquals(ChainProposalKind.PLANNER_DIRECT_ROUTE, result.unsupportedKind());
        assertEquals(0, admissions.get());
    }

    @Test
    void stepBlockedIsAdmittedWithoutActionOrEffectAndWaitsForReflector() {
        AtomicInteger admissions = new AtomicInteger();
        AtomicInteger actions = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        ExecutorPayload.StepBlocked blocked = new ExecutorPayload.StepBlocked(
                "VALIDATION", "receipt.compile.1",
                List.of("action.compile.1", "receipt.compile.1"),
                "javac returned a non-zero exit code",
                "report the negative compilation conclusion",
                List.of(), null, null, null);
        ChainPersistenceRecords.ModelProposalRecord proposal =
                new ChainPersistenceRecords.ModelProposalRecord(
                        "proposal-blocked", TASK, "invocation-1", 1,
                        ChainRole.EXECUTOR,
                        ChainProposalKind.EXECUTOR_STEP_BLOCKED,
                        canonical("{}"), canonical("[]"), null, null, NOW);
        ChainPersistenceRecords.ProposalStateEventRecord accepted =
                new ChainPersistenceRecords.ProposalStateEventRecord(
                        proposal.proposalId(), 1, TASK,
                        "proposal-state-blocked", ChainProposalState.ACCEPTED,
                        null, null, NOW);
        ProductChainExecutorPump pump = new ProductChainExecutorPump(
                request -> {
                    admissions.incrementAndGet();
                    assertEquals(proposal.proposalId(), request.proposalId());
                    return new ChainProposalAdmissionService.AdmissionResult(
                            accepted, true, false);
                },
                command -> {
                    actions.incrementAndGet();
                    throw new AssertionError("STEP_BLOCKED bound an action");
                },
                request -> {
                    effects.incrementAndGet();
                    throw new AssertionError("STEP_BLOCKED dispatched effect");
                });

        var ready = new ChainModelProtocolOutcome.ProposalReady(
                proposal, null, 1, false);
        ProductChainExecutorPump.Result result = pump.execute(
                TASK, ready, NOW);
        ProductChainExecutorPump.Result replay = pump.execute(
                TASK, ready, NOW.plusSeconds(1));

        assertEquals(ProductChainExecutorPump.Status.STEP_BLOCKED_ACCEPTED,
                result.status());
        assertEquals(result.proposalId(), replay.proposalId());
        assertEquals(ProductChainExecutorPump.Status.STEP_BLOCKED_ACCEPTED,
                replay.status());
        assertEquals(blocked.kind(), proposal.proposalKind());
        assertEquals(2, admissions.get());
        assertEquals(0, actions.get());
        assertEquals(0, effects.get());
    }

    @Test
    void admitsBindsAndDispatchesExactlyOneToolAction() {
        AtomicInteger admissions = new AtomicInteger();
        AtomicInteger actions = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        ChainPersistenceRecords.ModelProposalRecord proposal = proposal(
                "proposal-tool", ChainProposalKind.EXECUTOR_TOOL_ACTION,
                ChainRole.EXECUTOR);
        ChainPersistenceRecords.ProposalStateEventRecord state =
                new ChainPersistenceRecords.ProposalStateEventRecord(
                        proposal.proposalId(), 1, TASK, "proposal-state-1",
                        ChainProposalState.ACCEPTED, null, null, NOW);
        ChainPersistenceRecords.ActionBindingRecord binding = binding(proposal);
        ProductChainExecutorPump pump = new ProductChainExecutorPump(
                request -> {
                    admissions.incrementAndGet();
                    assertEquals(proposal.proposalId(), request.proposalId());
                    return new ChainProposalAdmissionService.AdmissionResult(
                            state, true, false);
                },
                command -> {
                    actions.incrementAndGet();
                    assertEquals(proposal.proposalId(), command.proposalId());
                    return binding;
                },
                request -> {
                    effects.incrementAndGet();
                    assertEquals(binding.actionId(), request.actionId());
                    return new ChainEffectRuntime.ExecutionOutcome(
                            ChainEffectRuntime.OutcomeKind.EFFECT_SUCCEEDED,
                            frozenAction(binding), "receipt-1", null, null, null,
                            ChainEffectRuntime.GateStatus.CURRENT);
                });

        ProductChainExecutorPump.Result result = pump.execute(TASK,
                new ChainModelProtocolOutcome.ProposalReady(proposal, null, 1, false), NOW);

        assertEquals(ProductChainExecutorPump.Status.EFFECT_DISPATCHED, result.status());
        assertEquals(binding.actionId(), result.actionId());
        assertEquals("receipt-1", result.receiptRef());
        assertEquals(1, admissions.get());
        assertEquals(1, actions.get());
        assertEquals(1, effects.get());
    }

    @Test
    void staleAdmissionStopsBeforeActionBinding() {
        ChainPersistenceRecords.ModelProposalRecord proposal = proposal(
                "proposal-stale", ChainProposalKind.EXECUTOR_TOOL_ACTION,
                ChainRole.EXECUTOR);
        ChainPersistenceRecords.ProposalStateEventRecord state =
                new ChainPersistenceRecords.ProposalStateEventRecord(
                        proposal.proposalId(), 1, TASK, "proposal-state-stale",
                        ChainProposalState.STALE, null, null, NOW);
        ProductChainExecutorPump pump = new ProductChainExecutorPump(
                request -> new ChainProposalAdmissionService.AdmissionResult(
                        state, false, false),
                command -> { throw new AssertionError("stale proposal bound an action"); },
                request -> { throw new AssertionError("stale proposal dispatched an effect"); });

        ProductChainExecutorPump.Result result = pump.execute(TASK,
                new ChainModelProtocolOutcome.ProposalReady(proposal, null, 1, false), NOW);

        assertEquals(ProductChainExecutorPump.Status.PROPOSAL_NOT_EXECUTABLE, result.status());
        assertEquals("PROPOSAL_STALE", result.failureCode());
        assertNull(result.actionId());
    }

    @Test
    void testPumpCannotSynthesizeStepResultWithoutProductionAuthorities() {
        ChainPersistenceRecords.ModelProposalRecord proposal = proposal(
                "proposal-step-result", ChainProposalKind.EXECUTOR_STEP_RESULT,
                ChainRole.EXECUTOR);
        ProductChainExecutorPump pump = new ProductChainExecutorPump(
                request -> { throw new AssertionError("step result must not be admitted by test pump"); },
                command -> { throw new AssertionError("step result must not bind an action"); },
                request -> { throw new AssertionError("step result must not dispatch an effect"); });

        assertThrows(IllegalStateException.class, () -> pump.commitStepResult(
                TASK,
                new ChainModelProtocolOutcome.ProposalReady(proposal, null, 1, false),
                new ProductChainExecutorPump.StepResultIdentity(
                        "instruction-1", "frame-1", "plan-1", "revision-1", 1,
                        "step-1", "activation-1", HASH, "workspace-candidate-1",
                        1, HASH, HASH),
                NOW));
    }

    @Test
    void stepResultIdentityAllowsReceiptOnlyStepWithoutWorkspaceCandidate() {
        ProductChainExecutorPump.StepResultIdentity identity =
                new ProductChainExecutorPump.StepResultIdentity(
                        "instruction-1", "frame-1", "plan-1", "revision-1", 1,
                        "step-1", "activation-1", HASH, null, null, null, null);

        assertNull(identity.workspaceCandidateId());
        assertNull(identity.artifactId());
        assertNull(identity.candidateFingerprint());
        assertNull(identity.diffDigest());
    }

    @Test
    void candidateValidationStepCannotCommitAReceiptOnlyResult() {
        ChainPersistenceRecords.ModelProposalRecord proposal = proposal(
                "proposal-candidate-required", ChainProposalKind.EXECUTOR_STEP_RESULT,
                ChainRole.EXECUTOR);
        ProductChainExecutorPump pump = new ProductChainExecutorPump(
                request -> { throw new AssertionError("invalid result must not be admitted"); },
                command -> { throw new AssertionError("invalid result must not bind an action"); },
                request -> { throw new AssertionError("invalid result must not dispatch an effect"); });
        var identity = new ProductChainExecutorPump.StepResultIdentity(
                "instruction-1", "frame-1", "plan-1", "revision-1", 1,
                "step-1", "activation-1", HASH, null, null, null, null, true);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> pump.commitStepResult(TASK,
                        new ChainModelProtocolOutcome.ProposalReady(
                                proposal, null, 1, false), identity, NOW));

        assertEquals("CHAIN_EXECUTOR_STEP_RESULT_CANDIDATE_REQUIRED",
                failure.getMessage());
    }

    private static ChainPersistenceRecords.ModelProposalRecord proposal(
            String id, ChainProposalKind kind, ChainRole role) {
        String payload = "{\"toolId\":\"sandbox.execute\"}";
        return new ChainPersistenceRecords.ModelProposalRecord(
                id, TASK, "invocation-1", 1, role, kind,
                canonical(payload), canonical("[]"), null, null, NOW);
    }

    private static ChainPersistenceRecords.ActionBindingRecord binding(
            ChainPersistenceRecords.ModelProposalRecord proposal) {
        return new ChainPersistenceRecords.ActionBindingRecord(
                "action-1", TASK, "action-event-1", proposal.proposalId(), 1,
                HASH, "idempotency-1", "instruction-1", "frame-1", "plan-1",
                "revision-1", "step-1", "activation-1", "workspace-1",
                "NONE", null, null, null, null, HASH, NOW);
    }

    private static ChainEffectRuntime.FrozenMutation frozenAction(
            ChainPersistenceRecords.ActionBindingRecord action) {
        return new ChainEffectRuntime.FrozenMutation(
                ChainEffectRuntime.SourceKind.TOOL_ACTION, action.taskId(),
                action.actionId(), action.idempotencyKey(), action.proposalId(),
                action.instructionId(), action.taskFrameId(), action.planId(),
                action.planRevisionId(), action.stepId(), action.activationEventId(),
                action.workspaceId(), action.baseCandidateKey(),
                action.actionSignatureSha256(), action.versionFenceSha256());
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(String value) {
        return new ChainPersistenceRecords.CanonicalJson(1, sha256(value), value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
