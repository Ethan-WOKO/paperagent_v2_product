package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.PlanId;

import java.util.LinkedHashMap;
import java.util.Map;

/** Thread-safe deterministic repository used by focused runtime tests. */
public final class InMemoryFinalSynthesisRepository
        implements FinalSynthesisRepository {
    private final Map<PlanId, FinalSynthesis> values = new LinkedHashMap<>();

    @Override
    public synchronized PersistenceResult<FinalSynthesis> append(
            FinalSynthesis synthesis) {
        if (synthesis == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT,
                    "finalSynthesis");
        }
        FinalSynthesis existing = values.get(synthesis.planId());
        if (existing == null) {
            values.put(synthesis.planId(), synthesis);
            return PersistenceResult.applied(synthesis);
        }
        return existing.equals(synthesis)
                ? PersistenceResult.replayed(existing)
                : PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "finalSynthesis.planId");
    }

    @Override
    public synchronized PersistenceResult<FinalSynthesis> find(
            PlanId planId) {
        if (planId == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT, "planId");
        }
        FinalSynthesis existing = values.get(planId);
        return existing == null
                ? PersistenceResult.rejected(
                        PersistenceErrorCode.NOT_FOUND, "planId")
                : PersistenceResult.found(existing);
    }
}
