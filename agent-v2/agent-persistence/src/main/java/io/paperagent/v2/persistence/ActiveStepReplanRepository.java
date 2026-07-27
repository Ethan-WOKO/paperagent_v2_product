package io.paperagent.v2.persistence;

public interface ActiveStepReplanRepository {
    PersistenceResult<PersistedActiveStepReplan> supersedeAndReplan(
            ActiveStepReplanRequest request);
}
