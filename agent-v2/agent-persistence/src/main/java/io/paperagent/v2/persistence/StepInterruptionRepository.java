package io.paperagent.v2.persistence;

public interface StepInterruptionRepository {
    PersistenceResult<PersistedStepInterruption> pause(StepPauseRequest request);

    PersistenceResult<PersistedStepInterruption> fail(StepFailRequest request);

    PersistenceResult<PersistedStepInterruption> cancel(StepCancelRequest request);
}
