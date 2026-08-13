package io.paperagent.v2.chain.instruction;

import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainInstructionWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainWorkflowRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Append-only instruction authority; it exposes no update or delete operation. */
public final class ChainInstructionRuntime {
    private final ChainFoundationRepository foundations;
    private final ChainWorkflowRepository workflow;
    private final ChainInstructionWriter writer;

    public ChainInstructionRuntime(
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            ChainInstructionWriter writer) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public AppendOutcome append(AppendRequest request) {
        Objects.requireNonNull(request, "request");
        ChainPersistenceRecords.TaskRecord task = foundations.findTask(request.taskId())
                .orElseThrow(() -> failure(
                        ChainInstructionException.Code.TASK_NOT_FOUND,
                        "task does not exist: " + request.taskId()));
        ChainPersistenceRecords.InstructionRecord instruction = request.instruction();
        ChainPersistenceRecords.AuthoritativeFact<
                ChainPersistenceRecords.TaskInstructionBindingRecord> authoritativeBinding =
                request.binding();
        ChainPersistenceRecords.TaskInstructionBindingRecord binding =
                authoritativeBinding.fact();
        if (!request.taskId().equals(binding.taskId())
                || !instruction.instructionId().equals(binding.instructionId())
                || !"INSTRUCTION_BOUND".equals(authoritativeBinding.event().eventType())) {
            throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                    "append request identities or authority event type do not match");
        }

