package com.yanban.api.agent.v2.adaptive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.V2SafeFailureDiagnostics;
import com.yanban.api.agent.v2.adaptive.reflection.*;
import com.yanban.api.agent.v2.bootstrap.*;
import com.yanban.api.agent.v2.workspace.*;
import com.yanban.api.agent.v2.effect.project.NaturalLanguageCandidateAuthorityStore;
import com.yanban.api.agent.v2.effect.project.ProjectCandidateCompositionEffect;
import com.yanban.api.agent.v2.result.V2StepResultService;
import com.yanban.api.project.AgentCandidateAutoApplicationService;
import io.paperagent.v2.contracts.*;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.context.composition.*;
import io.paperagent.v2.runtime.execution.recovery.composition.*;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class V2AdaptiveExecutionService {
    private static final Logger log = LoggerFactory.getLogger(
            V2AdaptiveExecutionService.class);
    private final V2AdaptiveExecutionStore store;
    private final AuthenticatedAgentTurnExecutionStartRecoveryComposer starts;
    private final AuthenticatedAgentTurnPlanExecutionContextComposer contexts;
    private final V2AdaptiveRuntimeCycleFactory cycles;
    private final ObjectMapper json;
    private final ProjectCandidateCompositionEffect candidates;
    private final NaturalLanguageCandidateAuthorityStore candidateAuthorities;
    private final V2StepResultService stepResults;
    private final V2AdaptiveFinalSynthesisService finalSynthesis;
    private final AgentCandidateAutoApplicationService autoApplications;

    public V2AdaptiveExecutionService(
            V2AdaptiveExecutionStore store,
            AuthenticatedAgentTurnExecutionStartRecoveryComposer starts,
            AuthenticatedAgentTurnPlanExecutionContextComposer contexts,
            V2AdaptiveRuntimeCycleFactory cycles,
            ObjectMapper json) {
        this(store, starts, contexts, cycles, json,
                null, null, null, null, null);
    }

    public V2AdaptiveExecutionService(
            V2AdaptiveExecutionStore store,
            AuthenticatedAgentTurnExecutionStartRecoveryComposer starts,
            AuthenticatedAgentTurnPlanExecutionContextComposer contexts,
            V2AdaptiveRuntimeCycleFactory cycles,
            ObjectMapper json,
            ProjectCandidateCompositionEffect candidates,
            NaturalLanguageCandidateAuthorityStore candidateAuthorities) {
        this(store, starts, contexts, cycles, json,
                candidates, candidateAuthorities, null, null, null);
    }

    public V2AdaptiveExecutionService(
            V2AdaptiveExecutionStore store,
            AuthenticatedAgentTurnExecutionStartRecoveryComposer starts,
            AuthenticatedAgentTurnPlanExecutionContextComposer contexts,
            V2AdaptiveRuntimeCycleFactory cycles,
            ObjectMapper json,
            ProjectCandidateCompositionEffect candidates,
            NaturalLanguageCandidateAuthorityStore candidateAuthorities,
            V2StepResultService stepResults) {
        this(store, starts, contexts, cycles, json, candidates,
                candidateAuthorities, stepResults, null, null);
    }

    public V2AdaptiveExecutionService(
            V2AdaptiveExecutionStore store,
            AuthenticatedAgentTurnExecutionStartRecoveryComposer starts,
            AuthenticatedAgentTurnPlanExecutionContextComposer contexts,
            V2AdaptiveRuntimeCycleFactory cycles,
            ObjectMapper json,
            ProjectCandidateCompositionEffect candidates,
            NaturalLanguageCandidateAuthorityStore candidateAuthorities,
            V2StepResultService stepResults,
            V2AdaptiveFinalSynthesisService finalSynthesis) {
        this(store, starts, contexts, cycles, json, candidates,
                candidateAuthorities, stepResults, finalSynthesis, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public V2AdaptiveExecutionService(
            V2AdaptiveExecutionStore store,
            AuthenticatedAgentTurnExecutionStartRecoveryComposer starts,
            AuthenticatedAgentTurnPlanExecutionContextComposer contexts,
            V2AdaptiveRuntimeCycleFactory cycles,
            ObjectMapper json,
            ProjectCandidateCompositionEffect candidates,
            NaturalLanguageCandidateAuthorityStore candidateAuthorities,
            V2StepResultService stepResults,
            V2AdaptiveFinalSynthesisService finalSynthesis,
            AgentCandidateAutoApplicationService autoApplications) {
        this.store = store;
        this.starts = starts;
        this.contexts = contexts;
        this.cycles = cycles;
        this.json = json;
        this.candidates = candidates;
        this.candidateAuthorities = candidateAuthorities;
        this.stepResults = stepResults;
        this.finalSynthesis = finalSynthesis;
        this.autoApplications = autoApplications;
    }

    public V2AdaptiveExecutionResult execute(Command command) {
        Optional<List<V2AdaptiveTurnResponse.Step>> resumed =
                store.runningSteps(
                        command.userId(), command.sessionId(),
                        command.clientRequestId());
        if (resumed == null) {
            resumed = Optional.empty();
        }
        List<V2AdaptiveTurnResponse.Step> initial =
                resumed.orElseGet(() -> initialSteps(
                                command.bootstrap().plan()
                                        .latestRevision()));
        store.open(command.intakeId(), command.userId(),
                command.sessionId(), command.clientRequestId(),
                command.bootstrap().plan().id().value(),
                command.projectVersion(), initial);
        V2AdaptiveExecutionResult result;
        try {
            result = executeStarted(command, initial);
        } catch (RuntimeException failure) {
            logFailure(command, "adaptive.execute", failure);
            result = failed(initial, "ADAPTIVE_EXECUTION_FAILED");
        }
        result = publishCandidateIfNeeded(command, result);
        result = synthesizeFinalAnswer(command, result);
        if ("RUNNING".equals(result.status())) {
            store.progress(
                    command.userId(), command.sessionId(),
                    command.clientRequestId(), result.steps(),
                    result.reflections(), result.replans(),
                    result.repairs());
        } else {
            store.finish(command.userId(), command.sessionId(),
                    command.clientRequestId(), result.status(),
                    result.steps(), result.finalText(),
                    result.candidateArtifactId(),
                    result.outputPaths(), result.errorCode(),
                    result.reflections(), result.replans(),
                    result.repairs());
        }
        return result;
    }

    private V2AdaptiveExecutionResult synthesizeFinalAnswer(
            Command command, V2AdaptiveExecutionResult result) {
        if (finalSynthesis == null
                || !("SUCCEEDED".equals(result.status())
                || "WAITING_CONFIRMATION".equals(result.status()))) {
            return result;
        }
        Optional<String> synthesized = finalSynthesis.synthesize(
                new V2AdaptiveFinalSynthesisService.Request(
                        command.bootstrap().taskFrame(),
                        command.bootstrap().plan(),
                        result.candidateArtifactId(),
                        result.outputPaths(), result.appliedRevisionId(),
                        result.appliedProjectVersion(),
                        command.modelProvider()));
        if (synthesized.isEmpty()) {
            return result;
        }
        return new V2AdaptiveExecutionResult(
                result.status(), result.steps(),
                synthesized.orElseThrow(), result.errorCode(),
                result.reflections(), result.replans(),
                result.repairs(), result.candidateArtifactId(),
                result.outputPaths(), result.appliedRevisionId(),
                result.appliedProjectVersion());
    }

    public boolean canResume(
            Long userId, Long sessionId, String clientRequestId) {
        return store.isRunning(userId, sessionId, clientRequestId);
    }

    private V2AdaptiveExecutionResult publishCandidateIfNeeded(
            Command command, V2AdaptiveExecutionResult result) {
        if (!"SUCCEEDED".equals(result.status())) {
            return result;
        }
        if (candidateAuthorities == null || candidates == null) {
            return result;
        }
        try {
            String planId = command.bootstrap().plan().id().value();
            var authority = command.bindings().containsValue(
                    "project.candidate.compose")
                    ? candidateAuthorities.require(planId)
                    : candidateAuthorities.find(planId).orElse(null);
            if (authority == null) {
                return result;
            }
            var published = candidates.publishNatural(
                    command.bootstrap().plan().id().value(),
                    command.userId(), command.turnId(),
                    candidateAuthorities);
            if (published.artifactId() == null) {
                return candidateFailed(result);
            }
            log.info(
                    "V2 Candidate published intakeId={} turnId={} "
                            + "planId={} artifactId={} pathCount={}",
                    command.intakeId(), command.turnId(), planId,
                    published.artifactId(), authority.paths().size());
            if (autoApplications != null) {
                try {
                    var applied = autoApplications.apply(
                            command.userId(), command.turnId(), planId,
                            published.artifactId());
                    log.info(
                            "V2 Candidate automatically applied intakeId={} "
                                    + "turnId={} planId={} artifactId={} "
                                    + "revisionId={}",
                            command.intakeId(), command.turnId(), planId,
                            published.artifactId(),
                            applied.resultRevisionId());
                    return new V2AdaptiveExecutionResult(
                            "SUCCEEDED", result.steps(), result.finalText(),
                            null, result.reflections(), result.replans(),
                            result.repairs(), published.artifactId(),
                            authority.paths(), applied.resultRevisionId(),
                            applied.resultVersion());
                } catch (RuntimeException failure) {
                    logFailure(command, "candidate.auto-apply", failure);
                    return candidateAutoApplyFailed(
                            result, published.artifactId(),
                            authority.paths());
                }
            }
            return new V2AdaptiveExecutionResult(
                    "WAITING_CONFIRMATION", result.steps(),
                    result.finalText(), null, result.reflections(),
                    result.replans(), result.repairs(),
                    published.artifactId(), authority.paths());
        } catch (RuntimeException failure) {
            logFailure(command, "candidate.publish", failure);
            return candidateFailed(result);
        }
    }

    private static V2AdaptiveExecutionResult candidateFailed(
            V2AdaptiveExecutionResult source) {
        return new V2AdaptiveExecutionResult(
                "FAILED", source.steps(), null,
                "CANDIDATE_PUBLISH_FAILED",
                source.reflections(), source.replans(),
                source.repairs());
    }

    private static V2AdaptiveExecutionResult candidateAutoApplyFailed(
            V2AdaptiveExecutionResult source, Long artifactId,
            List<String> paths) {
        return new V2AdaptiveExecutionResult(
                "FAILED", source.steps(), null,
                "CANDIDATE_AUTO_APPLY_FAILED",
                source.reflections(), source.replans(),
                source.repairs(), artifactId, paths);
    }

    private V2AdaptiveExecutionResult executeStarted(
            Command command, List<V2AdaptiveTurnResponse.Step> initial) {
        Instant base = command.authorityTime().truncatedTo(
                java.time.temporal.ChronoUnit.MICROS);
        String suffix = shortHash(command.bootstrap().plan().id().value());
        String owner = "adaptive-owner-" + suffix;
        String token = "adaptive-token-" + suffix;
        Instant expires = base.plus(Duration.ofMinutes(15));
        var attempt = new FreshExecutionStartAttempt(
                owner, token, expires,
                new ExecutionStartEventDraft(
                        new EventId("adaptive-start-" + suffix),
                        base.plusMillis(3),
                        new EventType("PLAN_STARTED"),
                        Optional.empty(), "adaptive-" + suffix,
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                base.plusMillis(4));
        RecoveredExecutionStart recovered;
        try {
            var started = starts.recover(
                    command.userId(), command.turnId(),
                    new AuthenticatedAgentTurnExecutionStartRecoveryCommand(
                            Optional.of(attempt)));
            if (!(started instanceof RecoveredExecutionStart value)
                    || !value.planId().equals(
                            command.bootstrap().plan().id())) {
                return failed(initial, "EXECUTION_START_REJECTED");
            }
            recovered = value;
        } catch (ExecutionStartRecoveryProtocolException failure) {
            return failed(initial, boundedCode(
                    "EXEC_START_" + failure.stage().name()
                            + "_" + failure.code().name()));
        } catch (ExecutionStartRecoveryValidationException failure) {
            return failed(initial, boundedCode(
                    "EXEC_START_VALIDATION_" + failure.code().name()));
        } catch (RuntimeException failure) {
            logFailure(command, "execution.start", failure);
            return failed(initial, "EXECUTION_START_EXCEPTION");
        }
        if (command.projectVersion() != null) {
            try {
                Optional<PlanExecutionContextLeaseAttempt> contextAttempt =
                        recovered.resolution()
                                == ExecutionStartRecoveryResolution
                                        .OBSERVED_COMMITTED
                                ? Optional.empty()
                                : Optional.of(
                                        new PlanExecutionContextLeaseAttempt(
                                                owner, token, expires));
                var context = contexts.compose(
                        command.userId(), command.turnId(),
                        new AuthenticatedAgentTurnPlanExecutionContextCommand(
                                contextAttempt));
                if (!(context instanceof PlanExecutionContextReady ready)
                        || !ready.planId().equals(recovered.planId())) {
                    return failed(initial, "WORKSPACE_CONTEXT_REJECTED");
                }
            } catch (RuntimeException failure) {
                logWorkspaceFailure(command, failure);
                logFailure(command, "workspace.context", failure);
                return failed(initial, "WORKSPACE_CONTEXT_EXCEPTION");
            }
        }
        V2AdaptiveCyclePort cyclePort;
        try {
            cyclePort = cycles.create(
                    command.bindings(), owner, token, expires,
                    suffix, base, command.modelProvider());
        } catch (RuntimeException failure) {
            logFailure(command, "cycle.setup", failure);
            return failed(initial, "CYCLE_SETUP_EXCEPTION");
        }
        var coordinator = new V2AdaptiveExecutionCoordinator(
                cyclePort,
                new V2ModelReflectionProvider(
                        command.modelProvider(), json,
                        command.bootstrap().taskFrame().id(),
                        command.bootstrap().plan().id(),
                        command.bootstrap().plan()
                                .latestRevision().id()),
                new StrictReflectionDecisionParser(json),
                stepResults, cycles.contextSource());
        try {
            return coordinator.execute(
                    new V2AdaptiveExecutionCoordinator.Command(
                            command.userId(), command.turnId(),
                            recovered.planId().value(), initial,
                            command.bindings(),
                            stepIndexes(command.bootstrap().plan()
                                    .latestRevision()),
                            new ReflectionContext(
                                    taskFrameFacts(
                                            command.bootstrap().taskFrame()),
                                    planFacts(command.bootstrap().plan()),
                                    command.conversationContext(),
                                    List.of(), List.of(),
                                    unfinished(initial))));
        } catch (RuntimeException failure) {
            logFailure(command, "coordination", failure);
            return failed(initial, "COORDINATION_EXCEPTION");
        }
    }

    private static void logFailure(
            Command command, String stage, RuntimeException failure) {
        log.warn(
                "V2 adaptive boundary failed stage={} intakeId={} "
                        + "turnId={} planId={} exceptionType={} "
                        + "causeType={} origin={}",
                stage,
                command.intakeId(),
                command.turnId(),
                command.bootstrap().plan().id().value(),
                V2SafeFailureDiagnostics.exceptionType(failure),
                V2SafeFailureDiagnostics.causeType(failure),
                V2SafeFailureDiagnostics.origin(failure));
    }

    private static void logWorkspaceFailure(
            Command command, RuntimeException failure) {
        if (failure
                instanceof PlanExecutionContextCompositionValidationException
                        validation) {
            log.warn(
                    "V2 workspace context validation failed intakeId={} "
                            + "turnId={} planId={} validationCode={} "
                            + "validationPath={}",
                    command.intakeId(), command.turnId(),
                    command.bootstrap().plan().id().value(),
                    validation.code(), validation.path());
        } else if (failure
                instanceof PlanExecutionContextCompositionProtocolException
                        protocol) {
            log.warn(
                    "V2 workspace context protocol failed intakeId={} "
                            + "turnId={} planId={} protocolStage={} "
                            + "protocolCode={} protocolPath={}",
                    command.intakeId(), command.turnId(),
                    command.bootstrap().plan().id().value(),
                    protocol.stage(), protocol.code(), protocol.path());
        }
    }

    private static V2AdaptiveExecutionResult failed(
            List<V2AdaptiveTurnResponse.Step> steps, String code) {
        return new V2AdaptiveExecutionResult(
                "FAILED", steps, null, code, 0, 0, 0);
    }

    private static String boundedCode(String value) {
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private static List<V2AdaptiveTurnResponse.Step> initialSteps(
            PlanRevision revision) {
        List<V2AdaptiveTurnResponse.Step> result = new ArrayList<>();
        int index = 1;
        for (PlanStep step : revision.steps()) {
            result.add(new V2AdaptiveTurnResponse.Step(
                    index++, step.intent(), "PENDING", ""));
        }
        return List.copyOf(result);
    }

    private static Map<String, Integer> stepIndexes(PlanRevision revision) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < revision.steps().size(); index++) {
            result.put(revision.steps().get(index).id().value(), index);
        }
        return Map.copyOf(result);
    }

    private static List<String> unfinished(
            List<V2AdaptiveTurnResponse.Step> steps) {
        return steps.stream().map(V2AdaptiveTurnResponse.Step::title).toList();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("adaptive facts encoding failed");
        }
    }

    private String taskFrameFacts(TaskFrame frame) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("id", frame.id().value());
        facts.put("objective", frame.objective());
        facts.put("targets", frame.targets());
        facts.put("deliverables", frame.deliverables());
        facts.put("constraints", frame.constraints());
        facts.put("projectVersion",
                frame.sourceProjectVersion()
                        .map(ProjectVersionRef::versionId).orElse(null));
        return write(facts);
    }

    private String planFacts(Plan plan) {
        PlanRevision revision = plan.latestRevision();
        List<Map<String, Object>> steps = revision.steps().stream()
                .map(step -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", step.id().value());
                    value.put("intent", step.intent());
                    value.put("expectedOutcome", step.expectedOutcome());
                    value.put("dependencies", step.dependencies().stream()
                            .map(PlanStepId::value).sorted().toList());
                    value.put("completionCriteria",
                            step.completionCriteria());
                    return value;
                }).toList();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("planId", plan.id().value());
        facts.put("revisionId", revision.id().value());
        facts.put("revisionNumber", revision.number());
        facts.put("reason", revision.reason());
        facts.put("steps", steps);
        return write(facts);
    }

    private static String shortHash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public record Command(
            Long intakeId, Long userId, Long sessionId, Long turnId,
            String clientRequestId, String projectVersion,
            PersistedPlanBootstrap bootstrap, Map<String, String> bindings,
            List<String> conversationContext, Instant authorityTime,
            ModelProvider modelProvider) {
        public Command {
            bindings = Map.copyOf(bindings);
            conversationContext = List.copyOf(conversationContext);
            Objects.requireNonNull(modelProvider, "modelProvider");
        }
    }
}
