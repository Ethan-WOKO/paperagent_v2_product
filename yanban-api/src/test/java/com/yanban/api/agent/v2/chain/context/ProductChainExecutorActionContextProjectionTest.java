package com.yanban.api.agent.v2.chain.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainCandidateMaterializationFailureRepositoryAdapter;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PersistedEffectResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductChainExecutorActionContextProjectionTest {
    private static final Instant NOW = Instant.parse("2026-08-08T05:00:00Z");
    private static final String TASK = "task-1";
    private static final String ACTION = "action-1";
    private static final String RECEIPT = "receipt-1";

    @Test
    void projectsExactFormalFailureAndItsAuthorityRefs() {
        Fixture fixture = fixture(arguments("[\"javac\",\"src/A.java\"]"));

        ProductChainExecutorActionContextProjection.Projection projection =
                fixture.subject.project(TASK, "step-1", "activation-1");

        String attempts = projection.fields().get("action.currentStepAttemptTable");
        assertTrue(attempts.contains("actionId=" + ACTION));
        assertTrue(attempts.contains("receiptId=" + RECEIPT));
        assertTrue(attempts.contains("status=FAILURE"));
        assertTrue(attempts.contains("stderr=compiler missing"));
        assertEquals(ACTION, projection.authorityRefs().get("action.1"));
        assertEquals(RECEIPT, projection.authorityRefs().get("receipt.1"));
        assertEquals(ACTION, projection.latestFailure().actionId());
    }

    @ParameterizedTest
    @EnumSource(value = ReceiptStatus.class,
            names = {"FAILURE", "TIMEOUT", "CANCELLED"})
    void everyFormalNonSuccessReceiptBecomesRepairAuthority(
            ReceiptStatus status) {
        Fixture fixture = fixture(
                arguments("[\"javac\",\"src/A.java\"]"), status);

        var failure = fixture.subject.project(
                TASK, "step-1", "activation-1").latestFailure();

        assertEquals(status, failure.receiptStatus());
        assertEquals("PROCESS_EXIT_NON_ZERO", failure.failureCode());
        assertEquals(RECEIPT, failure.errorRef());
    }

    @Test
    void acceptsChangedRepairBoundToTheExactFormalFailure() {
        Fixture fixture = fixture(arguments("[\"javac\",\"src/A.java\"]"));
        ChainPersistenceRecords.ModelProposalRecord repair = proposal(
                "repair-1", arguments("[\"javac\",\"src/B.java\"]"),
                ACTION, RECEIPT, "correct the verified target", "compile target");

        String rejection = fixture.subject.validateRepair(
                ready(repair), fixture.subject.project(
                        TASK, "step-1", "activation-1").latestFailure());

        assertNull(rejection);
    }

    @Test
    void rejectsSameActualInvocationEvenWhenRepairDescriptionChanges() {
        String arguments = arguments("[\"javac\",\"src/A.java\"]");
        Fixture fixture = fixture(arguments);
        ChainPersistenceRecords.ModelProposalRecord repair = proposal(
                "repair-1", arguments, ACTION, RECEIPT,
                "try the command again with a new explanation", "compile target");

        String rejection = fixture.subject.validateRepair(
                ready(repair), fixture.subject.project(
                        TASK, "step-1", "activation-1").latestFailure());

        assertEquals("REPAIR_DID_NOT_CHANGE_ACTION", rejection);
    }

    @Test
    void rejectsTransientRepairMissingFormalFailureRefsBeforePersistence() {
        Fixture fixture = fixture(arguments("[\"javac\",\"src/A.java\"]"));
        ChainPersistenceRecords.ModelProposalRecord repair = proposal(
                "repair-1", arguments("[\"javac\",\"src/B.java\"]"),
                null, null, null, null);

        String rejection = fixture.subject.validateRepair(
                parsed(repair), fixture.subject.project(
                        TASK, "step-1", "activation-1").latestFailure());

        assertEquals("REPAIR_AUTHORITY_MISSING", rejection);
    }

    @Test
    void candidateFailureBecomesTheExactRepairAuthority() {
        Fixture fixture = fixture(arguments("[\"javac\",\"src/A.java\"]"));
        var failure = new ChainPersistenceRecords
                .CandidateMaterializationFailureRecord(
                "candidate-failure-1", TASK, "candidate-failure-event-1",
                ACTION, fixture.binding.workspaceId(),
                fixture.binding.baseCandidateKey(), "TOOL_EFFECT_RESULT",
                RECEIPT, fixture.binding.versionFenceSha256(),
                "CANDIDATE_NO_ACTUAL_CHANGE", NOW);
        when(fixture.failures.findCandidateMaterializationFailure(TASK, ACTION))
                .thenReturn(Optional.of(failure));
        ExecutionReceipt success = new ExecutionReceipt(
                new ReceiptId(RECEIPT), new ToolCallId(ACTION),
                ReceiptStatus.SUCCESS, NOW, NOW.plusSeconds(1), Optional.of(0),
                Optional.empty(), OutputCapture.empty(),
                OutputCapture.empty(), List.of(), Optional.empty(), List.of());
        when(fixture.outcomes.findResult(new ToolCallId(ACTION))).thenReturn(
                PersistenceResult.found(new PersistedEffectResult(
                        success, "lease-owner-1", 1)));

        var projection = fixture.subject.project(
                TASK, "step-1", "activation-1");

        assertEquals("candidate-failure-1",
                projection.latestFailure().errorRef());
        assertEquals("candidate-failure-1",
                projection.authorityRefs().get("candidateFailure.1"));
        assertTrue(projection.fields().get(
                "action.latestOrUnresolvedReceiptAndErrorExpansion")
                .contains("candidateFailureCode=CANDIDATE_NO_ACTUAL_CHANGE"));
    }

    private static Fixture fixture(String arguments) {
        return fixture(arguments, ReceiptStatus.FAILURE);
    }

    private static Fixture fixture(
            String arguments, ReceiptStatus status) {
        ProductChainWorkflowRepositoryAdapter workflow =
                mock(ProductChainWorkflowRepositoryAdapter.class);
        ProductChainModelRepositoryAdapter models =
                mock(ProductChainModelRepositoryAdapter.class);
        EffectOutcomeRepository outcomes = mock(EffectOutcomeRepository.class);
        ProductChainCandidateMaterializationFailureRepositoryAdapter failures =
                mock(ProductChainCandidateMaterializationFailureRepositoryAdapter.class);
        ChainPersistenceRecords.ModelProposalRecord prior = proposal(
                "proposal-1", arguments, null, null, null, null);
        ChainPersistenceRecords.ActionBindingRecord binding =
                new ChainPersistenceRecords.ActionBindingRecord(
                        ACTION, TASK, "action-event-1", prior.proposalId(), 1,
                        prior.payload().sha256(), "idempotency-1", "instruction-1",
                        "frame-1", "plan-1", "revision-1", "step-1",
                        "activation-1", "workspace-1", "NONE", null, null,
                        null, null, "a".repeat(64), NOW);
        ExecutionReceipt receipt = new ExecutionReceipt(
                new ReceiptId(RECEIPT), new ToolCallId(ACTION),
                status, NOW, NOW.plusSeconds(1),
                status == ReceiptStatus.FAILURE
                        ? Optional.of(127) : Optional.empty(),
                Optional.of("PROCESS_EXIT_NON_ZERO"), OutputCapture.empty(),
                OutputCapture.inline("compiler missing", false), List.of(),
                Optional.empty(), List.of(new EventId("effect-event-1")));
        when(workflow.findActionBindings(TASK)).thenReturn(List.of(binding));
        when(workflow.findWorkspaceCandidates(TASK)).thenReturn(List.of());
        when(failures.findCandidateMaterializationFailure(TASK, ACTION))
                .thenReturn(Optional.empty());
        when(models.findProposal(prior.proposalId())).thenReturn(Optional.of(prior));
        when(outcomes.findResult(new ToolCallId(ACTION))).thenReturn(
                PersistenceResult.found(new PersistedEffectResult(
                        receipt, "lease-owner-1", 1)));
        return new Fixture(new ProductChainExecutorActionContextProjection(
                workflow, models, outcomes, new ObjectMapper(), failures),
                workflow, outcomes, failures, binding);
    }

    private static ChainModelProtocolOutcome ready(
            ChainPersistenceRecords.ModelProposalRecord proposal) {
        return new ChainModelProtocolOutcome.ProposalReady(
                proposal, null, 1, false);
    }

    private static ProviderRoleOutput parsed(
            ChainPersistenceRecords.ModelProposalRecord proposal) {
        return new StrictChainProviderOutputParser().parse(
                "{\"schemaVersion\":\"1\",\"kind\":\""
                        + proposal.proposalKind().wireName()
                        + "\",\"payload\":" + proposal.payload().json() + "}",
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING, null);
    }

    private static ChainPersistenceRecords.ModelProposalRecord proposal(
            String id,
            String arguments,
            String priorAction,
            String priorError,
            String change,
            String expectedProgress) {
        String payload = "{"
                + "\"toolId\":\"sandbox.execute\","
                + "\"completeArguments\":" + quote(arguments) + ","
                + "\"target\":\"project\","
                + "\"purpose\":\"compile\","
                + "\"expectedOutputs\":[\"compiler result\"],"
                + "\"requiredPermission\":\"permission.sandbox\","
                + "\"readScopes\":[\"src/A.java\"],"
                + "\"writeScopes\":[],"
                + "\"priorErrorRef\":" + nullable(priorError) + ","
                + "\"priorActionRef\":" + nullable(priorAction) + ","
                + "\"changeFromPriorAction\":" + nullable(change) + ","
                + "\"expectedProgress\":" + nullable(expectedProgress) + ","
                + "\"gapValidation\":null}";
        return new ChainPersistenceRecords.ModelProposalRecord(
                id, TASK, "invocation-" + id, 1, ChainRole.EXECUTOR,
                ChainProposalKind.EXECUTOR_TOOL_ACTION, canonical(payload),
                canonical("[]"), null, null, NOW);
    }

    private static String arguments(String argv) {
        return "{\"paths\":[\"src/A.java\"],\"argv\":" + argv + "}";
    }

    private static String nullable(String value) {
        return value == null ? "null" : quote(value);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
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

    private record Fixture(
            ProductChainExecutorActionContextProjection subject,
            ProductChainWorkflowRepositoryAdapter workflow,
            EffectOutcomeRepository outcomes,
            ProductChainCandidateMaterializationFailureRepositoryAdapter failures,
            ChainPersistenceRecords.ActionBindingRecord binding) {
    }
}
