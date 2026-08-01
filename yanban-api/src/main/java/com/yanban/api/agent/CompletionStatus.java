package com.yanban.api.agent;

/** The only completion claims emitted by the runtime verification gate. */
public enum CompletionStatus {
    VERIFIED,
    PARTIAL,
    INSUFFICIENT_EVIDENCE,
    FAILED
}
