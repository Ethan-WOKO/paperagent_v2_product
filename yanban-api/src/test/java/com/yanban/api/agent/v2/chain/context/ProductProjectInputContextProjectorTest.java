package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectFileResponse;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import com.yanban.core.agent.sandbox.CandidateReviewDiff;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextInputMatrix;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductProjectInputContextProjectorTest {
    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
    private static final String VERSION = "a".repeat(64);
    private static final String ORIGINAL = "class Sort {}";
    private static final String ORIGINAL_SHA = sha(ORIGINAL);
    private static final String REPLACEMENT = "class Sort { void merge() {} }";
    private static final String REPLACEMENT_SHA = sha(REPLACEMENT);
    private static final String CANDIDATE_SHA = "c".repeat(64);

    private ChainFoundationRepository foundations;
    private ChainWorkflowRepository workflow;
    private ChainFinalizationRepository finalization;
    private AgentMessageRepository messages;
    private ProjectService projects;
    private CandidateChangeArtifactService candidates;
    private ProductPlanBootstrapRepositoryAdapter bootstraps;
    private ProductChainStepAuthorityAdapter steps;
    private ProductProjectInputContextProjector projector;

    @BeforeEach
    void setUp() {
        foundations = mock(ChainFoundationRepository.class);
        workflow = mock(ChainWorkflowRepository.class);
        finalization = mock(ChainFinalizationRepository.class);
        messages = mock(AgentMessageRepository.class);
        projects = mock(ProjectService.class);
        candidates = mock(CandidateChangeArtifactService.class);
        bootstraps = mock(ProductPlanBootstrapRepositoryAdapter.class);
        steps = mock(ProductChainStepAuthorityAdapter.class);
        projector = new ProductProjectInputContextProjector(
                new ProductProjectInputAuthority(
                        foundations, workflow, finalization, messages,
                        projects, candidates, bootstraps, steps));
        when(foundations.findTask("task.1")).thenReturn(Optional.of(task()));
        when(foundations.findInstruction("instruction.1"))
                .thenReturn(Optional.of(instruction()));
        AgentMessage message = mock(AgentMessage.class);
        when(message.getId()).thenReturn(71L);
        when(message.getSessionId()).thenReturn(62L);
        when(message.getUserId()).thenReturn(2L);
        when(message.getContent()).thenReturn("Compile src/Sort.java");
        when(messages.findById(71L)).thenReturn(Optional.of(message));
        manifest(List.of(
                new ProjectFileEntry("z/Notes.md", 5, NOW, "d".repeat(64)),
                new ProjectFileEntry("src/Sort.java", ORIGINAL.length(),
                        NOW, ORIGINAL_SHA)));
        when(projects.readFile(2L, 41L, "src/Sort.java"))
                .thenReturn(new ProjectFileResponse(
                        "src/Sort.java", ORIGINAL, ORIGINAL.length(),
                        NOW, ORIGINAL_SHA));
    }

    @Test
    void plannerProjectsCompleteRootAndExactExplicitInputRefs() {
        var result = projector.read(request(ChainRole.PLANNER, false, false));

        assertEquals(ChainContextModuleStatus.PRESENT,
                result.presenceKind());
        assertEquals(required(ChainRole.PLANNER),
                result.projectionFields().keySet().stream().sorted().toList());
        assertEquals(List.of("explicitInputRefVector", "manifestFingerprint",
                        "projectVersion"),
                result.sourceVersionComponents().keySet().stream()
                        .sorted().toList());
        assertEquals(List.of("completeManifestCut", "projectAndVersion"),
                result.readBoundaryComponents().keySet().stream()
                        .sorted().toList());
        ChainContextValue.ObjectValue root = assertInstanceOf(
                ChainContextValue.ObjectValue.class,
                result.projectionFields().get("project.manifest.complete"));
        assertEquals(2, ((ChainContextValue.NumberValue) root.values()
                .get("fileCount")).value());
        ChainContextValue.ObjectValue authority = (ChainContextValue.ObjectValue)
                root.values().get("bodyAuthority");
        assertEquals(ProductProjectInputProjectionCodec.manifestBodyDigest(
                        new ProjectManifestResponse(41L, VERSION, List.of(
                                new ProjectFileEntry("z/Notes.md", 5, NOW,
                                        "d".repeat(64)),
                                new ProjectFileEntry("src/Sort.java",
                                        ORIGINAL.length(), NOW, ORIGINAL_SHA)))),
                ((ChainContextValue.Text) authority.values()
                        .get("authorityDigest")).value());
        ChainContextValue.ArrayValue explicit = (ChainContextValue.ArrayValue)
                result.projectionFields().get(
                        "project.explicitInputExpansion");
        assertEquals(1, explicit.values().size());
        String expanded = ProductChainContractProjectionCodec.canonicalJson(
                explicit);
        assertTrue(expanded.contains("src/Sort.java"));
        assertTrue(expanded.contains(ORIGINAL));
        assertFalse(ProductChainContractProjectionCodec.canonicalJson(
                result.sourceVersionComponents().get(
                        "explicitInputRefVector")).contains(ORIGINAL));
    }

    @Test
    void projectBackedDirectAnswerDoesNotReadProjectOrRequireTaskFrame() {
        when(workflow.findRouteDecisions("task.1"))
                .thenReturn(List.of(directRoute()));
        ContextRevisionRecord revision = new ContextRevisionRecord(
                "context.direct", "task.1", null, ChainRole.ANSWER,
                ChainWorkState.DIRECT_ANSWERING, "DIRECT_ROUTE",
                "instruction.1", null, null, null, null, null, null,
                41L, VERSION, null, null, null, null, null, null,
                "projectors.v1", "pagination.v1", "runtime.v1",
                ChainContextRevisionStatus.BUILDING, 0, null, null,
                null, null, null, NOW, null);

        var result = projector.read(new ChainContextProjectionRequest(
                revision, 1_000_000));

        assertEquals(ChainContextModuleStatus.PRESENT,
                result.presenceKind());
        assertEquals(required(ChainRole.ANSWER), result.projectionFields()
                .keySet().stream().sorted().toList());
        verify(projects, never()).manifest(2L, 41L);
        verify(finalization, never()).findTaskOutcome("task.1");
        verify(bootstraps, never()).find(new PlanId("plan.1"));
    }

    @Test
    void allRolesReceiveOnlyTheirRequiredProjectView() {
        persistentAuthorities();
        candidateAuthority();
        var outcome = mock(ChainPersistenceRecords.TaskOutcomeRecord.class);
        when(outcome.outcomeId()).thenReturn("outcome.1");
        when(outcome.finalArtifactId()).thenReturn(91L);
        when(outcome.candidateKey()).thenReturn("candidate.1");
        when(outcome.publishedProjectVersion()).thenReturn(null);
        when(finalization.findTaskOutcome("task.1"))
                .thenReturn(Optional.of(outcome));

        for (ChainRole role : ChainRole.values()) {
            boolean persistent = role != ChainRole.PLANNER;
            var result = projector.read(request(role, persistent,
                    persistent));
            assertEquals(required(role), result.projectionFields().keySet()
                    .stream().sorted().toList());
        }
        var executor = projector.read(request(
                ChainRole.EXECUTOR, true, true));
        String executionFiles = ProductChainContractProjectionCodec
                .canonicalJson(executor.projectionFields().get(
                        "project.targetAndModifiedFileExpansion"));
        assertTrue(executionFiles.contains(ORIGINAL));
        assertTrue(executionFiles.contains(REPLACEMENT));
        String executionVector = ProductChainContractProjectionCodec
                .canonicalJson(executor.sourceVersionComponents().get(
                        "explicitInputRefVector"));
        assertFalse(executionVector.contains(ORIGINAL));
        assertFalse(executionVector.contains(REPLACEMENT));
        var reflector = projector.read(request(
                ChainRole.REFLECTOR, true, true));
        assertFalse(reflector.projectionFields().containsKey(
                "project.targetAndModifiedFileExpansion"));
        String reviewed = ProductChainContractProjectionCodec.canonicalJson(
                reflector.projectionFields().get(
                        "project.reviewedAndDiffAffectedExpansion"));
        assertTrue(reviewed.contains(REPLACEMENT_SHA));
        assertTrue(reviewed.contains(REPLACEMENT));
        assertFalse(ProductChainContractProjectionCodec.canonicalJson(
                reflector.sourceVersionComponents().get(
                        "explicitInputRefVector")).contains(REPLACEMENT));
    }

    @Test
    void taskOutcomeCandidateKeyMayUseTheCandidateFingerprint() {
        persistentAuthorities();
        candidateAuthority();
        var outcome = mock(ChainPersistenceRecords.TaskOutcomeRecord.class);
        when(outcome.outcomeId()).thenReturn("outcome.1");
        when(outcome.finalArtifactId()).thenReturn(91L);
        when(outcome.candidateKey()).thenReturn(CANDIDATE_SHA);
        when(outcome.publishedProjectVersion()).thenReturn(null);
        when(finalization.findTaskOutcome("task.1"))
                .thenReturn(Optional.of(outcome));

        assertDoesNotThrow(() -> projector.read(request(
                ChainRole.ANSWER, true, true)));
    }

    @Test
    void executorReadsTheExactReplannedRevision() {
        persistentAuthorities();
        PlanStep replannedStep = new PlanStep(new PlanStepId("step.2"),
                "Repair the requested file", "Requested behavior works",
                Set.of(), List.of("verified result"),
                new BoundedExecutionHints(2, Duration.ofMinutes(1)),
                List.of("preserve unrelated behavior"));
        PlanRevision replanned = mock(PlanRevision.class);
        when(replanned.id()).thenReturn(new PlanRevisionId("revision.2"));
        when(replanned.taskFrameId()).thenReturn(new TaskFrameId("frame.1"));
        when(replanned.number()).thenReturn(2L);
        when(replanned.steps()).thenReturn(List.of(replannedStep));
        when(steps.findPlanRevision("task.1", "revision.2"))
                .thenReturn(Optional.of(replanned));
        when(workflow.findPlanBindings("task.1")).thenReturn(List.of(
                new ChainPersistenceRecords.PlanBindingRecord(
                        "binding.2", "task.1", "event.2", "instruction.1",
                        "route.1", "frame.1", "plan.1", "revision.2",
                        2, "PLAN", "plan.1", "f".repeat(64), null, NOW)));

        var result = projector.read(request(
                ChainRole.EXECUTOR, "revision.2", 2L, "step.2"));

        assertEquals(ChainContextModuleStatus.PRESENT,
                result.presenceKind());
        assertEquals(required(ChainRole.EXECUTOR),
                result.projectionFields().keySet().stream().sorted().toList());
    }

    @Test
    void ambiguousOrMissingTargetIsAFormalBuildBlock() {
        manifest(List.of(
                new ProjectFileEntry("src/Sort.java", 1, NOW, ORIGINAL_SHA),
                new ProjectFileEntry("SRC/sort.java", 1, NOW, ORIGINAL_SHA)));
        assertFormalBuildBlocked(() -> projector.read(request(
                ChainRole.PLANNER, false, false)));

        manifest(List.of());
        assertFormalBuildBlocked(() -> projector.read(request(
                ChainRole.PLANNER, false, false)));
    }

    @Test
    void versionDriftRemainsAPropagatedAuthorityFailure() {
        when(projects.manifest(2L, 41L)).thenReturn(
                new ProjectManifestResponse(41L, "b".repeat(64), List.of()));
        assertPropagatedBlocked(() -> projector.read(request(
                ChainRole.PLANNER, false, false)));
    }

    @Test
    void projectIdentityMismatchRemainsAPropagatedAuthorityFailure() {
        when(foundations.findTask("task.1")).thenReturn(Optional.of(
                new ChainPersistenceRecords.TaskRecord(
                        "task.1", "command.1", "instruction.1", null,
                        2, 62, 1, 71L, "request.1", "0".repeat(64),
                        42L, VERSION, 0, NOW)));

        assertPropagatedBlocked(() -> projector.read(request(
                ChainRole.PLANNER, false, false)));
    }

    @Test
    void explicitBodyDigestMismatchRemainsAPropagatedAuthorityFailure() {
        when(projects.readFile(2L, 41L, "src/Sort.java"))
                .thenReturn(new ProjectFileResponse(
                        "src/Sort.java", "changed", ORIGINAL.length(),
                        NOW, ORIGINAL_SHA));
        assertPropagatedBlocked(() -> projector.read(request(
                ChainRole.PLANNER, false, false)));
    }

    @Test
    void noProjectUsesTheFormalVersionMatrixEmptyWatermark() {
        when(foundations.findTask("task.none")).thenReturn(Optional.of(
                new ChainPersistenceRecords.TaskRecord(
                        "task.none", "command.none", "instruction.none", null,
                        2, 62, 1, 71L, "request.none", "0".repeat(64),
                        null, null, 0, NOW)));
        var revision = new ContextRevisionRecord(
                "context.none", "task.none", null, ChainRole.PLANNER,
                ChainWorkState.PLANNING, "project-input", "instruction.none",
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                "projectors.v1", "pagination.v1", "runtime.v1",
                ChainContextRevisionStatus.BUILDING, 0, null, null,
                null, null, null, NOW, null);

        var result = projector.read(new ChainContextProjectionRequest(
                revision, 1_000_000));

        assertEquals(ChainContextModuleStatus.EMPTY, result.presenceKind());
        assertEquals("project=NONE,input=[]", result.emptyWatermark());
    }

    private void manifest(List<ProjectFileEntry> files) {
        when(projects.manifest(2L, 41L)).thenReturn(
                new ProjectManifestResponse(41L, VERSION, files));
    }

    private void persistentAuthorities() {
        TaskFrame frame = new TaskFrame(
                new TaskFrameId("frame.1"), "Work", List.of("project"),
                List.of("result"), List.of(),
                Optional.of(new ProjectVersionRef("41", VERSION)),
                ProductChainPermissionPolicySource.executionProfile(true), NOW);
        PlanStep step = new PlanStep(new PlanStepId("step.1"),
                "Update src/Sort.java", "Sort.java is updated", Set.of(),
                List.of("updated"), new BoundedExecutionHints(
                        2, Duration.ofMinutes(1)), List.of("preserve"));
        PlanRevision revision = mock(PlanRevision.class);
        when(revision.id()).thenReturn(new PlanRevisionId("revision.1"));
        when(revision.taskFrameId()).thenReturn(frame.id());
        when(revision.number()).thenReturn(1L);
        when(revision.steps()).thenReturn(List.of(step));
        Plan plan = mock(Plan.class);
        when(plan.id()).thenReturn(new PlanId("plan.1"));
        when(plan.taskFrameId()).thenReturn(frame.id());
        when(plan.revisions()).thenReturn(List.of(revision));
        PersistedPlanBootstrap bootstrap = mock(PersistedPlanBootstrap.class);
        when(bootstrap.taskFrame()).thenReturn(frame);
        when(bootstrap.plan()).thenReturn(plan);
        when(bootstraps.find(new PlanId("plan.1")))
                .thenReturn(Optional.of(bootstrap));
        when(steps.findPlanRevision("task.1", "revision.1"))
                .thenReturn(Optional.of(revision));
        when(workflow.findPlanBindings("task.1"))
                .thenReturn(List.of(new ChainPersistenceRecords.PlanBindingRecord(
                        "binding.1", "task.1", "event.1", "instruction.1",
                        "route.1", "frame.1", "plan.1", "revision.1",
                        1, "PLAN", "plan.1", "f".repeat(64), null, NOW)));
    }

    private void candidateAuthority() {
        CandidateReviewDiff.Entry entry = new CandidateReviewDiff.Entry(
                CandidateFileChange.Type.MODIFY,
                new ProjectRelativePath("src/Sort.java"),
                new FileHash(ORIGINAL_SHA), new FileHash(REPLACEMENT_SHA),
                REPLACEMENT);
        CandidateReviewDiff diff = CandidateReviewDiff.fromJson(
                CandidateReviewDiff.FORMAT,
                new CandidateFingerprint(CANDIDATE_SHA),
                new com.yanban.core.research.ProjectVersionRef(VERSION),
                List.of(entry));
        CandidateArtifactResponse candidate = mock(
                CandidateArtifactResponse.class);
        when(candidate.artifactId()).thenReturn(91L);
        when(candidate.projectId()).thenReturn(41L);
        when(candidate.projectVersion()).thenReturn(
                new com.yanban.core.research.ProjectVersionRef(VERSION));
        when(candidate.fingerprint()).thenReturn(
                new CandidateFingerprint(CANDIDATE_SHA));
        when(candidate.reviewDiff()).thenReturn(diff);
        when(candidates.getCurrent(2L, 91L)).thenReturn(candidate);
        String diffDigest = ProductProjectInputProjectionCodec
                .candidateDiffDigest(candidate);
        when(workflow.findWorkspaceCandidates("task.1"))
                .thenReturn(List.of(
                        new ChainPersistenceRecords.WorkspaceCandidateRecord(
                                "candidate.1", "task.1", "candidate-event.1",
                                "action.1", "workspace.1", VERSION, 91L,
                                CANDIDATE_SHA, diffDigest, "e".repeat(64), NOW)));
    }

    private static ChainContextProjectionRequest request(
            ChainRole role, boolean persistent, boolean candidate) {
        return request(role, persistent ? "revision.1" : null,
                persistent ? 1L : null, persistent ? "step.1" : null,
                candidate);
    }

    private static ChainContextProjectionRequest request(
            ChainRole role, String revisionId, Long revisionNumber,
            String stepId) {
        return request(role, revisionId, revisionNumber, stepId, false);
    }

    private static ChainContextProjectionRequest request(
            ChainRole role, String revisionId, Long revisionNumber,
            String stepId, boolean candidate) {
        boolean persistent = revisionId != null;
        return new ChainContextProjectionRequest(new ContextRevisionRecord(
                "context." + role, "task.1", null, role,
                role == ChainRole.PLANNER
                        ? ChainWorkState.PLANNING : ChainWorkState.EXECUTING,
                "project-input", "instruction.1",
                persistent ? "frame.1" : null,
                persistent ? "plan.1" : null,
                revisionId,
                revisionNumber,
                stepId,
                persistent ? "activation.1" : null,
                41L, VERSION, persistent ? "workspace.1" : null,
                candidate ? 91L : null,
                candidate ? CANDIDATE_SHA : null,
                null, null, null, "projectors.v1", "pagination.v1",
                "runtime.v1", ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, NOW, null), 1_000_000);
    }

    private static ChainPersistenceRecords.TaskRecord task() {
        return new ChainPersistenceRecords.TaskRecord(
                "task.1", "command.1", "instruction.1", null,
                2, 62, 1, 71L, "request.1", "0".repeat(64),
                41L, VERSION, 0, NOW);
    }

    private static ChainPersistenceRecords.InstructionRecord instruction() {
        return new ChainPersistenceRecords.InstructionRecord(
                "instruction.1", "command.1", 62, "task.1", 71L,
                sha("Compile src/Sort.java"), "message.71",
                ChainInstructionRelation.INITIAL, null, null,
                "1".repeat(64), NOW);
    }

    private static ChainPersistenceRecords.RouteDecisionRecord directRoute() {
        return new ChainPersistenceRecords.RouteDecisionRecord(
                "route.direct", "task.1", "route.event", "instruction.1",
                "proposal.route",
                ChainPersistenceRecords.RouteDecisionType.INITIAL, 0,
                ChainExecutionMode.DIRECT, "plain answer",
                new ChainPersistenceRecords.CanonicalJson(1, sha("{}"), "{}"),
                new ChainPersistenceRecords.CanonicalJson(1, sha("[]"), "[]"),
                new ChainPersistenceRecords.CanonicalJson(1, sha("[]"), "[]"),
                false, false, false, false, null, null, null, NOW);
    }

    private static List<String> required(ChainRole role) {
        return ChainContextInputMatrix.requiredProjectionFields(role,
                        ChainContextModule.PROJECT_AND_INPUT_MATERIALS)
                .stream().sorted().toList();
    }

    private static void assertFormalBuildBlocked(
            org.junit.jupiter.api.function.Executable call) {
        assertBlocked(call, ChainContextException.FailureDisposition
                .FORMAL_BUILD_BLOCK);
    }

    private static void assertPropagatedBlocked(
            org.junit.jupiter.api.function.Executable call) {
        assertBlocked(call, ChainContextException.FailureDisposition.PROPAGATE);
    }

    private static void assertBlocked(
            org.junit.jupiter.api.function.Executable call,
            ChainContextException.FailureDisposition disposition) {
        ChainContextException failure = assertThrows(
                ChainContextException.class, call);
        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
        assertEquals(ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                failure.failedModule());
        assertEquals(disposition, failure.failureDisposition());
    }

    private static String sha(String value) {
        return ProductChainContractProjectionCodec.sha256(value);
    }
}
