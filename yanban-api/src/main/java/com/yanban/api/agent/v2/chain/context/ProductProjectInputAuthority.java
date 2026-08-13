package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.ProjectMaterialScope;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskOutcomeRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskRecord;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
final class ProductProjectInputAuthority {
    private static final ChainContextModule MODULE =
            ChainContextModule.PROJECT_AND_INPUT_MATERIALS;
    private final ChainFoundationRepository foundations;
    private final ChainWorkflowRepository workflow;
    private final ChainFinalizationRepository finalization;
    private final AgentMessageRepository messages;
    private final ProjectService projects;
    private final CandidateChangeArtifactService candidates;
    private final ProductPlanBootstrapRepositoryAdapter bootstraps;
    private final ProductChainStepAuthorityAdapter steps;
    ProductProjectInputAuthority(
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization,
            AgentMessageRepository messages,
            ProjectService projects,
            CandidateChangeArtifactService candidates,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductChainStepAuthorityAdapter steps) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.bootstraps = Objects.requireNonNull(bootstraps, "bootstraps");
        this.steps = Objects.requireNonNull(steps, "steps");
    }

    Snapshot read(ContextRevisionRecord revision) {
        TaskRecord task = foundations.findTask(revision.taskId())
                .orElseThrow(() -> blocked("Task is missing"));
        if (task.projectId() == null) {
            if (revision.projectId() != null || revision.projectVersion() != null
                    || task.initialProjectVersion() != null) {
                throw blocked("No-Project identity is inconsistent");
            }
            verifyNoProjectTaskFrame(revision);
            return new Snapshot(task, null, null, List.of(), List.of(),
                    Map.of(), Map.of(), null, null, null);
        }
        if (ProductDirectAnswerContextAuthority.isDirectAnswer(revision)) {
            verifyTaskProjectIdentity(revision, task);
            ProductDirectAnswerContextAuthority.require(revision, workflow);
            return new Snapshot(task, null, revision.projectVersion(),
                    List.of(), List.of(), Map.of(), Map.of(), null, null,
                    null);
        }
        verifyProjectIdentity(revision, task);
        TaskOutcomeRecord outcome = revision.role() == ChainRole.ANSWER
                ? finalization.findTaskOutcome(task.taskId())
                .orElseThrow(() -> blocked("TaskOutcome is missing")) : null;
        String visibleVersion = outcome != null
                && outcome.publishedProjectVersion() != null
                ? outcome.publishedProjectVersion() : revision.projectVersion();
        ProjectManifestResponse manifest = projects.manifest(
                task.userId(), task.projectId());
        if (!visibleVersion.equals(manifest.version())) {
            throw blocked("ProjectVersion changed from the frozen cut");
        }
        List<String> explicit = revision.role() == ChainRole.PLANNER
                ? resolve(manifest, ProjectMaterialScope.explicitRelativePaths(
                        instructionBody(revision, task))) : List.of();
        Map<String, String> explicitBodies =
                ProductProjectSelectedBodyReader.read(
                        projects, task, manifest, explicit);
        PlanStep step = revision.role() == ChainRole.EXECUTOR
                ? currentStep(revision) : null;
        List<String> targets = step == null ? List.of() : resolve(manifest,
                ProjectMaterialScope.explicitRelativePaths(
                        step.intent(), step.expectedOutcome(),
                        String.join(";", step.completionCriteria()),
                        String.join(";", step.constraints())));
        Map<String, String> targetBodies =
                ProductProjectSelectedBodyReader.read(
                        projects, task, manifest, targets);
        CandidateArtifactResponse candidate =
                ProductProjectCandidateAuthority.exactOrNull(
                        revision, task, workflow, candidates);
        if (outcome != null) verifyOutcome(outcome, revision, candidate);
        return new Snapshot(task, manifest, visibleVersion, explicit, targets,
                explicitBodies, targetBodies, step, candidate, outcome);
    }

    private void verifyProjectIdentity(
            ContextRevisionRecord revision, TaskRecord task) {
        verifyTaskProjectIdentity(revision, task);
        if (revision.taskFrameId() == null) {
            if (revision.role() != ChainRole.PLANNER) {
                throw blocked("Only initial Planner may omit TaskFrame");
            }
            return;
        }
        PersistedPlanBootstrap bootstrap = bootstraps
                .find(new PlanId(revision.planId()))
                .orElseThrow(() -> blocked("Plan bootstrap is missing"));
        var project = bootstrap.taskFrame().sourceProjectVersion()
                .orElseThrow(() -> blocked("TaskFrame ProjectVersion is absent"));
        if (!bootstrap.taskFrame().id().value().equals(revision.taskFrameId())
                || !bootstrap.plan().id().value().equals(revision.planId())
                || !bootstrap.plan().taskFrameId().equals(
                        bootstrap.taskFrame().id())
                || !project.projectId().equals(Long.toString(task.projectId()))
                || !project.versionId().equals(revision.projectVersion())) {
            throw blocked("TaskFrame ProjectVersion does not match Context");
        }
    }

    private static void verifyTaskProjectIdentity(
            ContextRevisionRecord revision, TaskRecord task) {
        if (!Objects.equals(task.projectId(), revision.projectId())
                || !Objects.equals(task.initialProjectVersion(),
                        revision.projectVersion())) {
            throw blocked("Task ProjectVersion does not match Context");
        }
    }

    private void verifyNoProjectTaskFrame(ContextRevisionRecord revision) {
        if (revision.taskFrameId() == null) return;
        PersistedPlanBootstrap bootstrap = bootstraps
                .find(new PlanId(revision.planId()))
                .orElseThrow(() -> blocked("Plan bootstrap is missing"));
        if (!bootstrap.taskFrame().id().value().equals(revision.taskFrameId())
                || bootstrap.taskFrame().sourceProjectVersion().isPresent()) {
            throw blocked("No-Project TaskFrame identity is inconsistent");
        }
    }

    private String instructionBody(
            ContextRevisionRecord revision, TaskRecord task) {
        var instruction = foundations.findInstruction(revision.instructionId())
                .orElseThrow(() -> blocked("Instruction is missing"));
        AgentMessage message = instruction.messageId() == null ? null
                : messages.findById(instruction.messageId()).orElse(null);
        if (message == null) throw blocked("Instruction body is missing");
        ProductConversationAuthoritySupport.validateInstructionMessage(
                MODULE, task, instruction, message);
        return message.getContent();
    }

    private PlanStep currentStep(ContextRevisionRecord revision) {
        if (revision.stepId() == null) return null;
        var bindings = workflow.findPlanBindings(revision.taskId()).stream()
                .filter(value -> value.planId().equals(revision.planId()))
                .filter(value -> value.planRevisionId().equals(
                        revision.planRevisionId()))
                .filter(value -> value.planRevisionNumber()
                        == revision.planRevisionNumber())
                .filter(value -> value.taskFrameId().equals(
                        revision.taskFrameId())).toList();
        if (bindings.size() != 1) throw blocked("Plan binding is ambiguous");
        PlanRevision planRevision = steps.findPlanRevision(
                        revision.taskId(), revision.planRevisionId())
                .orElseThrow(() -> blocked("Plan revision is missing"));
        if (!planRevision.id().value().equals(revision.planRevisionId())
                || planRevision.number() != revision.planRevisionNumber()
                || !planRevision.taskFrameId().value().equals(
                        revision.taskFrameId())) {
            throw blocked("Plan revision does not match Context");
        }
        List<PlanStep> matches = planRevision.steps().stream()
                .filter(value -> value.id().value().equals(
                        revision.stepId())).toList();
        if (matches.size() != 1) throw blocked("Current Step is missing");
        return matches.get(0);
    }

    private static List<String> resolve(
            ProjectManifestResponse manifest, java.util.Set<String> requested) {
        var result = ProjectMaterialScope.resolveCanonicalPaths(
                requested, manifest.files().stream()
                        .map(ProjectFileEntry::path).toList());
        if (!result.valid()) throw formalBuildBlocked(
                "Project target is missing or ambiguous");
        return result.paths().stream().sorted(Comparator.comparing(
                ProjectMaterialScope::normalize)).toList();
    }

    private void verifyOutcome(
            TaskOutcomeRecord outcome, ContextRevisionRecord revision,
            CandidateArtifactResponse candidate) {
        if (!Objects.equals(outcome.finalArtifactId(),
                revision.candidateArtifactId())
                || (outcome.finalArtifactId() == null) != (candidate == null)) {
            throw blocked("TaskOutcome final artifact does not match Context");
        }
        if (candidate != null) {
            long matches = workflow.findWorkspaceCandidates(
                            revision.taskId()).stream()
                    .filter(value -> outcome.candidateKey().equals(
                            value.workspaceCandidateId())
                            || outcome.candidateKey().equals(
                            value.candidateFingerprint()))
                    .filter(value -> value.artifactId()
                            == candidate.artifactId()).count();
            if (matches != 1) throw blocked(
                    "TaskOutcome Candidate does not match final artifact");
        }
    }

    record Snapshot(
            TaskRecord task, ProjectManifestResponse manifest,
            String visibleVersion, List<String> explicitPaths,
            List<String> targetPaths,
            Map<String, String> explicitBodies,
            Map<String, String> targetBodies, PlanStep step,
            CandidateArtifactResponse candidate, TaskOutcomeRecord outcome) {
        boolean hasProject() { return manifest != null; }
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }

    private static RuntimeException formalBuildBlocked(String reason) {
        return ProductChainContextProjectionSupport.formalBuildBlocked(
                MODULE, reason);
    }
}
