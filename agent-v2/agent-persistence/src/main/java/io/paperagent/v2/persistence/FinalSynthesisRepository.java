package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.PlanId;

/** Durable authority for the single final delivery of a Plan revision. */
public interface FinalSynthesisRepository {
    PersistenceResult<FinalSynthesis> append(FinalSynthesis synthesis);

    PersistenceResult<FinalSynthesis> find(PlanId planId);
}
