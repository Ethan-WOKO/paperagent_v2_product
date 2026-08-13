package io.paperagent.v2.chain.instruction;

import io.paperagent.v2.chain.ChainPersistenceRecords.InstructionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskInstructionBindingRecord;

import java.util.List;
import java.util.Objects;

/** Immutable instruction chain and the side-effect gate derived from formal facts. */
public record ChainInstructionState(
        String taskId,
        long sequenceCut,
        List<Entry> entries,
        Gate gate,
        String gateAuthorityRef) {
    public ChainInstructionState {
        taskId = required(taskId, "taskId");
        if (sequenceCut < 0) {
            throw new IllegalArgumentException("sequenceCut must not be negative");
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        gate = Objects.requireNonNull(gate, "gate");
        gateAuthorityRef = required(gateAuthorityRef, "gateAuthorityRef");
    }

    public InstructionRecord currentInstruction() {
        if (entries.isEmpty()) {
            throw new IllegalStateException("an instruction state has no current instruction");
        }
        return entries.get(entries.size() - 1).instruction();
    }

    public boolean allowsNewSideEffects() {
        return gate == Gate.SIDE_EFFECTS_ALLOWED;
    }

    public enum Gate {
        PLANNING,
        DIRECT_ANSWER,
        SIDE_EFFECTS_ALLOWED,
        PAUSED_FOR_DISPOSITION,
        PAUSED_FOR_PENDING_VALIDATION,
        CANCELLED,
        SUPERSEDED,
        TERMINAL
    }

    public record Entry(
            TaskInstructionBindingRecord binding,
            InstructionRecord instruction) {
        public Entry {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(instruction, "instruction");
            if (!binding.instructionId().equals(instruction.instructionId())) {
                throw new IllegalArgumentException(
                        "binding and instruction identities must match");
            }
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
