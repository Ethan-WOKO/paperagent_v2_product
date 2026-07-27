package io.paperagent.v2.contracts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PlanValidators {
    private PlanValidators() {
    }

    public static List<ContractViolation> validateHistory(
            TaskFrameId expectedTaskFrameId,
            List<PlanRevision> revisions) {
        List<ContractViolation> violations = Contracts.violations();
        if (expectedTaskFrameId == null || revisions == null) {
            violations.add(Contracts.violation(ViolationCode.REQUIRED_VALUE_MISSING,
                    "plan", "task frame and revisions are required"));
            return List.copyOf(violations);
        }

        PlanRevision previous = null;
        for (int index = 0; index < revisions.size(); index++) {
            PlanRevision current = revisions.get(index);
            String path = "plan.revisions[" + index + "]";
            if (current == null) {
                violations.add(Contracts.violation(ViolationCode.NULL_COLLECTION_ELEMENT,
                        path, "revision must not be null"));
                continue;
            }
            if (!current.taskFrameId().equals(expectedTaskFrameId)) {
                violations.add(Contracts.violation(ViolationCode.TASK_FRAME_MISMATCH,
                        path + ".taskFrameId", "revision is bound to another TaskFrame"));
            }
            if (previous == null) {
                if (current.number() != 1 || current.parentRevisionId().isPresent()) {
                    violations.add(Contracts.violation(ViolationCode.REVISION_PARENT_MISMATCH,
                            path, "history must begin with parentless revision 1"));
                }
            } else {
                if (current.number() != previous.number() + 1) {
                    violations.add(Contracts.violation(ViolationCode.REVISION_NOT_MONOTONIC,
                            path + ".number", "revision numbers must increase by exactly one"));
                }
                if (current.parentRevisionId().isEmpty()
                        || !current.parentRevisionId().get().equals(previous.id())) {
                    violations.add(Contracts.violation(ViolationCode.REVISION_PARENT_MISMATCH,
                            path + ".parentRevisionId", "revision must name the immediately preceding parent"));
                }
                violations.addAll(validateCompletedFactPreservation(previous, current));
            }
            previous = current;
        }
        return List.copyOf(violations);
    }

    public static List<ContractViolation> validateCompletedFactPreservation(
            PlanRevision previous,
            PlanRevision current) {
        List<ContractViolation> violations = new ArrayList<>();
        if (previous == null || current == null) {
            violations.add(Contracts.violation(ViolationCode.REQUIRED_VALUE_MISSING,
                    "planRevision", "both previous and current revisions are required"));
            return List.copyOf(violations);
        }
        Map<PlanStepId, PlanStep> previousSteps = indexSteps(previous.steps());
        Map<PlanStepId, PlanStep> currentSteps = indexSteps(current.steps());
        List<PlanStepId> factIds = new ArrayList<>(previous.completedFacts().keySet());
        for (PlanStepId stepId : current.completedFacts().keySet()) {
            if (!previous.completedFacts().containsKey(stepId)) {
                factIds.add(stepId);
            }
        }
        factIds.sort(Comparator.comparing(PlanStepId::value));

        for (PlanStepId stepId : factIds) {
            String path = "planRevision.completedFacts." + stepId.value();
            CompletionFact previousFact = previous.completedFacts().get(stepId);
            if (previousFact == null) {
                PlanStep previousStep = previousSteps.get(stepId);
                if (previousStep == null) {
                    violations.add(Contracts.violation(ViolationCode.INCONSISTENT_REFERENCE,
                            path,
                            "new completion fact must reference a step from the previous revision"));
                } else if (!previousStep.equals(currentSteps.get(stepId))) {
                    violations.add(Contracts.violation(ViolationCode.COMPLETED_FACT_REDEFINED,
                            path,
                            "a newly completed step must preserve its previous definition"));
                }
                continue;
            }

            if (!currentSteps.containsKey(stepId) || !current.completedFacts().containsKey(stepId)) {
                violations.add(Contracts.violation(ViolationCode.COMPLETED_FACT_REMOVED,
                        path,
                        "a completed authoritative fact and its step must be preserved"));
                continue;
            }
            if (!previousSteps.get(stepId).equals(currentSteps.get(stepId))
                    || !previousFact.equals(current.completedFacts().get(stepId))) {
                violations.add(Contracts.violation(ViolationCode.COMPLETED_FACT_REDEFINED,
                        path,
                        "completed work cannot be redefined or contradicted"));
            }
        }
        return List.copyOf(violations);
    }

    static void requireValidHistory(TaskFrameId taskFrameId, List<PlanRevision> revisions) {
        Contracts.requireNoViolations(validateHistory(taskFrameId, revisions));
    }

    static void requireStepGraph(List<PlanStep> steps) {
        Contracts.requireNoViolations(validateStepGraph(steps));
    }

    static void requireFactsMatchSteps(
            List<PlanStep> steps,
            Map<PlanStepId, CompletionFact> completedFacts) {
        List<ContractViolation> violations = new ArrayList<>();
        Set<PlanStepId> stepIds = indexSteps(steps).keySet();
        for (Map.Entry<PlanStepId, CompletionFact> entry : completedFacts.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().stepId()) || !stepIds.contains(entry.getKey())) {
                violations.add(Contracts.violation(ViolationCode.INCONSISTENT_REFERENCE,
                        "planRevision.completedFacts", "completion fact must reference a step in this revision"));
            }
        }
        Contracts.requireNoViolations(violations);
    }

    public static List<ContractViolation> validateStepGraph(List<PlanStep> steps) {
        List<ContractViolation> violations = new ArrayList<>();
        if (steps == null) {
            return List.of(Contracts.violation(ViolationCode.REQUIRED_VALUE_MISSING,
                    "planRevision.steps", "steps are required"));
        }
        if (steps.stream().anyMatch(step -> step == null)) {
            return List.of(Contracts.violation(ViolationCode.NULL_COLLECTION_ELEMENT,
                    "planRevision.steps", "steps must not contain null elements"));
        }
        Set<PlanStepId> seen = new HashSet<>();
        for (PlanStep step : steps) {
            if (!seen.add(step.id())) {
                violations.add(Contracts.violation(ViolationCode.DUPLICATE_ID,
                        "planRevision.steps", "duplicate step identifier: " + step.id().value()));
            }
        }
        Map<PlanStepId, PlanStep> indexed = indexSteps(steps);
        for (PlanStep step : steps) {
            for (PlanStepId dependency : step.dependencies()) {
                if (dependency.equals(step.id())) {
                    violations.add(Contracts.violation(ViolationCode.SELF_DEPENDENCY,
                            "planStep.dependencies", "step cannot depend on itself"));
                } else if (!indexed.containsKey(dependency)) {
                    violations.add(Contracts.violation(ViolationCode.UNKNOWN_STEP_DEPENDENCY,
                            "planStep.dependencies", "dependency does not exist: " + dependency.value()));
                }
            }
        }
        if (violations.isEmpty() && hasCycle(indexed)) {
            violations.add(Contracts.violation(ViolationCode.STEP_DEPENDENCY_CYCLE,
                    "planRevision.steps", "step dependency graph contains a cycle"));
        }
        return List.copyOf(violations);
    }

    private static boolean hasCycle(Map<PlanStepId, PlanStep> indexed) {
        Map<PlanStepId, Integer> incoming = new HashMap<>();
        Map<PlanStepId, Set<PlanStepId>> dependants = new HashMap<>();
        indexed.keySet().forEach(id -> {
            incoming.put(id, 0);
            dependants.put(id, new HashSet<>());
        });
        for (PlanStep step : indexed.values()) {
            for (PlanStepId dependency : step.dependencies()) {
                incoming.computeIfPresent(step.id(), (ignored, count) -> count + 1);
                Set<PlanStepId> children = dependants.get(dependency);
                if (children != null) {
                    children.add(step.id());
                }
            }
        }
        ArrayDeque<PlanStepId> ready = new ArrayDeque<>();
        incoming.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            PlanStepId id = ready.remove();
            visited++;
            for (PlanStepId dependant : dependants.get(id)) {
                int remaining = incoming.computeIfPresent(dependant, (ignored, count) -> count - 1);
                if (remaining == 0) {
                    ready.add(dependant);
                }
            }
        }
        return visited != indexed.size();
    }

    private static Map<PlanStepId, PlanStep> indexSteps(List<PlanStep> steps) {
        Map<PlanStepId, PlanStep> indexed = new LinkedHashMap<>();
        for (PlanStep step : steps) {
            if (step != null) {
                indexed.putIfAbsent(step.id(), step);
            }
        }
        return indexed;
    }
}
