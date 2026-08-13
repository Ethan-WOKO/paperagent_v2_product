package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectFileResponse;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import com.yanban.core.agent.sandbox.CandidateReviewDiff;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.context.ChainContextBodySource;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductProjectInputBodyAuthoritySourceTest {
    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
    private static final String VERSION = "a".repeat(64);
    private static final String A_BODY = "alpha";
    private static final String B_BODY = "beta";
    private static final String A_SHA = sha(A_BODY);
    private static final String B_SHA = sha(B_BODY);

    private ChainContextRepository contexts;
    private ChainWorkflowRepository workflow;
    private ProjectService projects;
    private CandidateChangeArtifactService candidates;
    private ProductChainContextBodySourceAdapter adapter;
    private ProjectManifestResponse manifest;

    @BeforeEach
    void setUp() {
        contexts = mock(ChainContextRepository.class);
        ChainFoundationRepository foundations = mock(
                ChainFoundationRepository.class);
        workflow = mock(ChainWorkflowRepository.class);
        ChainFinalizationRepository finalization = mock(
                ChainFinalizationRepository.class);
        projects = mock(ProjectService.class);
        candidates = mock(
                CandidateChangeArtifactService.class);
        var revision = revision();
        when(contexts.findContextRevision("context.1"))
                .thenReturn(Optional.of(revision));
        when(foundations.findTask("task.1"))
                .thenReturn(Optional.of(task()));
        manifest = new ProjectManifestResponse(41L, VERSION, List.of(
                new ProjectFileEntry("z/B.txt", B_BODY.length(), NOW, B_SHA),
                new ProjectFileEntry("a/A.txt", A_BODY.length(), NOW, A_SHA)));
        when(projects.manifest(2L, 41L)).thenReturn(manifest);
        when(projects.readFile(2L, 41L, "a/A.txt"))
                .thenReturn(new ProjectFileResponse(
                        "a/A.txt", A_BODY, A_BODY.length(), NOW, A_SHA));
        ProductProjectInputBodyAuthoritySource source =
                new ProductProjectInputBodyAuthoritySource(
                        contexts, foundations, workflow, finalization,
                        projects, candidates);
        adapter = new ProductChainContextBodySourceAdapter(
                contexts, mock(ChainModelRepository.class),
                source.authoritySources());
    }

    @Test
    void restoresCompleteManifestWithStableCursorAndNoTruncation() {
        String digest = ProductProjectInputProjectionCodec
                .manifestBodyDigest(manifest);
        ChainContextBodySource.BodyPage first = adapter.load(request(
                ProductProjectInputProjectionCodec.MANIFEST_AUTHORITY,
                ProductProjectInputProjectionCodec.manifestRef(41),
                VERSION, digest, null, 1));

        assertEquals(1, first.items().size());
        assertFalse(first.complete());
        assertTrue(first.items().get(0).body().contains("a/A.txt"));
        assertEquals(sha(first.items().get(0).body()),
                first.items().get(0).bodySha256());

        ChainContextBodySource.BodyPage second = adapter.load(request(
                ProductProjectInputProjectionCodec.MANIFEST_AUTHORITY,
                ProductProjectInputProjectionCodec.manifestRef(41),
                VERSION, digest, first.nextAfterItemId(), 1));
        assertTrue(second.complete());
        assertTrue(second.items().get(0).body().contains("z/B.txt"));
    }

    @Test
    void oversizedManifestUsesDeterministicPagesWithoutDroppingEntries() {
        List<ProjectFileEntry> files = IntStream.range(0, 1_001)
                .mapToObj(index -> new ProjectFileEntry(
                        "src/File" + String.format("%04d", index) + ".java",
                        1, NOW, "d".repeat(64))).toList();
        manifest = new ProjectManifestResponse(41L, VERSION, files);
        when(projects.manifest(2L, 41L)).thenReturn(manifest);
        String digest = ProductProjectInputProjectionCodec
                .manifestBodyDigest(manifest);

        var first = adapter.load(request(
                ProductProjectInputProjectionCodec.MANIFEST_AUTHORITY,
                ProductProjectInputProjectionCodec.manifestRef(41),
                VERSION, digest, null, 1_000));
        var second = adapter.load(request(
                ProductProjectInputProjectionCodec.MANIFEST_AUTHORITY,
                ProductProjectInputProjectionCodec.manifestRef(41),
                VERSION, digest, first.nextAfterItemId(), 1_000));

        assertEquals(1_000, first.items().size());
        assertFalse(first.complete());
        assertEquals(1, second.items().size());
        assertTrue(second.complete());
        assertEquals(1_001, first.items().size() + second.items().size());
    }

    @Test
    void sharedLoaderReadsEveryPageUsingFrozenPolicyBoundary() {
        List<ProjectFileEntry> files = IntStream.range(0, 1_001)
                .mapToObj(index -> new ProjectFileEntry(
                        "src/File" + String.format("%04d", index) + ".java",
                        1, NOW, "d".repeat(64))).toList();
        manifest = new ProjectManifestResponse(41L, VERSION, files);
        when(projects.manifest(2L, 41L)).thenReturn(manifest);
        String digest = ProductProjectInputProjectionCodec
                .manifestBodyDigest(manifest);

        List<ChainContextBodySource.BodyItem> items = adapter.loadAll(request(
                ProductProjectInputProjectionCodec.MANIFEST_AUTHORITY,
                ProductProjectInputProjectionCodec.manifestRef(41),
                VERSION, digest, null, 1));

        assertEquals(1_001, items.size());
        assertEquals(1_001, items.stream().map(
                ChainContextBodySource.BodyItem::itemId).distinct().count());
    }

    @Test
    void rejectsPageAboveFrozenRuntimePolicyBoundary() {
        String digest = ProductProjectInputProjectionCodec
                .manifestBodyDigest(manifest);

        ChainContextException failure = assertThrows(
                ChainContextException.class, () -> adapter.load(request(
                        ProductProjectInputProjectionCodec.MANIFEST_AUTHORITY,
                        ProductProjectInputProjectionCodec.manifestRef(41),
                        VERSION, digest, null,
                        ChainRuntimePolicy.V1.contextBodyPageItemsMax() + 1)));

        assertEquals(ChainContextErrorCode.CONTEXT_BODY_PAGE_INVALID,
                failure.code());
    }

    @Test
    void restoresExactProjectFileAndChecksRequestedBodyDigest() {
        String ref = ProductProjectInputProjectionCodec.fileRef(
                41, "a/A.txt");
        var page = adapter.load(request(
                ProductProjectInputProjectionCodec.FILE_AUTHORITY,
                ref, VERSION, A_SHA, null, 10));

        assertEquals(A_BODY, page.items().get(0).body());
        assertEquals(A_SHA, page.items().get(0).bodySha256());

        ChainContextException failure = assertThrows(
                ChainContextException.class, () -> adapter.load(request(
                        ProductProjectInputProjectionCodec.FILE_AUTHORITY,
                        ref, VERSION, "f".repeat(64), null, 10)));
        assertEquals(ChainContextErrorCode.CONTEXT_BODY_PAGE_INVALID,
                failure.code());
    }

    @Test
    void restoresExactCandidateReplacementWithItsActualDigest() {
        String candidateSha = "c".repeat(64);
        String replacement = "replacement";
        String replacementSha = sha(replacement);
        CandidateReviewDiff diff = CandidateReviewDiff.fromJson(
                CandidateReviewDiff.FORMAT,
                new CandidateFingerprint(candidateSha),
                new com.yanban.core.research.ProjectVersionRef(VERSION),
                List.of(new CandidateReviewDiff.Entry(
                        CandidateFileChange.Type.MODIFY,
                        new ProjectRelativePath("a/A.txt"),
                        new FileHash(A_SHA), new FileHash(replacementSha),
                        replacement)));
        CandidateArtifactResponse candidate = mock(
                CandidateArtifactResponse.class);
        when(candidate.artifactId()).thenReturn(91L);
        when(candidate.projectId()).thenReturn(41L);
        when(candidate.projectVersion()).thenReturn(
                new com.yanban.core.research.ProjectVersionRef(VERSION));
        when(candidate.fingerprint()).thenReturn(
                new CandidateFingerprint(candidateSha));
        when(candidate.reviewDiff()).thenReturn(diff);
        when(candidates.getCurrent(2L, 91L)).thenReturn(candidate);
        String diffDigest = ProductProjectInputProjectionCodec
                .candidateDiffDigest(candidate);
        when(workflow.findWorkspaceCandidates("task.1"))
                .thenReturn(List.of(new ChainPersistenceRecords
                        .WorkspaceCandidateRecord(
                        "candidate.1", "task.1", "event.1", "action.1",
                        "workspace.1", VERSION, 91L, candidateSha,
                        diffDigest, "e".repeat(64), NOW)));
        when(contexts.findContextRevision("context.1"))
                .thenReturn(Optional.of(candidateRevision(candidateSha)));

        var page = adapter.load(request(
                ProductProjectInputProjectionCodec.CANDIDATE_AUTHORITY,
                ProductProjectInputProjectionCodec.candidateRef(
                        91L, "a/A.txt"),
                candidateSha, replacementSha, null, 10));

        assertEquals(replacement, page.items().get(0).body());
        assertEquals(replacementSha, page.items().get(0).bodySha256());
    }

    private static ChainContextBodySource.BodyRequest request(
            String type, String ref, String version, String digest,
            String cursor, int size) {
        return new ChainContextBodySource.BodyRequest(
                "task.1", "context.1",
                ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                type, ref, version, digest, cursor, size);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord revision() {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context.1", "task.1", null, ChainRole.PLANNER,
                ChainWorkState.PLANNING, "project-input", "instruction.1",
                null, null, null, null, null, null, 41L, VERSION,
                null, null, null, null, null, null,
                "projectors.v1", "pagination.v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.BUILDING, 0, null, null,
                null, null, null, NOW, null);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord
            candidateRevision(String candidateSha) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context.1", "task.1", null, ChainRole.REFLECTOR,
                ChainWorkState.AWAITING_REVIEW, "project-input",
                "instruction.1", "frame.1", "plan.1", "revision.1", 1L,
                "step.1", "activation.1", 41L, VERSION, "workspace.1",
                91L, candidateSha, null, null, null,
                "projectors.v1", "pagination.v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.BUILDING, 0, null, null,
                null, null, null, NOW, null);
    }

    private static ChainPersistenceRecords.TaskRecord task() {
        return new ChainPersistenceRecords.TaskRecord(
                "task.1", "command.1", "instruction.1", null,
                2, 62, 1, 71L, "request.1", "0".repeat(64),
                41L, VERSION, 0, NOW);
    }

    private static String sha(String value) {
        return ProductChainContractProjectionCodec.sha256(value);
    }
}
