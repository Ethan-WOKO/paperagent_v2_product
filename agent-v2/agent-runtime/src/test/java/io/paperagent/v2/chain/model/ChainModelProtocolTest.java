package io.paperagent.v2.chain.model;

import io.paperagent.v2.chain.AnswerPayload;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainProposalPayload;
import io.paperagent.v2.chain.ChainModelInvocationWriter;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainProposalStateWriter;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextFreezeOutcome;
import io.paperagent.v2.chain.context.ChainContextFreezeRequest;
import io.paperagent.v2.chain.context.ChainContextManager;
import io.paperagent.v2.chain.context.ChainFrozenContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainModelProtocolTest {
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");

    @Test
    void persistsInlineBodyOnceAndReplaysWithoutCallingProviderAgain() {
        Store store = new Store(completeContext(), Set.of("route-1"));
        AtomicInteger calls = new AtomicInteger();
        ChainModelProtocolService service = service(store, calls,
                new AnswerPayload.DirectAnswer("route-1", "explain", "secret answer", List.of()));

        ChainModelProtocolOutcome.ProposalReady first = assertInstanceOf(
                ChainModelProtocolOutcome.ProposalReady.class, service.invoke(request()));
        assertEquals(1, calls.get());
        assertEquals(1, store.attempts.size());
        assertEquals(1, store.contents.size());
        assertEquals(1, store.proposals.size());
        assertEquals("secret answer", first.bodyContent().body());
        assertFalse(first.executable());
        assertTrue(first.admissionRequired());
        assertTrue(first.proposal().payload().json().contains("answerBodyRef"));
        assertFalse(first.proposal().payload().json().contains("secret answer"));

        ChainModelProtocolOutcome.ProposalReady replay = assertInstanceOf(
                ChainModelProtocolOutcome.ProposalReady.class, service.invoke(request()));
        assertTrue(replay.recovered());
        assertEquals(first.proposal().proposalId(), replay.proposal().proposalId());
        assertEquals(1, calls.get());
        assertEquals(1, store.contents.size());
        assertEquals(1, store.proposals.size());
    }

    @Test
    void recoveredProposalRequiresItsValidatedAttemptAndCanonicalDigest() {
        Store store = new Store(completeContext(), Set.of("route-1"));
        ChainModelProtocolService service = service(
                store, new AtomicInteger(), new AnswerPayload.DirectAnswer(
                        "route-1", "explain", "answer", List.of()));
        ChainModelProtocolOutcome.ProposalReady ready = assertInstanceOf(
                ChainModelProtocolOutcome.ProposalReady.class,
                service.invoke(request()));
        ChainPersistenceRecords.ModelProposalRecord original = ready.proposal();
        store.proposals.put(original.proposalId(),
                new ChainPersistenceRecords.ModelProposalRecord(
                        original.proposalId(), original.taskId(),
                        original.invocationId(), original.schemaVersion(),
                        original.role(), original.proposalKind(),
                        new ChainPersistenceRecords.CanonicalJson(
                                1, "0".repeat(64), original.payload().json()),
                        original.sourceRefs(), original.bodyAuthorityType(),
                        original.bodyAuthorityRef(), original.createdAt()));

        ChainModelProtocolException corrupted = assertThrows(
                ChainModelProtocolException.class,
                () -> service.invoke(request()));

        assertEquals(ChainModelProtocolException.Code.PROPOSAL_REPLAY_MISMATCH,
                corrupted.code());
    }

    @Test
    void atomicMaterializationFailureLeavesNoPassedAttemptContentOrProposal() {
        Store store = new Store(completeContext(), Set.of("route-1"));
        store.failNextMaterialization = true;
        AtomicInteger calls = new AtomicInteger();
        ChainModelProtocolService service = service(store, calls,
                new AnswerPayload.DirectAnswer("route-1", "explain", "secret answer", List.of()));

        assertThrows(IllegalStateException.class, () -> service.invoke(request()));
        assertTrue(store.attempts.isEmpty());
        assertTrue(store.contents.isEmpty());
        assertTrue(store.proposals.isEmpty());

        assertInstanceOf(ChainModelProtocolOutcome.ProposalReady.class,
                service.invoke(request()));
        assertEquals(2, calls.get());
        assertEquals(1, store.attempts.size());
        assertEquals(1, store.contents.size());
        assertEquals(1, store.proposals.size());
    }

    @Test
    void invisibleSourceRefProducesBoundedFailedAttemptsAndNoAuthorityBody() {
        Store store = new Store(completeContext(), Set.of("route-1"));
        AtomicInteger calls = new AtomicInteger();
        ChainModelProtocolService service = service(store, calls,
                new AnswerPayload.DirectAnswer("not-visible", "explain", "secret answer", List.of()));

        ChainModelProtocolOutcome.ModelCallFailed failed = assertInstanceOf(
                ChainModelProtocolOutcome.ModelCallFailed.class,
                service.invoke(request()));
        assertEquals(3, failed.attempts());
        assertEquals(3, calls.get());
        assertEquals(3, store.attempts.size());
        assertTrue(store.contents.isEmpty());
        assertTrue(store.proposals.isEmpty());
        assertTrue(store.attempts.stream().allMatch(attempt ->
                attempt.proposalValidationStatus() == ChainPersistenceRecords.ValidationStatus.FAILED));
    }

    @Test
    void validationRequirementIsLocalButItsReceiptMustBeFrozenVisible() {
        var requirement = new io.paperagent.v2.contracts.ValidationRequirement(
                "requirement-local-1",
                io.paperagent.v2.contracts.ValidationSubject.ACTION_RECEIPT,
                "formal action receipt exists");
        var taskRequirements = io.paperagent.v2.contracts.TaskRequirements
                .explicit(List.of(requirement),
                        io.paperagent.v2.contracts.PublishRequirement.NOT_REQUIRED);
        assertEquals(requirement, taskRequirements.validationRequirements().get(0));
        var coverage = new ProposalFields.RequirementCoverage(
                requirement.completionCondition(),
                ProposalFields.RequirementStatus.SATISFIED,
                List.of("receipt-visible"));
        var payload = new ExecutorPayload.StepResult(
                List.of(coverage), "body", List.of(), null,
                List.of("receipt-visible"),
                List.of(new ProposalFields.ValidationSource(
                        requirement.requirementId(), "receipt-visible")),
                List.of(), List.of(), List.of(), null);

        assertEquals(List.of("receipt-visible"),
                ChainProposalSourceRefs.extract(payload));
        Store visible = new Store(completeContext(
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "STEP_RESULT"), Set.of("receipt-visible"));
        assertInstanceOf(ChainModelProtocolOutcome.ProposalReady.class,
                service(visible, new AtomicInteger(), payload).invoke(request(
                        ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                        "STEP_RESULT")));

        Store hidden = new Store(completeContext(
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "STEP_RESULT"), Set.of());
        ChainModelProtocolOutcome.ModelCallFailed failed = assertInstanceOf(
                ChainModelProtocolOutcome.ModelCallFailed.class,
                service(hidden, new AtomicInteger(), payload).invoke(request(
                        ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                        "STEP_RESULT")));
        assertEquals(3, failed.attempts());
        assertTrue(hidden.proposals.isEmpty());
        assertTrue(hidden.contents.isEmpty());
    }

    @Test
    void finalAnswerRefRepairPointsBackToExactFrozenSchema() {
        Store store = new Store(completeContext(
                ChainRole.ANSWER, ChainWorkState.DELIVERING,
                "TASK_OUTCOME"), Set.of("outcome-1", "NONE"));
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger decodes = new AtomicInteger();
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            calls.incrementAndGet();
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            if (decodes.incrementAndGet() == 1) {
                throw new IllegalArgumentException(
                        "final Answer refs must exactly copy the formal TaskOutcome refs shown in Context");
            }
            AnswerPayload.FinalDelivery payload = new AnswerPayload.FinalDelivery(
                    "outcome-1", List.of("NONE"), "NONE", "NONE",
                    "formal answer");
            return new ProviderRoleOutput(
                    "1", payload.kind().wireName(), payload);
        };
        ChainModelProtocolService service = new ChainModelProtocolService(
                store, store, store, store, provider, decoder);

        assertInstanceOf(ChainModelProtocolOutcome.ProposalReady.class,
                service.invoke(request(ChainRole.ANSWER,
                        ChainWorkState.DELIVERING, "TASK_OUTCOME")));

        assertEquals(2, calls.get());
        assertTrue(repairFeedback.get().contains("rules.answerSchema"));
        assertTrue(repairFeedback.get().contains(
                "replace only payload.inlineAnswerBody"));
    }

    @Test
    void statusAnswerRefRepairPointsBackToExactFrozenTemplate() {
        Store store = new Store(completeContext(
                ChainRole.ANSWER, ChainWorkState.TERMINAL,
                "TASK_OUTCOME"), Set.of("outcome-1", "review-1"));
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger decodes = new AtomicInteger();
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            calls.incrementAndGet();
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            if (decodes.incrementAndGet() == 1) {
                throw new IllegalArgumentException(
                        "status Answer refs must exactly copy taskOrStepStatusRef=outcome-1; "
                                + "blockerOrTaskOutcomeRef=outcome-1; latestDecisionRef=review-1");
            }
            AnswerPayload.StatusOrFailure payload =
                    new AnswerPayload.StatusOrFailure(
                            "outcome-1", "review-1", "outcome-1",
                            "formal failure");
            return new ProviderRoleOutput(
                    "1", payload.kind().wireName(), payload);
        };
        ChainModelProtocolService service = new ChainModelProtocolService(
                store, store, store, store, provider, decoder);

        assertInstanceOf(ChainModelProtocolOutcome.ProposalReady.class,
                service.invoke(request(ChainRole.ANSWER,
                        ChainWorkState.TERMINAL, "TASK_OUTCOME")));

        assertEquals(2, calls.get());
        assertTrue(repairFeedback.get().contains(
                "runtime.answerPayloadTemplate"));
        assertTrue(repairFeedback.get().contains(
                "replace only payload.inlineAnswerBody"));
    }

    @Test
    void directAnswerRepairPointsBackToExactFrozenTemplate() {
        Store store = new Store(completeContext(
                ChainRole.ANSWER, ChainWorkState.DIRECT_ANSWERING,
                "DIRECT_ROUTE"), Set.of("route-1"));
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger decodes = new AtomicInteger();
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            calls.incrementAndGet();
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            if (decodes.incrementAndGet() == 1) {
                throw new IllegalArgumentException(
                        "Direct Answer must copy the selected route specification and fact refs");
            }
            AnswerPayload.DirectAnswer payload =
                    new AnswerPayload.DirectAnswer(
                            "route-1", "direct answer", "hello", List.of());
            return new ProviderRoleOutput(
                    "1", payload.kind().wireName(), payload);
        };
        ChainModelProtocolService service = new ChainModelProtocolService(
                store, store, store, store, provider, decoder);

        assertInstanceOf(ChainModelProtocolOutcome.ProposalReady.class,
                service.invoke(request(ChainRole.ANSWER,
                        ChainWorkState.DIRECT_ANSWERING, "DIRECT_ROUTE")));

        assertEquals(2, calls.get());
        assertTrue(repairFeedback.get().contains(
                "runtime.answerPayloadTemplate"));
        assertTrue(repairFeedback.get().contains("DIRECT_ANSWER"));
        assertTrue(repairFeedback.get().contains(
                "replace only payload.inlineAnswerBody"));
    }

    @Test
    void invalidJsonRepairShowsTheCompleteGenericRootShape() {
        Store store = new Store(completeContext(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "planning"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        AtomicReference<String> previousInvalidOutput = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
                previousInvalidOutput.set(request.previousInvalidOutput());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new IllegalArgumentException(
                    "INVALID_JSON at $: expected ','");
        };

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(ChainRole.PLANNER,
                        ChainWorkState.PLANNING, "planning")));

        assertTrue(repairFeedback.get().contains(
                "{\"schemaVersion\":\"1\",\"kind\":\"<allowed-kind>\",\"payload\":{}}"));
        assertTrue(repairFeedback.get().contains(
                "three members separated by literal ASCII commas"));
        assertTrue(repairFeedback.get().contains(
                "do not copy angle-bracket placeholders"));
        assertEquals("transient", previousInvalidOutput.get());
    }

    @Test
    void safeValidatorRepairKeepsTheCompleteAuthorityBindingChecklist() {
        Store store = new Store(completeContext(
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                "ACTION_FAILURE_REVIEW"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        String block = "block." + "a".repeat(64);
        String action = "action." + "b".repeat(64);
        String receipt = "receipt." + "c".repeat(64);
        String proposal = "proposal." + "d".repeat(64);
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new ChainModelAuthorityBindingRepairException(
                    "FORMAL_REVIEW_BINDING_MISSING",
                    "review.reviewedObjectRefs", block,
                    "review.directFactRefs",
                    List.of(block, action, receipt, proposal));
        };

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(ChainRole.REFLECTOR,
                        ChainWorkState.AWAITING_REVIEW,
                        "ACTION_FAILURE_REVIEW")));

        assertTrue(repairFeedback.get().contains(block));
        assertTrue(repairFeedback.get().contains(action));
        assertTrue(repairFeedback.get().contains(receipt));
        assertTrue(repairFeedback.get().contains(proposal));
    }

    @Test
    void invalidJsonRepairDistinguishesIncompleteAndDuplicateObjects() {
        Store store = new Store(completeContext(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "planning"), Set.of());
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> eofRepair = new AtomicReference<>();
        AtomicReference<String> duplicateRepair = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            int call = calls.incrementAndGet();
            if (call == 2) eofRepair.set(request.repairFeedback());
            if (call == 3) duplicateRepair.set(request.repairFeedback());
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        AtomicInteger decodes = new AtomicInteger();
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            if (decodes.incrementAndGet() == 1) {
                throw new IllegalArgumentException(
                        "INVALID_JSON at $: expected ',' at offset 3109 before EOF");
            }
            throw new IllegalArgumentException(
                    "INVALID_JSON at $.payload: duplicate object key at offset 42 before U+003A");
        };

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(ChainRole.PLANNER,
                        ChainWorkState.PLANNING, "planning")));

        assertTrue(eofRepair.get().contains(
                "Close every opened string, array, and object"));
        assertTrue(eofRepair.get().contains(
                "final non-whitespace character"));
        assertTrue(duplicateRepair.get().contains(
                "Every JSON object key must occur exactly once"));
    }

    @Test
    void executorSelfRepairFeedbackExplainsTheAllOrNoneFieldGroup() {
        Store store = new Store(completeContext(
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "advance"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new IllegalArgumentException(
                    "self-repair fields must be all present or all absent");
        };

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(ChainRole.EXECUTOR,
                        ChainWorkState.EXECUTING, "advance")));

        assertTrue(repairFeedback.get().contains("priorErrorRef"));
        assertTrue(repairFeedback.get().contains("priorActionRef"));
        assertTrue(repairFeedback.get().contains("changeFromPriorAction"));
        assertTrue(repairFeedback.get().contains("expectedProgress"));
        assertTrue(repairFeedback.get().contains(
                "possible future failure stated in the instruction is not a prior failed action"));
        assertTrue(repairFeedback.get().contains(
                "\"priorErrorRef\":null,\"priorActionRef\":null"));
        assertTrue(repairFeedback.get().contains(
                "\"changeFromPriorAction\":null,\"expectedProgress\":null"));
        assertTrue(repairFeedback.get().contains(
                "expectedOutputs describes the current action's expected outputs"));
        assertTrue(repairFeedback.get().contains(
                "expectedProgress is not a general current-action field"));
        assertTrue(repairFeedback.get().contains("provide all four as nonblank strings"));
    }

    @Test
    void executorInvisibleToolAuthorityRepairUsesTheMatchingVisibleToolSchema() {
        Store store = new Store(completeContext(
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "advance"), Set.of("sandbox.execute",
                "permission.sandbox-execute-install"));
        AtomicInteger decodes = new AtomicInteger();
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            String permission = decodes.incrementAndGet() == 1
                    ? "READ_PROJECT"
                    : "permission.sandbox-execute-install";
            ExecutorPayload.ToolAction payload = new ExecutorPayload.ToolAction(
                    "sandbox.execute", "{}", "workspace", "execute",
                    List.of("receipt"), permission, List.of("."),
                    List.of(), null, null, null, null, null);
            return new ProviderRoleOutput("1", payload.kind().wireName(), payload);
        };

        assertInstanceOf(ChainModelProtocolOutcome.ProposalReady.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(ChainRole.EXECUTOR,
                        ChainWorkState.EXECUTING, "advance")));

        assertTrue(repairFeedback.get().contains(
                "copy toolId from descriptor.id"));
        assertTrue(repairFeedback.get().contains(
                "requiredPermission from permissionRef of the same"));
        assertTrue(repairFeedback.get().contains(
                "Capability names and public aliases are not authority references"));
        assertTrue(repairFeedback.get().contains("sandbox.execute"));
        assertTrue(repairFeedback.get().contains(
                "permission.sandbox-execute-install"));
    }

    @Test
    void executorValidationBindingRepairExplainsExactActiveStepCoverage() {
        Store store = new Store(completeContext(
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "advance"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new IllegalArgumentException(
                    "validationSources requirementIds must exactly match active Step "
                            + "validationRequirementIds; bind every required ID once and no other ID");
        };

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(ChainRole.EXECUTOR,
                        ChainWorkState.EXECUTING, "advance")));

        assertTrue(repairFeedback.get().contains(
                "copy every ID from the frozen active Step"));
        assertTrue(repairFeedback.get().contains(
                "include that same receiptRef in receiptRefs"));
        assertTrue(repairFeedback.get().contains(
                "Use validationSources: [] only when the active Step list is empty"));
    }

    @Test
    void plannerRequirementRepairExplainsTypedRequirementsAndStepBindings() {
        Store store = new Store(completeContext(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "planning"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new IllegalArgumentException(
                    "validationRequirementIds must close every validation requirement exactly once");
        };
        ChainModelProtocolService service = new ChainModelProtocolService(
                store, store, store, store, provider, decoder);

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                service.invoke(request(ChainRole.PLANNER,
                        ChainWorkState.PLANNING, "planning")));

        assertTrue(repairFeedback.get().contains(
                "taskFrame.requirements.declarationMode"));
        assertTrue(repairFeedback.get().contains(
                "plan.steps[].validationRequirementIds"));
        assertTrue(repairFeedback.get().contains(
                "explicitly declare exactly one CANDIDATE validation requirement"));
        assertTrue(repairFeedback.get().contains(
                "the count must be 1"));
        assertTrue(repairFeedback.get().contains(
                "count=0, add one CANDIDATE item"));
        assertTrue(repairFeedback.get().contains(
                "one plan-level aggregate check of the final Candidate"));
        assertTrue(repairFeedback.get().contains(
                "replace those duplicate Candidate items with one aggregate CANDIDATE item"));
        assertTrue(repairFeedback.get().contains(
                "without deleting the affected Steps or their ordinary completionConditions"));
        assertTrue(repairFeedback.get().contains(
                "PermissionTier and free-form constraints must never substitute"));
        assertFalse(repairFeedback.get().contains("highest-stableOrder"));
    }

    @Test
    void plannerCoverageRepairExplainsStatusAndFactRefPairing() {
        Store store = new Store(completeContext(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "planning"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new IllegalArgumentException(
                    "PLANNED coverage cannot carry fact refs");
        };

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(ChainRole.PLANNER,
                        ChainWorkState.PLANNING, "planning")));

        assertTrue(repairFeedback.get().contains(
                "PLANNED or UNSATISFIED must use factRefs: []"));
        assertTrue(repairFeedback.get().contains(
                "SATISFIED requires one or more exact visible authority identifiers"));
    }

    @Test
    void plannerGapRepairRequiresNullOutsidePendingItemValidation() {
        Store store = new Store(completeContext(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "planning"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new IllegalArgumentException(
                    "gap validation and bound gap are only legal in pending-item validation context");
        };

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(ChainRole.PLANNER,
                        ChainWorkState.PLANNING, "planning")));

        assertTrue(repairFeedback.get().contains(
                "payload.gapValidation as JSON null"));
        assertTrue(repairFeedback.get().contains("do not invent a gapId"));
    }

    @Test
    void plannerCandidateValidationRepairExplainsExactConditionBinding() {
        Store store = new Store(completeContext(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "planning"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new IllegalArgumentException(
                    "candidate validation completion condition must be one of the step completion conditions");
        };

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(ChainRole.PLANNER,
                        ChainWorkState.PLANNING, "planning")));

        assertTrue(repairFeedback.get().contains("byte-for-byte"));
        assertTrue(repairFeedback.get().contains(
                "candidateValidationCompletionCondition"));
        assertTrue(repairFeedback.get().contains(
                "subject is exactly CANDIDATE"));
        assertTrue(repairFeedback.get().contains(
                "later validation Step with"));
        assertTrue(repairFeedback.get().contains(
                "mayChangeCandidate=false"));
        assertTrue(repairFeedback.get().contains(
                "Keep that requirementId bound"));
        assertTrue(repairFeedback.get().contains(
                "preserve all unrelated fields"));
    }

    @Test
    void plannerPersistentBoundaryRepairExplicitlyAllowsDirectRoute() {
        Store store = new Store(completeContext(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "planning"), Set.of());
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger decodes = new AtomicInteger();
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            calls.incrementAndGet();
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            if (decodes.incrementAndGet() == 1) {
                throw new IllegalArgumentException(
                        "PERSISTENT_ROUTE_WITHOUT_REQUIREMENT");
            }
            PlannerPayload.DirectRoute direct =
                    new PlannerPayload.DirectRoute(
                            "no persistent boundary", "answer directly",
                            List.of(), List.of(), false, false,
                            false, false, null);
            return new ProviderRoleOutput(
                    "1", direct.kind().wireName(), direct);
        };
        ChainModelProtocolService service = new ChainModelProtocolService(
                store, store, store, store, provider, decoder);

        assertInstanceOf(ChainModelProtocolOutcome.ProposalReady.class,
                service.invoke(request(ChainRole.PLANNER,
                        ChainWorkState.PLANNING, "planning")));

        assertEquals(2, calls.get());
        assertTrue(repairFeedback.get().contains(
                "PERSISTENT_PLAN semantic kind is rejected"));
        assertTrue(repairFeedback.get().contains("DIRECT_ROUTE"));
        assertTrue(repairFeedback.get().contains(
                "does not create a requirement"));
    }

    @Test
    void plannerBlockedRepairRejectsKindSwitchWithoutAuthority() {
        Store store = new Store(completeContext(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "planning"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new IllegalArgumentException("knownFactRefs must not be empty");
        };

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(ChainRole.PLANNER,
                        ChainWorkState.PLANNING, "planning")));

        assertTrue(repairFeedback.get().contains(
                "must not switch a viable plan into PLANNING_BLOCKED"));
        assertTrue(repairFeedback.get().contains(
                "preserve the intended semantic proposal kind"));
    }

    @Test
    void reflectorBoundAssessmentRepairExplainsMutuallyDependentFields() {
        Store store = new Store(completeContext(
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                "step-review"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new IllegalArgumentException(
                    "BOUND assessment cannot carry a non-binding reason");
        };

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(
                        ChainRole.REFLECTOR,
                        ChainWorkState.AWAITING_REVIEW, "step-review")));

        String feedback = repairFeedback.get();
        assertTrue(feedback.contains("JSON-null reason"));
        assertTrue(feedback.contains(
                "{\"status\":\"BOUND\",\"authorityRef\":\"exact-visible-ref\",\"reason\":null}"));
        assertTrue(feedback.contains("An empty string is not JSON null"));
    }

    @Test
    void reflectorValidationRequirementRepairBindsAcceptanceTaskFrame() {
        Store store = new Store(completeContext(
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                "step-review"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new IllegalArgumentException(
                    "validationAssessment must bind the frozen TaskFrame requirements");
        };

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(
                        ChainRole.REFLECTOR,
                        ChainWorkState.AWAITING_REVIEW, "step-review")));

        String feedback = repairFeedback.get();
        assertTrue(feedback.contains("binds the requirement declaration"));
        assertTrue(feedback.contains("not a Validation, Receipt, Candidate, or evidence ref"));
        assertTrue(feedback.contains("payload.acceptance.taskFrameRef"));
        assertTrue(feedback.contains("reason as JSON null"));
        assertTrue(feedback.contains("Preserve unrelated fields"));
    }

    @Test
    void reflectorPublishRequirementRepairBindsAcceptanceTaskFrame() {
        Store store = new Store(completeContext(
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                "step-review"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new IllegalArgumentException(
                    "publishRequirementAssessment must bind the exact TaskFrame");
        };

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(
                        ChainRole.REFLECTOR,
                        ChainWorkState.AWAITING_REVIEW, "step-review")));

        String feedback = repairFeedback.get();
        assertTrue(feedback.contains("binds the publish requirement declaration"));
        assertTrue(feedback.contains("not a publish result, Receipt, Candidate, or evidence ref"));
        assertTrue(feedback.contains("payload.acceptance.taskFrameRef"));
        assertTrue(feedback.contains("reason as JSON null"));
        assertTrue(feedback.contains("Preserve unrelated fields"));
    }

    @Test
    void reflectorCombinedFinalStepRepairRequiresOneExactReviewCopy() {
        Store store = new Store(completeContext(
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                "step-review"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new IllegalArgumentException(
                    "combined final-step review must have one common review payload");
        };

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(
                        ChainRole.REFLECTOR,
                        ChainWorkState.AWAITING_REVIEW, "step-review")));

        String feedback = repairFeedback.get();
        assertTrue(feedback.contains("payload.review"));
        assertTrue(feedback.contains("payload.acceptance.review"));
        assertTrue(feedback.contains("byte-for-byte identical JSON objects"));
        assertTrue(feedback.contains("array order"));
    }

    @Test
    void plannerInvisibleRefRepairSeparatesPathsFromAuthorityRefs() {
        Store store = new Store(completeContext(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "planning"), Set.of("instruction-visible"));
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) ->
                new ProviderRoleOutput("1", "PLANNING_BLOCKED",
                        new PlannerPayload.PlanningBlocked(
                                "blocked", List.of("path/not-an-authority"),
                                "reason", "recovery", "recommendation", null));

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                new ChainModelProtocolService(store, store, store, store,
                        provider, decoder).invoke(request(ChainRole.PLANNER,
                        ChainWorkState.PLANNING, "planning")));

        assertTrue(repairFeedback.get().contains(
                "remove project paths, descriptions, requirements, and prose"));
        assertTrue(repairFeedback.get().contains(
                "TaskFrame objects, which is also an authority-reference collection"));
        assertTrue(repairFeedback.get().contains("[\"instruction-visible\"]"));
        assertTrue(repairFeedback.get().contains(
                "if no visible authority identifier is required, use []"));
    }

    @Test
    void executorWorkspaceChangeRepairExplainsCanonicalChangeBundle() {
        Store store = new Store(completeContext(
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "active-step"), Set.of());
        AtomicReference<String> repairFeedback = new AtomicReference<>();
        ChainModelCallPort provider = request -> {
            if (request.protocolRepair()) {
                repairFeedback.set(request.repairFeedback());
            }
            return new ChainModelCallResult.Success(
                    "transient", "STOP", 7, Map.of());
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            throw new IllegalArgumentException(
                    "$.payload.inlineCanonicalChangeBody invalid canonical change bundle");
        };
        ChainModelProtocolService service = new ChainModelProtocolService(
                store, store, store, store, provider, decoder);

        assertInstanceOf(ChainModelProtocolOutcome.ModelCallFailed.class,
                service.invoke(request(ChainRole.EXECUTOR,
                        ChainWorkState.EXECUTING, "active-step")));

        String feedback = repairFeedback.get();
        assertTrue(feedback.contains("{\"changes\":[...]"));
        assertTrue(feedback.contains("expectedBaselineSha256"));
        assertTrue(feedback.contains("ADD uses baseline NONE"));
        assertTrue(feedback.contains("MODIFY uses"));
        assertTrue(feedback.contains("DELETE uses"));
        assertTrue(feedback.contains("forbids text"));
        assertTrue(feedback.contains("targetFiles in the same order"));
    }

    @Test
    void refusesInvocationBeforeContextRevisionIsComplete() {
        ChainPersistenceRecords.ContextRevisionRecord complete = completeContext();
        ChainPersistenceRecords.ContextRevisionRecord building = new ChainPersistenceRecords.ContextRevisionRecord(
                complete.contextRevisionId(), complete.taskId(), null, complete.role(), complete.workState(),
                complete.callReason(), complete.instructionId(), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                "projectors-v1", "pagination-v1", "chain-runtime-policy-v1",
                ChainContextRevisionStatus.BUILDING, 0, null, null, null, null, null, NOW, null);
        Store store = new Store(building, Set.of("route-1"));
        ChainModelProtocolService service = service(store, new AtomicInteger(),
                new AnswerPayload.DirectAnswer("route-1", "explain", "answer", List.of()));
        ChainModelProtocolException failure = assertThrows(ChainModelProtocolException.class,
                () -> service.invoke(request()));
        assertEquals(ChainModelProtocolException.Code.CONTEXT_NOT_COMPLETE, failure.code());
    }

    @Test
    void refusesFrozenIdentityOrPromptDigestMismatchBeforeCallingProvider() {
        AtomicInteger identityCalls = new AtomicInteger();
        Store identityStore = new Store(completeContext(), Set.of("route-1"));
        ChainModelProtocolException identityFailure = assertThrows(
                ChainModelProtocolException.class,
                () -> service(identityStore, identityCalls, new AnswerPayload.DirectAnswer(
                        "route-1", "explain", "answer", List.of()))
                        .invoke(request(
                                ChainRole.ANSWER,
                                ChainWorkState.DIRECT_ANSWERING,
                                "changed reason")));
        assertEquals(ChainModelProtocolException.Code.CONTEXT_IDENTITY_MISMATCH,
                identityFailure.code());
        assertEquals(0, identityCalls.get());

        AtomicInteger digestCalls = new AtomicInteger();
        Store digestStore = new Store(completeContext(
                ChainRole.ANSWER,
                ChainWorkState.DIRECT_ANSWERING,
                "direct answer",
                "f".repeat(64)), Set.of("route-1"));
        ChainModelProtocolException digestFailure = assertThrows(
                ChainModelProtocolException.class,
                () -> service(digestStore, digestCalls, new AnswerPayload.DirectAnswer(
                        "route-1", "explain", "answer", List.of())).invoke(request()));
        assertEquals(ChainModelProtocolException.Code.CONTEXT_REQUEST_DIGEST_MISMATCH,
                digestFailure.code());
        assertEquals(0, digestCalls.get());
    }

    @Test
    void lateProposalIsRecordedStaleAndNeverBecomesExecutable() {
        Store store = new Store(completeContext(), Set.of("route-1"));
        ChainModelProtocolOutcome.ProposalReady ready = assertInstanceOf(
                ChainModelProtocolOutcome.ProposalReady.class,
                service(store, new AtomicInteger(), new AnswerPayload.DirectAnswer(
                        "route-1", "explain", "answer", List.of()))
                        .invoke(request()));
        ChainProposalAdmissionService admissions = new ChainProposalAdmissionService(
                store, store, check -> false);
        ChainProposalAdmissionService.AdmissionRequest admission =
                new ChainProposalAdmissionService.AdmissionRequest(
                        ready.proposal().proposalId(), "task-1", "event-stale", true,
                        null, "b".repeat(64), NOW);
        ChainProposalAdmissionService.AdmissionResult first = admissions.admit(admission);
        assertEquals(ChainProposalState.STALE, first.state().stateKind());
        assertFalse(first.executable());
        assertFalse(first.replayed());
        ChainProposalAdmissionService.AdmissionResult replay = admissions.admit(admission);
        assertTrue(replay.replayed());
        assertFalse(replay.executable());
        assertEquals(1, store.stateEvents.size());
        assertEquals(List.of("PROPOSAL_STALE"), store.authorityEventTypes);
    }

    @Test
    void acceptedProposalBecomesNonExecutableAfterOfficialReplacementAndAdmissionReplay() {
        Store store = new Store(completeContext(), Set.of("route-1"));
        ChainModelProtocolOutcome.ProposalReady ready = assertInstanceOf(
                ChainModelProtocolOutcome.ProposalReady.class,
                service(store, new AtomicInteger(), new AnswerPayload.DirectAnswer(
                        "route-1", "explain", "answer", List.of())).invoke(request()));
        ChainProposalAdmissionService admissions = new ChainProposalAdmissionService(
                store, store, check -> true);
        ChainProposalAdmissionService.AdmissionRequest admission =
                new ChainProposalAdmissionService.AdmissionRequest(
                        ready.proposal().proposalId(), "task-1", "event-accepted", true,
                        null, "c".repeat(64), NOW);
        ChainProposalAdmissionService.AdmissionResult accepted = admissions.admit(admission);
        assertTrue(accepted.executable());

        ChainProposalAdmissionService.AdmissionResult replaced =
                admissions.replaceByOfficialResult(
                        new ChainProposalAdmissionService.OfficialReplacement(
                                ready.proposal().proposalId(), "task-1", "event-official",
                                ChainPersistenceRecords.ProposalOfficialAuthorityType.ANSWER,
                                ready.bodyContent().contentId(), null, "d".repeat(64), NOW));
        assertEquals(ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                replaced.state().stateKind());
        assertFalse(replaced.executable());

        ChainProposalAdmissionService.AdmissionResult replay = admissions.admit(admission);
        assertTrue(replay.replayed());
        assertEquals(ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                replay.state().stateKind());
        assertFalse(replay.executable());
        assertEquals(List.of(
                "PROPOSAL_ACCEPTED",
                "PROPOSAL_REPLACED_BY_OFFICIAL_RESULT"), store.authorityEventTypes);
    }

    @Test
    void rejectedProposalNeverExposesExecutable() {
        Store store = new Store(completeContext(), Set.of("route-1"));
        ChainModelProtocolOutcome.ProposalReady ready = assertInstanceOf(
                ChainModelProtocolOutcome.ProposalReady.class,
                service(store, new AtomicInteger(), new AnswerPayload.DirectAnswer(
                        "route-1", "explain", "answer", List.of())).invoke(request()));
        ChainProposalAdmissionService admissions = new ChainProposalAdmissionService(
                store, store, check -> true);
        ChainProposalAdmissionService.AdmissionResult rejected = admissions.admit(
                new ChainProposalAdmissionService.AdmissionRequest(
                        ready.proposal().proposalId(), "task-1", "event-rejected", false,
                        null, "e".repeat(64), NOW));
        assertEquals(ChainProposalState.REJECTED, rejected.state().stateKind());
        assertFalse(rejected.executable());
        assertEquals(List.of("PROPOSAL_REJECTED"), store.authorityEventTypes);
    }

    @Test
    void schemaDrivenSourceRefsRejectAnObjectOmittedFromFrozenVisibility() {
        ProposalFields.TaskFrameDraft frame = new ProposalFields.TaskFrameDraft(
                "objective", List.of("hidden-object"), List.of("deliverable"), List.of(),
                "project-version", "permission-tier",
                io.paperagent.v2.contracts.TaskRequirements.explicit(
                        List.of(), io.paperagent.v2.contracts.PublishRequirement.NOT_REQUIRED));
        ProposalFields.RequirementCoverage coverage = new ProposalFields.RequirementCoverage(
                "requirement", ProposalFields.RequirementStatus.SATISFIED,
                List.of("visible-fact"));
        ProposalFields.PlanDraft plan = new ProposalFields.PlanDraft(List.of(
                new ProposalFields.StepDraft(
                        "step-1", 1, "objective", List.of(), List.of("done"),
                        List.of("scope"), List.of("deliverable"), false, null)));
        PlannerPayload.PersistentPlan payload = new PlannerPayload.PersistentPlan(
                frame, List.of(coverage), plan, List.of(), null);
        Store store = new Store(
                completeContext(ChainRole.PLANNER, ChainWorkState.PLANNING, "planning"),
                Set.of("project-version", "permission-tier", "visible-fact"));

        ChainModelProtocolOutcome.ModelCallFailed failed = assertInstanceOf(
                ChainModelProtocolOutcome.ModelCallFailed.class,
                service(store, new AtomicInteger(), payload).invoke(request(
                        ChainRole.PLANNER, ChainWorkState.PLANNING, "planning")));

        assertEquals(3, failed.attempts());
        assertTrue(store.proposals.isEmpty());
        assertTrue(store.contents.isEmpty());
    }

    @Test
    void rejectsIllegalRoleWorkStateBeforeProviderOrContextUse() {
        Store store = new Store(completeContext(), Set.of("route-1"));
        AtomicInteger calls = new AtomicInteger();
        ChainModelProtocolService protocol = service(
                store, calls, new AnswerPayload.DirectAnswer(
                        "route-1", "explain", "answer", List.of()));

        assertThrows(IllegalArgumentException.class, () -> protocol.invoke(
                request(ChainRole.EXECUTOR, ChainWorkState.PLANNING, "illegal")));
        assertEquals(0, calls.get());
    }

    private static ChainModelProtocolService service(
            Store store, AtomicInteger calls, ChainProposalPayload payload) {
        ChainModelCallPort provider = request -> {
            calls.incrementAndGet();
            assertEquals("context-1", request.contextRevisionId());
            assertEquals("complete-token", request.completionToken());
            assertEquals("provider", request.expectedProvider());
            assertEquals("model", request.expectedModel());
            assertEquals("frozen prompt", request.canonicalPrompt());
            return new ChainModelCallResult.Success(
                    "raw provider response must remain transient", "STOP", 7, Map.of("tokens", "4"));
        };
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) ->
                new ProviderRoleOutput("1", payload.kind().wireName(), payload);
        return new ChainModelProtocolService(store, store, store, store, provider, decoder);
    }

    private static ChainModelProtocolRequest request() {
        return request(ChainRole.ANSWER, ChainWorkState.DIRECT_ANSWERING, "direct answer");
    }

    private static ChainModelProtocolRequest request(
            ChainRole role, ChainWorkState workState, String reason) {
        return new ChainModelProtocolRequest(
                "task-1", "invocation-1", "context-1", "complete-token",
                role, workState, reason,
                "provider", "model", 1, null, NOW);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord completeContext() {
        return completeContext(
                ChainRole.ANSWER, ChainWorkState.DIRECT_ANSWERING, "direct answer");
    }

    private static ChainPersistenceRecords.ContextRevisionRecord completeContext(
            ChainRole role, ChainWorkState workState, String reason) {
        return completeContext(
                role, workState, reason, ChainModelCanonical.sha256("frozen prompt"));
    }

    private static ChainPersistenceRecords.ContextRevisionRecord completeContext(
            ChainRole role, ChainWorkState workState, String reason, String requestDigest) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context-1", "task-1", null, role,
                workState, reason, "instruction-1",
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                "projectors-v1", "pagination-v1", "chain-runtime-policy-v1",
                ChainContextRevisionStatus.COMPLETE, 13,
                new ChainPersistenceRecords.FormattedJson(1, "{}"),
                requestDigest,
                "complete-token", null, null, NOW, NOW);
    }

    private static List<ChainPersistenceRecords.ContextModuleRecord> contextModules(
            ChainPersistenceRecords.ContextRevisionRecord context) {
        String json = "{}";
        ChainPersistenceRecords.CanonicalJson canonical =
                new ChainPersistenceRecords.CanonicalJson(
                        1, ChainModelCanonical.sha256(json), json);
        return List.of(ChainContextModule.values()).stream()
                .map(module -> new ChainPersistenceRecords.ContextModuleRecord(
                        context.contextRevisionId(), context.taskId(), module.ordinalCode(), module,
                        ChainContextModuleStatus.PRESENT, canonical, canonical,
                        "projection-v1", "pagination-v1", canonical, canonical, NOW))
                .toList();
    }

    private static final class Store implements ChainContextManager,
            ChainModelRepository, ChainModelInvocationWriter, ChainModelMaterializationPort,
            ChainProposalStateWriter {
        private final ChainPersistenceRecords.ContextRevisionRecord context;
        private final Set<String> visibleSourceRefs;
        private final Map<String, ChainPersistenceRecords.ModelInvocationRecord> invocations = new LinkedHashMap<>();
        private final List<ChainPersistenceRecords.ProviderAttemptRecord> attempts = new ArrayList<>();
        private final Map<String, ChainPersistenceRecords.ContentRecord> contents = new LinkedHashMap<>();
        private final Map<String, ChainPersistenceRecords.ModelProposalRecord> proposals = new LinkedHashMap<>();
        private final List<ChainPersistenceRecords.ProposalStateEventRecord> stateEvents = new ArrayList<>();
        private final List<String> authorityEventTypes = new ArrayList<>();
        private boolean failNextMaterialization;

        private Store(
                ChainPersistenceRecords.ContextRevisionRecord context,
                Set<String> visibleSourceRefs) {
            this.context = context;
            this.visibleSourceRefs = Set.copyOf(visibleSourceRefs);
        }

        @Override
        public ChainContextFreezeOutcome freeze(ChainContextFreezeRequest request) {
            throw new UnsupportedOperationException("test store does not freeze contexts");
        }

        @Override
        public ChainFrozenContext recover(String taskId, String contextRevisionId) {
            if (!context.taskId().equals(taskId)
                    || !context.contextRevisionId().equals(contextRevisionId)) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_REVISION_NOT_FOUND, "not found");
            }
            if (context.status() == ChainContextRevisionStatus.BUILDING) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_REVISION_NOT_RECOVERABLE, "building");
            }
            return new ChainFrozenContext(
                    context, contextModules(context), "frozen prompt", visibleSourceRefs);
        }

        @Override
        public ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.ModelInvocationRecord> appendInvocation(
                ChainPersistenceRecords.ModelInvocationRecord invocation) {
            ChainPersistenceRecords.ModelInvocationRecord existing = invocations.putIfAbsent(
                    invocation.invocationId(), invocation);
            return new ChainPersistenceRecords.AppendResult<>(existing == null ? invocation : existing,
                    existing != null);
        }

        @Override
        public ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.ProviderAttemptRecord> appendProviderAttempt(
                ChainPersistenceRecords.ProviderAttemptRecord attempt) {
            attempts.add(attempt);
            return new ChainPersistenceRecords.AppendResult<>(attempt, false);
        }

        @Override
        public SuccessfulMaterialization persistSuccessfulAttempt(
                ChainPersistenceRecords.ProviderAttemptRecord attempt,
                ChainPersistenceRecords.ContentRecord bodyContent,
                ChainPersistenceRecords.ModelProposalRecord proposal) {
            if (failNextMaterialization) {
                failNextMaterialization = false;
                throw new IllegalStateException("simulated transaction rollback");
            }
            ChainPersistenceRecords.ModelProposalRecord existing = proposals.get(proposal.proposalId());
            if (existing != null) {
                return new SuccessfulMaterialization(
                        attempts.stream().filter(value -> value.attemptNo() == attempt.attemptNo()).findFirst()
                                .orElse(attempt),
                        bodyContent == null ? null : contents.get(bodyContent.contentId()), existing, true);
            }
            attempts.add(attempt);
            if (bodyContent != null) contents.put(bodyContent.contentId(), bodyContent);
            proposals.put(proposal.proposalId(), proposal);
            return new SuccessfulMaterialization(attempt, bodyContent, proposal, false);
        }

        @Override
        public Optional<ChainPersistenceRecords.ModelInvocationRecord> findInvocation(String invocationId) {
            return Optional.ofNullable(invocations.get(invocationId));
        }

        @Override
        public long highestInvocationOrdinal(String taskId) {
            return invocations.values().stream()
                    .filter(value -> value.taskId().equals(taskId))
                    .mapToLong(ChainPersistenceRecords.ModelInvocationRecord::invocationOrdinal)
                    .max().orElse(0);
        }

        @Override
        public List<ChainPersistenceRecords.ModelInvocationRecord> findInvocations(String taskId, long cut) {
            return invocations.values().stream().filter(value -> value.taskId().equals(taskId)
                    && value.invocationOrdinal() <= cut).toList();
        }

        @Override
        public int highestProviderAttemptNo(String invocationId) {
            return attempts.stream()
                    .filter(value -> value.invocationId().equals(invocationId))
                    .mapToInt(ChainPersistenceRecords.ProviderAttemptRecord::attemptNo)
                    .max().orElse(0);
        }

        @Override
        public List<ChainPersistenceRecords.ProviderAttemptRecord> findProviderAttempts(String invocationId) {
            return attempts.stream().filter(value -> value.invocationId().equals(invocationId)).toList();
        }

        @Override
        public List<ChainPersistenceRecords.ContentRecord> findContents(String invocationId) {
            return contents.values().stream().filter(value -> value.invocationId().equals(invocationId)).toList();
        }

        @Override
        public Optional<ChainPersistenceRecords.ContentRecord> findContent(String contentId) {
            return Optional.ofNullable(contents.get(contentId));
        }

        @Override
        public Optional<ChainPersistenceRecords.ModelProposalRecord> findProposal(String proposalId) {
            return Optional.ofNullable(proposals.get(proposalId));
        }

        @Override
        public Optional<ChainPersistenceRecords.ModelProposalRecord> findProposalByInvocation(String invocationId) {
            return proposals.values().stream().filter(value -> value.invocationId().equals(invocationId)).findFirst();
        }

        @Override
        public List<ChainPersistenceRecords.ProposalStateEventRecord> findProposalStateEvents(String proposalId) {
            return stateEvents.stream().filter(value -> value.proposalId().equals(proposalId)).toList();
        }

        @Override
        public ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.ProposalStateEventRecord>
                appendProposalState(
                        ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.ProposalStateEventRecord> requested) {
            Optional<ChainPersistenceRecords.ProposalStateEventRecord> existing = stateEvents.stream()
                    .filter(value -> value.proposalId().equals(requested.fact().proposalId())
                            && value.stateSequence() == requested.fact().stateSequence()).findFirst();
            ChainPersistenceRecords.ProposalStateEventRecord fact = existing.orElseGet(() -> {
                stateEvents.add(requested.fact());
                return requested.fact();
            });
            ChainPersistenceRecords.AuthorityEventRequest source = requested.event();
            authorityEventTypes.add(source.eventType());
            ChainPersistenceRecords.AuthorityEventRecord event = new ChainPersistenceRecords.AuthorityEventRecord(
                    source.eventId(), source.taskId(), stateEvents.size(), source.eventType(),
                    source.transitionId(), source.sourceIdentitySha256(), source.committedAt());
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(event, fact, existing.isPresent());
        }
    }
}
