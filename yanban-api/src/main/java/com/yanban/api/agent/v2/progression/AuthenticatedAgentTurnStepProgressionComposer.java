package com.yanban.api.agent.v2.progression;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.runtime.execution.activation.composition.ReadyStepActivationCompositionRequest;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationAttempt;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCompositionOutcome;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionComposer;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionCompositionOutcome;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationRequest;
import io.paperagent.v2.runtime.execution.progression.StepProgressionInspector;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Authenticated product composition for progression inspection and the
 * existing Runtime activation/completion boundaries. It exposes no API.
 */
@Service
public class AuthenticatedAgentTurnStepProgressionComposer {
    private static final String INVALID = "invalid authenticated progression";

    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final StepProgressionInspector inspector;
    private final StepActivationComposer activation;
    private final ActiveStepCompletionComposer completion;

    public AuthenticatedAgentTurnStepProgressionComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepProgressionInspector inspector,
            StepActivationComposer activation,
            ActiveStepCompletionComposer completion) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.planIds = Objects.requireNonNull(planIds, "planIds");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.activation = Objects.requireNonNull(activation, "activation");
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    public PersistenceResult<StepRecoverySnapshot> inspect(
            Long userId, Long turnId) {
        return inspector.inspect(resolvePlanId(userId, turnId));
    }

    public StepActivationCompositionOutcome activateReady(
            Long userId, Long turnId, StepActivationAttempt attempt) {
        PlanId planId = resolvePlanId(userId, turnId);
        Objects.requireNonNull(attempt, "attempt");
        PersistenceResult<StepRecoverySnapshot> inspected =
                inspector.inspect(planId);
        if (inspected.outcome() != PersistenceOutcome.FOUND
                || !(inspected.value().orElse(null)
                instanceof PersistedStepRecoveryReady ready)
                || inspected.failure().isPresent()
                || !ready.planId().equals(planId)) {
            throw new IllegalStateException(INVALID);
        }
        return activation.composeReady(
                new ReadyStepActivationCompositionRequest(ready, attempt));
    }

    public ActiveStepCompletionCompositionOutcome completeActive(
            Long userId, Long turnId,
            ActiveStepCompletionMaterializationRequest request) {
        PlanId planId = resolvePlanId(userId, turnId);
        Objects.requireNonNull(request, "request");
        if (!request.recoveredActiveStep().planId().equals(planId)) {
            throw new IllegalStateException(INVALID);
        }
        return completion.compose(request);
    }

    private PlanId resolvePlanId(Long userId, Long turnId) {
        var context = contexts.resolve(userId, turnId);
        return planIds.derive(context.identity());
    }
}
