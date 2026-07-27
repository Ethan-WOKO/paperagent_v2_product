package io.paperagent.v2.persistence;

public enum PersistenceOutcome {
    APPLIED,
    REPLAYED,
    FOUND,
    REJECTED
}
