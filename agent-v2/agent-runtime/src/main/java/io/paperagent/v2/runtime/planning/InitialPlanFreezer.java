package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.Plan;

@FunctionalInterface
public interface InitialPlanFreezer {
    Plan freeze(InitialPlanFreezeRequest request);
}
