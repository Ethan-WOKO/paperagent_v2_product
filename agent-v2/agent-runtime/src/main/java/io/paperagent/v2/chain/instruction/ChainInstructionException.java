package io.paperagent.v2.chain.instruction;

import java.util.Objects;

public final class ChainInstructionException extends RuntimeException {
    public enum Code {
        TASK_NOT_FOUND,
        INSTRUCTION_CHAIN_INVALID,
        INSTRUCTION_REPLAY_MISMATCH,
        BINDING_REPLAY_MISMATCH,
        ANSWERED_GAP_NOT_OPEN,
        CANCEL_SOURCE_INVALID,
        CANCEL_REPLAY_MISMATCH,
        CANCEL_OUTCOME_INVALID
    }

    private final Code code;

    public ChainInstructionException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
