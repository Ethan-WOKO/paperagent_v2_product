package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact ContextRevision bindings for module 9 authorities. */
final class ProductValidationPublishIdentity {
    private ProductValidationPublishIdentity() {
    }

    static ChainPersistenceRecords.FinalizationReadinessRecord readiness(
            ChainPersistenceRecords.ContextRevisionRecord building,
            List<ChainPersistenceRecords.FinalizationReadinessRecord> values,
            Map<String, Long> sequences) {
        var exact = values.stream()
                .filter(value -> value.instructionId().equals(
                        building.instructionId()))
                .filter(value -> Objects.equals(value.taskFrameId(),
                        building.taskFrameId()))
                .filter(value -> Objects.equals(value.finalPlanId(),
                        building.planId()))
                .filter(value -> Objects.equals(value.finalPlanRevisionId(),
                        building.planRevisionId()))
                .filter(value -> Objects.equals(value.finalPlanRevisionNumber(),
                        building.planRevisionNumber()))
                .toList();
        if (exact.size() > 1) {
            throw blocked("FinalizationReadiness identity is ambiguous");
        }
        return exact.isEmpty() ? null : exact.get(0);
    }

    static ChainPersistenceRecords.CandidateStepResultRecord candidate(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            PlanRevision revision,
            List<ChainPersistenceRecords.CandidateStepResultRecord> values) {
        Long artifact = building.candidateArtifactId() != null
                ? building.candidateArtifactId()
                : readiness == null ? null : readiness.artifactId();
        if (artifact == null) return null;
        String step = building.stepId() != null ? building.stepId()
                : readiness == null ? null : readiness.finalStepId();
        if (revision == null) {
            throw blocked("Candidate validation Plan binding is missing");
        }
        var bound = values.stream()
                .filter(value -> value.taskId().equals(building.taskId()))
                .filter(value -> Objects.equals(value.taskFrameId(),
                        building.taskFrameId()))
                .filter(value -> Objects.equals(value.planId(),
                        building.planId()))
                .filter(value -> Objects.equals(value.planRevisionId(),
                        building.planRevisionId()))
                .filter(value -> Objects.equals(value.planRevisionNumber(),
                        building.planRevisionNumber()))
                .filter(value -> value.instructionId().equals(
                        building.instructionId()))
                .filter(value -> Objects.equals(value.artifactId(), artifact))
                .filter(value -> building.candidateFingerprint() == null
                        || Objects.equals(value.candidateFingerprint(),
                        building.candidateFingerprint())).toList();
        if (step == null) {
            if (building.role() == ChainRole.PLANNER
                    && readiness == null && bound.size() == 1) {
                return bound.get(0);
            }
            throw blocked("Candidate validation binding is not exact");
        }
        var current = bound.stream()
                .filter(value -> value.stepId().equals(step))
                .filter(value -> building.activationEventId() == null
                        || value.activationEventId().equals(
                        building.activationEventId()))
                .toList();
        if (current.size() > 1) {
            throw blocked("Candidate validation binding is not exact");
        }
        if (current.size() == 1) {
            return current.get(0);
        }
        Map<String, PlanStep> planSteps = new HashMap<>();
        revision.steps().forEach(value -> planSteps.put(
                value.id().value(), value));
        var inherited = bound.stream()
                .filter(value -> candidateChangingPredecessor(
                        step, value.stepId(), planSteps))
                .toList();
        if (inherited.size() == 1) {
            return inherited.get(0);
        }
        if (inherited.size() > 1) {
            throw blocked("Candidate validation binding is not exact");
        }
        if (building.role() == ChainRole.EXECUTOR
                && readiness == null
                && building.validationId() == null) {
            // A WorkspaceChange establishes the Candidate identity before
            // Executor reports the formal CandidateStepResult. Module 7
            // exposes that in-progress Workspace/Candidate; module 9 has
            // no Validation authority to project at this cut yet.
            return null;
        }
        throw blocked("Candidate validation binding is not exact");
    }