        List<ChainPersistenceRecords.TaskInstructionBindingRecord> existing =
                foundations.findTaskInstructions(request.taskId(), Long.MAX_VALUE).stream()
                        .sorted(Comparator.comparingLong(
                                ChainPersistenceRecords.TaskInstructionBindingRecord::taskInstructionSequence))
                        .toList();
        List<ChainInstructionState.Entry> committedPrefix =
                ChainInstructionStateReader.validateEntries(
                        task, existing,
                        foundations.findAuthorityEvents(
                                request.taskId(),
                                foundations.highestAuthorityEventSequence(
                                        request.taskId())),
                        foundations);
        ChainPersistenceRecords.InstructionRecord previous = committedPrefix.isEmpty()
                ? null
                : committedPrefix.get(committedPrefix.size() - 1).instruction();
        long expectedSequence = existing.size() + 1L;
        if (binding.taskInstructionSequence() != expectedSequence) {
            ChainPersistenceRecords.TaskInstructionBindingRecord replay = existing.stream()
                    .filter(value -> value.taskInstructionSequence()
                            == binding.taskInstructionSequence())
                    .findFirst().orElse(null);
            if (replay == null || !sameBinding(replay, binding)) {
                throw failure(ChainInstructionException.Code.BINDING_REPLAY_MISMATCH,
                        "instruction binding sequence is not the next immutable position");
            }
        } else {
            if (existing.stream().anyMatch(value ->
                    value.instructionId().equals(instruction.instructionId()))) {
                throw failure(ChainInstructionException.Code.BINDING_REPLAY_MISMATCH,
                        "an instruction cannot occupy two positions in one task chain");
            }
            validateAppendRelation(task, binding, instruction, previous, existing.isEmpty());
            if (instruction.relationKind()
                    == ChainInstructionRelation.ANSWER_TO_PENDING_ITEM
                    && workflow.findOpenPendingItems(request.taskId()).stream()
                    .noneMatch(item -> item.gapId().equals(instruction.answeredGapId()))) {
                throw failure(ChainInstructionException.Code.ANSWERED_GAP_NOT_OPEN,
                        "ANSWER_TO_PENDING_ITEM must bind one open formal gap");
            }
        }

        ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.InstructionRecord>
                storedInstruction = writer.appendInstruction(instruction);
        if (!sameImmutableContents(storedInstruction.value(), instruction)) {
            throw failure(ChainInstructionException.Code.INSTRUCTION_REPLAY_MISMATCH,
                    "instruction replay changed immutable contents");
        }
        ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.TaskInstructionBindingRecord> storedBinding =
                writer.appendTaskInstructionBinding(authoritativeBinding);
        if (!sameBinding(storedBinding.fact(), binding)) {
            throw failure(ChainInstructionException.Code.BINDING_REPLAY_MISMATCH,
                    "binding replay changed immutable contents");
        }
        return new AppendOutcome(storedInstruction.value(), storedBinding.fact(),
                storedInstruction.replayed() || storedBinding.replayed());
    }

    /**
     * Persistence adapters may normalize audit timestamps (for example,
     * MySQL DATETIME(6) drops nanoseconds).  The instruction identity and
     * boundary fields remain immutable, while createdAt is an audit field.
     */
    private static boolean sameImmutableContents(
            ChainPersistenceRecords.InstructionRecord left,
            ChainPersistenceRecords.InstructionRecord right) {
        return left.instructionId().equals(right.instructionId())
                && left.commandId().equals(right.commandId())
                && left.sessionId() == right.sessionId()
                && left.originTaskId().equals(right.originTaskId())
                && Objects.equals(left.messageId(), right.messageId())
                && Objects.equals(left.bodySha256(), right.bodySha256())
                && left.messageIdentityKey().equals(right.messageIdentityKey())
                && left.relationKind() == right.relationKind()
                && Objects.equals(left.parentInstructionId(),
                        right.parentInstructionId())
                && Objects.equals(left.answeredGapId(), right.answeredGapId())
                && left.effectiveBoundaryDigest().equals(
                        right.effectiveBoundaryDigest());
    }

    private static void validateAppendRelation(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.TaskInstructionBindingRecord binding,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.InstructionRecord previous,
            boolean first) {
        if (binding.relationRole() == ChainPersistenceRecords.BindingRole.ORIGIN
                && !task.taskId().equals(instruction.originTaskId())) {
            throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                    "ORIGIN instruction must originate from the bound task");
        }
        if (first) {
            if (binding.relationRole() == ChainPersistenceRecords.BindingRole.ORIGIN
                    && (instruction.relationKind() != ChainInstructionRelation.INITIAL
                    || instruction.parentInstructionId() != null)) {
                throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                        "the first ORIGIN instruction must be parentless INITIAL");
            }
            return;
        }
        if (binding.relationRole() != ChainPersistenceRecords.BindingRole.ORIGIN
                || instruction.relationKind() == ChainInstructionRelation.INITIAL
                || !previous.instructionId().equals(instruction.parentInstructionId())) {
            throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                    "new instruction must extend the current head as an ORIGIN binding");
        }
        boolean answer = instruction.relationKind()
                == ChainInstructionRelation.ANSWER_TO_PENDING_ITEM;
        if (answer != (instruction.answeredGapId() != null)) {
            throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                    "answeredGapId must match ANSWER_TO_PENDING_ITEM relation");
        }
    }

    private static boolean sameBinding(
            ChainPersistenceRecords.TaskInstructionBindingRecord left,
            ChainPersistenceRecords.TaskInstructionBindingRecord right) {
        return left.taskId().equals(right.taskId())
                && left.eventId().equals(right.eventId())
                && left.instructionId().equals(right.instructionId())
                && left.taskInstructionSequence()
                        == right.taskInstructionSequence()
                && left.relationRole() == right.relationRole();
    }

    private static ChainInstructionException failure(
            ChainInstructionException.Code code, String message) {
        return new ChainInstructionException(code, message);
    }

    public record AppendRequest(
            String taskId,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.TaskInstructionBindingRecord> binding) {
        public AppendRequest {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("taskId must not be blank");
            }
            Objects.requireNonNull(instruction, "instruction");
            Objects.requireNonNull(binding, "binding");
        }
    }

    public record AppendOutcome(
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.TaskInstructionBindingRecord binding,
            boolean replayed) {
        public AppendOutcome {
            Objects.requireNonNull(instruction, "instruction");
            Objects.requireNonNull(binding, "binding");
        }
    }
}
