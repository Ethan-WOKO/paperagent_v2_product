package io.paperagent.v2.persistence;

@FunctionalInterface
public interface StepCompletionRepository {
    PersistenceResult<PersistedStepCompletion> complete(
            StepCompletionRequest request);
}
