package io.paperagent.v2.runtime.execution.progression;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.StepRecoverySnapshot;

import java.util.Objects;

/** Observation-only READY/ACTIVE/SUCCEEDED progression inspection. */
public final class DefaultStepProgressionInspector
        implements StepProgressionInspector {
    private final StepRecoveryRepository repository;

    public DefaultStepProgressionInspector(StepRecoveryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public PersistenceResult<StepRecoverySnapshot> inspect(PlanId planId) {
        Objects.requireNonNull(planId, "planId");
        PersistenceResult<StepRecoverySnapshot> result;
        try {
            result = repository.inspect(planId);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "progression inspection failed");
        }
        if (result == null) {
            throw new IllegalStateException(
                    "progression inspection returned no result");
        }
        if (result.outcome() == PersistenceOutcome.FOUND) {
            StepRecoverySnapshot value = result.value().orElse(null);
            if (value == null || !value.planId().equals(planId)
                    || !(value instanceof PersistedStepRecoveryReady
                            || value instanceof PersistedStepRecoveryActive
                            || value instanceof PersistedStepRecoverySucceeded)) {
                throw new IllegalStateException(
                        "inconsistent progression inspection authority");
            }
        }
        return result;
    }
}
