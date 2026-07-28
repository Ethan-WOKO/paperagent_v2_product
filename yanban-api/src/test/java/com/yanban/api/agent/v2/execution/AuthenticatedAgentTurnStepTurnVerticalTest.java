package com.yanban.api.agent.v2.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.agent.v2.adapter.provider.DeterministicProductStepTurnAdapter;
import com.yanban.agent.v2.adapter.provider.ProductChatModelProviderAdapter;
import com.yanban.agent.v2.adapter.provider.ProductModelProviderConfiguration;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ToolCall;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.kernel.DefaultSingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnIntentPersisted;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticatedAgentTurnStepTurnVerticalTest {
    private static final Instant T0 = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void activeRecoveryMapsOneProviderCallThenAppliesAndReplaysSameIntent() {
        VerifiedAgentTurnProductContext context = context();
        PlanId planId = new ProductPlanIdDerivation()
                .derive(context.identity());
        RecoveredActiveStep active = recovered(planId);
        AgentTurnProductContextResolver contexts =
                mock(AgentTurnProductContextResolver.class);
        StepRecoverer recoverer = mock(StepRecoverer.class);
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(recoverer.recover(any())).thenReturn(active);

        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<ChatRequest> mapped = new AtomicReference<>();
        ChatModelProvider chat = new ChatModelProvider() {
            @Override
            public String providerName() {
                return "fake";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                modelCalls.incrementAndGet();
                mapped.set(request);
                return new ChatResponse(
                        new ChatMessage(
                                "assistant",
                                null,
                                List.of(new ToolCall(
                                        "stable-provider-call",
                                        "function",
                                        new ToolCall.FunctionCall(
                                                "literature.search",
                                                "{\"query\":\"agents\"}"))),
                                null),
                        "tool_calls",
                        new ChatResponse.Usage(3, 2, 5));
            }

            @Override
            public Flux<ChatChunk> streamChat(ChatRequest request) {
                throw new AssertionError("streaming is outside this boundary");
            }
        };
        var model = new ProductChatModelProviderAdapter(
                chat,
                new ObjectMapper(),
                new ProductModelProviderConfiguration(
                        "deepseek", "test-model"));
        var turn = new DeterministicProductStepTurnAdapter(
                model,
                List.of(new ToolDescriptor(
                        new ToolId("literature.search"),
                        "search",
                        Set.of())),
                new com.yanban.agent.v2.adapter.provider
                        .ProductStepTurnConfiguration(512, 0.1d));
        RecordingIntentRepository intents = new RecordingIntentRepository();
        var composer = new AuthenticatedAgentTurnStepTurnComposer(
                contexts,
                new ProductPlanIdDerivation(),
                recoverer,
                new DefaultSingleTurnStepKernel(turn, intents));

        var first = assertInstanceOf(
                AuthenticatedAgentTurnStepTurnExecuted.class,
                composer.execute(7L, 42L, command()));
        var replay = assertInstanceOf(
                AuthenticatedAgentTurnStepTurnExecuted.class,
                composer.execute(7L, 42L, command()));

        var firstPersisted = assertInstanceOf(
                SingleTurnIntentPersisted.class, first.outcome());
        var replayPersisted = assertInstanceOf(
                SingleTurnIntentPersisted.class, replay.outcome());
        assertEquals(firstPersisted.persistedIntent(),
                replayPersisted.persistedIntent());
        assertEquals(
                List.of(PersistenceOutcome.APPLIED, PersistenceOutcome.REPLAYED),
                intents.outcomes);
        assertEquals(2, modelCalls.get());
        assertEquals(2, intents.requests.size());
        assertEquals(intents.requests.get(0).intent().toolCallId(),
                intents.requests.get(1).intent().toolCallId());
        verify(recoverer, times(2)).recover(any());
        assertNull(mapped.get().apiKey());
        assertNull(mapped.get().apiUrl());
        assertEquals(active.recovery().activation().activationEvent().id(),
                intents.requests.get(0).expectedActivationEventId());
        assertEquals(active.lease().fencingToken(),
                intents.requests.get(0).fencingToken());
    }

    private static AuthenticatedAgentTurnStepTurnCommand command() {
        return new AuthenticatedAgentTurnStepTurnCommand(
                new io.paperagent.v2.runtime.execution.recovery.composition
                        .StepRecoveryLeaseAttempt(
                                "owner", "token",
                                T0.plus(Duration.ofHours(1))));
    }

    private static VerifiedAgentTurnProductContext context() {
        return new VerifiedAgentTurnProductContext(
                new AgentRunIdentity(
                        "AGENT_TURN", "turn-42", 7L, 11L, null),
                Optional.empty());
    }

    private static RecoveredActiveStep recovered(PlanId planId) {
        TaskFrameId taskId = new TaskFrameId("task-active");
        PlanStepId stepId = new PlanStepId("step-active");
        TaskFrame task = new TaskFrame(
                taskId,
                "find sources",
                List.of("paper"),
                List.of("references"),
                List.of("no project mutation"),
                Optional.empty(),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(),
                        NetworkPolicy.DENY_ALL,
                        List.of(),
                        new ResourceLimits(
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(2),
                                1024, 1024, 1),
                        Set.of()),
                T0);
        PlanStep step = new PlanStep(
                stepId,
                "search literature",
                "relevant sources",
                Set.of(),
                List.of("sources found"),
                new BoundedExecutionHints(1, Duration.ofMinutes(1)));
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-active"),
                taskId,
                1,
                Optional.empty(),
                "initial",
                T0,
                List.of(step),
                Map.of());
        Plan plan = new Plan(planId, taskId, List.of(revision));
        Checkpoint checkpoint = new Checkpoint(
                taskId,
                planId,
                revision.id(),
                1,
                2,
                PlanExecutionState.ACTIVE,
                Map.of(stepId, StepExecutionState.ACTIVE),
                List.of(),
                T0.plusSeconds(2));
        VersionedCheckpoint versioned = new VersionedCheckpoint(3, checkpoint);
        EventEnvelope event = new EventEnvelope(
                new EventId("activation-active"),
                taskId,
                planId,
                2,
                T0.plusSeconds(2),
                new EventType("step-activation"),
                Optional.empty(),
                "correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        LeaseRecord lease = new LeaseRecord(
                planId,
                "owner",
                "token",
                7,
                T0,
                T0.plus(Duration.ofHours(2)));
        PersistedStepActivation activation = new PersistedStepActivation(
                planId,
                stepId,
                lease.ownerId(),
                lease.fencingToken(),
                event,
                versioned);
        return new RecoveredActiveStep(
                new PersistedStepRecoveryActive(
                        task, plan, versioned, activation, Optional.empty()),
                lease,
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    private static final class RecordingIntentRepository
            implements EffectIntentRepository {
        private final List<EffectIntentRequest> requests = new ArrayList<>();
        private final List<PersistenceOutcome> outcomes = new ArrayList<>();

        @Override
        public PersistenceResult<PersistedEffectIntent> persist(
                EffectIntentRequest request) {
            requests.add(request);
            PersistedEffectIntent persisted = new PersistedEffectIntent(
                    request.intent(),
                    "owner",
                    request.fencingToken(),
                    request.expectedActivationEventId());
            PersistenceResult<PersistedEffectIntent> result =
                    requests.size() == 1
                            ? PersistenceResult.applied(persisted)
                            : PersistenceResult.replayed(persisted);
            outcomes.add(result.outcome());
            return result;
        }

        @Override
        public PersistenceResult<PersistedEffectIntent> find(
                ToolCallId toolCallId) {
            throw new AssertionError("find is outside this boundary");
        }
    }
}
