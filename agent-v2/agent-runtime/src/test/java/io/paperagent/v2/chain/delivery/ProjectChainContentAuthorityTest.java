package io.paperagent.v2.chain.delivery;

import io.paperagent.v2.chain.AnswerPayload;
import io.paperagent.v2.chain.ChainCommandStatus;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainDeliveryWriter;
import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.route.ChainRouteRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectChainContentAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-08-07T09:00:00Z");
    private static final String SHA = "a".repeat(64);
    private static final String BODY = "single-answer-body-canary";

    @Test
    void directDeliveryKeepsOneBodyAuthorityAndReplaysOriginalTimestamps() {
        Store store = Store.direct();
        ChainDeliveryRuntime runtime = runtime(store);
        ChainDeliveryRuntime.BeginCommand command = store.directCommand(
                "command-root", NOW.plusSeconds(1));

        ChainDeliveryRuntime.Started first = runtime.begin(command);
        ChainDeliveryRuntime.Started replay = runtime.begin(store.directCommand(
                "command-root", NOW.plusSeconds(99)));

        assertEquals(first, replay);
        assertEquals(NOW.plusSeconds(1).plusMillis(1),
                first.delivery().createdAt());
        assertEquals(NOW.plusSeconds(1).plusMillis(1),
                first.pending().committedAt());
        assertEquals(1, store.deliveries.size());
        assertEquals(1, store.deliveryEvents.size());
        assertEquals(1, store.reservations);
        assertEquals(BODY, store.content.body());
        assertFalse(first.delivery().toString().contains(BODY));
        assertFalse(store.proposal.payload().json().contains(BODY));
        assertFalse(hasRawBodyComponent(
                ChainPersistenceRecords.DeliveryRecord.class));
        assertFalse(hasRawBodyComponent(
                ChainDeliveryMessagePort.Reservation.class));
        assertFalse(java.util.Arrays.stream(
                        ChainDeliveryRuntime.class.getDeclaredConstructors())
                .flatMap(value -> java.util.Arrays.stream(
                        value.getParameterTypes()))
                .anyMatch(value -> value.getSimpleName()
                        .equals("ChainContentWriter")));
    }

    @Test
    void rejectsForgedDirectContractRefsAndCrossTaskCommand() {
        Store forged = Store.direct(new AnswerPayload.DirectAnswer(
                "route-1", "forged specification", BODY,
                List.of("instruction-1")));
        ChainDeliveryException sourceFailure = assertThrows(
                ChainDeliveryException.class,
                () -> runtime(forged).begin(forged.directCommand(
                        "command-root", NOW.plusSeconds(2))));
        assertEquals(ChainDeliveryException.Code.SOURCE_INVALID,
                sourceFailure.code());

        Store crossTask = Store.direct();
        crossTask.otherCommand = command("command-other", "task-other");
        ChainDeliveryException commandFailure = assertThrows(
                ChainDeliveryException.class,
                () -> runtime(crossTask).begin(crossTask.directCommand(
                        "command-other", NOW.plusSeconds(3))));
        assertEquals(ChainDeliveryException.Code.SOURCE_INVALID,
                commandFailure.code());
    }

    @Test
    void boundCurrentCommandsMayDeliverGapAndDecisionButNotOldCommand() {
        Store gap = Store.gap("command-current");
        assertEquals("command-current", runtime(gap).begin(
                gap.gapCommand("command-current", NOW.plusSeconds(40)))
                .delivery().sourceCommandId());

        Store decision = Store.decision("command-current");
        assertEquals("command-current", runtime(decision).begin(
                decision.decisionCommand(
                        "command-current", NOW.plusSeconds(41)))
                .delivery().sourceCommandId());
        ChainDeliveryException old = assertThrows(
                ChainDeliveryException.class, () -> runtime(
                        Store.decision("command-current")).begin(
                        Store.decision("command-current").decisionCommand(
                                "command-root", NOW.plusSeconds(42))));
        assertEquals(ChainDeliveryException.Code.SOURCE_INVALID, old.code());
    }

    @Test
    void rejectsForgedCandidateValidationAndPublishOutcomeRefs() {
        List<AnswerPayload.FinalDelivery> forgeries = List.of(
                finalPayload(List.of("41", "candidate-1"),
                        "validation-1", "publish-receipt-1"),
                finalPayload(List.of(ChainIdentity.candidateArtifactRef(41),
                                "forged-candidate"),
                        "validation-1", "publish-receipt-1"),
                finalPayload(List.of(ChainIdentity.candidateArtifactRef(41),
                                "candidate-1"),
                        "forged-validation", "publish-receipt-1"),
                finalPayload(List.of(ChainIdentity.candidateArtifactRef(41),
                                "candidate-1"),
                        "validation-1", "forged-publish"));
        for (AnswerPayload.FinalDelivery forgedPayload : forgeries) {
            Store store = Store.finalDelivery(forgedPayload);
            ChainDeliveryException failure = assertThrows(
                    ChainDeliveryException.class,
                    () -> runtime(store).begin(store.finalCommand(
                            NOW.plusSeconds(4))));
            assertEquals(ChainDeliveryException.Code.SOURCE_INVALID,
                    failure.code());
            assertTrue(store.deliveries.isEmpty());
        }
    }

    @Test
    void deliveryFailureIsTerminalAfterThreeAttemptsAndNeverMutatesOutcome() {
        Store store = Store.finalDelivery(finalPayload(
                List.of(ChainIdentity.candidateArtifactRef(41), "candidate-1"),
                "validation-1", "publish-receipt-1"));
        ChainPersistenceRecords.TaskOutcomeRecord original = store.outcome;
        ChainDeliveryRuntime runtime = runtime(store);
        ChainDeliveryRuntime.Started started = runtime.begin(
                store.finalCommand(NOW.plusSeconds(5)));

        ChainDeliveryRuntime.Attempted first = runtime.attempt(
                "task-1", started.delivery().deliveryId(),
                NOW.plusSeconds(6));
        ChainDeliveryRuntime.Attempted second = runtime.attempt(
                "task-1", started.delivery().deliveryId(),
                NOW.plusSeconds(7));
        ChainDeliveryRuntime.Attempted third = runtime.attempt(
                "task-1", started.delivery().deliveryId(),
                NOW.plusSeconds(8));

        assertEquals(ChainDeliveryStatus.RETRYING, first.event().eventKind());
        assertEquals(ChainDeliveryStatus.RETRYING, second.event().eventKind());
        assertEquals(ChainDeliveryStatus.DELIVERY_FAILED,
                third.event().eventKind());
        assertEquals(3, third.event().attemptNo());
        assertSame(original, store.outcome);
        assertEquals(1, store.reservations);
        assertEquals(4, store.deliveryEvents.size());
        ChainDeliveryRuntime.Attempted terminalReplay = runtime.attempt(
                "task-1", started.delivery().deliveryId(),
                NOW.plusSeconds(100));
        assertTrue(terminalReplay.replayed());
        assertEquals(third.event(), terminalReplay.event());
    }

    @Test
    void successfulMessageAttemptCommitsSucceededWithoutBodyCopy() {
        Store store = Store.direct();
        store.succeedAttempt = true;
        ChainDeliveryRuntime runtime = runtime(store);
        ChainDeliveryRuntime.Started started = runtime.begin(
                store.directCommand("command-root", NOW.plusSeconds(10)));

        ChainDeliveryRuntime.Attempted attempted = runtime.attempt(
                "task-1", started.delivery().deliveryId(),
                NOW.plusSeconds(11));

        assertEquals(ChainDeliveryStatus.SUCCEEDED,
                attempted.event().eventKind());
        assertTrue(store.messageInserted);
        assertEquals(store.content.contentId(),
                store.lastAttempt.answerContentId());
        assertNotEquals(BODY, store.lastAttempt.answerBodySha256());
    }

    @Test
    void attemptRequiresExactOfficialDeliveryBindingAndFixedFailureCode() {
        Store interrupted = Store.direct();
        ChainDeliveryRuntime interruptedRuntime = runtime(interrupted);
        var started = interruptedRuntime.begin(interrupted.directCommand(
                "command-root", NOW.plusSeconds(20)));
        interrupted.proposalStates.remove(1);
        ChainDeliveryException missingBinding = assertThrows(
                ChainDeliveryException.class,
                () -> interruptedRuntime.attempt("task-1",
                        started.delivery().deliveryId(),
                        NOW.plusSeconds(21)));
        assertEquals(ChainDeliveryException.Code.PROPOSAL_INVALID,
                missingBinding.code());

        Store forgedError = Store.direct();
        forgedError.attemptError = "FORGED_DELIVERY_ERROR";
        ChainDeliveryRuntime forgedRuntime = runtime(forgedError);
        var forgedStarted = forgedRuntime.begin(forgedError.directCommand(
                "command-root", NOW.plusSeconds(22)));
        ChainDeliveryException wrongError = assertThrows(
                ChainDeliveryException.class,
                () -> forgedRuntime.attempt("task-1",
                        forgedStarted.delivery().deliveryId(),
                        NOW.plusSeconds(23)));
        assertEquals(ChainDeliveryException.Code.MESSAGE_ATTEMPT_INVALID,
                wrongError.code());
    }

    @Test
    void completedOutcomeCannotUseStatusAndOldGapCannotDeliverLate() {
        var statusPayload = new AnswerPayload.StatusOrFailure(
                "outcome-1", ChainIdentity.NONE, "outcome-1", BODY);
        Store completedStatus = new Store(statusPayload, true);
        ChainDeliveryException completedFailure = assertThrows(
                ChainDeliveryException.class,
                () -> runtime(completedStatus).begin(
                        completedStatus.finalCommand(NOW.plusSeconds(30))));
        assertEquals(ChainDeliveryException.Code.SOURCE_INVALID,
                completedFailure.code());

        Store answeredGap = Store.gap();
        answeredGap.addGapEvent(ChainPendingItemStatus.RESPONSE_RECEIVED);
        ChainDeliveryException lateQuestion = assertThrows(
                ChainDeliveryException.class,
                () -> runtime(answeredGap).begin(
                        answeredGap.gapCommand(NOW.plusSeconds(31))));
        assertEquals(ChainDeliveryException.Code.SOURCE_INVALID,
                lateQuestion.code());
    }

    private static boolean hasRawBodyComponent(Class<?> type) {
        return java.util.Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .map(String::toLowerCase)
                .anyMatch(value -> value.equals("body")
                        || value.equals("answer")
                        || value.endsWith("body"));
    }

    private static AnswerPayload.FinalDelivery finalPayload(
            List<String> artifactRefs,
            String validationRef,
            String publishRef) {
        return new AnswerPayload.FinalDelivery(
                "outcome-1", artifactRefs, validationRef, publishRef, BODY);
    }

    private static ChainDeliveryRuntime runtime(Store store) {
        return new ChainDeliveryRuntime(
                store, store, store, store, store, store, store,
                ignored -> ChainRuntimePolicy.current());
    }

    private static ChainPersistenceRecords.CommandRecord command(
            String commandId, String resultTaskId) {
        return new ChainPersistenceRecords.CommandRecord(
                commandId, 3L, 7L, "client-" + commandId,
                ChainInstructionRelation.INITIAL, null, null, null, SHA,
                9L, 10L, resultTaskId, "result-event-" + commandId,
                "instruction-" + commandId, ChainCommandStatus.COMMITTED,
                null, NOW.minusSeconds(3), NOW.minusSeconds(2));
    }

    private static ChainPersistenceRecords.CanonicalJson json(String value) {
        return new ChainPersistenceRecords.CanonicalJson(1, sha(value), value);
    }

    private static String sha(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class Store implements
            ChainFoundationRepository,
            ChainWorkflowRepository,
            ChainFinalizationRepository,
            ChainModelRepository,
            ChainDeliveryWriter,
            ChainDeliveryMessagePort,
            ChainRouteRuntime.ProposalOfficialBinder {
        private final ChainPersistenceRecords.CommandRecord rootCommand =
                command("command-root", "task-1");
        private ChainPersistenceRecords.CommandRecord otherCommand;
        private final ChainPersistenceRecords.TaskRecord task =
                new ChainPersistenceRecords.TaskRecord(
                        "task-1", "command-root", "instruction-1", null,
                        3L, 7L, 9L, 10L, "client-command-root", SHA,
                        null, null, 0, NOW.minusSeconds(2));
        private final ChainPersistenceRecords.InstructionRecord instruction =
                new ChainPersistenceRecords.InstructionRecord(
                        "instruction-1", "command-root", 7L, "task-1",
                        10L, SHA, "message-identity-1",
                        ChainInstructionRelation.INITIAL, null, null, SHA,
                        NOW.minusSeconds(2));
        private ChainPersistenceRecords.InstructionRecord currentInstruction;
        private final List<ChainPersistenceRecords
                .TaskInstructionBindingRecord> instructionBindings =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.AuthorityEventRecord> events =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.ProposalStateEventRecord>
                proposalStates = new ArrayList<>();
        private final List<ChainPersistenceRecords.DeliveryRecord> deliveries =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.DeliveryEventRecord>
                deliveryEvents = new ArrayList<>();
        private final ChainPersistenceRecords.RouteDecisionRecord route;
        private final ChainPersistenceRecords.TaskOutcomeRecord outcome;
        private final ChainPersistenceRecords.ContentRecord content;
        private final ChainPersistenceRecords.ModelProposalRecord proposal;
        private final AnswerPayload payload;
        private ChainPersistenceRecords.PendingItemRecord pendingItem;
        private ChainPersistenceRecords.ReviewDecisionRecord reviewDecision;
        private final List<ChainPersistenceRecords.PendingItemEventRecord>
                pendingItemEvents = new ArrayList<>();
        private int reservations;
        private boolean succeedAttempt;
        private boolean messageInserted;
        private String attemptError =
                "CHAIN_DELIVERY_MESSAGE_WRITE_FAILED";
        private ChainDeliveryMessagePort.AttemptCommand lastAttempt;

        private Store(AnswerPayload payload, boolean finalDelivery) {
            this.payload = payload;
            this.route = finalDelivery ? null
                    : new ChainPersistenceRecords.RouteDecisionRecord(
                    "route-1", "task-1", "event-route-1", "instruction-1",
                    "planner-proposal-1",
                    ChainPersistenceRecords.RouteDecisionType.INITIAL, 0,
                    ChainExecutionMode.DIRECT, "direct",
                    json("{\"specification\":\"answer the question\"}"),
                    json("[]"), json("[\"instruction-1\"]"),
                    false, false, false, false,
                    null, null, null, NOW.minusSeconds(1));
            this.outcome = finalDelivery ? outcome() : null;
            String invocationId = finalDelivery
                    ? "answer-invocation-final" : "answer-invocation-direct";
            this.content = new ChainPersistenceRecords.ContentRecord(
                    "answer-content-1", "task-1", invocationId,
                    ChainContentKind.ANSWER_BODY, BODY, sha(BODY),
                    "text/plain", NOW);
            this.proposal = new ChainPersistenceRecords.ModelProposalRecord(
                    "answer-proposal-1", "task-1", invocationId, 1,
                    ChainRole.ANSWER, payload.kind(),
                    json(ChainDeliveryCanonical.materializedPayload(
                            payload, content.contentId())),
                    json("[]"), "ANSWER_BODY", content.contentId(), NOW);
            if (route != null) {
                addAuthority(route.eventId(), "ROUTE_DECISION", null,
                        sha(route.routeDecisionId() + "\0" + route.taskId()
                                + "\0" + route.instructionId() + "\0"
                                + route.proposalId() + "\0"
                                + route.route().name() + "\0"
                                + route.decisionOrdinal()), route.createdAt());
            }
            if (outcome != null) {
                addAuthority(outcome.eventId(), "TASK_OUTCOME",
                        outcome.sourceDecisionId(),
                        sha(ChainTaskOutcomeStatus.COMPLETED + "\0"
                                + outcome.sourceDecisionId()),
                        outcome.createdAt());
            }
            ChainPersistenceRecords.ProposalStateEventRecord accepted =
                    new ChainPersistenceRecords.ProposalStateEventRecord(
                            proposal.proposalId(), 1, "task-1",
                            "event-proposal-accepted", ChainProposalState.ACCEPTED,
                            null, null, NOW);
            proposalStates.add(accepted);
            addAuthority(accepted.eventId(), "PROPOSAL_ACCEPTED", null,
                    SHA, accepted.committedAt());
        }

        static Store direct() {
            return direct(new AnswerPayload.DirectAnswer(
                    "route-1", "answer the question", BODY,
                    List.of("instruction-1")));
        }

        static Store direct(AnswerPayload.DirectAnswer payload) {
            return new Store(payload, false);
        }

        static Store finalDelivery(AnswerPayload.FinalDelivery payload) {
            return new Store(payload, true);
        }

        static Store gap() {
            return gap(null);
        }

        static Store gap(String currentCommandId) {
            Store store = new Store(new AnswerPayload.UserQuestion(
                    "gap-1", BODY), false);
            if (currentCommandId != null) {
                store.bindCurrentCommand(currentCommandId);
            }
            store.pendingItem = new ChainPersistenceRecords.PendingItemRecord(
                    "gap-1", "task-1", "event-gap-1",
                    store.proposal.proposalId(),
                    ChainPendingItemType.USER_INFORMATION, SHA, json("[]"),
                    null, "Provide value", "plain text",
                    ChainRole.PLANNER, ChainRole.EXECUTOR, json("{}"),
                    SHA, NOW.minusSeconds(1));
            store.addAuthority(store.pendingItem.eventId(), "PENDING_ITEM",
                    null, store.pendingItem.gapIdentitySha256(),
                    store.pendingItem.createdAt());
            return store;
        }

        static Store decision(String currentCommandId) {
            Store store = new Store(new AnswerPayload.StatusOrFailure(
                    "candidate-1", "review-1", "review-1", BODY), false);
            store.bindCurrentCommand(currentCommandId);
            store.reviewDecision = new ChainPersistenceRecords
                    .ReviewDecisionRecord(
                    "review-1", "task-1", "event-review-1",
                    "reflector-proposal-1", "CANDIDATE_STEP_RESULT",
                    "candidate-1", io.paperagent.v2.chain.ChainProposalKind
                    .REFLECTOR_TASK_FAILED, "failed", json("[]"), SHA, NOW);
            store.addAuthority(store.reviewDecision.eventId(),
                    "REVIEW_DECISION", null,
                    sha(store.reviewDecision.proposalId() + "\0"
                            + store.reviewDecision.reviewObjectType() + "\0"
                            + store.reviewDecision.reviewObjectId() + "\0"
                            + store.reviewDecision.versionFenceSha256()),
                    store.reviewDecision.createdAt());
            return store;
        }

        private void bindCurrentCommand(String commandId) {
            otherCommand = new ChainPersistenceRecords.CommandRecord(
                    commandId, 3L, 7L, "client-" + commandId,
                    ChainInstructionRelation.SUPPLEMENT, "task-1",
                    "client-command-root", null, SHA, 9L, 11L,
                    "task-1", "result-event-" + commandId,
                    "instruction-current", ChainCommandStatus.COMMITTED,
                    null, NOW.minusSeconds(1), NOW);
            currentInstruction = new ChainPersistenceRecords.InstructionRecord(
                    "instruction-current", commandId, 7L, "task-1", 11L,
                    SHA, "message-current", ChainInstructionRelation.SUPPLEMENT,
                    "instruction-1", null, SHA, NOW);
            var binding = new ChainPersistenceRecords
                    .TaskInstructionBindingRecord(
                    "task-1", "event-binding-current",
                    currentInstruction.instructionId(), 2L,
                    ChainPersistenceRecords.BindingRole.ORIGIN, NOW);
            instructionBindings.add(binding);
            addAuthority(binding.eventId(), "TASK_INSTRUCTION", null,
                    SHA, binding.createdAt());
        }

        ChainDeliveryRuntime.BeginCommand directCommand(
                String commandId, Instant committedAt) {
            return new ChainDeliveryRuntime.BeginCommand(
                    "task-1", commandId, proposal.proposalId(),
                    new ChainDeliveryRuntime.RouteSource("route-1"),
                    payload, committedAt);
        }

        ChainDeliveryRuntime.BeginCommand finalCommand(Instant committedAt) {
            return new ChainDeliveryRuntime.BeginCommand(
                    "task-1", "command-root", proposal.proposalId(),
                    new ChainDeliveryRuntime.TaskOutcomeSource("outcome-1"),
                    payload, committedAt);
        }

        ChainDeliveryRuntime.BeginCommand gapCommand(Instant committedAt) {
            return gapCommand("command-root", committedAt);
        }

        ChainDeliveryRuntime.BeginCommand gapCommand(
                String commandId, Instant committedAt) {
            return new ChainDeliveryRuntime.BeginCommand(
                    "task-1", commandId, proposal.proposalId(),
                    new ChainDeliveryRuntime.GapSource("gap-1"),
                    payload, committedAt);
        }

        ChainDeliveryRuntime.BeginCommand decisionCommand(
                String commandId, Instant committedAt) {
            return new ChainDeliveryRuntime.BeginCommand(
                    "task-1", commandId, proposal.proposalId(),
                    new ChainDeliveryRuntime.DecisionSource("review-1"),
                    payload, committedAt);
        }

        void addGapEvent(ChainPendingItemStatus status) {
            ChainPersistenceRecords.PendingItemEventRecord event =
                    new ChainPersistenceRecords.PendingItemEventRecord(
                    pendingItem.gapId(), 1, status, "task-1",
                    "event-gap-" + status.name().toLowerCase(),
                    status == ChainPendingItemStatus.RESPONSE_RECEIVED
                            ? "answer-instruction-1" : null,
                    null, null, json("{}"), NOW);
            pendingItemEvents.add(event);
            addAuthority(event.eventId(),
                    "PENDING_ITEM_" + status.name(), null,
                    sha(event.gapId() + "\0" + event.responseRound()
                            + "\0" + event.eventKind() + "\0"
                            + (event.answerInstructionId() == null ? ""
                            : event.answerInstructionId()) + "\0"),
                    event.committedAt());
        }

        private static ChainPersistenceRecords.TaskOutcomeRecord outcome() {
            return new ChainPersistenceRecords.TaskOutcomeRecord(
                    "outcome-1", "task-1", "event-outcome-1",
                    "command-root", ChainTaskOutcomeStatus.COMPLETED,
                    "instruction-1", "task-frame-1", "plan-1", "revision-1",
                    json("{\"covered\":true}"), json("[\"accepted-1\"]"),
                    41L, "candidate-1", "validation-1",
                    "publish-operation-1", "project-v2", 2L,
                    "publish-receipt-1", json("[]"), json("[]"), json("[]"),
                    null, null, "transition-finalization", NOW.minusSeconds(1));
        }

        @Override public long reserveAssistantMessage(Reservation command) {
            reservations++;
            return 700L;
        }

        @Override public AttemptSubmission attempt(AttemptCommand command) {
            lastAttempt = command;
            ChainDeliveryStatus status = succeedAttempt
                    ? ChainDeliveryStatus.SUCCEEDED
                    : command.terminalOnFailure()
                    ? ChainDeliveryStatus.DELIVERY_FAILED
                    : ChainDeliveryStatus.RETRYING;
            String error = succeedAttempt ? null : attemptError;
            String eventId = succeedAttempt
                    ? command.successEventId() : command.failureEventId();
            ChainPersistenceRecords.DeliveryEventRecord event =
                    new ChainPersistenceRecords.DeliveryEventRecord(
                            command.deliveryId(), command.eventSequence(),
                            command.taskId(), eventId, status,
                            command.attemptNo(), error,
                            command.runtimePolicyVersion(), command.committedAt());
            String source = command.deliveryId() + "\0" + command.attemptNo()
                    + "\0" + status
                    + (error == null ? "" : "\0" + error);
            var appended = appendDeliveryEvent(
                    new ChainPersistenceRecords.AuthoritativeFact<>(
                            new ChainPersistenceRecords.AuthorityEventRequest(
                                    eventId, command.taskId(),
                                    "DELIVERY_" + status, null,
                                    sha(source), command.committedAt()), event));
            if (succeedAttempt) messageInserted = true;
            return new AttemptSubmission(appended.fact(), appended.replayed());
        }

        @Override public void bindOfficialResult(
                String taskId, String proposalId,
                String authorityType, String authorityRef) {
            if (proposalStates.size() == 2) {
                assertEquals(authorityRef,
                        proposalStates.get(1).officialAuthorityRef());
                return;
            }
            ChainPersistenceRecords.ProposalStateEventRecord bound =
                    new ChainPersistenceRecords.ProposalStateEventRecord(
                            proposalId, 2, taskId, "event-proposal-bound",
                            ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                            authorityType, authorityRef, NOW.plusSeconds(1));
            proposalStates.add(bound);
            addAuthority(bound.eventId(),
                    "PROPOSAL_REPLACED_BY_OFFICIAL_RESULT", null,
                    SHA, bound.committedAt());
        }

        @Override public ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.DeliveryRecord> appendDelivery(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.DeliveryRecord> requested) {
            ChainPersistenceRecords.DeliveryRecord existing = deliveries.stream()
                    .filter(value -> value.deliveryId().equals(
                            requested.fact().deliveryId()))
                    .findFirst().orElse(null);
            boolean replayed = existing != null;
            if (existing == null) {
                var value = requested.fact();
                Instant storedAt = value.createdAt().plusMillis(1);
                existing = new ChainPersistenceRecords.DeliveryRecord(
                        value.deliveryId(), value.taskId(), value.eventId(),
                        value.sourceCommandId(), value.routeDecisionId(),
                        value.taskOutcomeId(), value.gapId(), value.decisionId(),
                        value.answerContentId(), value.assistantMessageId(),
                        storedAt);
                deliveries.add(existing);
                addAuthority(new ChainPersistenceRecords.AuthorityEventRequest(
                        requested.event().eventId(),
                        requested.event().taskId(),
                        requested.event().eventType(),
                        requested.event().transitionId(),
                        requested.event().sourceIdentitySha256(), storedAt));
            }
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    event(existing.eventId()), existing, replayed);
        }

        @Override public ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.DeliveryEventRecord>
                appendDeliveryEvent(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.DeliveryEventRecord> requested) {
            ChainPersistenceRecords.DeliveryEventRecord existing =
                    deliveryEvents.stream()
                            .filter(value -> value.eventSequence()
                                    == requested.fact().eventSequence()
                                    && value.deliveryId().equals(
                                    requested.fact().deliveryId()))
                            .findFirst().orElse(null);
            boolean replayed = existing != null;
            if (existing == null) {
                var value = requested.fact();
                Instant storedAt = value.committedAt().plusMillis(1);
                existing = new ChainPersistenceRecords.DeliveryEventRecord(
                        value.deliveryId(), value.eventSequence(),
                        value.taskId(), value.eventId(), value.eventKind(),
                        value.attemptNo(), value.errorCode(),
                        value.runtimePolicyVersion(), storedAt);
                deliveryEvents.add(existing);
                addAuthority(new ChainPersistenceRecords.AuthorityEventRequest(
                        requested.event().eventId(),
                        requested.event().taskId(),
                        requested.event().eventType(),
                        requested.event().transitionId(),
                        requested.event().sourceIdentitySha256(), storedAt));
            }
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    event(existing.eventId()), existing, replayed);
        }

        private void addAuthority(
                ChainPersistenceRecords.AuthorityEventRequest request) {
            addAuthority(request.eventId(), request.eventType(),
                    request.transitionId(), request.sourceIdentitySha256(),
                    request.committedAt());
        }

        private void addAuthority(
                String eventId, String type, String transitionId,
                String sourceDigest, Instant committedAt) {
            events.add(new ChainPersistenceRecords.AuthorityEventRecord(
                    eventId, "task-1", events.size() + 1L, type,
                    transitionId, sourceDigest, committedAt));
        }

        private ChainPersistenceRecords.AuthorityEventRecord event(String id) {
            return events.stream().filter(value -> id.equals(value.eventId()))
                    .findFirst().orElseThrow();
        }

        @Override public Optional<ChainPersistenceRecords.CommandRecord>
                findCommand(long userId, long sessionId, String clientRequestId) {
            return Optional.empty();
        }

        @Override public Optional<ChainPersistenceRecords.CommandRecord>
                findCommand(String commandId) {
            if (rootCommand.commandId().equals(commandId)) {
                return Optional.of(rootCommand);
            }
            return otherCommand != null
                    && otherCommand.commandId().equals(commandId)
                    ? Optional.of(otherCommand) : Optional.empty();
        }

        @Override public Optional<ChainPersistenceRecords.TaskRecord> findTask(
                String taskId) {
            return task.taskId().equals(taskId)
                    ? Optional.of(task) : Optional.empty();
        }

        @Override public Optional<ChainPersistenceRecords.InstructionRecord>
                findInstruction(String instructionId) {
            if (instruction.instructionId().equals(instructionId)) {
                return Optional.of(instruction);
            }
            return currentInstruction != null
                    && currentInstruction.instructionId().equals(instructionId)
                    ? Optional.of(currentInstruction) : Optional.empty();
        }

        @Override public List<ChainPersistenceRecords.TaskInstructionBindingRecord>
                findTaskInstructions(String taskId, long cut) {
            return List.copyOf(instructionBindings);
        }

        @Override public List<ChainPersistenceRecords.AuthorityEventRecord>
                findAuthorityEvents(String taskId, long cut) {
            return events.stream()
                    .filter(value -> value.eventSequence() <= cut).toList();
        }

        @Override public long highestAuthorityEventSequence(String taskId) {
            return events.size();
        }

        @Override public Optional<ChainPersistenceRecords.ModelInvocationRecord>
                findInvocation(String invocationId) { return Optional.empty(); }

        @Override public long highestInvocationOrdinal(String taskId) {
            return 0;
        }

        @Override public List<ChainPersistenceRecords.ModelInvocationRecord>
                findInvocations(String taskId, long cut) { return List.of(); }

        @Override public int highestProviderAttemptNo(String invocationId) {
            return 0;
        }

        @Override public List<ChainPersistenceRecords.ProviderAttemptRecord>
                findProviderAttempts(String invocationId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.ContentRecord> findContents(
                String invocationId) { return List.of(content); }

        @Override public Optional<ChainPersistenceRecords.ContentRecord>
                findContent(String contentId) {
            return content.contentId().equals(contentId)
                    ? Optional.of(content) : Optional.empty();
        }

        @Override public Optional<ChainPersistenceRecords.ModelProposalRecord>
                findProposal(String proposalId) {
            return proposal.proposalId().equals(proposalId)
                    ? Optional.of(proposal) : Optional.empty();
        }

        @Override public Optional<ChainPersistenceRecords.ModelProposalRecord>
                findProposalByInvocation(String invocationId) {
            return proposal.invocationId().equals(invocationId)
                    ? Optional.of(proposal) : Optional.empty();
        }

        @Override public List<ChainPersistenceRecords.ProposalStateEventRecord>
                findProposalStateEvents(String proposalId) {
            return List.copyOf(proposalStates);
        }

        @Override public Optional<ChainPersistenceRecords
                .FinalizationReadinessRecord> findReadinessById(String id) {
            return Optional.empty();
        }

        @Override public Optional<ChainPersistenceRecords
                .FinalizationReadinessRecord> findReadinessByScope(String id) {
            return Optional.empty();
        }

        @Override public List<ChainPersistenceRecords.FinalizationReadinessRecord>
                findReadiness(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.FinalizationCheckRecord>
                findFinalizationChecks(String readinessId) { return List.of(); }

        @Override public Optional<ChainPersistenceRecords.TaskOutcomeRecord>
                findTaskOutcome(String taskId) {
            return Optional.ofNullable(outcome);
        }

        @Override public List<ChainPersistenceRecords.DeliveryRecord>
                findDeliveries(String taskId) { return List.copyOf(deliveries); }

        @Override public List<ChainPersistenceRecords.DeliveryRecord>
                findIncompleteDeliveries(String taskId) {
            return List.copyOf(deliveries);
        }

        @Override public List<ChainPersistenceRecords.DeliveryEventRecord>
                findDeliveryEvents(String deliveryId) {
            return deliveryEvents.stream()
                    .filter(value -> deliveryId.equals(value.deliveryId()))
                    .toList();
        }

        @Override public Optional<ChainPersistenceRecords.TransitionRecord>
                findTransition(String id) { return Optional.empty(); }

        @Override public List<ChainPersistenceRecords.TransitionStageRecord>
                findTransitionStages(String id) { return List.of(); }

        @Override public List<ChainPersistenceRecords.TransitionRecord>
                findIncompleteTransitions(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.RouteDecisionRecord>
                findRouteDecisions(String taskId) {
            return route == null ? List.of() : List.of(route);
        }

        @Override public List<ChainPersistenceRecords.PlanBindingRecord>
                findPlanBindings(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.CandidateStepResultRecord>
                findCandidateStepResults(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.ReviewDecisionRecord>
                findReviewDecisions(String taskId) {
            return reviewDecision == null
                    ? List.of() : List.of(reviewDecision);
        }

        @Override public List<ChainPersistenceRecords.AcceptedResultRecord>
                findAcceptedResults(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.ResultApplicabilityRecord>
                findApplicabilityDecisions(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.PendingItemRecord>
                findPendingItems(String taskId) {
            return pendingItem == null ? List.of() : List.of(pendingItem);
        }

        @Override public List<ChainPersistenceRecords.PendingItemRecord>
                findOpenPendingItems(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.PendingItemEventRecord>
                findPendingItemEvents(String gapId) {
            return List.copyOf(pendingItemEvents);
        }

        @Override public List<ChainPersistenceRecords.PermissionDecisionRecord>
                findPermissionDecisions(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.ActionBindingRecord>
                findActionBindings(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.ActionBindingRecord>
                findInFlightActions(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.WorkspaceCandidateRecord>
                findWorkspaceCandidates(String taskId) { return List.of(); }
    }
}
