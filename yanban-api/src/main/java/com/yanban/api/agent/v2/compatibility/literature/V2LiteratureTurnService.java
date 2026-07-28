package com.yanban.api.agent.v2.compatibility.literature;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapCommand;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnFreshExecutionStartCommand;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnFreshExecutionStartComposer;
import com.yanban.api.agent.v2.loop.AuthenticatedPersistentPlanAgentLoopComposer;
import com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopCommand;
import com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopState;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionActivationLeaseAttempt;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationAttempt;
import io.paperagent.v2.runtime.execution.activation.materialization.StepActivationEventDraft;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import io.paperagent.v2.runtime.execution.start.FreshExecutionRecoveryRequired;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarted;
import io.paperagent.v2.runtime.planning.InitialPlanDraft;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.routing.RoutingDecisionReason;
import io.paperagent.v2.runtime.routing.RoutingRequestId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import io.paperagent.v2.runtime.synthesis.DefaultFinalSynthesisComposer;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisDisposition;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisTerminalCut;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisCompositionRequest;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class V2LiteratureTurnService {
    private static final PlanStepId STEP_ID =
            new PlanStepId("literature-search");
    private final Object[] requestLocks =
            java.util.stream.IntStream.range(0, 64)
                    .mapToObj(index -> new Object()).toArray();

    private final AgentSessionRepository sessions;
    private final LiteratureDeliveryTransactions deliveries;
    private final AuthenticatedAgentTurnFreshExecutionStartComposer starts;
    private final AuthenticatedPersistentPlanAgentLoopComposer loop;
    private final StepRecoveryRepository recovery;
    private final DefaultFinalSynthesisComposer synthesis;

    public V2LiteratureTurnService(
            AgentSessionRepository sessions,
            LiteratureDeliveryTransactions deliveries,
            AuthenticatedAgentTurnFreshExecutionStartComposer starts,
            AuthenticatedPersistentPlanAgentLoopComposer loop,
            StepRecoveryRepository recovery,
            DefaultFinalSynthesisComposer synthesis) {
        this.sessions = sessions;
        this.deliveries = deliveries;
        this.starts = starts;
        this.loop = loop;
        this.recovery = recovery;
        this.synthesis = synthesis;
    }

    public V2LiteratureTurnResponse execute(
            Long userId, Long sessionId, V2LiteratureTurnRequest input) {
        String lockKey = String.valueOf(userId) + "\0"
                + String.valueOf(sessionId) + "\0"
                + (input == null ? "" : String.valueOf(
                input.clientRequestId()));
        Object lock = requestLocks[(lockKey.hashCode() & Integer.MAX_VALUE)
                % requestLocks.length];
        synchronized (lock) {
            return executeSerialized(userId, sessionId, input);
        }
    }

    private V2LiteratureTurnResponse executeSerialized(
            Long userId, Long sessionId, V2LiteratureTurnRequest input) {
        if (userId == null || input == null) {
            throw new IllegalArgumentException("V2 literature turn is invalid");
        }
        var session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "agent session was not found"));
        if (session.getScope() != AgentSessionScope.WORKSPACE
                || session.getProjectId() != null) {
            throw new IllegalArgumentException(
                    "V2 literature turn requires a workspace session");
        }
        Request request = normalize(input);
        String requestHash = hash(request.canonical());
        String owner = "v2-literature-" + hash(
                userId + "\0" + sessionId + "\0" + request.requestId())
                .substring(0, 32);
        Instant now = Instant.now();
        String initialToken = "lease-" + hash(
                requestHash + "\0" + now.toEpochMilli());
        LiteratureDeliveryEntity delivery = deliveries.open(
                userId, sessionId, request.requestId(), requestHash,
                request.query(), request.topK(), request.yearFrom(),
                request.includeBibtex(), owner, initialToken,
                now.plus(Duration.ofMinutes(10)));
        if ("DELIVERED".equals(delivery.status())) {
            return response(delivery, true);
        }
        if (!delivery.leaseExpiresAt().isAfter(now)) {
            delivery = deliveries.rotateExpiredLease(
                    delivery.id(),
                    "lease-" + hash(requestHash + "\0"
                            + now.toEpochMilli() + "\0recovery"),
                    now.plus(Duration.ofMinutes(10)), now);
        }

        Instant authorityTime = delivery.createdAt();
        ProductPersistentPlanBootstrapCommand bootstrap =
                bootstrap(request, delivery.turnId(), authorityTime);
        var started = starts.start(userId, delivery.turnId(),
                new AuthenticatedAgentTurnFreshExecutionStartCommand(
                        bootstrap,
                        Optional.of(startAttempt(delivery, authorityTime))));
        io.paperagent.v2.contracts.PlanId planId;
        if (started instanceof FreshExecutionStarted fresh) {
            planId = fresh.persistedStart().planId();
        } else if (started instanceof FreshExecutionRecoveryRequired replay) {
            planId = replay.planId();
        } else {
            throw new IllegalStateException(
                    "V2 literature execution is unavailable");
        }
        delivery = deliveries.bindPlan(delivery.id(), planId.value());

        var loopResult = loop.execute(userId, delivery.turnId(),
                loopCommand(delivery, authorityTime));
        if (loopResult.state() != PersistentPlanAgentLoopState.PLAN_SUCCEEDED) {
            throw new IllegalStateException(
                    "V2 literature execution did not reach a terminal delivery");
        }
        var inspected = recovery.inspect(planId);
        if (inspected.outcome() != PersistenceOutcome.FOUND
                || !(inspected.value().orElse(null)
                instanceof PersistedStepRecoverySucceeded terminal)) {
            throw new IllegalStateException(
                    "V2 literature terminal authority is unavailable");
        }
        var composed = synthesis.compose(
                new FinalSynthesisCompositionRequest(
                        new FinalSynthesisTerminalCut(
                                terminal.taskFrame(), terminal.plan(),
                                terminal.checkpoint().checkpoint()),
                        Optional.empty(), authorityTime.plusMillis(9)));
        delivery = deliveries.deliver(
                delivery.id(), planId.value(),
                composed.synthesis().id().value(),
                composed.synthesis().narrative());
        return response(delivery,
                composed.disposition() == FinalSynthesisDisposition.REPLAYED);
    }

    private V2LiteratureTurnResponse response(
            LiteratureDeliveryEntity value, boolean replayed) {
        String content = deliveries.assistant(
                value.assistantMessageId()).getContent();
        return new V2LiteratureTurnResponse(
                value.id().sessionId(), value.turnId(),
                value.userMessageId(), value.assistantMessageId(),
                value.id().clientRequestId(), value.planId(),
                value.synthesisId(), content, replayed);
    }

    private static ProductPersistentPlanBootstrapCommand bootstrap(
            Request request, Long turnId, Instant now) {
        String args = "query=" + request.query()
                + "; topK=" + request.topK()
                + "; yearFrom=" + request.yearFrom()
                + "; includeBibtex=" + request.includeBibtex();
        return new ProductPersistentPlanBootstrapCommand(
                new RoutingDecision(
                        new RoutingRequestId("literature-route-" + turnId),
                        Route.PERSISTENT_PLAN_EXECUTE,
                        RoutingDecisionReason.DECLARED_REQUIREMENT,
                        Set.of(RoutingRequirement.TOOL_USE,
                                RoutingRequirement.NETWORK)),
                new TaskFrameDraft(
                        "Create one literature search task for: "
                                + request.query(),
                        List.of("literature search request"),
                        List.of("durable queued-task confirmation"),
                        List.of("Do not claim that paper results were returned")),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(Capability.ACCESS_NETWORK,
                                Capability.INVOKE_EXTERNAL_TOOL),
                        NetworkPolicy.ALLOWLIST_ONLY,
                        List.of("product-literature-search"),
                        new ResourceLimits(
                                Duration.ofMinutes(10),
                                Duration.ofMinutes(2),
                                256L * 1024 * 1024,
                                1024L * 1024,
                                1),
                        Set.of()),
                new InitialPlanDraft(
                        "Explicit literature capability request",
                        List.of(new PlanStep(
                                STEP_ID,
                                "Call literature.search exactly once with "
                                        + args,
                                "A durable literature search task is queued",
                                Set.of(),
                                List.of("One successful execution receipt"),
                                new BoundedExecutionHints(
                                        1, Duration.ofMinutes(2))))),
                now, now.plusMillis(1), now.plusMillis(2));
    }

    private static FreshExecutionStartAttempt startAttempt(
            LiteratureDeliveryEntity delivery, Instant now) {
        String suffix = hash(delivery.requestSha256());
        return new FreshExecutionStartAttempt(
                delivery.leaseOwnerId(), delivery.leaseToken(),
                delivery.leaseExpiresAt(),
                new ExecutionStartEventDraft(
                        new EventId("literature-start-" + suffix),
                        now.plusMillis(3),
                        new EventType("PLAN_STARTED"),
                        Optional.empty(),
                        "literature-" + suffix,
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                now.plusMillis(4));
    }

    private static PersistentPlanAgentLoopCommand loopCommand(
            LiteratureDeliveryEntity delivery, Instant now) {
        String suffix = hash(delivery.requestSha256());
        return new PersistentPlanAgentLoopCommand(
                1,
                new StepRecoveryLeaseAttempt(
                        delivery.leaseOwnerId(), delivery.leaseToken(),
                        delivery.leaseExpiresAt()),
                new StepActivationAttempt(
                        delivery.leaseOwnerId(), delivery.leaseToken(),
                        delivery.leaseExpiresAt(),
                        new StepActivationEventDraft(
                                new EventId("literature-activate-" + suffix),
                                now.plusMillis(5),
                                new EventType("STEP_ACTIVATED"),
                                Optional.empty(),
                                "literature-" + suffix,
                                new InlineEventPayload(
                                        new ObjectValue(Map.of()))),
                        now.plusMillis(6)),
                new EffectDrivenStepProgressionActivationLeaseAttempt(
                        delivery.leaseOwnerId(), delivery.leaseToken(),
                        delivery.leaseExpiresAt()),
                Optional.empty());
    }

    private static Request normalize(V2LiteratureTurnRequest input) {
        String query = input.query() == null ? ""
                : input.query().replaceAll("\\s+", " ").trim();
        String requestId = input.clientRequestId() == null
                ? "" : input.clientRequestId().strip();
        int topK = input.topK() == null ? 10 : input.topK();
        Integer yearFrom = input.yearFrom();
        boolean bibtex = Boolean.TRUE.equals(input.includeBibtex());
        if (query.isEmpty() || query.length() > 1000
                || requestId.isEmpty() || requestId.length() > 128
                || topK < 1 || topK > 20
                || (yearFrom != null && (yearFrom < 1900
                || yearFrom > Year.now().getValue() + 1))) {
            throw new IllegalArgumentException(
                    "V2 literature turn request is invalid");
        }
        return new Request(query, topK, yearFrom, bibtex, requestId);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private record Request(
            String query, int topK, Integer yearFrom,
            boolean includeBibtex, String requestId) {
        String canonical() {
            return query + "\0" + topK + "\0" + yearFrom
                    + "\0" + includeBibtex;
        }
    }
}
