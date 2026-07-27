package io.paperagent.v2.persistence;

@FunctionalInterface
public interface StepActivationRepository {
    PersistenceResult<PersistedStepActivation> activate(
            StepActivationRequest request);
}
