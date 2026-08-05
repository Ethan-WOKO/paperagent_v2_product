package com.yanban.api.agent.v2.compatibility.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapCommand;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnFreshExecutionStartCommand;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnFreshExecutionStartComposer;
import com.yanban.api.agent.v2.loop.AuthenticatedPersistentPlanAgentLoopComposer;
import com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopException;
import com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopCommand;
import com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopState;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionActivationLeaseAttempt;
import com.yanban.api.agent.v2.workspace.AuthenticatedAgentTurnPlanExecutionContextCommand;
import com.yanban.api.agent.v2.workspace.AuthenticatedAgentTurnPlanExecutionContextComposer;
import com.yanban.api.agent.v2.workspace.AuthenticatedAgentTurnWorkspacePortFactory;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.DiffId;
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
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.FinalSynthesisRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.ReceiptRepository;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.providers.CorrelationId;
import io.paperagent.v2.providers.GenerationOptions;
import io.paperagent.v2.providers.MessageRole;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelRequestId;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationAttempt;
import io.paperagent.v2.runtime.execution.activation.materialization.StepActivationEventDraft;
import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextLeaseAttempt;
import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextReady;
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
import io.paperagent.v2.runtime.synthesis.ExactIntentOwnershipSource;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisCompositionRequest;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisCompositionResult;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisDisposition;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisStore;
import io.paperagent.v2.runtime.synthesis.FinalSynthesisTerminalCut;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class V2ProjectAnalysisService {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(V2ProjectAnalysisService.class);
    private final Object[] locks = java.util.stream.IntStream.range(0, 64)
            .mapToObj(ignored -> new Object()).toArray();
    private final AgentSessionRepository sessions;
    private final ProjectService projects;
    private final ProjectAnalysisDeliveryTransactions deliveries;
    private final AuthenticatedAgentTurnFreshExecutionStartComposer starts;
    private final AuthenticatedAgentTurnPlanExecutionContextComposer contexts;
    private final AuthenticatedPersistentPlanAgentLoopComposer loop;
    private final StepRecoveryRepository recovery;
    private final LeaseRepository leases;
    private final AuthenticatedAgentTurnWorkspacePortFactory workspaces;
    private final AgentTurnProductContextResolver turnContexts;
    private final FinalSynthesisRepository syntheses;
    private final ReceiptRepository receipts;
    private final EffectIntentRepository intents;
    private final ObjectMapper json;

    public V2ProjectAnalysisService(
            AgentSessionRepository sessions, ProjectService projects,
            ProjectAnalysisDeliveryTransactions deliveries,
            AuthenticatedAgentTurnFreshExecutionStartComposer starts,
            AuthenticatedAgentTurnPlanExecutionContextComposer contexts,
            AuthenticatedPersistentPlanAgentLoopComposer loop,
            StepRecoveryRepository recovery, LeaseRepository leases,
            AuthenticatedAgentTurnWorkspacePortFactory workspaces,
            AgentTurnProductContextResolver turnContexts,
            FinalSynthesisRepository syntheses,
            ReceiptRepository receipts, EffectIntentRepository intents,
            ModelProvider provider, ObjectMapper json) {
        this.sessions = sessions;
        this.projects = projects;
        this.deliveries = deliveries;
        this.starts = starts;
        this.contexts = contexts;
        this.loop = loop;
        this.recovery = recovery;
        this.leases = leases;
        this.workspaces = workspaces;
        this.turnContexts = turnContexts;
        this.syntheses = syntheses;
        this.receipts = receipts;
        this.intents = intents;
        this.json = json;
    }

    public V2ProjectAnalysisResponse execute(
            Long userId, Long projectId, Long sessionId,
            V2ProjectAnalysisRequest input) {
        String key = userId + "\0" + projectId + "\0" + sessionId + "\0"
                + (input == null ? "" : input.clientRequestId());
        synchronized (locks[(key.hashCode() & Integer.MAX_VALUE)
                % locks.length]) {
            return executeSerialized(
                    userId, projectId, sessionId, input);
        }
    }

    public V2ProjectAnalysisResponse read(
            Long userId, Long projectId, Long sessionId,
            String clientRequestId) {
        ProjectAnalysisDeliveryEntity delivery;
        try {
            delivery = deliveries.find(
                    new ProjectAnalysisDeliveryKey(
                            userId, projectId, sessionId,
                            normalizeRequestId(clientRequestId)));
        } catch (IllegalArgumentException missing) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "V2 Project analysis turn was not found");
        }
        if ("SUCCEEDED".equals(delivery.status())) {
            return response(delivery, true);
        }
        return execute(userId, projectId, sessionId,
                new V2ProjectAnalysisRequest(
                        delivery.objective(),
                        deliveries.paths(delivery),
                        delivery.searchQuery(),
                        delivery.maxSearchResults(),
                        delivery.id().clientRequestId()));
    }

    private V2ProjectAnalysisResponse executeSerialized(
            Long userId, Long projectId, Long sessionId,
            V2ProjectAnalysisRequest input) {
        var session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "project agent session was not found"));
        if (session.getScope() != AgentSessionScope.PROJECT
                || !projectId.equals(session.getProjectId())) {
            throw new IllegalArgumentException(
                    "V2 Project analysis requires the routed Project session");
        }
        Request request = normalize(input);
        String requestHash = hash(request.canonical());
        ProjectAnalysisDeliveryKey deliveryKey =
                new ProjectAnalysisDeliveryKey(
                        userId, projectId, sessionId, request.requestId());
        Optional<ProjectAnalysisDeliveryEntity> existing =
                deliveries.findMatching(deliveryKey, requestHash);
        if (existing.isPresent()
                && isTerminal(existing.orElseThrow())) {
            return response(existing.orElseThrow(), true);
        }
        String owner = "v2-project-analysis-" + hash(
                userId + "\0" + projectId + "\0" + sessionId + "\0"
                        + request.requestId()).substring(0, 24);
        Instant now = ProjectLeaseAuthorityTime.canonical(Instant.now());
        String token = "lease-" + hash(owner + "\0" + requestHash);
        ProjectAnalysisDeliveryEntity delivery;
        if (existing.isPresent()) {
            delivery = existing.orElseThrow();
        } else {
            var manifest = projects.manifest(userId, projectId);
            if (manifest == null || !projectId.equals(manifest.projectId())
                    || manifest.version() == null
                    || manifest.version().isBlank()) {
                throw invalid();
            }
            Set<String> manifestPaths = manifest.files().stream()
                    .map(value -> value.path()).collect(
                            java.util.stream.Collectors.toSet());
            if (!manifestPaths.containsAll(request.paths())) {
                throw invalid();
            }
            delivery = deliveries.open(
                    userId, projectId, sessionId, request.requestId(),
                    requestHash, request.objective(), request.paths(),
                    request.searchQuery(), request.maxSearchResults(),
                    manifest.version(), owner, token,
                    now.plus(Duration.ofMinutes(10)));
        }
        if (isTerminal(delivery)) {
            return response(delivery, true);
        }
        if (!delivery.leaseExpiresAt().isAfter(now)) {
            delivery = deliveries.rotateExpiredLease(
                    delivery.id(),
                    "lease-" + hash(owner + "\0" + requestHash + "\0"
                            + now.toEpochMilli() + "\0recovery"),
                    now.plus(Duration.ofMinutes(10)), now);
            if (delivery.planId() != null) {
                var takeover = leases.acquire(
                        new io.paperagent.v2.contracts.PlanId(
                                delivery.planId()),
                        delivery.leaseOwnerId(), delivery.leaseToken(),
                        delivery.leaseExpiresAt());
                if (takeover.outcome() != PersistenceOutcome.APPLIED
                        && takeover.outcome()
                                != PersistenceOutcome.REPLAYED) {
                    return response(deliveries.fail(
                            delivery.id(),
                            "PROJECT_ANALYSIS_LEASE_FAILED"), false);
                }
            }
        }
        try {
            return executeRunning(userId, projectId, request, delivery);
        } catch (RuntimeException failure) {
            logFailure(failure);
            return response(deliveries.fail(
                    delivery.id(), "PROJECT_ANALYSIS_FAILED"), false);
        }
    }

    private V2ProjectAnalysisResponse executeRunning(
            Long userId, Long projectId, Request request,
            ProjectAnalysisDeliveryEntity initialDelivery) {
        ProjectAnalysisDeliveryEntity delivery = initialDelivery;
        var verified = turnContexts.resolve(userId, delivery.turnId());
        if (!projectId.equals(verified.identity().projectId())
                || verified.projectVersionId().filter(
                        delivery.projectVersionId()::equals).isEmpty()) {
            throw new IllegalStateException(
                    "Project version changed before V2 execution");
        }
        Instant authorityTime = delivery.createdAt();
        io.paperagent.v2.runtime.execution.start.FreshExecutionStartOutcome started;
        try {
            started = starts.start(userId, delivery.turnId(),
                    new AuthenticatedAgentTurnFreshExecutionStartCommand(
                            bootstrap(request, delivery.turnId(), authorityTime),
                            Optional.of(startAttempt(delivery, authorityTime))));
        } catch (RuntimeException failure) {
            throw StartFailure.thrown(failure);
        }
        io.paperagent.v2.contracts.PlanId planId;
        if (started instanceof FreshExecutionStarted value) {
            planId = value.persistedStart().planId();
        } else if (started instanceof FreshExecutionRecoveryRequired value) {
            planId = value.planId();
        } else {
            throw StartFailure.rejected(started);
        }
        List<ProjectAnalysisDeliveryTransactions.StepAuthority> authorities =
                authorities(planId.value(), request);
        delivery = deliveries.bindPlanAndSteps(
                delivery.id(), planId.value(), authorities);
        var context = contexts.compose(
                userId, delivery.turnId(),
                new AuthenticatedAgentTurnPlanExecutionContextCommand(
                        Optional.of(new PlanExecutionContextLeaseAttempt(
                                delivery.leaseOwnerId(),
                                delivery.leaseToken(),
                                delivery.leaseExpiresAt()))));
        if (!(context instanceof PlanExecutionContextReady ready)
                || !ready.planId().equals(planId)) {
            throw new IllegalStateException(
                    "V2 Project Workspace is unavailable");
        }
        delivery = deliveries.bindWorkspace(
                delivery.id(),
                ready.verifiedWorkspace().workspace().id().value());
        var loopResult = loop.execute(
                userId, delivery.turnId(),
                loopCommand(delivery, request.steps(), authorityTime));
        if (loopResult.state() != PersistentPlanAgentLoopState.PLAN_SUCCEEDED) {
            throw new IllegalStateException(
                    "V2 Project analysis did not complete");
        }
        var inspected = recovery.inspect(planId);
        if (inspected.outcome() != PersistenceOutcome.FOUND
                || !(inspected.value().orElse(null)
                instanceof PersistedStepRecoverySucceeded terminal)) {
            throw new IllegalStateException(
                    "V2 Project terminal authority is unavailable");
        }
        var workspace = workspaces.create(userId, delivery.turnId());
        var materialized = workspace.inspectMaterialization(
                ready.persistedContext().materializationSpec());
        var diff = workspace.diff(
                materialized.workspace(),
                new DiffId("project-analysis-diff." + hash(planId.value())),
                authorityTime.plusMillis(20));
        if (!diff.entries().isEmpty()) {
            throw new IllegalStateException(
                    "read-only Project analysis changed its Workspace");
        }
        FinalSynthesisCompositionResult composed =
                synthesis(request).compose(
                        new FinalSynthesisCompositionRequest(
                                new FinalSynthesisTerminalCut(
                                        terminal.taskFrame(),
                                        terminal.plan(),
                                        terminal.checkpoint().checkpoint()),
                                Optional.of(diff),
                                authorityTime.plusMillis(21)));
        delivery = deliveries.deliver(
                delivery.id(), planId.value(),
                composed.synthesis().id().value(),
                composed.synthesis().narrative());
        return response(delivery,
                composed.disposition() == FinalSynthesisDisposition.REPLAYED);
    }

    private static boolean isTerminal(
            ProjectAnalysisDeliveryEntity delivery) {
        return "SUCCEEDED".equals(delivery.status())
                || "FAILED".equals(delivery.status());
    }

    private DefaultFinalSynthesisComposer synthesis(Request request) {
        FinalSynthesisStore store = new FinalSynthesisStore() {
            public Optional<io.paperagent.v2.contracts.FinalSynthesis> find(
                    io.paperagent.v2.contracts.PlanId planId) {
                var found = syntheses.find(planId);
                return found.outcome() == PersistenceOutcome.FOUND
                        ? found.value() : Optional.empty();
            }
            public Optional<FinalSynthesisCompositionResult> append(
                    io.paperagent.v2.contracts.FinalSynthesis value) {
                var saved = syntheses.append(value);
                if (saved.outcome() != PersistenceOutcome.APPLIED
                        && saved.outcome() != PersistenceOutcome.REPLAYED) {
                    return Optional.empty();
                }
                return Optional.of(new FinalSynthesisCompositionResult(
                        saved.value().orElseThrow(),
                        saved.outcome() == PersistenceOutcome.APPLIED
                                ? FinalSynthesisDisposition.APPLIED
                                : FinalSynthesisDisposition.REPLAYED));
            }
        };
        ExactIntentOwnershipSource ownership =
                (toolCallId, planId, stepId) -> {
                    var intent = intents.find(toolCallId);
                    if (intent.outcome() != PersistenceOutcome.FOUND
                            || intent.value().isEmpty()) return false;
                    var value = intent.value().orElseThrow().intent();
                    try {
                        var authority = deliveries.authority(
                                planId.value(), stepId.value());
                        return value.planId().equals(planId)
                                && value.stepId().equals(stepId)
                                && value.kind().equals(
                                        authority.effectKind());
                    } catch (RuntimeException failure) {
                        return false;
                    }
                };
        return new DefaultFinalSynthesisComposer(
                store,
                receiptId -> {
                    var found = receipts.find(receiptId);
                    return found.outcome() == PersistenceOutcome.FOUND
                            ? found.value() : Optional.empty();
                },
                ownership,
                narration -> narrate(request, narration));
    }

    private String narrate(
            Request request,
            io.paperagent.v2.runtime.synthesis.FinalSynthesisNarrationRequest
                    narration) {
        StringBuilder evidence = new StringBuilder(
                "Project analysis results for: ")
                .append(request.objective()).append('\n');
        narration.untrustedReceipts().forEach(value -> evidence
                .append("- ").append(value.resultSummary())
                .append('\n'));
        return evidence.toString().stripTrailing();
    }

    private V2ProjectAnalysisResponse response(
            ProjectAnalysisDeliveryEntity delivery, boolean replayed) {
        return new V2ProjectAnalysisResponse(
                delivery.id().projectId(), delivery.id().sessionId(),
                delivery.id().clientRequestId(), delivery.status(),
                isTerminal(delivery),
                delivery.turnId(), delivery.planId(),
                delivery.projectVersionId(),
                "SUCCEEDED".equals(delivery.status())
                        ? syntheses.find(new io.paperagent.v2.contracts.PlanId(
                                delivery.planId())).value().orElseThrow()
                                .narrative()
                        : null,
                delivery.assistantMessageId(), delivery.errorCode(), replayed);
    }

    private ProductPersistentPlanBootstrapCommand bootstrap(
            Request request, Long turnId, Instant now) {
        List<PlanStep> steps = new ArrayList<>();
        int index = 1;
        for (String path : request.paths()) {
            String arguments = readArguments(path);
            steps.add(new PlanStep(
                    new PlanStepId(String.format("project-read-%02d", index++)),
                    "Call project.read exactly once with " + arguments,
                    "Return bounded UTF-8 evidence for exactly " + path,
                    Set.of(), List.of("One successful project.read receipt"),
                    new BoundedExecutionHints(
                            1, Duration.ofMinutes(2))));
        }
        if (request.searchQuery() != null) {
            String arguments = searchArguments(
                    request.searchQuery(), request.maxSearchResults());
            steps.add(new PlanStep(
                    new PlanStepId("project-search-01"),
                    "Call project.search exactly once with " + arguments,
                    "Return bounded literal Project search evidence",
                    Set.of(), List.of("One successful project.search receipt"),
                    new BoundedExecutionHints(
                            1, Duration.ofMinutes(2))));
        }
        return new ProductPersistentPlanBootstrapCommand(
                new RoutingDecision(
                        new RoutingRequestId(
                                "project-analysis-route-" + turnId),
                        Route.PERSISTENT_PLAN_EXECUTE,
                        RoutingDecisionReason.DECLARED_REQUIREMENT,
                        Set.of(RoutingRequirement.PROJECT_FILE_ACCESS,
                                RoutingRequirement.TOOL_USE)),
                new TaskFrameDraft(
                        request.objective(),
                        request.paths(),
                        List.of("Evidence-grounded read-only Project analysis"),
                        List.of("Do not modify Project or Workspace",
                                "Use only exact frozen read/search evidence")),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(Capability.READ_PROJECT),
                        NetworkPolicy.DENY_ALL, List.of(),
                        new ResourceLimits(
                                Duration.ofMinutes(10),
                                Duration.ofMinutes(2),
                                256L * 1024 * 1024,
                                64L * 1024, 1),
                        Set.of()),
                new InitialPlanDraft(
                        "Explicit read-only Project analysis", steps),
                now, now.plusMillis(1), now.plusMillis(2));
    }

    private List<ProjectAnalysisDeliveryTransactions.StepAuthority>
            authorities(String planId, Request request) {
        List<ProjectAnalysisDeliveryTransactions.StepAuthority> values =
                new ArrayList<>();
        int index = 1;
        for (String path : request.paths()) {
            String arguments = readArguments(path);
            values.add(new ProjectAnalysisDeliveryTransactions.StepAuthority(
                    String.format("project-read-%02d", index++),
                    "project.read", arguments, hash(arguments)));
        }
        if (request.searchQuery() != null) {
            String arguments = searchArguments(
                    request.searchQuery(), request.maxSearchResults());
            values.add(new ProjectAnalysisDeliveryTransactions.StepAuthority(
                    "project-search-01", "project.search",
                    arguments, hash(arguments)));
        }
        return List.copyOf(values);
    }

    private static FreshExecutionStartAttempt startAttempt(
            ProjectAnalysisDeliveryEntity delivery, Instant now) {
        String suffix = hash(delivery.id().toString()
                + "\0" + delivery.requestSha256());
        return new FreshExecutionStartAttempt(
                delivery.leaseOwnerId(), delivery.leaseToken(),
                delivery.leaseExpiresAt(),
                new ExecutionStartEventDraft(
                        new EventId("project-analysis-start-" + suffix),
                        now.plusMillis(3), new EventType("PLAN_STARTED"),
                        Optional.empty(), "project-analysis-" + suffix,
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                now.plusMillis(4));
    }

    private static PersistentPlanAgentLoopCommand loopCommand(
            ProjectAnalysisDeliveryEntity delivery,
            int steps, Instant now) {
        String suffix = hash(delivery.id().toString()
                + "\0" + delivery.requestSha256());
        return new PersistentPlanAgentLoopCommand(
                steps,
                new StepRecoveryLeaseAttempt(
                        delivery.leaseOwnerId(), delivery.leaseToken(),
                        delivery.leaseExpiresAt()),
                new StepActivationAttempt(
                        delivery.leaseOwnerId(), delivery.leaseToken(),
                        delivery.leaseExpiresAt(),
                        new StepActivationEventDraft(
                                new EventId(
                                        "project-analysis-activate-" + suffix),
                                now.plusMillis(5),
                                new EventType("STEP_ACTIVATED"),
                                Optional.empty(),
                                "project-analysis-" + suffix,
                                new InlineEventPayload(
                                        new ObjectValue(Map.of()))),
                        now.plusMillis(6)),
                new EffectDrivenStepProgressionActivationLeaseAttempt(
                        delivery.leaseOwnerId(), delivery.leaseToken(),
                        delivery.leaseExpiresAt()),
                Optional.empty());
    }

    private String readArguments(String path) {
        ObjectNode node = json.createObjectNode();
        node.put("path", path);
        return write(node);
    }

    private String searchArguments(String query, int maximum) {
        ObjectNode node = json.createObjectNode();
        node.put("maxResults", maximum);
        node.put("query", query);
        return write(node);
    }

    private String write(ObjectNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Project analysis request is invalid");
        }
    }

    private static Request normalize(V2ProjectAnalysisRequest input) {
        if (input == null) throw invalid();
        String objective = input.objective() == null
                ? "" : input.objective().strip();
        String requestId = normalizeRequestId(input.clientRequestId());
        int maximum = input.maxSearchResults() == null
                ? 10 : input.maxSearchResults();
        String query = input.searchQuery() == null
                ? null : input.searchQuery().strip();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (input.paths() != null) {
            for (String raw : input.paths()) {
                String path = raw == null ? "" : raw.strip();
                if (!portable(path) || !paths.add(path)) throw invalid();
            }
        }
        if (objective.isBlank() || objective.length() > 2000
                || paths.isEmpty() || paths.size() > 4
                || requestId.length() > 128
                || query != null && (query.isBlank() || query.length() > 256)
                || maximum < 1 || maximum > 20) {
            throw invalid();
        }
        return new Request(
                objective, List.copyOf(paths), query, maximum, requestId);
    }

    private static String normalizeRequestId(String value) {
        String result = value == null ? "" : value.strip();
        if (result.isBlank() || result.length() > 128) throw invalid();
        return result;
    }

    private static boolean portable(String path) {
        if (path.isBlank() || path.startsWith("/")
                || path.startsWith("\\") || path.contains("\\")
                || path.matches("^[A-Za-z]:.*") || path.endsWith("/")
                || path.contains("//")) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment)
                    || "..".equals(segment)) return false;
        }
        return true;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "V2 Project analysis request is invalid");
    }

    private static void logFailure(RuntimeException failure) {
        FailureDiagnostic diagnostic = failureDiagnostic(failure);
        LOGGER.warn(
                "v2ProjectAnalysisFailure stage={} errorCode={} failureType={}",
                diagnostic.stage(), "PROJECT_ANALYSIS_FAILED",
                diagnostic.failureType());
    }

    static FailureDiagnostic failureDiagnostic(RuntimeException failure) {
        String stage = "execution";
        String failureType = failure.getClass().getName();
        if (failure instanceof StartFailure startFailure) {
            stage = "fresh_start";
            failureType = startFailure.failureType;
        } else if (failure instanceof PersistentPlanAgentLoopException loopFailure) {
            String diagnosticStage = loopFailure.diagnosticStage();
            stage = loopStage(diagnosticStage == null
                    ? loopFailure.stage() : diagnosticStage);
        }
        return new FailureDiagnostic(stage, failureType);
    }

    private static String loopStage(String stage) {
        return stage != null && stage.matches("[a-z0-9._-]{1,64}")
                ? "loop." + stage : "loop.unknown";
    }

    record FailureDiagnostic(String stage, String failureType) {}

    private static final class StartFailure extends IllegalStateException {
        private final String failureType;

        private StartFailure(String failureType, RuntimeException cause) {
            super("V2 Project analysis fresh start failed", cause);
            this.failureType = failureType;
        }

        private static StartFailure thrown(RuntimeException failure) {
            return new StartFailure(failure.getClass().getName(), failure);
        }

        private static StartFailure rejected(Object outcome) {
            return new StartFailure(outcome.getClass().getName(), null);
        }
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
            String objective, List<String> paths, String searchQuery,
            int maxSearchResults, String requestId) {
        String canonical() {
            return objective + "\0" + String.join("\0", paths)
                    + "\0" + searchQuery + "\0" + maxSearchResults;
        }
        int steps() {
            return paths.size() + (searchQuery == null ? 0 : 1);
        }
    }
}
