package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryCompositionOutcome;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryRequest;
import org.springframework.stereotype.Service;

/** Internal active-Step recovery composition for an authenticated Agent turn. */
@Service
public class AuthenticatedAgentTurnStepRecoveryComposer {
    private static final String ROOT = "authenticatedStepRecovery";

    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final StepRecoverer recoverer;

    public AuthenticatedAgentTurnStepRecoveryComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepRecoverer recoverer) {
        this.contexts = contexts;
        this.planIds = planIds;
        this.recoverer = recoverer;
    }

    public StepRecoveryCompositionOutcome recover(
            Long userId,
            Long turnId,
            AuthenticatedAgentTurnStepRecoveryCommand command) {
        VerifiedAgentTurnProductContext context = contexts.resolve(userId, turnId);
        PlanId planId = planIds.derive(context.identity());
        requireCommand(command);
        return recoverer.recover(new StepRecoveryRequest(planId, command.attempt()));
    }

    private static void requireCommand(
            AuthenticatedAgentTurnStepRecoveryCommand command) {
        if (command == null) {
            throw failure(ROOT + ".command");
        }
        if (command.attempt() == null) {
            throw failure(ROOT + ".command.attempt");
        }
    }

    private static AuthenticatedAgentTurnStepRecoveryCompositionException failure(
            String path) {
        return new AuthenticatedAgentTurnStepRecoveryCompositionException(
                AuthenticatedAgentTurnStepRecoveryCompositionCode
                        .REQUIRED_VALUE_MISSING,
                path);
    }
}
