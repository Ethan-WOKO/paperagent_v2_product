package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact Workspace/Candidate authority for one building Context revision. */
final class ProductWorkspaceCandidateAuthority {
    private static final ChainContextModule MODULE =
            ChainContextModule.WORKSPACE_AND_CANDIDATE;
    private final ChainFoundationRepository foundations;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ChainFinalizationRepository finalization;
    private final CandidateChangeArtifactService candidates;
    private final ProjectService projects;

    ProductWorkspaceCandidateAuthority(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            ChainFinalizationRepository finalization,
            CandidateChangeArtifactService candidates,
            ProjectService projects) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    Snapshot read(ChainPersistenceRecords.ContextRevisionRecord revision) {
        var task = foundations.findTask(revision.taskId())
                .orElseThrow(() -> blocked("Task is missing"));
        verifyTaskIdentity(revision, task);
        List<ChainPersistenceRecords.WorkspaceCandidateRecord> bindings =
                workflow.findWorkspaceCandidates(task.taskId());
        if (revision.candidateArtifactId() == null) {
            if (!bindings.isEmpty()) throw blocked(
                    "Context omits an existing formal Candidate");
            return new Snapshot(task, null, null, null, 0, false);
        }
        var exact = ProductProjectCandidateAuthority.exactBindingOrNull(
                MODULE, revision, task, workflow, candidates);
        if (exact == null || revision.workspaceId() == null
                || !revision.workspaceId().equals(
                exact.binding().workspaceId())) {
            throw blocked("Workspace/Candidate identity is incomplete");
        }
        requireCurrentBinding(bindings, exact.binding());
        verifyActionBinding(exact.binding());
        long sequence = bindingSequence(task.taskId(), bindings,
                exact.binding());
        var outcome = revision.role() == ChainRole.ANSWER
                ? finalization.findTaskOutcome(task.taskId())
                .orElseThrow(() -> blocked("Answer TaskOutcome is missing"))
                : null;
        String visibleVersion = visibleVersion(
                revision, exact.binding(), outcome);
        ProjectManifestResponse manifest = projects.manifest(
                task.userId(), task.projectId());
        if (!visibleVersion.equals(manifest.version())) {
            throw blocked("ProjectVersion changed from the exact cut");
        }
        boolean published = !visibleVersion.equals(
                exact.binding().baseProjectVersion());
        verifyDiffAgainstManifest(
                manifest, exact.candidate(), published);
        return new Snapshot(task, exact.binding(), exact.candidate(),
                manifest, sequence, published);
    }

    private static void verifyTaskIdentity(
            ChainPersistenceRecords.ContextRevisionRecord revision,
            ChainPersistenceRecords.TaskRecord task) {
        if (!task.taskId().equals(revision.taskId())
                || !Objects.equals(task.projectId(), revision.projectId())
                || !Objects.equals(task.initialProjectVersion(),
                revision.projectVersion())) {
            throw blocked("Task ProjectVersion does not match Context");
        }
    }

    private static void requireCurrentBinding(
            List<ChainPersistenceRecords.WorkspaceCandidateRecord> bindings,
            ChainPersistenceRecords.WorkspaceCandidateRecord exact) {
        if (bindings.isEmpty()
                || !bindings.get(bindings.size() - 1).equals(exact)) {
            throw blocked("Context Candidate is not the current formal cut");
        }
        Set<String> ids = new HashSet<>();
        Set<String> events = new HashSet<>();
        for (var value : bindings) {
            if (!value.taskId().equals(exact.taskId())
                    || !ids.add(value.workspaceCandidateId())
                    || !events.add(value.eventId())) {
                throw blocked("WorkspaceCandidate prefix is inconsistent");
            }
        }
    }

    private void verifyActionBinding(
            ChainPersistenceRecords.WorkspaceCandidateRecord candidate) {
        var matches = workflow.findActionBindings(candidate.taskId()).stream()
                .filter(value -> value.actionId().equals(candidate.actionId()))
                .filter(value -> value.taskId().equals(candidate.taskId()))
                .filter(value -> value.workspaceId().equals(
                        candidate.workspaceId()))
                .filter(value -> value.versionFenceSha256().equals(
                        candidate.versionFenceSha256())).toList();
        if (matches.size() != 1) throw blocked(
                "Candidate lacks one exact formal Action binding");
    }

