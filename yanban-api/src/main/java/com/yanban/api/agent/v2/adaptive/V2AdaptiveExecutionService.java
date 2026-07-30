package com.yanban.api.agent.v2.adaptive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.adaptive.reflection.*;
import com.yanban.api.agent.v2.bootstrap.*;
import com.yanban.api.agent.v2.workspace.*;
import com.yanban.api.agent.v2.effect.project.NaturalLanguageCandidateAuthorityStore;
import com.yanban.api.agent.v2.effect.project.ProjectCandidateCompositionEffect;
import io.paperagent.v2.contracts.*;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.context.composition.*;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredExecutionStart;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class V2AdaptiveExecutionService {
    private final V2AdaptiveExecutionStore store;
    private final AuthenticatedAgentTurnExecutionStartRecoveryComposer starts;
    private final AuthenticatedAgentTurnPlanExecutionContextComposer contexts;
    private final V2AdaptiveRuntimeCycleFactory cycles;
    private final ObjectMapper json;
    private final ProjectCandidateCompositionEffect candidates;
    private final NaturalLanguageCandidateAuthorityStore candidateAuthorities;

    public V2AdaptiveExecutionService(
            V2AdaptiveExecutionStore store,
            AuthenticatedAgentTurnExecutionStartRecoveryComposer starts,
            AuthenticatedAgentTurnPlanExecutionContextComposer contexts,
            V2AdaptiveRuntimeCycleFactory cycles,
            ObjectMapper json) {
        this(store, starts, contexts, cycles, json,
                null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public V2AdaptiveExecutionService(
            V2AdaptiveExecutionStore store,
            AuthenticatedAgentTurnExecutionStartRecoveryComposer starts,
            AuthenticatedAgentTurnPlanExecutionContextComposer contexts,
            V2AdaptiveRuntimeCycleFactory cycles,
            ObjectMapper json,
            ProjectCandidateCompositionEffect candidates,
            NaturalLanguageCandidateAuthorityStore candidateAuthorities) {
        this.store = store;
        this.starts = starts;
        this.contexts = contexts;
        this.cycles = cycles;
        this.json = json;
        this.candidates = candidates;
        this.candidateAuthorities = candidateAuthorities;
    }

    public V2AdaptiveExecutionResult execute(Command command) {
        List<V2AdaptiveTurnResponse.Step> initial =
                initialSteps(command.bootstrap().plan().latestRevision());
        store.open(command.intakeId(), command.userId(),
                command.sessionId(), command.clientRequestId(),
                command.bootstrap().plan().id().value(),
                command.projectVersion(), initial);
        V2AdaptiveExecutionResult result;
        if (command.bindings().containsValue("sandbox.execute")) {
            result = new V2AdaptiveExecutionResult(
                    "FAILED", initial, null,
                    "SANDBOX_EXECUTION_UNAVAILABLE", 0, 0, 0);
        } else {
            try {
                result = executeStarted(command, initial);
            } catch (RuntimeException failure) {
                result = failed(initial, "ADAPTIVE_EXECUTION_FAILED");
            }
        }
        result = publishCandidateIfNeeded(command, result);
        store.finish(command.userId(), command.sessionId(),
                command.clientRequestId(), result.status(), result.steps(),
                result.finalText(), result.candidateArtifactId(),
                result.outputPaths(), result.errorCode(),
                result.reflections(), result.replans(), result.repairs());
        return result;
    }

    private V2AdaptiveExecutionResult publishCandidateIfNeeded(
            Command command, V2AdaptiveExecutionResult result) {
        if (!"SUCCEEDED".equals(result.status())) {
            return result;
        }
        if (!command.bindings().containsValue(
                "project.candidate.compose")) {
            return result;
        }
        if (candidateAuthorities == null || candidates == null) {
            return candidateFailed(result);
        }
        try {
            var authority = candidateAuthorities.require(
                    command.bootstrap().plan().id().value());
            var published = candidates.publishNatural(
                    command.bootstrap().plan().id().value(),
                    command.userId(), command.turnId(),
                    candidateAuthorities);
            if (published.artifactId() == null) {
                return candidateFailed(result);
            }
            return new V2AdaptiveExecutionResult(
                    "WAITING_CONFIRMATION", result.steps(),
                    result.finalText(), null, result.reflections(),
                    result.replans(), result.repairs(),
                    published.artifactId(), authority.paths());
        } catch (RuntimeException failure) {
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

    private V2AdaptiveExecutionResult executeStarted(
            Command command, List<V2AdaptiveTurnResponse.Step> initial) {
        Instant base = command.authorityTime();
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
        var started = starts.recover(
                command.userId(), command.turnId(),
                new AuthenticatedAgentTurnExecutionStartRecoveryCommand(
                        Optional.of(attempt)));
        if (!(started instanceof RecoveredExecutionStart recovered)
                || !recovered.planId().equals(command.bootstrap().plan().id())) {
            return failed(initial, "EXECUTION_START_REJECTED");
        }
        if (command.projectVersion() != null) {
            var context = contexts.compose(
                    command.userId(), command.turnId(),
                    new AuthenticatedAgentTurnPlanExecutionContextCommand(
                            Optional.of(new PlanExecutionContextLeaseAttempt(
                                    owner, token, expires))));
            if (!(context instanceof PlanExecutionContextReady ready)
                    || !ready.planId().equals(recovered.planId())) {
                return failed(initial, "WORKSPACE_CONTEXT_REJECTED");
            }
        }
        V2AdaptiveCyclePort cyclePort = cycles.create(
                command.bindings(), owner, token, expires,
                suffix, base, command.modelProvider());
        var coordinator = new V2AdaptiveExecutionCoordinator(
                cyclePort,
                new V2ModelReflectionProvider(
                        command.modelProvider(), json,
                        command.bootstrap().taskFrame().id(),
                        command.bootstrap().plan().id(),
                        command.bootstrap().plan()
                                .latestRevision().id()),
                new StrictReflectionDecisionParser(json));
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
                                List.of(), List.of(), unfinished(initial))));
    }

    private static V2AdaptiveExecutionResult failed(
            List<V2AdaptiveTurnResponse.Step> steps, String code) {
        return new V2AdaptiveExecutionResult(
                "FAILED", steps, null, code, 0, 0, 0);
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
