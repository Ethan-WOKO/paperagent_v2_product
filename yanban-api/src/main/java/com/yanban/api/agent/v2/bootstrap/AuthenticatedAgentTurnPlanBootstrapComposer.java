package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapCommand;
import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapRequestAdapter;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapRequest;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import org.springframework.stereotype.Service;

/**
 * Internal no-execution composition for an authenticated Agent turn.
 */
@Service
public class AuthenticatedAgentTurnPlanBootstrapComposer {
    private final AgentTurnProductContextResolver contexts;
    private final ProductPersistentPlanBootstrapRequestAdapter requests;
    private final PersistentPlanBootstrapper bootstrapper;

    public AuthenticatedAgentTurnPlanBootstrapComposer(
            AgentTurnProductContextResolver contexts,
            ProductPersistentPlanBootstrapRequestAdapter requests,
            PersistentPlanBootstrapper bootstrapper) {
        this.contexts = contexts;
        this.requests = requests;
        this.bootstrapper = bootstrapper;
    }

    public PersistenceResult<PersistedPlanBootstrap> bootstrap(
            Long userId,
            Long turnId,
            ProductPersistentPlanBootstrapCommand command) {
        VerifiedAgentTurnProductContext context = contexts.resolve(userId, turnId);
        PersistentPlanBootstrapRequest request = requests.adapt(
                context.identity(),
                context.projectVersionId(),
                command);
        return bootstrapper.bootstrap(request);
    }
}
