package com.yanban.api.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.effect.NaturalLanguageEffectAuthoritySource;
import com.yanban.api.agent.v2.persistence
        .ProductEffectExecutionClaimRepository;
import com.yanban.api.agent.v2.persistence
        .ProductEffectExecutionClaimRequest;
import com.yanban.api.agent.v2.persistence
        .ProductEffectExecutionClaimResult;
import com.yanban.api.agent.v2.workspace
        .AuthenticatedAgentTurnWorkspacePortFactory;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.sandbox.contract.SandboxDispatch;
import com.yanban.sandbox.contract.SandboxDispatchResponse;
import com.yanban.sandbox.contract.SandboxExecutionStatus;
import com.yanban.sandbox.contract.SandboxExecutionView;
import com.yanban.sandbox.contract.SandboxReceipt;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanExecutionContextRepository;
import io.paperagent.v2.runtime.execution.recovery.composition
        .RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition
        .StepRecoveryLeaseAttempt;
import io.paperagent.v2.runtime.execution.recovery.composition
        .StepRecoveryLeaseDisposition;
import io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class V2SandboxEffectExecutionComposerTest {
    @Test
    void pendingExecutionResumesWithSameIdentityAndReturnsTerminalReceipt() {
        Fixture fixture = new Fixture();
        List<SandboxDispatch> dispatches = new ArrayList<>();
        when(fixture.broker.dispatch(any())).thenAnswer(invocation -> {
            SandboxDispatch dispatch = invocation.getArgument(0);
            dispatches.add(dispatch);
            return accepted(dispatch);
        });
        when(fixture.broker.status("execution-1"))
                .thenAnswer(invocation -> running(dispatches.get(0)))
                .thenAnswer(invocation -> succeeded(dispatches.get(1)));

        assertThrows(V2SandboxEffectPendingException.class,
                fixture::execute);
        V2SandboxEffectExecutionOutcome result = fixture.execute();

        assertThat(result.result().receipt().status())
                .isEqualTo(ReceiptStatus.SUCCESS);
        assertThat(result.result().receipt().toolCallId())
                .isEqualTo(fixture.toolCallId);
        assertThat(dispatches).hasSize(2);
        assertThat(dispatches)
                .extracting(SandboxDispatch::idempotencyKey)
                .containsOnly(fixture.toolCallId.value());
        assertThat(dispatches)
                .extracting(SandboxDispatch::requestDigest)
                .containsOnly(dispatches.get(0).requestDigest());
        verify(fixture.broker, times(2)).status("execution-1");
        verify(fixture.claims, times(2)).execute(any());
    }

    @Test
    void brokerAuthorityStageIsNotCollapsedIntoGenericClaimFailure() {
        Fixture fixture = new Fixture();
        when(fixture.broker.dispatch(any())).thenAnswer(invocation -> {
            SandboxDispatch dispatch = invocation.getArgument(0);
            return new SandboxDispatchResponse(
                    "execution-1", dispatch.idempotencyKey(),
                    "f".repeat(64), dispatch.fence(),
                    SandboxExecutionStatus.RUNNING);
        });

        V2SandboxEffectExecutionException failure = assertThrows(
                V2SandboxEffectExecutionException.class,
                fixture::execute);

        assertThat(failure.stage()).isEqualTo("dispatch_authority");
    }

    private static SandboxDispatchResponse accepted(
            SandboxDispatch dispatch) {
        return new SandboxDispatchResponse(
                "execution-1", dispatch.idempotencyKey(),
                dispatch.requestDigest(), dispatch.fence(),
                SandboxExecutionStatus.RUNNING);
    }

    private static SandboxExecutionView running(
            SandboxDispatch dispatch) {
        return new SandboxExecutionView(
                "execution-1", dispatch.idempotencyKey(),
                dispatch.requestDigest(), dispatch.fence(),
                SandboxExecutionStatus.RUNNING, null, null);
    }

    private static SandboxExecutionView succeeded(
            SandboxDispatch dispatch) {
        Instant started = Instant.parse("2026-07-31T00:00:00Z");
        SandboxReceipt receipt = new SandboxReceipt(
                "execution-1", dispatch.idempotencyKey(),
                dispatch.requestDigest(), dispatch.userId(),
                dispatch.projectId(), dispatch.sessionId(),
                dispatch.planId(), dispatch.stepId(), dispatch.fence(),
                dispatch.projectVersion(), dispatch.policyDigest(),
                "e2b", SandboxExecutionStatus.SUCCEEDED, 0,
                "ok", "", false, Map.of(), started,
                started.plusSeconds(1), null);
        return new SandboxExecutionView(
                receipt.executionId(), receipt.idempotencyKey(),
                receipt.requestDigest(), receipt.fence(),
                receipt.status(), receipt, null);
    }

    private static final class Fixture {
        private final Long userId = 7L;
        private final Long turnId = 12L;
        private final PlanId planId = new PlanId("product-plan.test");
        private final PlanStepId stepId = new PlanStepId("run-1");
        private final ToolCallId toolCallId =
                new ToolCallId("sandbox-call-1");
        private final StepRecoveryLeaseAttempt attempt =
                new StepRecoveryLeaseAttempt(
                        "owner", "lease-token",
                        Instant.parse("2026-07-31T01:00:00Z"));
        private final SandboxBrokerClient broker =
                mock(SandboxBrokerClient.class);
        private final ProductEffectExecutionClaimRepository claims =
                mock(ProductEffectExecutionClaimRepository.class);
        private final V2SandboxEffectExecutionComposer composer;

        @SuppressWarnings("unchecked")
        private Fixture() {
            AgentTurnProductContextResolver contexts =
                    mock(AgentTurnProductContextResolver.class);
            ProductPlanIdDerivation planIds =
                    mock(ProductPlanIdDerivation.class);
            StepRecoverer recoverer = mock(StepRecoverer.class);
            EffectIntentRepository intents =
                    mock(EffectIntentRepository.class);
            PlanExecutionContextRepository executionContexts =
                    mock(PlanExecutionContextRepository.class);
            AuthenticatedAgentTurnWorkspacePortFactory workspaces =
                    mock(AuthenticatedAgentTurnWorkspacePortFactory.class);
            NaturalLanguageEffectAuthoritySource authorities =
                    mock(NaturalLanguageEffectAuthoritySource.class);
            SandboxExecutionProperties properties =
                    new SandboxExecutionProperties();
            properties.setProvider("e2b");
            SandboxCommandPolicy commands =
                    mock(SandboxCommandPolicy.class);
            V2SandboxPollWaiter waiter =
                    mock(V2SandboxPollWaiter.class);
            when(waiter.maximumPolls()).thenReturn(1);

            AgentRunIdentity identity = new AgentRunIdentity(
                    "AGENT_TURN", "12", userId, 11L, 13L);
            when(contexts.resolve(userId, turnId)).thenReturn(
                    new VerifiedAgentTurnProductContext(
                            identity, Optional.of("version-1")));
            when(planIds.derive(identity)).thenReturn(planId);

            PersistedStepRecoveryActive recovery =
                    mock(PersistedStepRecoveryActive.class);
            PersistedStepActivation activation =
                    mock(PersistedStepActivation.class);
            when(recovery.planId()).thenReturn(planId);
            when(recovery.activation()).thenReturn(activation);
            when(activation.stepId()).thenReturn(stepId);
            when(activation.activationEvent()).thenReturn(
                    mock(io.paperagent.v2.contracts.EventEnvelope.class));
            when(activation.activationEvent().id()).thenReturn(
                    new EventId("activation-1"));
            LeaseRecord lease = new LeaseRecord(
                    planId, "owner", "lease-token", 7,
                    Instant.parse("2026-07-31T00:00:00Z"),
                    Instant.parse("2026-07-31T01:00:00Z"));
            RecoveredActiveStep active = new RecoveredActiveStep(
                    recovery, lease,
                    StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
            when(recoverer.recover(any())).thenReturn(active);

            PersistedEffectIntent intent = new PersistedEffectIntent(
                    new EffectIntent(
                            toolCallId, planId, stepId,
                            V2SandboxEffectExecutionComposer.KIND,
                            new ObjectValue(Map.of(
                                    "paths", new ListValue(List.of(
                                            new TextValue(
                                                    "src/Main.java"))),
                                    "argv", new ListValue(List.of(
                                            new TextValue("java"),
                                            new TextValue(
                                                    "src/Main.java")))))),
                    "owner", 7, new EventId("activation-1"));
            when(intents.find(toolCallId)).thenReturn(
                    PersistenceResult.found(intent));
            when(authorities.authorizes(
                    userId, turnId, planId.value(),
                    stepId.value(),
                    V2SandboxEffectExecutionComposer.KIND))
                    .thenReturn(true);

            PersistedPlanExecutionContextConfirmed confirmed =
                    mock(PersistedPlanExecutionContextConfirmed.class);
            when(executionContexts.inspect(planId)).thenReturn(
                    PersistenceResult.found(confirmed));
            VerifiedWorkspaceMaterialization materialization =
                    mock(VerifiedWorkspaceMaterialization.class);
            WorkspaceRef workspaceRef = mock(WorkspaceRef.class);
            when(materialization.workspace()).thenReturn(workspaceRef);
            WorkspacePort workspace = mock(WorkspacePort.class);
            when(workspaces.create(userId, turnId)).thenReturn(workspace);
            when(workspace.inspectMaterialization(any()))
                    .thenReturn(materialization);
            when(workspace.read(
                    workspaceRef, new ProjectPath("src/Main.java")))
                    .thenReturn("class Main {}".getBytes(
                            StandardCharsets.UTF_8));

            when(claims.execute(any())).thenAnswer(invocation -> {
                ProductEffectExecutionClaimRequest request =
                        invocation.getArgument(0);
                var receipt = request.execution().get();
                return new ProductEffectExecutionClaimResult(
                        new PersistedEffectResult(
                                receipt, "owner", 7),
                        false);
            });
            composer = new V2SandboxEffectExecutionComposer(
                    contexts, planIds, recoverer, intents, claims,
                    executionContexts, workspaces, authorities,
                    broker, properties, commands, waiter);
        }

        private V2SandboxEffectExecutionOutcome execute() {
            return composer.execute(
                    userId, turnId, planId, toolCallId, attempt);
        }
    }
}
