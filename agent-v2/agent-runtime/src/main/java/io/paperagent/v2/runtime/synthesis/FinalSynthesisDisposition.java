package io.paperagent.v2.runtime.synthesis;

/** Describes whether final synthesis was newly persisted or durably replayed. */
public enum FinalSynthesisDisposition {
    APPLIED,
    REPLAYED
}
