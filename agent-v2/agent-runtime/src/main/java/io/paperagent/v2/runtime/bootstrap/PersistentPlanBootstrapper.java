package io.paperagent.v2.runtime.bootstrap;

import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;

@FunctionalInterface
public interface PersistentPlanBootstrapper {
    PersistenceResult<PersistedPlanBootstrap> bootstrap(
            PersistentPlanBootstrapRequest request);
}
