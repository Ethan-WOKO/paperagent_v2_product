package io.paperagent.v2.chain;

public enum ChainStepStatus {
    NOT_STARTED,
    READY,
    ACTIVE,
    AWAITING_REVIEW,
    WAITING_GAP,
    COMPLETED,
    SUPERSEDED_BY_REPLAN
}