    private long bindingSequence(
            String taskId,
            List<ChainPersistenceRecords.WorkspaceCandidateRecord> bindings,
            ChainPersistenceRecords.WorkspaceCandidateRecord exact) {
        long cut = foundations.highestAuthorityEventSequence(taskId);
        Map<String, Long> sequences = new HashMap<>();
        foundations.findAuthorityEvents(taskId, cut).forEach(event -> {
            if (event.taskId().equals(taskId)
                    && "WORKSPACE_CANDIDATE".equals(event.eventType())) {
                if (sequences.putIfAbsent(event.eventId(),
                        event.eventSequence()) != null) {
                    throw blocked("Candidate event identity is ambiguous");
                }
            }
        });
        long previous = 0;
        for (var binding : bindings) {
            Long sequence = sequences.get(binding.eventId());
            if (sequence == null || sequence <= previous) throw blocked(
                    "Candidate authority prefix is missing or unordered");
            previous = sequence;
        }
        return sequences.get(exact.eventId());
    }

    private static String visibleVersion(
            ChainPersistenceRecords.ContextRevisionRecord revision,
            ChainPersistenceRecords.WorkspaceCandidateRecord binding,
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        if (outcome == null) return binding.baseProjectVersion();
        if (!outcome.taskId().equals(revision.taskId())
                || !Objects.equals(outcome.finalArtifactId(),
                binding.artifactId())
                || !(outcome.candidateKey().equals(
                binding.workspaceCandidateId())
                || outcome.candidateKey().equals(
                binding.candidateFingerprint()))) {
            throw blocked("TaskOutcome final Candidate is inconsistent");
        }
        return outcome.publishedProjectVersion() == null
                ? binding.baseProjectVersion()
                : outcome.publishedProjectVersion();
    }

    private static void verifyDiffAgainstManifest(
            ProjectManifestResponse manifest,
            CandidateArtifactResponse candidate,
            boolean published) {
        Map<String, ProjectFileEntry> files = new HashMap<>();
        manifest.files().forEach(value -> {
            if (files.putIfAbsent(value.path(), value) != null) throw blocked(
                    "Project manifest contains duplicate paths");
        });
        Set<String> changed = new HashSet<>();
        for (var entry : candidate.reviewDiff().entries()) {
            String path = entry.relativePath().value();
            if (!changed.add(path)) throw blocked(
                    "Candidate diff contains duplicate paths");
            ProjectFileEntry file = files.get(path);
            if (published) verifyPublishedFile(entry, file);
            else verifyBaseFile(entry, file);
        }
    }

    private static void verifyBaseFile(
            com.yanban.core.agent.sandbox.CandidateReviewDiff.Entry entry,
            ProjectFileEntry file) {
        boolean add = entry.type()
                == com.yanban.core.agent.sandbox.CandidateFileChange.Type.ADD;
        if (add != (file == null)
                || (!add && !entry.baseFileHash().sha256().equals(
                file.sha256()))) {
            throw blocked("Candidate diff does not match the base manifest");
        }
    }

    private static void verifyPublishedFile(
            com.yanban.core.agent.sandbox.CandidateReviewDiff.Entry entry,
            ProjectFileEntry file) {
        boolean deleted = entry.type()
                == com.yanban.core.agent.sandbox.CandidateFileChange.Type.DELETE;
        if (deleted != (file == null)
                || (!deleted && (!entry.resultFileHash().sha256().equals(
                file.sha256()) || entry.replacementText().getBytes(
                StandardCharsets.UTF_8).length != file.sizeBytes()))) {
            throw blocked("Published manifest does not match Candidate diff");
        }
    }

    record Snapshot(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.WorkspaceCandidateRecord binding,
            CandidateArtifactResponse candidate,
            ProjectManifestResponse manifest,
            long bindingSequence,
            boolean published) {
        boolean empty() { return binding == null; }
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
