package com.yanban.api.agent.v2.chain.api;

import io.paperagent.v2.chain.ChainCommandStatus;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectChainBoundaryReplacementTest {
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void initialAndExplicitReplacementPublishBeforePlannerWhileContinuationClassifiesFirst()
            throws Exception {
        Method start = ProjectChainTurnCoordinator.class.getMethod(
                "start", long.class, long.class,
                com.yanban.api.agent.v2.intake
                        .V2NaturalLanguageTurnRequest.class);
        assertNull(start.getAnnotation(Transactional.class));

        String source = Files.readString(Path.of(
                "src/main/java/com/yanban/api/agent/v2/chain/api/"
                        + "ProjectChainTurnCoordinator.java"));
        int beginWrite = source.indexOf(
                "entryTransactions.inBeginWrite(() -> beginStart(");
        int immediatePublish = source.indexOf(
                "return publishIntakeCut(begin);", beginWrite);
        int continuation = source.indexOf(
                "return advanceFirstPlanner(begin, kind, kind,", beginWrite);
        assertTrue(beginWrite >= 0
                        && immediatePublish > beginWrite
                        && continuation > immediatePublish,
                "known new Task boundaries must publish before continuation classification");

        int classificationMethod = source.indexOf(
                "private V2NaturalLanguageTurnResponse advanceFirstPlanner(");
        int planner = source.indexOf(
                "plannerProgression.advance(", classificationMethod);
        int replacement = source.indexOf(
                "cut = entryTransactions.inBeginWrite(() -> {", planner);
        int replacementProgression = source.indexOf(
                "progression = plannerProgression.advance(", replacement);
        int publicCutWrite = source.indexOf(
                "entryTransactions.inPublicCutWrite(() -> {",
                replacementProgression);
        assertTrue(replacement >= 0
                        && replacementProgression > replacement
                        && publicCutWrite > replacementProgression,
                "a supplement/correction boundary disposition must still choose its immutable result Task before public commit");
    }

    @Test
    void committedReplayProjectsThePersistedResultTask() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/yanban/api/agent/v2/chain/api/"
                        + "ProjectChainTurnCoordinator.java"));
        int replayReturn = source.indexOf(
                "startResponse(begin.command(), begin.replayed())");
        int committedReplay = source.indexOf(
                "null, existing.get().createdAt(), true)");
        int resultTaskProjection = source.indexOf(
                "findTask(command.resultTaskId())");
        assertTrue(committedReplay >= 0 && replayReturn >= 0
                        && resultTaskProjection > replayReturn,
                "replay must project the command's committed replacement task");
    }

    @Test
    void listOnlyExposesTasksBoundToACommittedRootCommand()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/yanban/api/agent/v2/chain/api/"
                        + "ProjectChainTurnCoordinator.java"));

        assertTrue(source.contains(
                "root_command.command_id = task.created_by_command_id"));
        assertTrue(source.contains(
                "root_command.status = 'COMMITTED'"));
        assertTrue(source.contains(
                "root_command.result_task_id = task.task_id"));
    }

    @Test
    void acceptsExactSupplementDispositionAndReplacementIntakeProof() {
        Fixture value = fixture(ChainInstructionRelation.SUPPLEMENT, true);

        assertDoesNotThrow(() -> value.verify());
    }

    @Test
    void acceptsExactCorrectionDispositionAndReplacementIntakeProof() {
        Fixture value = fixture(ChainInstructionRelation.CORRECTION, true);

        assertDoesNotThrow(() -> value.verify());
    }

    @Test
    void rejectsDispositionThatDidNotChangeTheBoundary() {
        Fixture value = fixture(ChainInstructionRelation.SUPPLEMENT, false);

        assertThrows(IllegalStateException.class, value::verify);
    }

    @Test
    void rejectsReplacementThatDoesNotPointBackToTheOldTask() {
        Fixture value = fixture(ChainInstructionRelation.SUPPLEMENT, true);
        ChainPersistenceRecords.TaskRecord detached =
                new ChainPersistenceRecords.TaskRecord(
                        value.replacement().taskId(),
                        value.replacement().createdByCommandId(),
                        value.replacement().sourceInstructionId(),
                        "another-task", value.replacement().userId(),
                        value.replacement().sessionId(),
                        value.replacement().turnId(),
                        value.replacement().requestMessageId(),
                        value.replacement().rootClientRequestId(),
                        value.replacement().rootRequestSha256(),
                        value.replacement().projectId(),
                        value.replacement().initialProjectVersion(), 0, NOW);

        assertThrows(IllegalStateException.class, () ->
                ProjectChainTurnCoordinator.verifyBoundaryReplacementProof(
                        value.oldTask(), detached, value.command(),
                        value.oldHead(), value.trigger(), value.disposition(),
                        value.bindings(), value.oldHead().instructionId(),
                        value.trigger().instructionId()));
    }

    @Test
    void rejectsASecondOrOriginBindingInReplacementIntake() {
        Fixture value = fixture(ChainInstructionRelation.SUPPLEMENT, true);
        var origin = new ChainPersistenceRecords.TaskInstructionBindingRecord(
                value.replacement().taskId(), "binding-event-origin",
                value.trigger().instructionId(), 1,
                ChainPersistenceRecords.BindingRole.ORIGIN, NOW);

        assertThrows(IllegalStateException.class, () ->
                ProjectChainTurnCoordinator.verifyBoundaryReplacementProof(
                        value.oldTask(), value.replacement(), value.command(),
                        value.oldHead(), value.trigger(), value.disposition(),
                        List.of(origin), value.oldHead().instructionId(),
                        value.trigger().instructionId()));
    }

    private static Fixture fixture(
            ChainInstructionRelation relation, boolean boundaryChanged) {
        var oldTask = new ChainPersistenceRecords.TaskRecord(
                "task-old", "command-initial", "instruction-old", null,
                7, 11, 101, 201L, "root-request", HASH,
                31L, "project-version-1", 0, NOW);
        var command = new ChainPersistenceRecords.CommandRecord(
                "command-current", 7, 11, "replacement-request", relation,
                oldTask.taskId(), oldTask.rootClientRequestId(), null, HASH,
                102L, 202L, null, null, null,
                ChainCommandStatus.RECEIVED, null, NOW, null);
        var oldHead = new ChainPersistenceRecords.InstructionRecord(
                "instruction-old", "command-initial", 11, oldTask.taskId(),
                201L, HASH, "command:command-initial",
                ChainInstructionRelation.INITIAL, null, null, HASH, NOW);
        var trigger = new ChainPersistenceRecords.InstructionRecord(
                "instruction-trigger", command.commandId(), 11,
                oldTask.taskId(), 202L, HASH,
                "command:" + command.commandId(), relation,
                oldHead.instructionId(), null, HASH, NOW);
        var replacement = new ChainPersistenceRecords.TaskRecord(
                "task-replacement", command.commandId(),
                trigger.instructionId(), oldTask.taskId(), 7, 11,
                102, 202L, command.clientRequestId(), HASH,
                31L, "project-version-1", 0, NOW);
        var disposition =
                new ChainPersistenceRecords.InstructionDispositionRecord(
                        "disposition-1", oldTask.taskId(),
                        "disposition-event-1", "proposal-1",
                        trigger.instructionId(), "BOUNDARY_CHANGE",
                        "SUPERSEDE", false, "NEW_INTAKE",
                        boundaryChanged, json("[]"), json("[]"), NOW);
        var binding = new ChainPersistenceRecords.TaskInstructionBindingRecord(
                replacement.taskId(), "binding-event-1",
                trigger.instructionId(), 1,
                ChainPersistenceRecords.BindingRole.INHERITED_ROOT, NOW);
        return new Fixture(oldTask, replacement, command, oldHead, trigger,
                disposition, List.of(binding));
    }

    private static ChainPersistenceRecords.CanonicalJson json(String value) {
        return new ChainPersistenceRecords.CanonicalJson(1, HASH, value);
    }

    private record Fixture(
            ChainPersistenceRecords.TaskRecord oldTask,
            ChainPersistenceRecords.TaskRecord replacement,
            ChainPersistenceRecords.CommandRecord command,
            ChainPersistenceRecords.InstructionRecord oldHead,
            ChainPersistenceRecords.InstructionRecord trigger,
            ChainPersistenceRecords.InstructionDispositionRecord disposition,
            List<ChainPersistenceRecords.TaskInstructionBindingRecord> bindings) {
        private void verify() {
            ProjectChainTurnCoordinator.verifyBoundaryReplacementProof(
                    oldTask, replacement, command, oldHead, trigger,
                    disposition, bindings, oldHead.instructionId(),
                    trigger.instructionId());
        }
    }
}
