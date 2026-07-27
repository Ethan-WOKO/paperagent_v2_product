package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.Plan;

@FunctionalInterface
public interface NextPlanRevisionFreezer {
    Plan freeze(NextPlanRevisionFreezeRequest request);
}
