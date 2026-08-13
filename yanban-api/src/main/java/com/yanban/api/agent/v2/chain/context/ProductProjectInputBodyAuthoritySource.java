package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.ProjectMaterialScope;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectFileResponse;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.sandbox.CandidateReviewDiff;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskRecord;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.context.ChainContextBodySource;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact Project/Candidate body pages registered in the shared body adapter. */
public final class ProductProjectInputBodyAuthoritySource
        implements ChainContextBodySource {
    private final ChainContextRepository contexts;
    private final ChainFoundationRepository foundations;
    private final ChainWorkflowRepository workflow;
    private final ChainFinalizationRepository finalization;
    private final ProjectService projects;
    private final CandidateChangeArtifactService candidates;

    public ProductProjectInputBodyAuthoritySource(
            ChainContextRepository contexts,
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization,
            ProjectService projects,
            CandidateChangeArtifactService candidates) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
    }

    /** Registration entries for ProductChainContextBodySourceAdapter. */
    public Map<String, ChainContextBodySource> authoritySources() {
        return Map.of(
                ProductProjectInputProjectionCodec.MANIFEST_AUTHORITY, this,
                ProductProjectInputProjectionCodec.FILE_AUTHORITY, this,
                ProductProjectInputProjectionCodec.CANDIDATE_AUTHORITY, this);
    }

    @Override
    public BodyPage load(BodyRequest request) {
        Objects.requireNonNull(request, "request");
        ContextRevisionRecord revision = contexts.findContextRevision(
                        request.contextRevisionId())
                .orElseThrow(() -> invalid("Context revision is missing"));
        TaskRecord task = foundations.findTask(request.taskId())
                .orElseThrow(() -> invalid("Task is missing"));
        if (!revision.taskId().equals(task.taskId())
                || task.projectId() == null
                || !Objects.equals(task.projectId(), revision.projectId())) {
            throw invalid("Project authority does not match Context");
        }
        return switch (request.authorityType()) {
            case ProductProjectInputProjectionCodec.MANIFEST_AUTHORITY ->
                    manifest(request, revision, task);
            case ProductProjectInputProjectionCodec.FILE_AUTHORITY ->
                    projectFile(request, revision, task);
            case ProductProjectInputProjectionCodec.CANDIDATE_AUTHORITY ->
                    candidateFile(request, revision, task);
            default -> throw invalid("Unsupported Project body authority");
        };
    }

    private BodyPage manifest(
            BodyRequest request, ContextRevisionRecord revision,
            TaskRecord task) {
        ProjectManifestResponse manifest = exactManifest(revision, task);
        if (!request.authorityRef().equals(
                ProductProjectInputProjectionCodec.manifestRef(
                        task.projectId()))) {
            throw invalid("Manifest authority ref is invalid");
        }
        verifyVersion(request, manifest.version(),
                ProductProjectInputProjectionCodec.manifestBodyDigest(
                        manifest));
        List<BodyItem> all = new ArrayList<>();
        for (ProjectFileEntry file :
                ProductProjectInputProjectionCodec.ordered(manifest)) {
            String body = ProductProjectInputProjectionCodec
                    .manifestEntryBody(file);
            all.add(new BodyItem(itemId(file.path()), manifest.version(), body,
                    ProductChainContractProjectionCodec.sha256(body)));
        }
        List<BodyItem> page = ChainContextBodySource.deterministicPage(
                all, BodyItem::itemId, request);
        long remaining = all.stream().filter(value ->
                request.afterItemId() == null || value.itemId().compareTo(
                        request.afterItemId()) > 0).count();
        boolean complete = page.size() == remaining;
        return new BodyPage(page, complete || page.isEmpty() ? null
                : page.get(page.size() - 1).itemId(), complete);
    }

    private BodyPage projectFile(
            BodyRequest request, ContextRevisionRecord revision,
            TaskRecord task) {
        ProjectManifestResponse manifest = exactManifest(revision, task);
        String prefix = "project-file:" + task.projectId() + ":";
        String path = ProductProjectInputProjectionCodec.decodePath(
                request.authorityRef(), prefix);
        ProjectFileEntry entry = exactEntry(manifest, path);
        verifyVersion(request, manifest.version(), entry.sha256());
        ProjectFileResponse body = projects.readFile(
                task.userId(), task.projectId(), entry.path());
        if (!body.path().equals(entry.path())
                || body.sizeBytes() != entry.sizeBytes()
                || !body.sha256().equals(entry.sha256())) {
            throw invalid("Project file body changed from its manifest");
        }
        return single(request, entry.path(), manifest.version(),
                body.content(), entry.sha256());
    }

    private BodyPage candidateFile(
            BodyRequest request, ContextRevisionRecord revision,
            TaskRecord task) {
        CandidateArtifactResponse candidate;
        try {
            candidate = ProductProjectCandidateAuthority.exactOrNull(
                    revision, task, workflow, candidates);
        } catch (RuntimeException invalidCandidate) {
            throw invalid("Candidate body identity changed");
        }
        if (candidate == null) throw invalid("Candidate authority is absent");
        String prefix = "candidate-file:" + candidate.artifactId() + ":";
        String path = ProductProjectInputProjectionCodec.decodePath(
                request.authorityRef(), prefix);
        List<CandidateReviewDiff.Entry> matches = candidate.reviewDiff()
                .entries().stream().filter(value -> value.relativePath()
                        .value().equals(path)).toList();
        if (matches.size() != 1
                || matches.get(0).replacementText() == null
                || matches.get(0).resultFileHash() == null) {
            throw invalid("Candidate body target is missing or ambiguous");
        }
        CandidateReviewDiff.Entry entry = matches.get(0);
        verifyVersion(request, candidate.fingerprint().sha256(),
                entry.resultFileHash().sha256());
        return single(request, path, candidate.fingerprint().sha256(),
                entry.replacementText(), entry.resultFileHash().sha256());
    }

    private ProjectManifestResponse exactManifest(
            ContextRevisionRecord revision, TaskRecord task) {
        String expected = revision.projectVersion();
        if (revision.role() == io.paperagent.v2.chain.ChainRole.ANSWER) {
            expected = finalization.findTaskOutcome(task.taskId())
                    .map(value -> value.publishedProjectVersion() == null
                            ? revision.projectVersion()
                            : value.publishedProjectVersion())
                    .orElseThrow(() -> invalid("TaskOutcome is missing"));
        }
        ProjectManifestResponse manifest = projects.manifest(
                task.userId(), task.projectId());
        if (!expected.equals(manifest.version())) {
            throw invalid("ProjectVersion changed from the frozen cut");
        }
        return manifest;
    }

    private static ProjectFileEntry exactEntry(
            ProjectManifestResponse manifest, String path) {
        List<ProjectFileEntry> matches = manifest.files().stream()
                .filter(value -> value.path().equals(path)).toList();
        if (matches.size() != 1) {
            throw invalid("Project file target is missing or ambiguous");
        }
        return matches.get(0);
    }

    private static BodyPage single(
            BodyRequest request, String itemId, String version,
            String body, String digest) {
        if (request.afterItemId() != null
                && itemId.compareTo(request.afterItemId()) <= 0) {
            return new BodyPage(List.of(), null, true);
        }
        return new BodyPage(List.of(new BodyItem(
                itemId, version, body, digest)), null, true);
    }

    private static void verifyVersion(
            BodyRequest request, String version, String digest) {
        if (!request.authorityVersion().equals(version)
                || !request.authorityDigest().equals(digest)) {
            throw invalid("Body authority version or digest changed");
        }
    }

    private static String itemId(String path) {
        return ProjectMaterialScope.normalize(path) + "|" + path;
    }

    private static ChainContextException invalid(String reason) {
        return new ChainContextException(
                ChainContextErrorCode.CONTEXT_BODY_PAGE_INVALID, reason);
    }
}