    private static boolean candidateChangingPredecessor(
            String stepId, String candidateStepId,
            Map<String, PlanStep> steps) {
        PlanStep candidate = steps.get(candidateStepId);
        PlanStep current = steps.get(stepId);
        if (candidate == null || current == null
                || !candidate.mayChangeCandidate()
                || candidateStepId.equals(stepId)) {
            return false;
        }
        ArrayDeque<String> remaining = new ArrayDeque<>();
        current.dependencies().forEach(value ->
                remaining.addLast(value.value()));
        HashSet<String> visited = new HashSet<>();
        while (!remaining.isEmpty()) {
            String dependency = remaining.removeFirst();
            if (!visited.add(dependency)) {
                continue;
            }
            if (candidateStepId.equals(dependency)) {
                return true;
            }
            PlanStep predecessor = steps.get(dependency);
            if (predecessor != null) {
                predecessor.dependencies().forEach(value ->
                        remaining.addLast(value.value()));
            }
        }
        return false;
    }

    static ChainPersistenceRecords.WorkspaceCandidateRecord workspace(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.CandidateStepResultRecord candidate,
            List<ChainPersistenceRecords.WorkspaceCandidateRecord> values) {
        if (candidate == null) return null;
        String workspaceId = building.workspaceId() != null
                ? building.workspaceId()
                : readiness == null ? null : readiness.workspaceId();
        String projectVersion = readiness == null
                ? building.projectVersion() : readiness.projectVersion();
        var exact = values.stream()
                .filter(value -> value.workspaceId().equals(workspaceId))
                .filter(value -> value.artifactId() == candidate.artifactId())
                .filter(value -> value.candidateFingerprint().equals(
                        candidate.candidateFingerprint()))
                .filter(value -> value.diffDigest().equals(
                        candidate.diffDigest()))
                .filter(value -> value.baseProjectVersion().equals(
                        projectVersion)).toList();
        if (exact.size() != 1) {
            throw blocked("Validation Workspace/Candidate binding is not exact");
        }
        return exact.get(0);
    }

    static void verifyOutcome(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.CandidateStepResultRecord candidate) {
        if (outcome == null) {
            if (building.role() == ChainRole.ANSWER) {
                throw blocked("Answer requires a formal TaskOutcome");
            }
            return;
        }
        if (!outcome.instructionId().equals(building.instructionId())
                || !Objects.equals(outcome.taskFrameId(), building.taskFrameId())
                || !Objects.equals(outcome.finalPlanId(), building.planId())
                || !Objects.equals(outcome.finalPlanRevisionId(),
                building.planRevisionId())) {
            throw blocked("TaskOutcome validation/finalization identity mismatches");
        }
        if (readiness == null) {
            String expectedCandidateKey = building.candidateArtifactId() == null
                    ? ChainIdentity.NONE : building.candidateFingerprint();
            String expectedValidationId = building.validationId() == null
                    ? candidate == null || candidate.validationId() == null
                    ? ChainIdentity.NONE : candidate.validationId()
                    : building.validationId();
            if (!Objects.equals(outcome.finalArtifactId(),
                    building.candidateArtifactId())
                    || !Objects.equals(outcome.candidateKey(),
                    expectedCandidateKey)
                    || !Objects.equals(outcome.validationId(),
                    expectedValidationId)
                    || outcome.finalizationReadinessId() != null
                    || outcome.finalizationCheckId() != null
                    || outcome.publishRequirement() != null
                    || outcome.publishRequirementDigest() != null
                    || outcome.publishOperationId() != null
                    || outcome.publishedProjectVersion() != null
                    || outcome.publishedRevisionId() != null
                    || outcome.publishReceiptId() != null) {
                throw blocked("non-finalized TaskOutcome identity mismatches Context");
            }
            return;
        }
        if (!outcome.validationId().equals(readiness.validationId())
                || !outcome.candidateKey().equals(readiness.candidateKey())) {
            throw blocked("TaskOutcome readiness identity mismatches");
        }
        if (!Objects.equals(building.projectVersion(), readiness.projectVersion())) {
            throw blocked("TaskOutcome ProjectVersion mismatches ContextRevision");
        }
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(
                ChainContextModule.VALIDATION_AND_PUBLISH, reason);
    }
}
