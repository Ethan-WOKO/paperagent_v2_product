package io.paperagent.v2.persistence;

public enum StepInterruptionKind {
    PAUSE,
    FAIL,
    CANCEL;

    @Override
    public String toString() {
        return "<provided>";
    }
}
