package com.yanban.api.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** Typed, observable execution facts. Model and user prose cannot populate these fields. */
public record DomainRuntimeFacts(
        List<ToolOutcome> toolOutcomes,
        List<PlanStepOutcome> planStepOutcomes,
        List<ConsistencyFact> consistencyFacts
) {
    private static final Pattern TRUSTED_TRACE_PREFIX = Pattern.compile(
            "^step=(\\d+) tool=([A-Za-z0-9_.-]+) executed=(true|false) budgetConsumed=(true|false) "
                    + "success=(true|false) reused=(true|false) skipped=(true|false)(?: |$)");

    public DomainRuntimeFacts {
        toolOutcomes = toolOutcomes == null ? List.of() : List.copyOf(toolOutcomes);
        planStepOutcomes = planStepOutcomes == null ? List.of() : List.copyOf(planStepOutcomes);
        consistencyFacts = consistencyFacts == null ? List.of() : List.copyOf(consistencyFacts);
    }

    public static DomainRuntimeFacts empty() {
        return new DomainRuntimeFacts(List.of(), List.of(), List.of());
    }

    /**
     * Parses only the runtime-owned prefix emitted before untrusted tool arguments. Legacy traces
     * deliberately produce no fact and therefore cannot satisfy domain verification.
     */
    public static DomainRuntimeFacts fromTrustedToolTrace(List<String> trace, List<String> allowedTools) {
        if (trace == null || trace.isEmpty() || allowedTools == null || allowedTools.isEmpty()) {
            return empty();
        }
        Set<String> allowed = Set.copyOf(allowedTools);
        List<ToolOutcome> outcomes = new ArrayList<>();
        int observationOrder = 0;
        for (String line : trace) {
            if (!StringUtils.hasText(line)) continue;
            Matcher matcher = TRUSTED_TRACE_PREFIX.matcher(line);
            if (!matcher.find() || !allowed.contains(matcher.group(2))) continue;
            outcomes.add(new ToolOutcome(
                    matcher.group(2),
                    ++observationOrder,
                    null,
                    Boolean.parseBoolean(matcher.group(3)),
                    Boolean.parseBoolean(matcher.group(4)),
                    Boolean.parseBoolean(matcher.group(5)),
                    Boolean.parseBoolean(matcher.group(6)),
                    Boolean.parseBoolean(matcher.group(7))
            ));
        }
        return new DomainRuntimeFacts(outcomes, List.of(), List.of());
    }

    public DomainRuntimeFacts merge(DomainRuntimeFacts other) {
        if (other == null) return this;
        List<ToolOutcome> tools = new ArrayList<>(toolOutcomes);
        tools.addAll(other.toolOutcomes);
        List<PlanStepOutcome> steps = new ArrayList<>(planStepOutcomes);
        steps.addAll(other.planStepOutcomes);
        List<ConsistencyFact> consistency = new ArrayList<>(consistencyFacts);
        consistency.addAll(other.consistencyFacts);
        return new DomainRuntimeFacts(tools, steps, consistency);
    }

    public DomainRuntimeFacts withExecutionAttempt(int executionAttempt) {
        return new DomainRuntimeFacts(toolOutcomes.stream()
                .map(outcome -> outcome.withExecutionAttempt(executionAttempt)).toList(),
                planStepOutcomes, consistencyFacts);
    }

    public DomainRuntimeFacts withConsistencyFacts(List<ConsistencyFact> additionalFacts) {
        LinkedHashSet<ConsistencyFact> facts = new LinkedHashSet<>(consistencyFacts);
        if (additionalFacts != null) additionalFacts.stream()
                .filter(java.util.Objects::nonNull).forEach(facts::add);
        return new DomainRuntimeFacts(toolOutcomes, planStepOutcomes, List.copyOf(facts));
    }

    public int consumedToolCalls() {
        return (int) toolOutcomes.stream().filter(ToolOutcome::budgetConsumed).count();
    }

    /** A failure is recovered only by a later trusted success in the same tool/material scope. */
    public boolean hasUnrecoveredToolFailure(AgentOrchestrationRequirements requirements) {
        Set<String> supersededSteps = planStepOutcomes.stream()
                .filter(step -> step.status() == PlanStepStatus.SUPERSEDED)
                .map(PlanStepOutcome::stepKey)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<String, PlanStepOutcome> planStepsByKey = planStepOutcomes.stream()
                .collect(java.util.stream.Collectors.toMap(
                        PlanStepOutcome::stepKey, step -> step, (left, right) -> right));
        List<ResearchMaterialRequirement> materialRequirements = requirements == null
                ? List.of() : requirements.materialRequirements();
        for (ToolOutcome failed : toolOutcomes) {
            if (!failed.executed() || failed.success()) continue;
            if (StringUtils.hasText(failed.planStepKey()) && supersededSteps.contains(failed.planStepKey())) {
                if (recoveredByReplacement(failed, planStepsByKey, materialRequirements)) continue;
                return true;
            }
            boolean recovered = toolOutcomes.stream().anyMatch(success -> success.executed() && success.success()
                    && samePlanScope(failed, success)
                    && laterThan(success, failed)
                    && sameRecoveryScope(failed, success, materialRequirements));
            if (!recovered) return true;
        }
        return false;
    }

    private boolean recoveredByReplacement(ToolOutcome failed,
                                           java.util.Map<String, PlanStepOutcome> planStepsByKey,
                                           List<ResearchMaterialRequirement> requirements) {
        PlanStepOutcome current = planStepsByKey.get(failed.planStepKey());
        Set<String> visited = new LinkedHashSet<>();
        while (current != null && StringUtils.hasText(current.replacementStepKey())
                && visited.add(current.stepKey())) {
            String replacementKey = current.replacementStepKey();
            PlanStepOutcome replacement = planStepsByKey.get(replacementKey);
            if (replacement == null) return false;
            if (replacement.status() == PlanStepStatus.COMPLETED) {
                return toolOutcomes.stream().anyMatch(success -> success.executed() && success.success()
                        && replacementKey.equals(success.planStepKey())
                        && sameRecoveryScope(failed, success, requirements));
            }
            if (replacement.status() != PlanStepStatus.SUPERSEDED) return false;
            current = replacement;
        }
        return false;
    }

    private boolean samePlanScope(ToolOutcome failed, ToolOutcome success) {
        if (StringUtils.hasText(failed.planStepKey()) || StringUtils.hasText(success.planStepKey())) {
            return java.util.Objects.equals(failed.planStepKey(), success.planStepKey());
        }
        return true;
    }

    private boolean laterThan(ToolOutcome candidate, ToolOutcome earlier) {
        return candidate.executionAttempt() > earlier.executionAttempt()
                || (candidate.executionAttempt() == earlier.executionAttempt()
                && candidate.runtimeStep() > earlier.runtimeStep());
    }

    private boolean sameRecoveryScope(ToolOutcome failed,
                                      ToolOutcome success,
                                      List<ResearchMaterialRequirement> requirements) {
        if (failed.toolName().equals(success.toolName())) return true;
        return requirements.stream().anyMatch(requirement ->
                requirement.availableTools().contains(failed.toolName())
                        && requirement.availableTools().contains(success.toolName()));
    }

    public record ToolOutcome(
            String toolName,
            int runtimeStep,
            String planStepKey,
            boolean executed,
            boolean budgetConsumed,
            boolean success,
            boolean reused,
            boolean skipped,
            int executionAttempt
    ) {
        public ToolOutcome(String toolName,
                           int runtimeStep,
                           String planStepKey,
                           boolean executed,
                           boolean budgetConsumed,
                           boolean success,
                           boolean reused,
                           boolean skipped) {
            this(toolName, runtimeStep, planStepKey, executed, budgetConsumed, success, reused, skipped, 0);
        }

        public ToolOutcome {
            if (!StringUtils.hasText(toolName) || !toolName.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("tool outcome requires a valid tool name");
            }
            runtimeStep = Math.max(0, runtimeStep);
            if (reused || skipped) executed = false;
            if (!executed) budgetConsumed = false;
            if (skipped) success = false;
            executionAttempt = Math.max(0, executionAttempt);
        }

        public ToolOutcome withPlanStepKey(String stepKey) {
            return new ToolOutcome(toolName, runtimeStep, stepKey, executed, budgetConsumed, success, reused, skipped,
                    executionAttempt);
        }

        public ToolOutcome withExecutionAttempt(int attempt) {
            return new ToolOutcome(toolName, runtimeStep, planStepKey, executed, budgetConsumed, success, reused, skipped,
                    attempt);
        }
    }

    public record PlanStepOutcome(String stepKey,
                                  PlanStepStatus status,
                                  boolean controlledStop,
                                  String replacementStepKey) {
        public PlanStepOutcome(String stepKey, PlanStepStatus status, boolean controlledStop) {
            this(stepKey, status, controlledStop, null);
        }

        public PlanStepOutcome {
            if (!StringUtils.hasText(stepKey)) {
                throw new IllegalArgumentException("plan step outcome requires a step key");
            }
            status = status == null ? PlanStepStatus.UNKNOWN : status;
            replacementStepKey = StringUtils.hasText(replacementStepKey) ? replacementStepKey.trim() : null;
        }
    }

    public enum PlanStepStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        DEGRADED,
        FAILED,
        SKIPPED,
        SUPERSEDED,
        REPAIRING,
        UNKNOWN;

        public static PlanStepStatus from(String value) {
            if (!StringUtils.hasText(value)) return UNKNOWN;
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return UNKNOWN;
            }
        }
    }

    /** Only deterministic server domain rules may assert this fact; model judgments have no source value. */
    public record ConsistencyFact(
            String ruleId,
            List<ResearchMaterialKind> materials,
            List<String> evidenceRefs,
            boolean consistent,
            ConsistencyFactSource source
    ) {
        public ConsistencyFact {
            if (!StringUtils.hasText(ruleId)) {
                throw new IllegalArgumentException("consistency fact requires a deterministic rule id");
            }
            materials = distinct(materials);
            evidenceRefs = distinctText(evidenceRefs);
            if (materials.size() < 2 || evidenceRefs.isEmpty()) {
                throw new IllegalArgumentException("consistency fact requires multiple materials and evidence");
            }
            if (source != ConsistencyFactSource.DETERMINISTIC_DOMAIN_RULE) {
                throw new IllegalArgumentException("consistency fact must come from a deterministic domain rule");
            }
        }

        private static List<ResearchMaterialKind> distinct(List<ResearchMaterialKind> values) {
            if (values == null) return List.of();
            LinkedHashSet<ResearchMaterialKind> result = new LinkedHashSet<>();
            values.stream().filter(java.util.Objects::nonNull).forEach(result::add);
            return List.copyOf(result);
        }

        private static List<String> distinctText(List<String> values) {
            if (values == null) return List.of();
            LinkedHashSet<String> result = new LinkedHashSet<>();
            values.stream().filter(StringUtils::hasText).forEach(result::add);
            return List.copyOf(result);
        }
    }

    public enum ConsistencyFactSource {
        DETERMINISTIC_DOMAIN_RULE
    }
}
