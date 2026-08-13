package io.paperagent.v2.chain.instruction;

import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainCommandStatus;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainInstructionWriter;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainTaskOutcomeWriter;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.review.ChainTaskOutcomeRuntime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainInstructionStateTest {
    private static final Instant NOW = Instant.parse("2026-08-07T04:00:00Z");
    private static final String SHA = "a".repeat(64);

    @Test
    void appendsAnImmutableInstructionChainAndClosesTheSideEffectGateForNewInstructions() {
        Store store = new Store();
        ChainInstructionRuntime runtime = new ChainInstructionRuntime(store, store, store);
        ChainInstructionStateReader reader = new ChainInstructionStateReader(store, store, store);
        var initial = appendRequest("instruction-1", 1, ChainInstructionRelation.INITIAL,
                null, null, "event-instruction-1", SHA);

        ChainInstructionRuntime.AppendOutcome first = runtime.append(initial);
        assertFalse(first.replayed());
        assertEquals(ChainInstructionState.Gate.PLANNING, reader.read("task-1").gate());
        assertFalse(reader.read("task-1").allowsNewSideEffects());

        assertTrue(runtime.append(initial).replayed());
        assertEquals(1, store.instructions.size());
        assertEquals(1, store.bindings.size());

        var supplement = appendRequest("instruction-2", 2,
                ChainInstructionRelation.SUPPLEMENT, "instruction-1", null,
                "event-instruction-2", "b".repeat(64));
        runtime.append(supplement);
        ChainInstructionState paused = reader.read("task-1");
        assertEquals(List.of("instruction-1", "instruction-2"), paused.entries().stream()
                .map(entry -> entry.instruction().instructionId()).toList());
        assertEquals(ChainInstructionState.Gate.PAUSED_FOR_DISPOSITION, paused.gate());
        assertFalse(paused.allowsNewSideEffects());

        store.addPlanBinding(planBinding("instruction-2", "plan-binding-2"));
        ChainInstructionState resumed = reader.read("task-1");
        assertEquals(ChainInstructionState.Gate.SIDE_EFFECTS_ALLOWED, resumed.gate());
        assertTrue(resumed.allowsNewSideEffects());

        runtime.append(appendRequest("instruction-3", 3, ChainInstructionRelation.CANCEL,
                "instruction-2", null, "event-instruction-3", "b".repeat(64)));
        assertEquals(ChainInstructionState.Gate.CANCELLED, reader.read("task-1").gate());
        assertFalse(reader.read("task-1").allowsNewSideEffects());
    }

    @Test
    void rejectsReplayMutationAndNonContiguousBinding() {
        Store store = new Store();
        ChainInstructionRuntime runtime = new ChainInstructionRuntime(store, store, store);
        var initial = appendRequest("instruction-1", 1, ChainInstructionRelation.INITIAL,
                null, null, "event-instruction-1", SHA);
        runtime.append(initial);

        ChainPersistenceRecords.InstructionRecord changed = new ChainPersistenceRecords.InstructionRecord(
                "instruction-1", "command-changed", 7, "task-1", 10L,
                SHA, "message-1", ChainInstructionRelation.INITIAL,
                null, null, SHA, NOW);
        ChainInstructionException replay = assertThrows(ChainInstructionException.class,
                () -> runtime.append(new ChainInstructionRuntime.AppendRequest(
                        "task-1", changed, initial.binding())));
        assertEquals(ChainInstructionException.Code.INSTRUCTION_REPLAY_MISMATCH, replay.code());

        ChainInstructionException sequence = assertThrows(ChainInstructionException.class,
                () -> runtime.append(appendRequest(
                        "instruction-3", 3, ChainInstructionRelation.SUPPLEMENT,
                        "instruction-1", null, "event-instruction-3", SHA)));
        assertEquals(ChainInstructionException.Code.BINDING_REPLAY_MISMATCH, sequence.code());
    }

    @Test
    void acceptsPersistenceTimestampPrecisionNormalization() {
        Store store = new Store();
        ChainInstructionRuntime runtime = new ChainInstructionRuntime(store, store, store);
        var base = appendRequest("instruction-1", 1,
                ChainInstructionRelation.INITIAL, null, null,
                "event-instruction-1", SHA);
        ChainPersistenceRecords.InstructionRecord nanos =
                new ChainPersistenceRecords.InstructionRecord(
                        base.instruction().instructionId(),
                        base.instruction().commandId(),
                        base.instruction().sessionId(),
                        base.instruction().originTaskId(),
                        base.instruction().messageId(),
                        base.instruction().bodySha256(),
                        base.instruction().messageIdentityKey(),
                        base.instruction().relationKind(),
                        base.instruction().parentInstructionId(),
                        base.instruction().answeredGapId(),
                        base.instruction().effectiveBoundaryDigest(),
                        NOW.plusNanos(999));
        ChainPersistenceRecords.TaskInstructionBindingRecord nanosBinding =
                new ChainPersistenceRecords.TaskInstructionBindingRecord(
                        base.binding().fact().taskId(), base.binding().fact().eventId(),
                        base.binding().fact().instructionId(),
                        base.binding().fact().taskInstructionSequence(),
                        base.binding().fact().relationRole(), NOW.plusNanos(999));
        ChainInstructionRuntime.AppendOutcome outcome = assertDoesNotThrow(
                () -> runtime.append(new ChainInstructionRuntime.AppendRequest(
                        "task-1", nanos,
                        new ChainPersistenceRecords.AuthoritativeFact<>(
                                base.binding().event(), nanosBinding))));
        assertEquals(NOW, outcome.instruction().createdAt());
    }

    @Test
    void answerToPendingItemRemainsPausedUntilTheFormalGapIsResolved() {
        Store store = new Store();
        ChainInstructionRuntime runtime = new ChainInstructionRuntime(store, store, store);
        ChainInstructionStateReader reader = new ChainInstructionStateReader(store, store, store);
        runtime.append(appendRequest("instruction-1", 1, ChainInstructionRelation.INITIAL,
                null, null, "event-instruction-1", SHA));
        store.addPlanBinding(planBinding("instruction-1", "plan-binding-1"));
        store.addPendingItem(pendingItem("gap-1"));
        store.addPendingEvent(pendingEvent(
                "gap-1", 0, ChainPendingItemStatus.PENDING, "pending-0", null));

        runtime.append(appendRequest("instruction-answer", 2,
                ChainInstructionRelation.ANSWER_TO_PENDING_ITEM, "instruction-1", "gap-1",
                "event-answer", SHA));
        store.addPendingEvent(pendingEvent(
                "gap-1", 1, ChainPendingItemStatus.RESPONSE_RECEIVED,
                "pending-response", "instruction-answer"));

        assertEquals(ChainInstructionState.Gate.PAUSED_FOR_PENDING_VALIDATION,
                reader.read("task-1").gate());
        assertFalse(reader.read("task-1").allowsNewSideEffects());

        store.addPendingEvent(pendingEvent(
                "gap-1", 1, ChainPendingItemStatus.RESOLVED,
                "pending-resolved", "instruction-answer"));
        ChainInstructionState resolved = reader.read("task-1");
        assertEquals(ChainInstructionState.Gate.SIDE_EFFECTS_ALLOWED, resolved.gate());
        assertTrue(resolved.allowsNewSideEffects());
    }

    @Test
    void historicalReaderRejectsAChainWithoutContiguousFormalBindings() {
        Store store = new Store();
        ChainPersistenceRecords.InstructionRecord instruction = instruction(
                "instruction-2", ChainInstructionRelation.INITIAL, null, null, SHA);
        store.instructions.put(instruction.instructionId(), instruction);
        ChainPersistenceRecords.TaskInstructionBindingRecord binding = binding(
                "instruction-2", 2, "event-instruction-2");
        store.bindings.add(binding);
        store.events.add(authorityEvent("event-instruction-2", 1));

        ChainInstructionException failure = assertThrows(ChainInstructionException.class,
                () -> new ChainInstructionStateReader(store, store, store).read("task-1"));

        assertEquals(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID, failure.code());
        ChainInstructionException appendFailure = assertThrows(
                ChainInstructionException.class,
                () -> new ChainInstructionRuntime(store, store, store).append(
                        appendRequest("instruction-3", 3,
                                ChainInstructionRelation.SUPPLEMENT,
                                "instruction-2", null,
                                "event-instruction-3", SHA)));
        assertEquals(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                appendFailure.code());
    }

    @Test
    void everyPendingItemClosesTheGlobalSideEffectGateUntilAUserGapResolves() {
        Store store = new Store();
        ChainInstructionRuntime runtime = new ChainInstructionRuntime(store, store, store);
        ChainInstructionStateReader reader = new ChainInstructionStateReader(store, store, store);
        runtime.append(appendRequest(
                "instruction-1", 1, ChainInstructionRelation.INITIAL,
                null, null, "event-instruction-1", SHA));
        store.addPlanBinding(planBinding("instruction-1", "plan-binding-1"));
        assertTrue(reader.read("task-1").allowsNewSideEffects());

        ChainPersistenceRecords.PendingItemRecord userGap = pendingItem(
                "gap-user", ChainPendingItemType.USER_INFORMATION);
        store.addPendingItem(userGap);
        assertEquals(ChainInstructionState.Gate.PAUSED_FOR_PENDING_VALIDATION,
                reader.read("task-1").gate());
        assertFalse(reader.read("task-1").allowsNewSideEffects());

        store.addPendingEvent(pendingEvent(
                userGap.gapId(), 1, ChainPendingItemStatus.RESPONSE_RECEIVED,
                "event-gap-user-response", "instruction-gap-answer"));
        assertFalse(reader.read("task-1").allowsNewSideEffects());

        store.addPendingEvent(pendingEvent(
                userGap.gapId(), 1, ChainPendingItemStatus.RESOLVED,
                "event-gap-user-resolved", "instruction-gap-answer"));
        assertTrue(reader.read("task-1").allowsNewSideEffects());

        ChainPersistenceRecords.PendingItemRecord rejected = pendingItem(
                "gap-rejected", ChainPendingItemType.USER_CHOICE);
        store.addPendingItem(rejected);
        store.addPendingEvent(pendingEvent(
                rejected.gapId(), 0, ChainPendingItemStatus.REJECTED,
                "event-gap-rejected-state", null));
        assertFalse(reader.read("task-1").allowsNewSideEffects());

        store.addPendingItem(pendingItem(
                "gap-second-blocker", ChainPendingItemType.USER_INFORMATION));
        ChainInstructionState multiple = reader.read("task-1");
        assertEquals(ChainInstructionState.Gate.PAUSED_FOR_PENDING_VALIDATION,
                multiple.gate());
        assertEquals("task-1", multiple.gateAuthorityRef());

        Store permissionStore = new Store();
        ChainInstructionRuntime permissionRuntime = new ChainInstructionRuntime(
                permissionStore, permissionStore, permissionStore);
        ChainInstructionStateReader permissionReader = new ChainInstructionStateReader(
                permissionStore, permissionStore, permissionStore);
        permissionRuntime.append(appendRequest(
                "instruction-1", 1, ChainInstructionRelation.INITIAL,
                null, null, "event-instruction-1", SHA));
        permissionStore.addPlanBinding(
                planBinding("instruction-1", "plan-binding-1"));
        ChainPersistenceRecords.PendingItemRecord permission = pendingItem(
                "gap-permission", ChainPendingItemType.PERMISSION);
        permissionStore.addPendingItem(permission);
        permissionStore.addPendingEvent(pendingEvent(
                permission.gapId(), 1, ChainPendingItemStatus.RESOLVED,
                "event-gap-permission-resolved", "instruction-gap-answer"));
        assertFalse(permissionReader.read("task-1").allowsNewSideEffects());
    }

    @Test
    void cancellationUsesOnlyTheTypedOutcomeCommandAndRejectsNonCancelInstruction() {
        Store store = new Store();
        ChainInstructionRuntime instructions = new ChainInstructionRuntime(
                store, store, store);
        ChainInstructionStateReader reader = new ChainInstructionStateReader(
                store, store, store);
        instructions.append(appendRequest(
                "instruction-1", 1, ChainInstructionRelation.INITIAL,
                null, null, "event-instruction-1", SHA));
        instructions.append(appendRequest(
                "instruction-cancel", 2, ChainInstructionRelation.CANCEL,
                "instruction-1", null, "event-instruction-cancel", SHA));
        String cancelRequestSha = "c".repeat(64);
        store.registerCancelCommand(
                "command-instruction-cancel", cancelRequestSha);
        ChainCancellationRuntime runtime = new ChainCancellationRuntime(
                store, reader, store);
        ChainCancellationRuntime.CancelRequest request =
                new ChainCancellationRuntime.CancelRequest(
                        "task-1", "instruction-cancel",
                        "command-instruction-cancel", cancelRequestSha,
                        "event-cancel-outcome", NOW.plusSeconds(20));

        ChainTaskOutcomeCommandPort.CancellationSubmission first =
                runtime.cancel(request);
        assertEquals(ChainTaskOutcomeStatus.CANCELLED,
                first.outcome().outcomeType());
        assertFalse(first.replayed());
        assertEquals(1, store.cancelOutcomeCalls);

        ChainTaskOutcomeCommandPort.CancellationSubmission replay =
                runtime.cancel(request);
        assertTrue(replay.replayed());
        assertEquals(first.outcome(), replay.outcome());
        assertEquals("instruction-cancel",
                replay.outcome().sourceDecisionId());

        store.returnWrongCancellationSource = true;
        ChainInstructionException wrongOutcomeSource = assertThrows(
                ChainInstructionException.class, () -> runtime.cancel(request));
        assertEquals(ChainInstructionException.Code.CANCEL_OUTCOME_INVALID,
                wrongOutcomeSource.code());

        Store invalid = new Store();
        ChainInstructionRuntime invalidInstructions = new ChainInstructionRuntime(
                invalid, invalid, invalid);
        invalidInstructions.append(appendRequest(
                "instruction-1", 1, ChainInstructionRelation.INITIAL,
                null, null, "event-instruction-1", SHA));
        invalidInstructions.append(appendRequest(
                "instruction-supplement", 2,
                ChainInstructionRelation.SUPPLEMENT,
                "instruction-1", null,
                "event-instruction-supplement", SHA));
        invalid.registerCancelCommand(
                "command-instruction-supplement", cancelRequestSha);
        ChainInstructionException rejected = assertThrows(
                ChainInstructionException.class,
                () -> new ChainCancellationRuntime(
                        invalid,
                        new ChainInstructionStateReader(
                                invalid, invalid, invalid),
                        invalid).cancel(new ChainCancellationRuntime.CancelRequest(
                        "task-1", "instruction-supplement",
                        "command-instruction-supplement", cancelRequestSha,
                        "event-invalid-cancel", NOW.plusSeconds(21))));
        assertEquals(ChainInstructionException.Code.CANCEL_SOURCE_INVALID,
                rejected.code());
    }

    private static ChainInstructionRuntime.AppendRequest appendRequest(
            String instructionId,
            long sequence,
            ChainInstructionRelation relation,
            String parentInstructionId,
            String answeredGapId,
            String eventId,
            String boundaryDigest) {
        ChainPersistenceRecords.InstructionRecord instruction = instruction(
                instructionId, relation, parentInstructionId, answeredGapId, boundaryDigest);
        ChainPersistenceRecords.TaskInstructionBindingRecord binding = binding(
                instructionId, sequence, eventId);
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        eventId, "task-1", "INSTRUCTION_BOUND", null, SHA, NOW);
        return new ChainInstructionRuntime.AppendRequest(
                "task-1", instruction,
                new ChainPersistenceRecords.AuthoritativeFact<>(event, binding));
    }

    private static ChainPersistenceRecords.InstructionRecord instruction(
            String instructionId,
            ChainInstructionRelation relation,
            String parentInstructionId,
            String answeredGapId,
            String boundaryDigest) {
        boolean cancel = relation == ChainInstructionRelation.CANCEL;
        return new ChainPersistenceRecords.InstructionRecord(
                instructionId, "command-" + instructionId, 7, "task-1",
                cancel ? null : (long) (10 + Math.abs(instructionId.hashCode() % 1000)),
                cancel ? null : SHA, "message-" + instructionId, relation,
                parentInstructionId, answeredGapId, boundaryDigest, NOW);
    }

    private static ChainPersistenceRecords.TaskInstructionBindingRecord binding(
            String instructionId, long sequence, String eventId) {
        return new ChainPersistenceRecords.TaskInstructionBindingRecord(
                "task-1", eventId, instructionId, sequence,
                ChainPersistenceRecords.BindingRole.ORIGIN, NOW);
    }

    private static ChainPersistenceRecords.AuthorityEventRecord authorityEvent(
            String eventId, long sequence) {
        return new ChainPersistenceRecords.AuthorityEventRecord(
                eventId, "task-1", sequence, "INSTRUCTION_BOUND", null, SHA, NOW);
    }

    private static ChainPersistenceRecords.PlanBindingRecord planBinding(
            String instructionId, String id) {
        return new ChainPersistenceRecords.PlanBindingRecord(
                id, "task-1", "event-" + id, instructionId,
                "route-1", "task-frame-1", "plan-1", "revision-1", 1,
                "STABLE_V2_PLAN", "plan-authority-1", SHA, null, NOW);
    }

    private static ChainPersistenceRecords.PendingItemRecord pendingItem(String gapId) {
        return pendingItem(gapId, ChainPendingItemType.USER_INFORMATION);
    }

    private static ChainPersistenceRecords.PendingItemRecord pendingItem(
            String gapId,
            ChainPendingItemType type) {
        return new ChainPersistenceRecords.PendingItemRecord(
                gapId, "task-1", "event-" + gapId, "proposal-" + gapId,
                type, SHA, json("[]"),
                type == ChainPendingItemType.PERMISSION ? "project.write" : null,
                "Which value?", "plain text", ChainRole.PLANNER, ChainRole.EXECUTOR,
                json("{}"), SHA, NOW);
    }

    private static ChainPersistenceRecords.PendingItemEventRecord pendingEvent(
            String gapId,
            int round,
            ChainPendingItemStatus status,
            String eventId,
            String answerInstructionId) {
        return new ChainPersistenceRecords.PendingItemEventRecord(
                gapId, round, status, "task-1", eventId, answerInstructionId,
                status == ChainPendingItemStatus.RESOLVED ? "invocation-1" : null,
                status == ChainPendingItemStatus.RESOLVED
                        ? io.paperagent.v2.chain.GapValidation.Outcome.RESOLVED : null,
                json("{}"), NOW.plusSeconds(status.ordinal()));
    }

    private static ChainPersistenceRecords.CanonicalJson json(String value) {
        return new ChainPersistenceRecords.CanonicalJson(1, sha(value), value);
    }

    private static String sha(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class Store implements
            ChainFoundationRepository, ChainWorkflowRepository,
            ChainFinalizationRepository, ChainInstructionWriter,
            ChainTaskOutcomeCommandPort, ChainTaskOutcomeWriter {
        private final ChainPersistenceRecords.TaskRecord task =
                new ChainPersistenceRecords.TaskRecord(
                        "task-1", "command-root", "instruction-1", null,
                        3, 7, 9, 10L, "client-root", SHA,
                        null, null, 0, NOW);
        private final Map<String, ChainPersistenceRecords.InstructionRecord> instructions =
                new LinkedHashMap<>();
        private final List<ChainPersistenceRecords.TaskInstructionBindingRecord> bindings =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.AuthorityEventRecord> events =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.PlanBindingRecord> planBindings =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.PendingItemRecord> pendingItems =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.PendingItemEventRecord> pendingEvents =
                new ArrayList<>();
        private final Map<String, ChainPersistenceRecords.CommandRecord> commands =
                new LinkedHashMap<>();
        private ChainPersistenceRecords.TaskOutcomeRecord cancellationOutcome;
        private int cancelOutcomeCalls;
        private boolean returnWrongCancellationSource;

        void registerCancelCommand(
                String commandId,
                String requestSha256) {
            commands.put(commandId, new ChainPersistenceRecords.CommandRecord(
                    commandId, 3, 7, "cancel-client",
                    ChainInstructionRelation.CANCEL,
                    "task-1", "client-root", null, requestSha256,
                    null, null, null, null, null,
                    ChainCommandStatus.RECEIVED, null, NOW, null));
        }

        void addPlanBinding(ChainPersistenceRecords.PlanBindingRecord value) {
            planBindings.add(value);
            addAuthority(value.eventId(), "PLAN_BINDING", value.createdAt());
        }

        void addPendingItem(ChainPersistenceRecords.PendingItemRecord value) {
            pendingItems.add(value);
            addAuthority(value.eventId(), "PENDING_ITEM", value.createdAt());
        }

        void addPendingEvent(ChainPersistenceRecords.PendingItemEventRecord value) {
            pendingEvents.add(value);
            addAuthority(value.eventId(),
                    "PENDING_ITEM_" + value.eventKind().name(),
                    value.committedAt());
        }

        private void addAuthority(
                String eventId,
                String eventType,
                Instant committedAt) {
            events.add(new ChainPersistenceRecords.AuthorityEventRecord(
                    eventId, "task-1", events.size() + 1L,
                    eventType, null, SHA, committedAt));
        }

        @Override public Optional<ChainPersistenceRecords.CommandRecord> findCommand(
                long userId, long sessionId, String clientRequestId) { return Optional.empty(); }
        @Override public Optional<ChainPersistenceRecords.CommandRecord> findCommand(
                String commandId) { return Optional.ofNullable(commands.get(commandId)); }
        @Override public Optional<ChainPersistenceRecords.TaskRecord> findTask(String taskId) {
            return task.taskId().equals(taskId) ? Optional.of(task) : Optional.empty();
        }
        @Override public Optional<ChainPersistenceRecords.InstructionRecord> findInstruction(
                String instructionId) { return Optional.ofNullable(instructions.get(instructionId)); }
        @Override public List<ChainPersistenceRecords.TaskInstructionBindingRecord> findTaskInstructions(
                String taskId, long sequenceCut) {
            return bindings.stream().filter(value -> value.taskId().equals(taskId)
                    && value.taskInstructionSequence() <= sequenceCut).toList();
        }
        @Override public List<ChainPersistenceRecords.AuthorityEventRecord> findAuthorityEvents(
                String taskId, long sequenceCut) {
            return events.stream().filter(value -> value.taskId().equals(taskId)
                    && value.eventSequence() <= sequenceCut).toList();
        }
        @Override public long highestAuthorityEventSequence(String taskId) { return events.size(); }

        @Override public ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.InstructionRecord>
                appendInstruction(ChainPersistenceRecords.InstructionRecord instruction) {
            instruction = new ChainPersistenceRecords.InstructionRecord(
                    instruction.instructionId(), instruction.commandId(),
                    instruction.sessionId(), instruction.originTaskId(),
                    instruction.messageId(), instruction.bodySha256(),
                    instruction.messageIdentityKey(), instruction.relationKind(),
                    instruction.parentInstructionId(), instruction.answeredGapId(),
                    instruction.effectiveBoundaryDigest(),
                    instruction.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            ChainPersistenceRecords.InstructionRecord existing = instructions.putIfAbsent(
                    instruction.instructionId(), instruction);
            return new ChainPersistenceRecords.AppendResult<>(
                    existing == null ? instruction : existing, existing != null);
        }

        @Override public ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.TaskInstructionBindingRecord> appendTaskInstructionBinding(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.TaskInstructionBindingRecord> requested) {
            ChainPersistenceRecords.TaskInstructionBindingRecord fact = requested.fact();
            fact = new ChainPersistenceRecords.TaskInstructionBindingRecord(
                    fact.taskId(), fact.eventId(), fact.instructionId(),
                    fact.taskInstructionSequence(), fact.relationRole(),
                    fact.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.TaskInstructionBindingRecord> normalized =
                    new ChainPersistenceRecords.AuthoritativeFact<>(
                    requested.event(), fact);
            ChainPersistenceRecords.TaskInstructionBindingRecord existing = bindings.stream()
                    .filter(value -> value.taskInstructionSequence()
                            == normalized.fact().taskInstructionSequence())
                    .findFirst().orElse(null);
            boolean replayed = existing != null;
            if (existing == null) {
                bindings.add(normalized.fact());
                addAuthority(normalized.event().eventId(),
                        normalized.event().eventType(),
                        normalized.event().committedAt());
                existing = normalized.fact();
            }
            String storedEventId = existing.eventId();
            ChainPersistenceRecords.AuthorityEventRecord event = events.stream()
                    .filter(value -> value.eventId().equals(storedEventId)).findFirst()
                    .orElseThrow();
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    event, existing, replayed);
        }

        @Override public ChainTaskOutcomeCommandPort.CancellationSubmission submitCancelled(
                ChainTaskOutcomeCommandPort.CancelledTaskOutcomeCommand command) {
            cancelOutcomeCalls++;
            ChainTaskOutcomeRuntime runtime = new ChainTaskOutcomeRuntime(
                    this, cancellationVerifier(command));
            ChainTaskOutcomeRuntime.OutcomeDraft draft =
                    new ChainTaskOutcomeRuntime.OutcomeDraft(
                            command.taskId(), command.eventId(),
                            command.sourceCommandId(), command.instructionId(),
                            null, null, null, json("[]"), json("[]"),
                            null, "NONE", "NONE", null, null, null, null,
                            json("[]"), json("[]"), json("[]"),
                            command.createdAt());
            ChainTaskOutcomeRuntime.CommitResult committed = runtime.commit(
                    new ChainTaskOutcomeRuntime.Cancelled(
                            draft, command.instructionId()));
            ChainPersistenceRecords.TaskOutcomeRecord returned =
                    returnWrongCancellationSource
                            ? withSourceDecision(
                            committed.outcome(), "wrong-instruction")
                            : committed.outcome();
            return new ChainTaskOutcomeCommandPort.CancellationSubmission(
                    returned, committed.replayed());
        }

        private ChainTaskOutcomeRuntime.FormalSourceVerifier cancellationVerifier(
                ChainTaskOutcomeCommandPort.CancelledTaskOutcomeCommand expected) {
            return new ChainTaskOutcomeRuntime.FormalSourceVerifier() {
                @Override public void verifyCompleted(
                        ChainTaskOutcomeRuntime.Completed command) {
                    throw new AssertionError("unexpected completed command");
                }

                @Override public void verifyFailed(
                        ChainTaskOutcomeRuntime.Failed command) {
                    throw new AssertionError("unexpected failed command");
                }

                @Override public void verifyCancelled(
                        ChainTaskOutcomeRuntime.Cancelled command) {
                    if (!expected.taskId().equals(command.draft().taskId())
                            || !expected.instructionId().equals(
                            command.cancellationInstructionId())) {
                        throw new IllegalStateException(
                                "cancellation source changed at TaskOutcome runtime");
                    }
                }

                @Override public void verifySuperseded(
                        ChainTaskOutcomeRuntime.Superseded command) {
                    throw new AssertionError("unexpected superseded command");
                }
            };
        }

        private static ChainPersistenceRecords.TaskOutcomeRecord withSourceDecision(
                ChainPersistenceRecords.TaskOutcomeRecord value,
                String sourceDecisionId) {
            return new ChainPersistenceRecords.TaskOutcomeRecord(
                    value.outcomeId(), value.taskId(), value.eventId(),
                    value.sourceCommandId(), value.outcomeType(),
                    value.instructionId(), value.taskFrameId(),
                    value.finalPlanId(), value.finalPlanRevisionId(),
                    value.coverage(), value.acceptedSet(), value.finalArtifactId(),
                    value.candidateKey(), value.validationId(),
                    value.publishOperationId(), value.publishedProjectVersion(),
                    value.publishedRevisionId(), value.publishReceiptId(),
                    value.incompleteItems(), value.limitations(), value.risks(),
                    value.failureCategory(), value.failureCode(), sourceDecisionId,
                    value.createdAt());
        }

        @Override public ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.TaskOutcomeRecord> appendTaskOutcome(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.TaskOutcomeRecord> requested) {
            boolean replayed = cancellationOutcome != null;
            if (cancellationOutcome == null) {
                cancellationOutcome = requested.fact();
                ChainPersistenceRecords.AuthorityEventRequest requestedEvent =
                        requested.event();
                events.add(new ChainPersistenceRecords.AuthorityEventRecord(
                        requestedEvent.eventId(), requestedEvent.taskId(),
                        events.size() + 1L, requestedEvent.eventType(),
                        requestedEvent.transitionId(),
                        requestedEvent.sourceIdentitySha256(),
                        requestedEvent.committedAt()));
            } else if (!cancellationOutcome.equals(requested.fact())) {
                throw new IllegalStateException(
                        "TaskOutcome replay changed immutable contents");
            }
            ChainPersistenceRecords.AuthorityEventRecord event = events.stream()
                    .filter(value -> value.eventId().equals(
                            cancellationOutcome.eventId()))
                    .findFirst().orElseThrow();
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    event, cancellationOutcome, replayed);
        }

        @Override public Optional<ChainPersistenceRecords.TransitionRecord> findTransition(String id) { return Optional.empty(); }
        @Override public List<ChainPersistenceRecords.TransitionStageRecord> findTransitionStages(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.TransitionRecord> findIncompleteTransitions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.RouteDecisionRecord> findRouteDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PlanBindingRecord> findPlanBindings(String id) { return List.copyOf(planBindings); }
        @Override public List<ChainPersistenceRecords.CandidateStepResultRecord> findCandidateStepResults(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ReviewDecisionRecord> findReviewDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.AcceptedResultRecord> findAcceptedResults(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ResultApplicabilityRecord> findApplicabilityDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.PendingItemRecord> findPendingItems(String id) { return List.copyOf(pendingItems); }
        @Override public List<ChainPersistenceRecords.PendingItemRecord> findOpenPendingItems(String id) { return List.copyOf(pendingItems); }
        @Override public List<ChainPersistenceRecords.PendingItemEventRecord> findPendingItemEvents(String gapId) {
            return pendingEvents.stream().filter(value -> value.gapId().equals(gapId)).toList();
        }
        @Override public List<ChainPersistenceRecords.PermissionDecisionRecord> findPermissionDecisions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findActionBindings(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.ActionBindingRecord> findInFlightActions(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.WorkspaceCandidateRecord> findWorkspaceCandidates(String id) { return List.of(); }

        @Override public Optional<ChainPersistenceRecords.FinalizationReadinessRecord> findReadinessById(String id) { return Optional.empty(); }
        @Override public Optional<ChainPersistenceRecords.FinalizationReadinessRecord> findReadinessByScope(String id) { return Optional.empty(); }
        @Override public List<ChainPersistenceRecords.FinalizationReadinessRecord> findReadiness(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.FinalizationCheckRecord> findFinalizationChecks(String id) { return List.of(); }
        @Override public Optional<ChainPersistenceRecords.TaskOutcomeRecord> findTaskOutcome(String id) { return Optional.ofNullable(cancellationOutcome); }
        @Override public List<ChainPersistenceRecords.DeliveryRecord> findDeliveries(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.DeliveryRecord> findIncompleteDeliveries(String id) { return List.of(); }
        @Override public List<ChainPersistenceRecords.DeliveryEventRecord> findDeliveryEvents(String id) { return List.of(); }
    }
}
