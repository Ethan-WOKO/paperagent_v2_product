package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryOutcome;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryRequest;
import org.springframework.stereotype.Service;

/**
 * Internal recovery composition for an authenticated Agent turn.
 */
@Service
public class AuthenticatedAgentTurnExecutionStartRecoveryComposer {
    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final ExecutionStartRecoverer recoverer;

    public AuthenticatedAgentTurnExecutionStartRecoveryComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            ExecutionStartRecoverer recoverer) {
        this.contexts = contexts;
        this.planIds = planIds;
        this.recoverer = recoverer;
    }

    public ExecutionStartRecoveryOutcome recover(
            Long userId,
            Long turnId,
            AuthenticatedAgentTurnExecutionStartRecoveryCommand command) {
        VerifiedAgentTurnProductContext context = contexts.resolve(userId, turnId);
        PlanId planId = planIds.derive(context.identity());
        return recoverer.recover(
                new ExecutionStartRecoveryRequest(planId, command.attempt()));
    }
}
