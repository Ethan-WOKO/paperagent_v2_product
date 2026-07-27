package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;

import java.util.Map;

final class ProductEffectIntentTestFixtures {
    private ProductEffectIntentTestFixtures() {
    }

    static Scenario seed(
            String plan, String task, String owner, String token, long fence,
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductPlanBootstrapCodec bootstrapCodec,
            ProductLeaseJpaRepository leases,
            ProductExecutionStartJpaRepository starts,
            ProductExecutionStartCodec startCodec,
            ProductStepActivationJpaRepository activations,
            ProductStepActivationCodec activationCodec) {
        PersistedPlanBootstrap bootstrap =
                ProductPlanBootstrapTestFixtures.workspace(plan, task);
        ProductStepActivationTestFixtures.seedH0(
                bootstrap, owner, token, fence, bootstraps, bootstrapCodec,
                leases, starts, startCodec);
        StepActivationRequest activation =
                ProductStepActivationTestFixtures.request(
                        bootstrap, token, fence, "activation-" + plan);
        PersistedStepActivation persisted = new PersistedStepActivation(
                activation.planId(), activation.stepId(), owner, fence,
                activation.activationEvent(),
                new VersionedCheckpoint(3, activation.activatedCheckpoint()));
        activations.saveAndFlush(new ProductStepActivationEntity(
                plan, activation.stepId().value(),
                activation.activationEvent().id().value(),
                activation.expectedRevisionId().value(),
                activation.expectedRevisionNumber(),
                persisted.activatedCheckpoint().checkpoint()
                        .revisionId().value(),
                persisted.activatedCheckpoint().checkpoint().revisionNumber(),
                activation.expectedCheckpointVersion(),
                persisted.activatedCheckpoint().version(),
                activation.expectedEventHeadSequence(),
                activation.activationEvent().sequence(),
                owner, fence, activationCodec.encodeRequest(activation),
                activationCodec.encodeResult(persisted),
                ProductStepActivationTestFixtures.NOW));
        return new Scenario(bootstrap, activation, persisted);
    }

    static EffectIntentRequest request(
            Scenario scenario, String toolCallId, String token, long fence) {
        return request(scenario, toolCallId, token, fence, "search",
                new ObjectValue(Map.of("query", new TextValue("safe input"))));
    }

    static EffectIntentRequest request(
            Scenario scenario, String toolCallId, String token, long fence,
            String kind, ObjectValue arguments) {
        return new EffectIntentRequest(new EffectIntent(
                new ToolCallId(toolCallId), scenario.bootstrap().plan().id(),
                scenario.activation().stepId(), kind, arguments),
                token, fence, scenario.activation().activationEvent().id());
    }

    record Scenario(
            PersistedPlanBootstrap bootstrap,
            StepActivationRequest activation,
            PersistedStepActivation persistedActivation) {
    }
}
