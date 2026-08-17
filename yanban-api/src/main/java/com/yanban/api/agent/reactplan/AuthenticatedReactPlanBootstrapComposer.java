package com.yanban.api.agent.reactplan;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapRequestAdapter;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapRequest;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import org.springframework.stereotype.Service;

/** Authenticated product composition for the deterministic ReAct Plan shell. */
@Service
public class AuthenticatedReactPlanBootstrapComposer {
    private final AgentTurnProductContextResolver contexts;
    private final ProductPersistentPlanBootstrapRequestAdapter requests;
    private final PersistentPlanBootstrapper bootstrapper;
    private final DeterministicReactPlanDraftFactory drafts;

    public AuthenticatedReactPlanBootstrapComposer(
            AgentTurnProductContextResolver contexts,
            ProductPersistentPlanBootstrapRequestAdapter requests,
            PersistentPlanBootstrapper bootstrapper,
            DeterministicReactPlanDraftFactory drafts) {
        this.contexts = contexts;
        this.requests = requests;
        this.bootstrapper = bootstrapper;
        this.drafts = drafts;
    }

    public PersistenceResult<PersistedPlanBootstrap> bootstrap(
            Long userId,
            Long turnId,
            ReactPlanBootstrapCommand command) {
        VerifiedAgentTurnProductContext context = contexts.resolve(userId, turnId);
        PersistentPlanBootstrapRequest request = requests.adapt(
                context.identity(),
                context.projectVersionId(),
                drafts.create(context.identity().runId(), command));
        return bootstrapper.bootstrap(request);
    }
}
