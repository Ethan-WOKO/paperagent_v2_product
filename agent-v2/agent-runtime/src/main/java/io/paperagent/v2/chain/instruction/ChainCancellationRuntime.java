package io.paperagent.v2.chain.instruction;

import io.paperagent.v2.chain.ChainCommandStatus;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;

import java.time.Instant;
import java.util.Objects;

/** Validates an explicit cancel command and delegates through the typed outcome command port. */
public final class ChainCancellationRuntime {
    private final ChainFoundationRepository foundations;
    private final ChainInstructionStateReader instructions;
    private final ChainTaskOutcomeCommandPort outcomes;

    public ChainCancellationRuntime(
            ChainFoundationRepository foundations,
            ChainInstructionStateReader instructions,
            ChainTaskOutcomeCommandPort outcomes) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.instructions = Objects.requireNonNull(instructions, "instructions");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    }

    public ChainTaskOutcomeCommandPort.CancellationSubmission cancel(
            CancelRequest request) {
        Objects.requireNonNull(request, "request");
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(request.taskId())
                .orElseThrow(() -> failure(
                        ChainInstructionException.Code.TASK_NOT_FOUND,
                        "cancel task does not exist"));
        ChainPersistenceRecords.CommandRecord command = foundations
                .findCommand(request.commandId())
                .orElseThrow(() -> failure(
                        ChainInstructionException.Code.CANCEL_SOURCE_INVALID,
                        "formal cancel command does not exist"));
        validateCommand(task, command, request);

        ChainInstructionState state = instructions.read(request.taskId());
        ChainPersistenceRecords.InstructionRecord instruction =
                state.currentInstruction();
        if (!request.instructionId().equals(instruction.instructionId())
                || instruction.relationKind() != ChainInstructionRelation.CANCEL
                || !request.commandId().equals(instruction.commandId())
                || !request.taskId().equals(instruction.originTaskId())
                || instruction.sessionId() != task.sessionId()) {
            throw failure(
                    ChainInstructionException.Code.CANCEL_SOURCE_INVALID,
                    "only the current explicit CANCEL instruction may cancel a task");
        }

        ChainTaskOutcomeCommandPort.CancelledTaskOutcomeCommand typed =
                new ChainTaskOutcomeCommandPort.CancelledTaskOutcomeCommand(
                        request.eventId(), request.taskId(),
                        request.commandId(), request.instructionId(),
                        request.sourceRequestSha256(), request.createdAt());
        ChainTaskOutcomeCommandPort.CancellationSubmission submission =
                Objects.requireNonNull(
                        outcomes.submitCancelled(typed),
                        "cancellation submission");
        validateOutcome(submission.outcome(), typed);
        return submission;
    }

    private static void validateCommand(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.CommandRecord command,
            CancelRequest request) {
        if (!request.commandId().equals(command.commandId())
                || command.commandKind() != ChainInstructionRelation.CANCEL
                || !request.taskId().equals(command.targetTaskId())
                || !request.sourceRequestSha256().equals(command.requestSha256())
                || command.userId() != task.userId()
                || command.sessionId() != task.sessionId()
                || command.status() == ChainCommandStatus.FAILED) {
            throw failure(
                    ChainInstructionException.Code.CANCEL_SOURCE_INVALID,
                    "cancel command identity does not match the current task");
        }
        if (command.status() == ChainCommandStatus.COMMITTED
                && (!request.taskId().equals(command.resultTaskId())
                || !request.eventId().equals(command.resultEventId())
                || !request.instructionId().equals(
                command.resultInstructionId()))) {
            throw failure(
                    ChainInstructionException.Code.CANCEL_REPLAY_MISMATCH,
                    "committed cancel command replay changed result identity");
        }
    }

    private static void validateOutcome(
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            ChainTaskOutcomeCommandPort.CancelledTaskOutcomeCommand command) {
        if (!command.eventId().equals(outcome.eventId())
                || !command.taskId().equals(outcome.taskId())
                || !command.sourceCommandId().equals(outcome.sourceCommandId())
                || outcome.outcomeType() != ChainTaskOutcomeStatus.CANCELLED
                || !command.instructionId().equals(outcome.instructionId())
                || !command.instructionId().equals(
                outcome.sourceDecisionId())) {
            throw failure(
                    ChainInstructionException.Code.CANCEL_OUTCOME_INVALID,
                    "typed outcome port returned another cancellation identity");
        }
    }

    private static ChainInstructionException failure(
            ChainInstructionException.Code code,
            String message) {
        return new ChainInstructionException(code, message);
    }

    public record CancelRequest(
            String taskId,
            String instructionId,
            String commandId,
            String sourceRequestSha256,
            String eventId,
            Instant createdAt) {
        public CancelRequest {
            taskId = required(taskId, "taskId");
            instructionId = required(instructionId, "instructionId");
            commandId = required(commandId, "commandId");
            sourceRequestSha256 = required(
                    sourceRequestSha256, "sourceRequestSha256");
            if (!sourceRequestSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "sourceRequestSha256 must be lowercase SHA-256");
            }
            eventId = required(eventId, "eventId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
