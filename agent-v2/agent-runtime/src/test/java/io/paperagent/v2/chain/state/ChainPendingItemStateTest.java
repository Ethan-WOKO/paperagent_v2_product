package io.paperagent.v2.chain.state;

import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPendingItemWriter;
import io.paperagent.v2.chain.ChainPermissionDecision;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.GapValidation;
import io.paperagent.v2.chain.PlannerPayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainPendingItemStateTest {
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void reviewDecisionSourceOpensAndReplaysTheSamePendingItemWithoutSecondProposalBinding() {
        String proposalId = "reflector-pending";
        var opening = new ChainPendingItemRuntime.PendingProposal(
                "task-1", proposalId,
                ChainProposalKind.REFLECTOR_NEED_USER_INPUT,
                state(proposalId,
                        ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                        "REVIEW_DECISION", "review-1"),
                ChainPendingItemType.USER_INFORMATION, List.of("owner"), null,
                "Who owns this?", "text", ChainRole.EXECUTOR,
                ChainRole.EXECUTOR, "current-step", HASH);
        Fixture fixture = new Fixture(opening);
        var request = new ChainPendingItemRuntime.OpenRequest(
                "task-1", proposalId, "event-reviewed-open", NOW);

        var first = fixture.runtime.openFromReviewDecision(request, "review-1");
        var replay = fixture.runtime.openFromReviewDecision(request, "review-1");

        assertEquals(first, replay);
        assertEquals(proposalId, first.sourceProposalId());
        assertThrows(IllegalStateException.class, () ->
                fixture.runtime.openFromReviewDecision(
                        request, "another-review"));
    }

    @Test
    void responseValidationReopensTheSameGapThenResolvesOnlyAfterOfficialSuccessor() {
        Fixture fixture = new Fixture(userPendingProposal("proposal-open"));
        ChainPersistenceRecords.PendingItemRecord item = fixture.runtime.open(
                new ChainPendingItemRuntime.OpenRequest(
                        "task-1", "proposal-open", "event-open", NOW));
        assertEquals(List.of("PENDING_ITEM"), fixture.store.authorityEventTypes);
        ChainPendingItemRuntime.Directive.QuestionRequired question = assertInstanceOf(
                ChainPendingItemRuntime.Directive.QuestionRequired.class,
                fixture.runtime.nextDirective("task-1", item.gapId()));
        assertEquals(ChainRole.ANSWER, question.role());
        assertEquals(ChainWorkState.WAITING_USER, question.workState());

        fixture.addAnswerInstruction("instruction-answer-1", item.gapId());
        ChainPendingItemRuntime.Directive.ValidationRequired validation = assertInstanceOf(
                ChainPendingItemRuntime.Directive.ValidationRequired.class,
                fixture.runtime.recordResponse(new ChainPendingItemRuntime.ResponseRequest(
                        "task-1", item.gapId(), "event-response-1", "instruction-answer-1", NOW)));
        assertEquals(ChainWorkState.VALIDATING_PENDING_ITEM, validation.workState());
        assertEquals(ChainRole.PLANNER, validation.role());
        assertEquals(1, validation.responseRound());

        fixture.validationSources.put("proposal-still", validationProposal(
                "proposal-still", "invocation-still", new PlannerPayload.NeedUserInput(
                        List.of("owner"), "owner is user-specific", "Which owner?", "text",
                        List.of("owner known"), ChainRole.PLANNER, ChainRole.PLANNER,
                        "planning", new GapValidation(item.gapId(), List.of(
                        new GapValidation.Check("owner known", false, "answer-fact-1")),
                        GapValidation.Outcome.STILL_PENDING))));
        ChainPendingItemRuntime.Directive.QuestionRequired askedAgain = assertInstanceOf(
                ChainPendingItemRuntime.Directive.QuestionRequired.class,
                fixture.runtime.applyValidation(new ChainPendingItemRuntime.ValidationRequest(
                        "task-1", item.gapId(), "event-still", "proposal-still", NOW)));
        assertEquals(item.gapId(), askedAgain.gapId());
        assertEquals(List.of("owner"), askedAgain.missingFields());
        assertEquals("Which owner?", askedAgain.question());
        assertEquals("text", askedAgain.expectedFormat());
        assertTrue(fixture.successors.isEmpty());
        int stillEventCount = fixture.store.events.get(item.gapId()).size();
        assertInstanceOf(ChainPendingItemRuntime.Directive.QuestionRequired.class,
                fixture.runtime.applyValidation(new ChainPendingItemRuntime.ValidationRequest(
                        "task-1", item.gapId(), "event-still", "proposal-still", NOW)));
        assertEquals(stillEventCount, fixture.store.events.get(item.gapId()).size());

        fixture.addAnswerInstruction("instruction-answer-2", item.gapId());
        assertInstanceOf(ChainPendingItemRuntime.Directive.ValidationRequired.class,
                fixture.runtime.recordResponse(new ChainPendingItemRuntime.ResponseRequest(
                        "task-1", item.gapId(), "event-response-2", "instruction-answer-2", NOW)));
        fixture.validationSources.put("proposal-resolved", validationProposal(
                "proposal-resolved", "invocation-resolved", new PlannerPayload.DirectRoute(
                        "direct", "answer", List.of(), List.of(),
                        false, false, false, false,
                        new GapValidation(item.gapId(), List.of(
                                new GapValidation.Check(
                                        "owner known", true, "answer-fact-2")),
                                GapValidation.Outcome.RESOLVED))));
        ChainPendingItemRuntime.Directive.ResumeRequired resumed = assertInstanceOf(
                ChainPendingItemRuntime.Directive.ResumeRequired.class,
                fixture.runtime.applyValidation(new ChainPendingItemRuntime.ValidationRequest(
                        "task-1", item.gapId(), "event-resolved", "proposal-resolved", NOW)));
        assertEquals(ChainRole.PLANNER, resumed.role());
        assertTrue(resumed.transitionId().startsWith("transition."));
        assertEquals(List.of("successor", "pending:RESOLVED"),
                fixture.commitOrder.subList(fixture.commitOrder.size() - 2,
                        fixture.commitOrder.size()));
        int resolvedEventCount = fixture.store.events.get(item.gapId()).size();
        assertInstanceOf(ChainPendingItemRuntime.Directive.ResumeRequired.class,
                fixture.runtime.applyValidation(new ChainPendingItemRuntime.ValidationRequest(
                        "task-1", item.gapId(), "event-resolved", "proposal-resolved", NOW)));
        assertEquals(resolvedEventCount, fixture.store.events.get(item.gapId()).size());
        assertEquals(List.of(
                "PENDING_ITEM",
                "PENDING_ITEM_RESPONSE_RECEIVED",
                "PENDING_ITEM_PENDING",
                "PENDING_ITEM_RESPONSE_RECEIVED",
                "PENDING_ITEM_RESOLVED"), fixture.store.authorityEventTypes);
    }

    @Test
    void arbitraryAnswerNeverClosesGapAndPermissionRequiresFormalProductDecision() {
        Fixture user = new Fixture(userPendingProposal("proposal-open"));
        ChainPersistenceRecords.PendingItemRecord userGap = user.runtime.open(
                new ChainPendingItemRuntime.OpenRequest(
                        "task-1", "proposal-open", "event-open", NOW));
        user.addAnswerInstruction("instruction-answer", userGap.gapId());
        assertInstanceOf(ChainPendingItemRuntime.Directive.ValidationRequired.class,
                user.runtime.recordResponse(new ChainPendingItemRuntime.ResponseRequest(
                        "task-1", userGap.gapId(), "event-answer", "instruction-answer", NOW)));
        assertEquals(ChainPendingItemStatus.RESPONSE_RECEIVED,
                user.store.events.get(userGap.gapId()).get(0).eventKind());
        assertThrows(IllegalStateException.class, () -> user.runtime.applyPermissionDecision(
                new ChainPendingItemRuntime.PermissionRequest(
                        "task-1", userGap.gapId(), "event-permission", "permission-1", NOW)));

        Fixture wrongInstruction = new Fixture(userPendingProposal("proposal-wrong-instruction"));
        ChainPersistenceRecords.PendingItemRecord wrongInstructionGap = wrongInstruction.runtime.open(
                new ChainPendingItemRuntime.OpenRequest(
                        "task-1", "proposal-wrong-instruction",
                        "event-wrong-instruction-open", NOW));
        wrongInstruction.addAnswerInstruction("instruction-wrong-gap", "gap.other");
        assertThrows(IllegalStateException.class, () -> wrongInstruction.runtime.recordResponse(
                new ChainPendingItemRuntime.ResponseRequest(
                        "task-1", wrongInstructionGap.gapId(), "event-wrong-gap",
                        "instruction-wrong-gap", NOW)));

        Fixture unboundInstruction = new Fixture(userPendingProposal("proposal-unbound-answer"));
        ChainPersistenceRecords.PendingItemRecord unboundGap = unboundInstruction.runtime.open(
                new ChainPendingItemRuntime.OpenRequest(
                        "task-1", "proposal-unbound-answer", "event-unbound-open", NOW));
        unboundInstruction.addUnboundAnswerInstruction(
                "instruction-unbound", unboundGap.gapId());
        assertThrows(IllegalStateException.class, () -> unboundInstruction.runtime.recordResponse(
                new ChainPendingItemRuntime.ResponseRequest(
                        "task-1", unboundGap.gapId(), "event-unbound-response",
                        "instruction-unbound", NOW)));

        Fixture staleInstruction = new Fixture(userPendingProposal("proposal-stale-answer"));
        ChainPersistenceRecords.PendingItemRecord staleGap = staleInstruction.runtime.open(
                new ChainPendingItemRuntime.OpenRequest(
                        "task-1", "proposal-stale-answer", "event-stale-open", NOW));
        staleInstruction.addAnswerInstruction("instruction-stale", staleGap.gapId());
        staleInstruction.addAnswerInstruction("instruction-current", staleGap.gapId());
        assertThrows(IllegalStateException.class, () -> staleInstruction.runtime.recordResponse(
                new ChainPendingItemRuntime.ResponseRequest(
                        "task-1", staleGap.gapId(), "event-stale-response",
                        "instruction-stale", NOW)));

        Fixture permission = new Fixture(permissionPendingProposal("proposal-permission"));
        ChainPersistenceRecords.PendingItemRecord permissionGap = permission.runtime.open(
                new ChainPendingItemRuntime.OpenRequest(
                        "task-1", "proposal-permission", "event-permission-open", NOW));
        assertThrows(IllegalStateException.class, () -> permission.runtime.applyValidation(
                new ChainPendingItemRuntime.ValidationRequest(
                        "task-1", permissionGap.gapId(), "event-model", "forged", NOW)));

        ChainPersistenceRecords.PermissionDecisionRecord granted =
                new ChainPersistenceRecords.PermissionDecisionRecord(
                        "permission-1", "task-1", "event-product-permission",
                        permissionGap.gapId(), "project:write", "PRODUCT_ACL", "grant-1",
                        ChainPermissionDecision.GRANTED, "user granted", NOW);
        permission.permissionDecisions.put("permission-1", granted);
        ChainPendingItemRuntime.Directive.PermissionReintakeRequired reintake = assertInstanceOf(
                ChainPendingItemRuntime.Directive.PermissionReintakeRequired.class,
                permission.runtime.applyPermissionDecision(
                        new ChainPendingItemRuntime.PermissionRequest(
                                "task-1", permissionGap.gapId(), "event-granted",
                                "permission-1", NOW)));
        assertEquals(ChainRole.PLANNER, reintake.role());
        assertEquals(ChainWorkState.PLANNING, reintake.workState());
        assertEquals("permission-1", reintake.permissionDecisionId());

        Fixture malformedPermission = new Fixture(
                permissionPendingProposal("proposal-malformed-permission"));
        ChainPersistenceRecords.PendingItemRecord malformedGap = malformedPermission.runtime.open(
                new ChainPendingItemRuntime.OpenRequest(
                        "task-1", "proposal-malformed-permission",
                        "event-malformed-open", NOW));
        malformedPermission.store.events.put(malformedGap.gapId(), List.of(
                new ChainPersistenceRecords.PendingItemEventRecord(
                        malformedGap.gapId(), 1, ChainPendingItemStatus.RESOLVED,
                        "task-1", "event-malformed-resolved", null, null, null,
                        canonical("{}"), NOW)));
        assertThrows(IllegalStateException.class, () ->
                malformedPermission.runtime.nextDirective("task-1", malformedGap.gapId()));
    }

    @Test
    void exactBoundResolvedProposalRecoversTheResponseReceivedFailureWindow() {
        Fixture fixture = new Fixture(userPendingProposal("proposal-open-window"));
        ChainPersistenceRecords.PendingItemRecord item = fixture.runtime.open(
                new ChainPendingItemRuntime.OpenRequest(
                        "task-1", "proposal-open-window", "event-open-window", NOW));
        fixture.addAnswerInstruction("instruction-window", item.gapId());
        fixture.runtime.recordResponse(new ChainPendingItemRuntime.ResponseRequest(
                "task-1", item.gapId(), "event-response-window", "instruction-window", NOW));
        PlannerPayload.DirectRoute payload = new PlannerPayload.DirectRoute(
                "direct", "answer", List.of(), List.of(),
                false, false, false, false,
                new GapValidation(item.gapId(), List.of(
                        new GapValidation.Check("owner known", true, "answer-fact")),
                        GapValidation.Outcome.RESOLVED));
        ChainPendingItemRuntime.AcceptedGapValidation accepted = validationProposal(
                "proposal-window", "invocation-window", payload);
        fixture.validationSources.put("proposal-window", accepted);
        fixture.store.failResolvedAppend = true;
        assertThrows(IllegalStateException.class, () -> fixture.runtime.applyValidation(
                new ChainPendingItemRuntime.ValidationRequest(
                        "task-1", item.gapId(), "event-resolved-window",
                        "proposal-window", NOW)));
        assertEquals(ChainPendingItemStatus.RESPONSE_RECEIVED,
                fixture.store.events.get(item.gapId()).get(0).eventKind());
        assertEquals(1, fixture.successors.size());

        fixture.validationSources.put("proposal-window",
                new ChainPendingItemRuntime.AcceptedGapValidation(
                        accepted.proposal(),
                        state("proposal-window",
                                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                                "ROUTE_DECISION", "route-1"),
                        accepted.invocation(), payload));
        ChainPendingItemRuntime.Directive.ResumeRequired resumed = assertInstanceOf(
                ChainPendingItemRuntime.Directive.ResumeRequired.class,
                fixture.runtime.applyValidation(new ChainPendingItemRuntime.ValidationRequest(
                        "task-1", item.gapId(), "event-resolved-window",
                        "proposal-window", NOW)));
        assertTrue(resumed.transitionId().startsWith("transition."));
        assertEquals(1, fixture.successors.size(),
                "failure recovery must reuse the committed normal successor");
    }

    @Test
    void validationSourceRejectsCrossTaskRoleAndInvocationIdentityMixing() {
        PlannerPayload.NeedUserInput payload = new PlannerPayload.NeedUserInput(
                List.of("field"), "reason", "question", "text", List.of("condition"),
                ChainRole.PLANNER, ChainRole.PLANNER, "planning",
                new GapValidation("gap-1", List.of(
                        new GapValidation.Check("condition", false, "fact")),
                        GapValidation.Outcome.STILL_PENDING));
        ChainPersistenceRecords.ModelProposalRecord proposal =
                new ChainPersistenceRecords.ModelProposalRecord(
                        "proposal-1", "task-1", "invocation-1", 1,
                        ChainRole.PLANNER, payload.kind(), canonical("{}"), canonical("[]"),
                        null, null, NOW);
        ChainPersistenceRecords.ModelInvocationRecord otherTaskInvocation =
                new ChainPersistenceRecords.ModelInvocationRecord(
                        "invocation-1", "task-2", "context-1", "token",
                        ChainRole.PLANNER, ChainWorkState.VALIDATING_PENDING_ITEM,
                        "validate", "provider", "model", 1,
                        "chain-runtime-policy-v1", NOW);
        assertThrows(IllegalArgumentException.class, () ->
                new ChainPendingItemRuntime.AcceptedGapValidation(
                        proposal,
                        state("proposal-1", ChainProposalState.ACCEPTED, null, null),
                        otherTaskInvocation, payload));
    }

    private static ChainPendingItemRuntime.PendingProposal userPendingProposal(String proposalId) {
        return new ChainPendingItemRuntime.PendingProposal(
                "task-1", proposalId, ChainProposalKind.PLANNER_NEED_USER_INPUT,
                state(proposalId, ChainProposalState.ACCEPTED, null, null),
                ChainPendingItemType.USER_INFORMATION, List.of("owner"), null,
                "Who owns this?", "text", ChainRole.PLANNER, ChainRole.PLANNER,
                "planning", HASH);
    }

    private static ChainPendingItemRuntime.PendingProposal permissionPendingProposal(String proposalId) {
        return new ChainPendingItemRuntime.PendingProposal(
                "task-1", proposalId, ChainProposalKind.REFLECTOR_NEED_PERMISSION,
                state(proposalId, ChainProposalState.ACCEPTED, null, null),
                ChainPendingItemType.PERMISSION, List.of(), "project:write",
                "Allow project write?", "grant-or-deny", ChainRole.PLANNER,
                ChainRole.PLANNER, "NEW_INTAKE", HASH);
    }

    private static ChainPendingItemRuntime.AcceptedGapValidation validationProposal(
            String proposalId, String invocationId, io.paperagent.v2.chain.ChainProposalPayload payload) {
        ChainPersistenceRecords.ModelProposalRecord proposal =
                new ChainPersistenceRecords.ModelProposalRecord(
                        proposalId, "task-1", invocationId, 1, payload.role(), payload.kind(),
                        canonical("{}"), canonical("[]"), null, null, NOW);
        ChainPersistenceRecords.ModelInvocationRecord invocation =
                new ChainPersistenceRecords.ModelInvocationRecord(
                        invocationId, "task-1", "context-1", "token-1",
                        ChainRole.PLANNER, ChainWorkState.VALIDATING_PENDING_ITEM,
                        "validate gap", "provider", "model", 1,
                        "chain-runtime-policy-v1", NOW);
        return new ChainPendingItemRuntime.AcceptedGapValidation(
                proposal, state(proposalId, ChainProposalState.ACCEPTED, null, null),
                invocation, payload);
    }

    private static ChainPersistenceRecords.ProposalStateEventRecord state(
            String proposalId,
            ChainProposalState state,
            String authorityType,
            String authorityRef) {
        return new ChainPersistenceRecords.ProposalStateEventRecord(
                proposalId, state == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT ? 2 : 1,
                "task-1", "state-" + proposalId + "-" + state,
                state, authorityType, authorityRef, NOW);
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(String json) {
        return new ChainPersistenceRecords.CanonicalJson(
                1, sha256(json), json);
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

    private static final class Fixture {
        private final Store store = new Store();
        private final Map<String, ChainPendingItemRuntime.AcceptedGapValidation> validationSources =
                new HashMap<>();
        private final Map<String, ChainPendingItemRuntime.OfficialSuccessor> successors =
                new HashMap<>();
        private final Map<String, ChainPersistenceRecords.PermissionDecisionRecord> permissionDecisions =
                new HashMap<>();
        private final List<String> commitOrder = new ArrayList<>();
        private final ChainPendingItemRuntime runtime;

        private Fixture(ChainPendingItemRuntime.PendingProposal opening) {
            store.successors = successors;
            store.commitOrder = commitOrder;
            runtime = new ChainPendingItemRuntime(
                    store,
                    store,
                    store,
                    proposalId -> {
                        assertEquals(opening.proposalId(), proposalId);
                        return opening;
                    },
                    proposalId -> {
                        ChainPendingItemRuntime.AcceptedGapValidation source =
                                validationSources.get(proposalId);
                        if (source == null) throw new IllegalStateException("validation proposal missing");
                        return source;
                    },
                    new ChainPendingItemRuntime.NormalSuccessorPort() {
                        @Override
                        public ChainPendingItemRuntime.OfficialSuccessor commit(
                                ChainPendingItemRuntime.NormalSuccessorRequest request) {
                            commitOrder.add("successor");
                            ChainPendingItemRuntime.OfficialSuccessor successor =
                                    new ChainPendingItemRuntime.OfficialSuccessor(
                                            request.transitionId(), "ROUTE_DECISION", "route-1");
                            successors.put(request.transitionId(), successor);
                            return successor;
                        }

                        @Override
                        public Optional<ChainPendingItemRuntime.OfficialSuccessor> findCommitted(
                                String taskId, String transitionId) {
                            return Optional.ofNullable(successors.get(transitionId));
                        }
                    },
                    new ChainPendingItemRuntime.PermissionDecisionSource() {
                        @Override
                        public Optional<ChainPersistenceRecords.PermissionDecisionRecord> find(
                                String taskId, String gapId, String permissionDecisionId) {
                            return Optional.ofNullable(permissionDecisions.get(permissionDecisionId));
                        }

                        @Override
                        public Optional<ChainPersistenceRecords.PermissionDecisionRecord> findLatest(
                                String taskId, String gapId) {
                            return permissionDecisions.values().stream()
                                    .filter(value -> value.taskId().equals(taskId)
                                            && value.gapId().equals(gapId)).reduce((left, right) -> right);
                        }
                    },
                    (taskId, proposalId, authorityType, authorityRef) -> {
                        if (opening.proposalId().equals(proposalId)) {
                            assertEquals("PENDING_ITEM", authorityType);
                        } else {
                            assertTrue(validationSources.containsKey(proposalId));
                            assertTrue(authorityType.equals("PENDING_ITEM")
                                    || authorityType.equals("ROUTE_DECISION"));
                        }
                    });
        }

        private void addAnswerInstruction(String instructionId, String gapId) {
            addUnboundAnswerInstruction(instructionId, gapId);
            store.bindings.add(new ChainPersistenceRecords.TaskInstructionBindingRecord(
                    "task-1", "event-binding-" + instructionId, instructionId,
                    store.bindings.size() + 1L,
                    ChainPersistenceRecords.BindingRole.ORIGIN, NOW));
        }

        private void addUnboundAnswerInstruction(String instructionId, String gapId) {
            store.instructions.put(instructionId,
                    new ChainPersistenceRecords.InstructionRecord(
                            instructionId, "command-" + instructionId, 1L,
                            "task-1", 1L, HASH, "message-" + instructionId,
                            ChainInstructionRelation.ANSWER_TO_PENDING_ITEM,
                            null, gapId, HASH, NOW));
        }
    }

    private static final class Store implements
            ChainWorkflowRepository, ChainFoundationRepository, ChainPendingItemWriter {
        private final Map<String, ChainPersistenceRecords.PendingItemRecord> items =
                new LinkedHashMap<>();
        private final Map<String, List<ChainPersistenceRecords.PendingItemEventRecord>> events =
                new LinkedHashMap<>();
        private final List<String> authorityEventTypes = new ArrayList<>();
        private final Map<String, ChainPersistenceRecords.InstructionRecord> instructions =
                new HashMap<>();
        private final List<ChainPersistenceRecords.TaskInstructionBindingRecord> bindings =
                new ArrayList<>();
        private Map<String, ChainPendingItemRuntime.OfficialSuccessor> successors;
        private List<String> commitOrder;
        private long eventSequence;
        private boolean failResolvedAppend;

        @Override
        public ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.PendingItemRecord>
                appendPendingItem(
                        ChainPersistenceRecords.AuthoritativeFact<
                                ChainPersistenceRecords.PendingItemRecord> requested) {
            authorityEventTypes.add(requested.event().eventType());
            ChainPersistenceRecords.PendingItemRecord existing = items.putIfAbsent(
                    requested.fact().gapId(), requested.fact());
            ChainPersistenceRecords.PendingItemRecord fact = existing == null
                    ? requested.fact() : existing;
            return result(requested.event(), fact, existing != null);
        }

        @Override
        public ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.PendingItemEventRecord> appendPendingItemEvent(
                        ChainPersistenceRecords.AuthoritativeFact<
                                ChainPersistenceRecords.PendingItemEventRecord> requested) {
            if (requested.fact().eventKind() == ChainPendingItemStatus.RESOLVED
                    && requested.fact().validationInvocationId() != null) {
                assertTrue(successors.containsKey(requested.event().transitionId()),
                        "normal successor must exist before RESOLVED append");
                if (failResolvedAppend) {
                    failResolvedAppend = false;
                    throw new IllegalStateException("simulated failure after proposal binding");
                }
            }
            authorityEventTypes.add(requested.event().eventType());
            events.computeIfAbsent(requested.fact().gapId(), ignored -> new ArrayList<>())
                    .add(requested.fact());
            commitOrder.add("pending:" + requested.fact().eventKind());
            return result(requested.event(), requested.fact(), false);
        }

        private <T extends ChainPersistenceRecords.TaskAuthorityFact>
                ChainPersistenceRecords.AuthoritativeAppendResult<T> result(
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

        @Override public List<ChainPersistenceRecords.PendingItemRecord> findPendingItems(String taskId) {
            return items.values().stream().filter(value -> value.taskId().equals(taskId)).toList();
        }
        @Override public List<ChainPersistenceRecords.PendingItemEventRecord> findPendingItemEvents(String gapId) {
            return List.copyOf(events.getOrDefault(gapId, List.of()));
        }
        @Override public List<ChainPersistenceRecords.PendingItemRecord> findOpenPendingItems(String taskId) {
            return findPendingItems(taskId);
        }
        @Override public Optional<ChainPersistenceRecords.TransitionRecord> findTransition(String id) { return Optional.empty(); }
        @Override public List<ChainPersistenceRecords.TransitionStageRecord> findTransitionStages(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.TransitionRecord> findIncompleteTransitions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.RouteDecisionRecord> findRouteDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PlanBindingRecord> findPlanBindings(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.CandidateStepResultRecord> findCandidateStepResults(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ReviewDecisionRecord> findReviewDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.AcceptedResultRecord> findAcceptedResults(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ResultApplicabilityRecord> findApplicabilityDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PermissionDecisionRecord> findPermissionDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findActionBindings(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findInFlightActions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.WorkspaceCandidateRecord> findWorkspaceCandidates(String id) { return List.of(); }
        @Override public Optional<ChainPersistenceRecords.InstructionRecord> findInstruction(String id) {
            return Optional.ofNullable(instructions.get(id));
        }
        @Override public Optional<ChainPersistenceRecords.CommandRecord> findCommand(long userId, long sessionId, String id) { return Optional.empty(); }
        @Override public Optional<ChainPersistenceRecords.CommandRecord> findCommand(String id) { return Optional.empty(); }
        @Override public Optional<ChainPersistenceRecords.TaskRecord> findTask(String id) { return Optional.empty(); }
        @Override public List<ChainPersistenceRecords.TaskInstructionBindingRecord> findTaskInstructions(
                String id, long cut) {
            return bindings.stream().filter(value -> value.taskId().equals(id)
                    && value.taskInstructionSequence() <= cut).toList();
        }
        @Override public List<ChainPersistenceRecords.AuthorityEventRecord> findAuthorityEvents(String id, long cut) { return List.of(); }
        @Override public long highestAuthorityEventSequence(String id) { return eventSequence; }
    }
}
