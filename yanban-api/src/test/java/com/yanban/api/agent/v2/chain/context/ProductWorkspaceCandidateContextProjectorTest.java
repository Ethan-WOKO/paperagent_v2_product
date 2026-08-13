package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import com.yanban.core.agent.sandbox.CandidateReviewDiff;
import com.yanban.core.agent.sandbox.CandidateTextPayload;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextInputMatrix;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductWorkspaceCandidateContextProjectorTest {
    private static final Instant NOW =
            Instant.parse("2026-08-08T10:00:00Z");
    private static final String BASE_VERSION = "a".repeat(64);
    private static final String PUBLISHED_VERSION = "b".repeat(64);
    private static final String CANDIDATE = "c".repeat(64);
    private static final String FENCE = "d".repeat(64);
    private static final String ORIGINAL = "old body";
    private static final String REPLACEMENT = "new body";
    private static final String ORIGINAL_SHA = sha(ORIGINAL);
    private static final String REPLACEMENT_SHA = sha(REPLACEMENT);

    private ChainFoundationRepository foundations;
    private ProductChainWorkflowRepositoryAdapter workflow;
    private ChainFinalizationRepository finalization;
    private CandidateChangeArtifactService candidates;
    private ProjectService projects;
    private ProductWorkspaceCandidateContextProjector projector;

    @BeforeEach
    void setUp() {
        foundations = mock(ChainFoundationRepository.class);
        workflow = mock(ProductChainWorkflowRepositoryAdapter.class);
        finalization = mock(ChainFinalizationRepository.class);
        candidates = mock(CandidateChangeArtifactService.class);
        projects = mock(ProjectService.class);
        projector = new ProductWorkspaceCandidateContextProjector(
                foundations, workflow, finalization, candidates, projects);
        when(foundations.findTask("task.1"))
                .thenReturn(Optional.of(task()));
    }

    @Test
    void opaqueWorkspaceWithoutFormalCandidateIsProvableEmpty() {
        var result = projector.read(request(ChainRole.EXECUTOR, false));

        assertEquals(ChainContextModuleStatus.EMPTY, result.presenceKind());
        assertEquals("workspace=NONE,candidateSequence=0",
                result.emptyWatermark());
        assertEquals("NONE", text(result.readBoundaryComponents(),
                "workspace"));
        assertFalse(ProductChainContractProjectionCodec.canonicalJson(
                ChainContextValue.object(result.projectionParameters()))
                .contains("C:\\"));
    }

    @Test
    void executorProjectsExactAuthoritySequenceManifestAndChangedRefs() {
        CandidateArtifactResponse candidate = wireCandidate(false);

        var result = projector.read(request(ChainRole.EXECUTOR, true));

        assertEquals(ChainContextModuleStatus.PRESENT,
                result.presenceKind());
        assertEquals(required(ChainRole.EXECUTOR),
                result.projectionFields().keySet().stream().sorted().toList());
        assertEquals(13, number(result.sourceVersionComponents(),
                "candidateBindingSequence"));
        assertEquals("opaque.workspace.1", text(
                result.readBoundaryComponents(), "workspace"));
        String changed = ProductChainContractProjectionCodec.canonicalJson(
                result.projectionFields().get(
                        "workspace.targetAndModifiedFileExpansion"));
        assertTrue(changed.contains("src/Main.txt"));
        assertTrue(changed.contains(REPLACEMENT_SHA));
        assertTrue(changed.contains("effectiveSha256"));
        assertFalse(changed.contains(REPLACEMENT));
        var files = (ChainContextValue.ArrayValue)
                ProductProjectInputProjectionCodec.candidateFiles(candidate);
        var file = (ChainContextValue.ObjectValue) files.values().get(0);
        assertEquals(ORIGINAL_SHA, ((ChainContextValue.Text) file.values()
                .get("baseSha256")).value());
        assertEquals(REPLACEMENT_SHA, ((ChainContextValue.Text) file.values()
                .get("effectiveSha256")).value());
    }

    @Test
    void plannerExecutorAndReflectorReceiveOnlyTheirRequiredWorkspaceView() {
        wireCandidate(false);

        for (ChainRole role : List.of(
                ChainRole.PLANNER, ChainRole.EXECUTOR,
                ChainRole.REFLECTOR)) {
            var result = projector.read(request(role, true));
            assertEquals(required(role), result.projectionFields().keySet()
                    .stream().sorted().toList());
        }
    }

    @Test
    void plannerRevisionProjectsExactCandidateWithoutAnActiveStep() {
        wireCandidate(false);

        var result = projector.read(plannerRevisionRequest());

        assertEquals(ChainContextModuleStatus.PRESENT,
                result.presenceKind());
        assertEquals(required(ChainRole.PLANNER),
                result.projectionFields().keySet().stream().sorted().toList());
        assertEquals("opaque.workspace.1", text(
                result.readBoundaryComponents(), "workspace"));
    }

    @Test
    void answerUsesFinalPublishedManifestBoundToTaskOutcome() {
        wireCandidate(true);

        var result = projector.read(request(ChainRole.ANSWER, true));

        assertEquals(required(ChainRole.ANSWER),
                result.projectionFields().keySet().stream().sorted().toList());
        assertEquals(PUBLISHED_VERSION, text(
                result.readBoundaryComponents(), "projectVersion"));
        String delivery = ProductChainContractProjectionCodec.canonicalJson(
                result.projectionFields().get("workspace.deliveryManifest"));
        assertTrue(delivery.contains(PUBLISHED_VERSION));
        assertTrue(delivery.contains("src/Main.txt"));
    }

    @Test
    void answerAcceptsAFormalOutcomeKeyedByCandidateFingerprint() {
        wireCandidate(true, true);

        var result = projector.read(request(ChainRole.ANSWER, true));

        assertEquals(PUBLISHED_VERSION, text(
                result.readBoundaryComponents(), "projectVersion"));
    }

    @Test
    void missingStaleOrSupersededCandidateAuthorityIsTypedBlocked() {
        assertBlocked(() -> projector.read(request(
                ChainRole.REFLECTOR, true)));

        CandidateArtifactResponse candidate = wireCandidate(false);
        when(candidate.fingerprint()).thenReturn(
                new CandidateFingerprint("e".repeat(64)));
        assertBlocked(() -> projector.read(request(
                ChainRole.REFLECTOR, true)));

        candidate = candidate();
        when(candidates.getCurrent(7L, 91L)).thenReturn(candidate);
        var first = binding("workspace-candidate.1", "event.candidate.1",
                91L, CANDIDATE);
        var later = binding("workspace-candidate.2", "event.candidate.2",
                92L, "f".repeat(64));
        when(workflow.findWorkspaceCandidates("task.1"))
                .thenReturn(List.of(first, later));
        assertBlocked(() -> projector.read(request(
                ChainRole.REFLECTOR, true)));
    }

    private CandidateArtifactResponse wireCandidate(boolean published) {
        return wireCandidate(published, false);
    }

    private CandidateArtifactResponse wireCandidate(
            boolean published, boolean fingerprintKey) {
        CandidateArtifactResponse candidate = candidate();
        var binding = binding("workspace-candidate.1",
                "event.candidate.1", 91L, CANDIDATE);
        when(workflow.findWorkspaceCandidates("task.1"))
                .thenReturn(List.of(binding));
        when(workflow.findActionBindings("task.1"))
                .thenReturn(List.of(action()));
        when(candidates.getCurrent(7L, 91L)).thenReturn(candidate);
        when(foundations.highestAuthorityEventSequence("task.1"))
                .thenReturn(20L);
        when(foundations.findAuthorityEvents("task.1", 20L))
                .thenReturn(List.of(new ChainPersistenceRecords
                        .AuthorityEventRecord(
                        "event.candidate.1", "task.1", 13,
                        "WORKSPACE_CANDIDATE", null, "1".repeat(64), NOW)));
        String visible = published ? PUBLISHED_VERSION : BASE_VERSION;
        String body = published ? REPLACEMENT : ORIGINAL;
        String digest = published ? REPLACEMENT_SHA : ORIGINAL_SHA;
        when(projects.manifest(7L, 41L)).thenReturn(
                new ProjectManifestResponse(41L, visible, List.of(
                        new ProjectFileEntry("src/Main.txt",
                                body.getBytes(java.nio.charset.StandardCharsets
                                        .UTF_8).length, NOW, digest))));
        if (published) {
            var outcome = mock(
                    ChainPersistenceRecords.TaskOutcomeRecord.class);
            when(outcome.taskId()).thenReturn("task.1");
            when(outcome.finalArtifactId()).thenReturn(91L);
            when(outcome.candidateKey()).thenReturn(
                    fingerprintKey ? CANDIDATE : "workspace-candidate.1");
            when(outcome.publishedProjectVersion()).thenReturn(
                    PUBLISHED_VERSION);
            when(finalization.findTaskOutcome("task.1"))
                    .thenReturn(Optional.of(outcome));
        }
        return candidate;
    }

    private CandidateArtifactResponse candidate() {
        var payload = CandidateTextPayload.fromText(REPLACEMENT);
        var entry = new CandidateReviewDiff.Entry(
                CandidateFileChange.Type.MODIFY,
                new ProjectRelativePath("src/Main.txt"),
                new FileHash(ORIGINAL_SHA), payload.contentHash(),
                REPLACEMENT);
        var diff = CandidateReviewDiff.fromJson(
                CandidateReviewDiff.FORMAT,
                new CandidateFingerprint(CANDIDATE),
                new com.yanban.core.research.ProjectVersionRef(BASE_VERSION),
                List.of(entry));
        CandidateArtifactResponse result = mock(
                CandidateArtifactResponse.class);
        when(result.artifactId()).thenReturn(91L);
        when(result.projectId()).thenReturn(41L);
        when(result.projectVersion()).thenReturn(
                new com.yanban.core.research.ProjectVersionRef(BASE_VERSION));
        when(result.fingerprint()).thenReturn(
                new CandidateFingerprint(CANDIDATE));
        when(result.reviewDiff()).thenReturn(diff);
        return result;
    }

    private ChainPersistenceRecords.WorkspaceCandidateRecord binding(
            String id, String event, long artifactId, String fingerprint) {
        CandidateArtifactResponse candidate = candidate();
        return new ChainPersistenceRecords.WorkspaceCandidateRecord(
                id, "task.1", event, "action.1", "opaque.workspace.1",
                BASE_VERSION, artifactId, fingerprint,
                ProductProjectInputProjectionCodec.candidateDiffDigest(
                        candidate), FENCE, NOW);
    }

    private static ChainPersistenceRecords.ActionBindingRecord action() {
        return new ChainPersistenceRecords.ActionBindingRecord(
                "action.1", "task.1", "event.action.1", "proposal.1", 1,
                "2".repeat(64), "idempotency.1", "instruction.creator",
                "frame.creator", "plan.creator", "revision.creator",
                "step.creator", "activation.creator",
                "opaque.workspace.1", "NONE",
                null, null, null, null, FENCE, NOW);
    }

    private static ChainContextProjectionRequest request(
            ChainRole role, boolean candidate) {
        return new ChainContextProjectionRequest(
                new ChainPersistenceRecords.ContextRevisionRecord(
                        "context." + role, "task.1", null, role,
                        role == ChainRole.PLANNER
                                ? ChainWorkState.PLANNING
                                : role == ChainRole.ANSWER
                                ? ChainWorkState.DELIVERING
                                : role == ChainRole.REFLECTOR
                                ? ChainWorkState.AWAITING_REVIEW
                                : ChainWorkState.EXECUTING,
                        "workspace-context", "instruction.1", "frame.1",
                        "plan.1", "revision.1", 1L, "step.1",
                        "activation.1", 41L, BASE_VERSION,
                        "opaque.workspace.1", candidate ? 91L : null,
                        candidate ? CANDIDATE : null, null, null, null,
                        "projectors.v1", "pages.v1", "policy.v1",
                        ChainContextRevisionStatus.BUILDING, 0, null, null,
                        null, null, null, NOW, null),
                1_000_000);
    }

    private static ChainContextProjectionRequest plannerRevisionRequest() {
        return new ChainContextProjectionRequest(
                new ChainPersistenceRecords.ContextRevisionRecord(
                        "context.planner-revision", "task.1", null,
                        ChainRole.PLANNER, ChainWorkState.PLANNING,
                        "PLAN_REVISION", "instruction.1", "frame.1",
                        "plan.1", "revision.1", 1L, null, null,
                        41L, BASE_VERSION, "opaque.workspace.1", 91L,
                        CANDIDATE, null, null, null,
                        "projectors.v1", "pages.v1", "policy.v1",
                        ChainContextRevisionStatus.BUILDING, 0, null, null,
                        null, null, null, NOW, null),
                1_000_000);
    }

    private static ChainPersistenceRecords.TaskRecord task() {
        return new ChainPersistenceRecords.TaskRecord(
                "task.1", "command.1", "instruction.1", null,
                7, 8, 9, 10L, "request.1", "0".repeat(64),
                41L, BASE_VERSION, 21, NOW);
    }

    private static List<String> required(ChainRole role) {
        return ChainContextInputMatrix.requiredProjectionFields(
                        role, ChainContextModule.WORKSPACE_AND_CANDIDATE)
                .stream().sorted().toList();
    }

    private static long number(
            java.util.Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.NumberValue) values.get(key)).value();
    }

    private static String text(
            java.util.Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.Text) values.get(key)).value();
    }

    private static void assertBlocked(
            org.junit.jupiter.api.function.Executable call) {
        ChainContextException failure = assertThrows(
                ChainContextException.class, call);
        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    private static String sha(String body) {
        return ProductChainContractProjectionCodec.sha256(body);
    }
}
