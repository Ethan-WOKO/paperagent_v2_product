package com.yanban.api.agent.v2.compatibility.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapCommand;
import com.yanban.api.agent.v2.bootstrap.*;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.effect.project.ProjectCandidateCompositionEffect;
import com.yanban.api.agent.v2.loop.*;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionActivationLeaseAttempt;
import com.yanban.api.agent.v2.workspace.*;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.*;
import io.paperagent.v2.contracts.*;
import io.paperagent.v2.persistence.*;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationAttempt;
import io.paperagent.v2.runtime.execution.activation.materialization.StepActivationEventDraft;
import io.paperagent.v2.runtime.execution.context.composition.*;
import io.paperagent.v2.runtime.execution.recovery.composition.*;
import io.paperagent.v2.runtime.execution.start.*;
import io.paperagent.v2.runtime.planning.InitialPlanDraft;
import io.paperagent.v2.runtime.routing.*;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class V2ProjectCandidateService {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(V2ProjectCandidateService.class);
    private final Object[] locks = java.util.stream.IntStream.range(0, 64)
            .mapToObj(ignored -> new Object()).toArray();
    private final AgentSessionRepository sessions;
    private final ProjectService projects;
    private final ProjectCandidateDeliveryTransactions deliveries;
    private final AuthenticatedAgentTurnFreshExecutionStartComposer starts;
    private final AuthenticatedAgentTurnPlanExecutionContextComposer contexts;
    private final AuthenticatedPersistentPlanAgentLoopComposer loop;
    private final StepRecoveryRepository recovery;
    private final LeaseRepository leases;
    private final AgentTurnProductContextResolver turnContexts;
    private final AuthenticatedAgentTurnWorkspacePortFactory workspaces;
    private final ProjectCandidateCompositionEffect composition;
    private final ObjectMapper json;

    public V2ProjectCandidateService(AgentSessionRepository sessions, ProjectService projects,
            ProjectCandidateDeliveryTransactions deliveries,
            AuthenticatedAgentTurnFreshExecutionStartComposer starts,
            AuthenticatedAgentTurnPlanExecutionContextComposer contexts,
            AuthenticatedPersistentPlanAgentLoopComposer loop,
            StepRecoveryRepository recovery, LeaseRepository leases,
            AgentTurnProductContextResolver turnContexts,
            AuthenticatedAgentTurnWorkspacePortFactory workspaces,
            ProjectCandidateCompositionEffect composition, ObjectMapper json) {
        this.sessions = sessions; this.projects = projects; this.deliveries = deliveries;
        this.starts = starts; this.contexts = contexts; this.loop = loop;
        this.recovery = recovery; this.leases = leases;
        this.turnContexts = turnContexts; this.workspaces = workspaces;
        this.composition = composition; this.json = json;
    }

    public V2ProjectCandidateResponse execute(Long userId, Long projectId, Long sessionId,
                                               V2ProjectCandidateRequest input) {
        String key = userId + "\0" + projectId + "\0" + sessionId + "\0"
                + (input == null ? "" : input.clientRequestId());
        synchronized (locks[(key.hashCode() & Integer.MAX_VALUE) % locks.length]) {
            return executeSerialized(userId, projectId, sessionId, input);
        }
    }

    public V2ProjectCandidateResponse read(Long userId, Long projectId, Long sessionId,
                                            String clientRequestId) {
        ProjectCandidateDeliveryEntity delivery;
        try {
            delivery = deliveries.find(new ProjectCandidateDeliveryKey(
                    userId, projectId, sessionId, normalizeRequestId(clientRequestId)));
        } catch (IllegalArgumentException missing) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "V2 Project Candidate turn was not found");
        }
        if (terminal(delivery)) return response(delivery, true);
        return execute(userId, projectId, sessionId, new V2ProjectCandidateRequest(
                delivery.objective(), deliveries.paths(delivery),
                delivery.id().clientRequestId()));
    }

    private V2ProjectCandidateResponse executeSerialized(Long userId, Long projectId,
            Long sessionId, V2ProjectCandidateRequest input) {
        Request request = normalize(input);
        var session = sessions.findByIdAndUserId(sessionId, userId).orElseThrow(
                () -> new IllegalArgumentException("project agent session was not found"));
        if (session.getScope() != AgentSessionScope.PROJECT
                || !projectId.equals(session.getProjectId())) throw invalid();
        String requestHash = hash(request.canonical());
        var key = new ProjectCandidateDeliveryKey(userId, projectId, sessionId, request.requestId());
        var existing = deliveries.findMatching(key, requestHash);
        if (existing.isPresent() && terminal(existing.orElseThrow())) {
            return response(existing.orElseThrow(), true);
        }
        String owner = "v2-project-candidate-" + hash(keyBinding(key)).substring(0, 24);
        Instant now = ProjectLeaseAuthorityTime.canonical(Instant.now());
        String token = "lease-" + hash(owner + "\0" + requestHash);
        ProjectCandidateDeliveryEntity delivery;
        if (existing.isPresent()) {
            delivery = existing.orElseThrow();
        } else {
            var manifest = projects.manifest(userId, projectId);
            requireManifest(projectId, request.paths(), manifest);
            delivery = deliveries.open(userId, projectId, sessionId, request.requestId(),
                    requestHash, request.objective(), request.paths(), manifest.version(),
                    owner, token, now.plus(Duration.ofMinutes(5)));
        }
        if (!delivery.leaseExpiresAt().isAfter(now)) {
            delivery = deliveries.rotateExpiredLease(key,
                    "lease-" + hash(owner + "\0" + requestHash + "\0"
                            + now.toEpochMilli() + "\0recovery"),
                    now.plus(Duration.ofMinutes(5)), now);
            if (delivery.planId() != null) {
                var takeover = leases.acquire(new PlanId(delivery.planId()),
                        delivery.leaseOwnerId(), delivery.leaseToken(),
                        delivery.leaseExpiresAt());
                if (takeover.outcome() != PersistenceOutcome.APPLIED
                        && takeover.outcome() != PersistenceOutcome.REPLAYED) {
                    return response(deliveries.fail(
                            key, "PROJECT_CANDIDATE_LEASE_FAILED"), false);
                }
            }
        }
        try {
            return run(request, delivery, userId, projectId, now);
        } catch (RuntimeException failure) {
            logFailure(failure);
            var failed = deliveries.fail(key, "PROJECT_CANDIDATE_FAILED");
            if (failed.artifactId() != null) throw failure;
            return response(failed, false);
        }
    }

    private V2ProjectCandidateResponse run(Request request,
            ProjectCandidateDeliveryEntity delivery, Long userId, Long projectId, Instant now) {
        var verified = turnContexts.resolve(userId, delivery.turnId());
        if (!projectId.equals(verified.identity().projectId())
                || verified.projectVersionId().filter(
                delivery.projectVersionId()::equals).isEmpty()) throw failed();
        FreshExecutionStartOutcome started;
        try {
            started = starts.start(userId, delivery.turnId(),
                    new AuthenticatedAgentTurnFreshExecutionStartCommand(
                            bootstrap(request, delivery.turnId(), now),
                            Optional.of(startAttempt(delivery, now))));
        } catch (RuntimeException failure) {
            throw StartFailure.thrown(failure);
        }
        PlanId planId;
        if (started instanceof FreshExecutionStarted value) {
            planId = value.persistedStart().planId();
        } else if (started instanceof FreshExecutionRecoveryRequired value) {
            planId = value.planId();
        } else {
            throw StartFailure.rejected(started);
        }
        delivery = deliveries.bindPlanAndSteps(delivery.id(), planId.value(),
                authorities(request));
        var context = contexts.compose(userId, delivery.turnId(),
                new AuthenticatedAgentTurnPlanExecutionContextCommand(
                        Optional.of(new PlanExecutionContextLeaseAttempt(
                                delivery.leaseOwnerId(), delivery.leaseToken(),
                                delivery.leaseExpiresAt()))));
        if (!(context instanceof PlanExecutionContextReady ready)
                || !ready.planId().equals(planId)) throw failed();
        delivery = deliveries.bindWorkspace(delivery.id(),
                ready.verifiedWorkspace().workspace().id().value());
        var result = loop.execute(userId, delivery.turnId(),
                loopCommand(delivery, request.paths().size() + 1, now));
        if (result.state() != PersistentPlanAgentLoopState.PLAN_SUCCEEDED) throw failed();
        var inspected = recovery.inspect(planId);
        if (inspected.outcome() != PersistenceOutcome.FOUND
                || !(inspected.value().orElse(null)
                instanceof PersistedStepRecoverySucceeded)) throw failed();
        if (delivery.artifactId() == null) {
            var workspace = workspaces.create(userId, delivery.turnId());
            var materialized = workspace.inspectMaterialization(
                    ready.persistedContext().materializationSpec());
            composition.publish(planId.value(), userId, delivery.turnId(), workspace,
                    materialized.workspace(), now.plusMillis(20));
        }
        delivery = deliveries.deliver(delivery.id());
        if (!projectId.equals(projects.manifest(userId, projectId).projectId())) throw failed();
        return response(delivery, false);
    }

    private ProductPersistentPlanBootstrapCommand bootstrap(Request request, Long turnId,
                                                             Instant now) {
        List<PlanStep> steps = new ArrayList<>();
        int index = 1;
        for (String path : request.paths()) {
            String arguments = readArguments(path);
            steps.add(new PlanStep(new PlanStepId(String.format("project-read-%02d", index++)),
                    "Call project.read exactly once with " + arguments,
                    "Return bounded UTF-8 source for exactly " + path,
                    Set.of(), List.of("One successful project.read receipt"),
                    new BoundedExecutionHints(1, Duration.ofMinutes(2))));
        }
        String compositionArguments = compositionArguments();
        steps.add(new PlanStep(new PlanStepId("project-candidate-compose"),
                "Call project.candidate.compose exactly once with " + compositionArguments
                        + ". The governed effect creates replacements for only the frozen targets.",
                "Produce one non-empty reviewable MODIFY-only Workspace diff",
                Set.of(), List.of("One successful project.candidate.compose receipt"),
                new BoundedExecutionHints(1, Duration.ofMinutes(3))));
        return new ProductPersistentPlanBootstrapCommand(
                new RoutingDecision(new RoutingRequestId("project-candidate-route-" + turnId),
                        Route.PERSISTENT_PLAN_EXECUTE,
                        RoutingDecisionReason.DECLARED_REQUIREMENT,
                        Set.of(RoutingRequirement.PROJECT_FILE_ACCESS,
                                RoutingRequirement.TOOL_USE)),
                new TaskFrameDraft(request.objective(), request.paths(),
                        List.of("Reviewable Candidate only"),
                        List.of("Modify only exact frozen paths in isolated Workspace",
                                "Do not apply or mutate the original Project")),
                new ExecutionProfile(ExecutionTier.SANDBOX_STANDARD,
                        Set.of(Capability.READ_PROJECT, Capability.WRITE_WORKSPACE),
                        NetworkPolicy.DENY_ALL, List.of(),
                        new ResourceLimits(Duration.ofMinutes(10), Duration.ofMinutes(3),
                                256L * 1024 * 1024, 256L * 1024, 1), Set.of()),
                new InitialPlanDraft("Explicit reviewable Project Candidate", steps),
                now, now.plusMillis(1), now.plusMillis(2));
    }

    private List<ProjectCandidateDeliveryTransactions.StepAuthority> authorities(Request request) {
        List<ProjectCandidateDeliveryTransactions.StepAuthority> values = new ArrayList<>();
        int index = 1;
        for (String path : request.paths()) {
            String arguments = readArguments(path);
            values.add(new ProjectCandidateDeliveryTransactions.StepAuthority(
                    String.format("project-read-%02d", index++), "project.read",
                    arguments, hash(arguments)));
        }
        String arguments = compositionArguments();
        values.add(new ProjectCandidateDeliveryTransactions.StepAuthority(
                "project-candidate-compose", ProjectCandidateCompositionEffect.KIND,
                arguments, hash(arguments)));
        return List.copyOf(values);
    }

    private static FreshExecutionStartAttempt startAttempt(
            ProjectCandidateDeliveryEntity delivery, Instant now) {
        String suffix = hash(keyBinding(delivery.id()) + "\0" + delivery.requestSha256());
        return new FreshExecutionStartAttempt(delivery.leaseOwnerId(), delivery.leaseToken(),
                delivery.leaseExpiresAt(), new ExecutionStartEventDraft(
                new EventId("project-candidate-start-" + suffix), now.plusMillis(3),
                new EventType("PLAN_STARTED"), Optional.empty(),
                "project-candidate-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of()))), now.plusMillis(4));
    }

    private static PersistentPlanAgentLoopCommand loopCommand(
            ProjectCandidateDeliveryEntity delivery, int steps, Instant now) {
        String suffix = hash(keyBinding(delivery.id()) + "\0" + delivery.requestSha256());
        return new PersistentPlanAgentLoopCommand(steps,
                new StepRecoveryLeaseAttempt(delivery.leaseOwnerId(), delivery.leaseToken(),
                        delivery.leaseExpiresAt()),
                new StepActivationAttempt(delivery.leaseOwnerId(), delivery.leaseToken(),
                        delivery.leaseExpiresAt(), new StepActivationEventDraft(
                        new EventId("project-candidate-activate-" + suffix), now.plusMillis(5),
                        new EventType("STEP_ACTIVATED"), Optional.empty(),
                        "project-candidate-" + suffix,
                        new InlineEventPayload(new ObjectValue(Map.of()))), now.plusMillis(6)),
                new EffectDrivenStepProgressionActivationLeaseAttempt(
                        delivery.leaseOwnerId(), delivery.leaseToken(),
                        delivery.leaseExpiresAt()), Optional.empty());
    }

    private V2ProjectCandidateResponse response(ProjectCandidateDeliveryEntity value,
                                                 boolean replayed) {
        return new V2ProjectCandidateResponse(value.id().projectId(), value.id().sessionId(),
                value.id().clientRequestId(), value.status(), terminal(value), value.turnId(),
                value.planId(), value.projectVersionId(), value.artifactId(),
                value.candidateFingerprint(), value.diffFingerprint(),
                value.assistantMessageId(), value.errorCode(), replayed);
    }

    private String readArguments(String path) {
        ObjectNode node = json.createObjectNode(); node.put("path", path); return write(node);
    }
    private String compositionArguments() {
        ObjectNode node = json.createObjectNode(); node.put("operation", "compose"); return write(node);
    }
    private String write(ObjectNode node) {
        try { return json.writeValueAsString(node); }
        catch (Exception failure) { throw invalid(); }
    }
    private static Request normalize(V2ProjectCandidateRequest input) {
        if (input == null) throw invalid();
        String objective = input.objective() == null ? "" : input.objective().strip();
        String requestId = normalizeRequestId(input.clientRequestId());
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (input.paths() != null) for (String raw : input.paths()) {
            String path = raw == null ? "" : raw.strip();
            if (!portable(path) || !paths.add(path)) throw invalid();
        }
        if (objective.isBlank() || objective.length() > 2000
                || paths.isEmpty() || paths.size() > 4) throw invalid();
        return new Request(objective, List.copyOf(paths), requestId);
    }
    private static void requireManifest(Long projectId, List<String> paths,
                                        com.yanban.api.project.ProjectManifestResponse manifest) {
        if (manifest == null || !projectId.equals(manifest.projectId())
                || manifest.version() == null || manifest.version().isBlank()) throw invalid();
        Map<String, com.yanban.api.project.ProjectFileEntry> files = new HashMap<>();
        manifest.files().forEach(file -> files.put(file.path(), file));
        for (String path : paths) {
            var file = files.get(path);
            if (file == null || file.sizeBytes() > 64 * 1024) throw invalid();
        }
    }
    private static String normalizeRequestId(String value) {
        String result = value == null ? "" : value.strip();
        if (result.isBlank() || result.length() > 128) throw invalid();
        return result;
    }
    private static boolean portable(String path) {
        if (path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                || path.contains("\\") || path.matches("^[A-Za-z]:.*")
                || path.endsWith("/") || path.contains("//")) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }
    private static boolean terminal(ProjectCandidateDeliveryEntity value) {
        return "SUCCEEDED".equals(value.status()) || "FAILED".equals(value.status());
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "V2 Project Candidate request is invalid");
    }

    private static void logFailure(RuntimeException failure) {
        FailureDiagnostic diagnostic = failureDiagnostic(failure);
        LOGGER.warn(
                "v2ProjectCandidateFailure stage={} errorCode={} failureType={}",
                diagnostic.stage(), "PROJECT_CANDIDATE_FAILED",
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
            super("V2 Project Candidate fresh start failed", cause);
            this.failureType = failureType;
        }

        private static StartFailure thrown(RuntimeException failure) {
            return new StartFailure(failure.getClass().getName(), failure);
        }

        private static StartFailure rejected(Object outcome) {
            return new StartFailure(outcome.getClass().getName(), null);
        }
    }
    private static IllegalStateException failed() {
        return new IllegalStateException("V2 Project Candidate execution failed");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String keyBinding(ProjectCandidateDeliveryKey key) {
        return key.userId() + "\0" + key.projectId() + "\0"
                + key.sessionId() + "\0" + key.clientRequestId();
    }
    private record Request(String objective, List<String> paths, String requestId) {
        String canonical() {
            StringBuilder value = new StringBuilder()
                    .append(objective.length()).append(':').append(objective)
                    .append(paths.size()).append(':');
            paths.forEach(path -> value.append(path.length()).append(':').append(path));
            return value.toString();
        }
    }
}
