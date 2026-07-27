package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapRequestAdapter;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapRequest;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartOutcome;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartRequest;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarter;
import org.springframework.stereotype.Service;

/**
 * Internal composition of authenticated bootstrap and fresh execution start.
 */
@Service
public class AuthenticatedAgentTurnFreshExecutionStartComposer {
    private final AgentTurnProductContextResolver contexts;
    private final ProductPersistentPlanBootstrapRequestAdapter requests;
    private final PersistentPlanBootstrapper bootstrapper;
    private final FreshExecutionStarter starter;

    public AuthenticatedAgentTurnFreshExecutionStartComposer(
            AgentTurnProductContextResolver contexts,
            ProductPersistentPlanBootstrapRequestAdapter requests,
            PersistentPlanBootstrapper bootstrapper,
            FreshExecutionStarter starter) {
        this.contexts = contexts;
        this.requests = requests;
        this.bootstrapper = bootstrapper;
        this.starter = starter;
    }

    public FreshExecutionStartOutcome start(
            Long userId,
            Long turnId,
            AuthenticatedAgentTurnFreshExecutionStartCommand command) {
        VerifiedAgentTurnProductContext context = contexts.resolve(userId, turnId);
        PersistentPlanBootstrapRequest request = requests.adapt(
                context.identity(),
                context.projectVersionId(),
                command.bootstrapCommand());
        PersistenceResult<PersistedPlanBootstrap> bootstrapResult =
                bootstrapper.bootstrap(request);
        return starter.start(new FreshExecutionStartRequest(
                bootstrapResult,
                command.attempt()));
    }
}
