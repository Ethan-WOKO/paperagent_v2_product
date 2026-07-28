package com.yanban.api.agent.v2.effect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.LiteratureSearchStartToolExecutor;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.compatibility.literature.LiteratureSearchRequestAuthority;
import com.yanban.api.agent.v2.compatibility.literature.LiteratureSearchRequestAuthoritySource;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimRepository;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimResult;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolResult;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class LiteratureSearchEffectTestFixtures {
    static final Instant START = Instant.parse("2026-07-28T03:00:00Z");
    static final PlanStepId STEP = new PlanStepId("step-a");
    static final ToolCallId TOOL_CALL = new ToolCallId("tool-a");
    static final EventId ACTIVATION = new EventId("activation-a");

    final AgentTurnProductContextResolver contexts =
            mock(AgentTurnProductContextResolver.class);
    final ProductPlanIdDerivation planIds = new ProductPlanIdDerivation();
    final StepRecoverer recoverer = mock(StepRecoverer.class);
    final EffectIntentRepository intents = mock(EffectIntentRepository.class);
    final ProductEffectExecutionClaimRepository claims =
            mock(ProductEffectExecutionClaimRepository.class);
    final LiteratureSearchStartToolExecutor executor =
            mock(LiteratureSearchStartToolExecutor.class);
    final ObjectMapper json = new ObjectMapper();
    final LiteratureSearchRequestAuthoritySource authorities =
            mock(LiteratureSearchRequestAuthoritySource.class);
    final AtomicInteger time = new AtomicInteger();
    final LiteratureSearchEffectExecutionTimeSource times =
            () -> START.plusMillis(time.getAndIncrement());
    final VerifiedAgentTurnProductContext context =
            new VerifiedAgentTurnProductContext(
                    new AgentRunIdentity(
                            "AGENT_TURN", "42", 7L, 11L, null),
                    Optional.empty());
    final PlanId planId = planIds.derive(context.identity());
    final LeaseRecord lease = new LeaseRecord(
            planId, "owner", "lease-token", 3, START.minusSeconds(1),
            START.plusSeconds(60));
    final PersistedEffectIntent intent;
    final RecoveredActiveStep active;
    final TaskFrame taskFrame;
    final AuthenticatedLiteratureSearchEffectExecutionComposer composer;

    LiteratureSearchEffectTestFixtures() {
        PersistedStepRecoveryActive recovery =
                mock(PersistedStepRecoveryActive.class);
        PersistedStepActivation activation =
                mock(PersistedStepActivation.class);
        EventEnvelope event = mock(EventEnvelope.class);
        taskFrame = mock(TaskFrame.class);
        when(recovery.planId()).thenReturn(planId);
        when(recovery.activation()).thenReturn(activation);
        when(recovery.taskFrame()).thenReturn(taskFrame);
        when(taskFrame.sourceProjectVersion()).thenReturn(Optional.empty());
        when(activation.stepId()).thenReturn(STEP);
        when(activation.activationEvent()).thenReturn(event);
        when(event.id()).thenReturn(ACTIVATION);
        active = new RecoveredActiveStep(
                recovery, lease,
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
        intent = intent(Map.of("query", new TextValue("graph retrieval")));
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(recoverer.recover(any())).thenReturn(active);
        when(intents.find(TOOL_CALL))
                .thenReturn(PersistenceResult.found(intent));
        when(authorities.find(7L, 42L)).thenReturn(Optional.of(
                new LiteratureSearchRequestAuthority(
                        "graph retrieval", 10, null, false)));
        when(claims.execute(any())).thenAnswer(invocation -> {
            var request = (com.yanban.api.agent.v2.persistence
                    .ProductEffectExecutionClaimRequest)
                    invocation.getArgument(0);
            var receipt = request.execution().get();
            return new ProductEffectExecutionClaimResult(
                    new PersistedEffectResult(
                            receipt, lease.ownerId(),
                            lease.fencingToken()), false);
        });
        ObjectNode output = json.createObjectNode();
        output.put("taskId", 99L);
        output.put("status", "PENDING");
        output.put("currentStage", "QUEUED");
        output.put("clientRequestId", "product-output");
        when(executor.execute(any(ToolCall.class))).thenReturn(
                ToolResult.success(
                        TOOL_CALL.value(),
                        AuthenticatedLiteratureSearchEffectExecutionComposer
                                .PRODUCT_TOOL,
                        output));
        composer = new AuthenticatedLiteratureSearchEffectExecutionComposer(
                contexts, planIds, recoverer, intents, claims, executor,
                times, json, authorities);
    }

    PersistedEffectIntent intent(Map<String, io.paperagent.v2.contracts.ContractValue> args) {
        return intent(
                AuthenticatedLiteratureSearchEffectExecutionComposer.V2_TOOL,
                STEP, ACTIVATION, lease.ownerId(), lease.fencingToken(), args);
    }

    PersistedEffectIntent intent(
            String kind,
            PlanStepId step,
            EventId activation,
            String owner,
            long fence,
            Map<String, io.paperagent.v2.contracts.ContractValue> args) {
        return new PersistedEffectIntent(
                new EffectIntent(
                        TOOL_CALL, planId, step, kind,
                        new ObjectValue(args)),
                owner, fence, activation);
    }

    void useProject(long projectId) {
        var projectContext = new VerifiedAgentTurnProductContext(
                new AgentRunIdentity(
                        "AGENT_TURN", "42", 7L, 11L, projectId),
                Optional.of("manifest-v1"));
        when(contexts.resolve(7L, 42L)).thenReturn(projectContext);
        when(taskFrame.sourceProjectVersion()).thenReturn(Optional.of(
                new ProjectVersionRef(
                        String.valueOf(projectId), "manifest-v1")));
    }

    AuthenticatedLiteratureSearchEffectExecutionCommand command() {
        return new AuthenticatedLiteratureSearchEffectExecutionCommand(
                planId, TOOL_CALL, new StepRecoveryLeaseAttempt(
                        lease.ownerId(), lease.leaseToken(),
                        lease.expiresAt()));
    }
}
