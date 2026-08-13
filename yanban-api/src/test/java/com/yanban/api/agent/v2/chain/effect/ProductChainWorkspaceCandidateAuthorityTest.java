package com.yanban.api.agent.v2.chain.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.project.ProjectFileResponse;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.api.agent.v2.chain.persistence.ProductChainCandidateMaterializationFailureRepositoryAdapter;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import com.yanban.core.agent.sandbox.CandidateReviewDiff;
import com.yanban.core.agent.sandbox.CandidateTextPayload;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ProductChainWorkspaceCandidateAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String FENCE = "b".repeat(64);
    private static final String CANDIDATE = "c".repeat(64);
    private static final String VERSION = "d".repeat(64);
    private static final String OLD_TEXT_HASH = CandidateTextPayload
            .fromText("old text").contentHash().sha256();

    @Test
    void artifactAndChainBindingConvergeBeforeReplayCanStoreAgain() {
        ChainFoundationRepository foundations = mock(
                ChainFoundationRepository.class);
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        ChainModelRepository models = mock(ChainModelRepository.class);
        EffectOutcomeRepository outcomes = mock(EffectOutcomeRepository.class);
        CandidateChangeArtifactService candidates = mock(
                CandidateChangeArtifactService.class);
        ProjectService projects = mock(ProjectService.class);
        ProductChainTaskMutationFence mutationFence = mock(
                ProductChainTaskMutationFence.class);
        ProductChainCandidateMaterializationFailureRepositoryAdapter failures =
                mock(ProductChainCandidateMaterializationFailureRepositoryAdapter.class);
        when(failures.findCandidateMaterializationFailure(any(), any()))
                .thenReturn(Optional.empty());
        when(mutationFence.materializeCurrent(any(), any())).thenAnswer(
                invocation -> ((Supplier<ChainEffectRuntime.MaterializedCandidate>)
                        invocation.getArgument(1)).get());
        ProductChainWorkspaceCandidateAuthority authority =
                new ProductChainWorkspaceCandidateAuthority(
                        foundations, workflow, models, outcomes,
                        candidates, projects, mutationFence,
                        new ObjectMapper(), failures,
                        new com.yanban.api.project.ProjectStorageProperties());

        when(foundations.findTask("task.1")).thenReturn(Optional.of(
                new ChainPersistenceRecords.TaskRecord(
                        "task.1", "command.1", "instruction.1", null,
                        7L, 9L, 42L, null, "client.1", HASH,
                        8L, VERSION, 0L, NOW)));
        ChainPersistenceRecords.ModelProposalRecord proposal =
                new ChainPersistenceRecords.ModelProposalRecord(
                        "proposal.1", "task.1", "invocation.1", 1,
                        ChainRole.EXECUTOR,
                        ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE,
                        new ChainPersistenceRecords.CanonicalJson(
                                1, HASH,
                                "{\"baseCandidateRef\":\"NONE\","
                                        + "\"targetFiles\":[\"README.md\"],"
                                        + "\"manifestChanges\":[]}"),
                        new ChainPersistenceRecords.CanonicalJson(
                                1, HASH, "[]"),
                        ChainContentKind.WORKSPACE_CHANGE_BODY.name(),
                        "content.1", NOW);
        when(models.findProposal("proposal.1"))
                .thenReturn(Optional.of(proposal));
        when(models.findContent("content.1")).thenReturn(Optional.of(
                new ChainPersistenceRecords.ContentRecord(
                        "content.1", "task.1", "invocation.1",
                        ChainContentKind.WORKSPACE_CHANGE_BODY,
                        "{\"replacements\":[{\"path\":\"README.md\","
                                + "\"text\":\"new text\"}]}",
                        CandidateTextPayload.fromText(
                                "{\"replacements\":[{\"path\":\"README.md\","
                                        + "\"text\":\"new text\"}]}"
                        ).contentHash().sha256(), "application/json", NOW)));
        when(projects.manifest(7L, 8L)).thenReturn(
                new ProjectManifestResponse(8L, VERSION, List.of(
                        new ProjectFileEntry(
                                "README.md", 8L, NOW, OLD_TEXT_HASH))));
        when(projects.readFile(7L, 8L, "README.md")).thenReturn(
                new ProjectFileResponse(
                        "README.md", "old text", 8L, NOW, OLD_TEXT_HASH));

        CandidateArtifactResponse candidate = mock(
                CandidateArtifactResponse.class);
        CandidateFingerprint fingerprint = new CandidateFingerprint(CANDIDATE);
        CandidateReviewDiff diff = CandidateReviewDiff.fromJson(
                CandidateReviewDiff.FORMAT, fingerprint,
                new ProjectVersionRef(VERSION),
                List.of(new CandidateReviewDiff.Entry(
                        CandidateFileChange.Type.MODIFY,
                        new ProjectRelativePath("README.md"),
                        new FileHash(OLD_TEXT_HASH),
                        CandidateTextPayload.fromText("new text").contentHash(),
                        "new text")));
        when(candidate.projectId()).thenReturn(8L);
        when(candidate.projectVersion()).thenReturn(
                new ProjectVersionRef(VERSION));
        when(candidate.artifactId()).thenReturn(55L);
        when(candidate.fingerprint()).thenReturn(fingerprint);
        when(candidate.reviewDiff()).thenReturn(diff);
        when(candidates.store(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(candidate);
        when(candidates.getCurrent(7L, 55L)).thenReturn(candidate);

        List<ChainPersistenceRecords.WorkspaceCandidateRecord> bindings =
                new ArrayList<>();
        when(workflow.findWorkspaceCandidates("task.1"))
                .thenAnswer(ignored -> List.copyOf(bindings));
        AtomicInteger callbackCalls = new AtomicInteger();
        ChainEffectRuntime.CandidateBindingPort binding = materialized -> {
            callbackCalls.incrementAndGet();
            ChainPersistenceRecords.WorkspaceCandidateRecord fact =
                    new ChainPersistenceRecords.WorkspaceCandidateRecord(
                            "workspace-candidate.1", "task.1",
                            "event.candidate.1", "action.1", "workspace.1",
                            materialized.baseProjectVersion(),
                            materialized.artifactId(),
                            materialized.candidateFingerprint(),
                            materialized.diffDigest(), FENCE, NOW);
            bindings.add(fact);
            return fact;
        };
        ChainEffectRuntime.FrozenMutation frozen =
                new ChainEffectRuntime.FrozenMutation(
                        ChainEffectRuntime.SourceKind.WORKSPACE_CHANGE,
                        "task.1", "action.1", "key.1", "proposal.1",
                        "instruction.1", "task-frame.1", "plan.1",
                        "revision.1", "step.1", "activation.1",
                        "workspace.1", ChainIdentity.NONE, HASH, FENCE);
        ChainEffectRuntime.CandidateMutation mutation =
                new ChainEffectRuntime.CandidateMutation(
                        frozen, "WORKSPACE_CHANGE_BODY", "content.1");

        ChainEffectRuntime.MaterializedCandidate first =
                authority.materialize(mutation, binding);
        ChainEffectRuntime.MaterializedCandidate replay =
                authority.materialize(mutation, binding);

        assertEquals(first, replay);
        assertEquals(1, callbackCalls.get());
        verify(candidates, times(1)).store(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        when(candidate.projectId()).thenReturn(99L);
        assertThrows(IllegalStateException.class,
                () -> authority.reconcile(mutation),
                "replay cannot reuse another Project's Candidate");
        when(candidate.projectId()).thenReturn(8L);

        when(foundations.findTask("task.1")).thenReturn(Optional.of(
                new ChainPersistenceRecords.TaskRecord(
                        "task.1", "command.1", "instruction.1", null,
                        7L, 9L, 42L, null, "client.1", HASH,
                        8L, "e".repeat(64), 0L, NOW)));
        assertThrows(IllegalStateException.class,
                () -> authority.reconcile(mutation),
                "replay cannot cross the Task's frozen ProjectVersion");
    }

    @Test
    void noChangeCreatesOneTypedFailureAndReplayReturnsExactAuthority() {
        ChainFoundationRepository foundations = mock(
                ChainFoundationRepository.class);
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        ChainModelRepository models = mock(ChainModelRepository.class);
        EffectOutcomeRepository outcomes = mock(EffectOutcomeRepository.class);
        CandidateChangeArtifactService candidates = mock(
                CandidateChangeArtifactService.class);
        ProjectService projects = mock(ProjectService.class);
        ProductChainTaskMutationFence mutationFence = mock(
                ProductChainTaskMutationFence.class);
        ProductChainCandidateMaterializationFailureRepositoryAdapter failures =
                mock(ProductChainCandidateMaterializationFailureRepositoryAdapter.class);
        when(mutationFence.materializeCurrent(any(), any())).thenAnswer(
                invocation -> ((Supplier<ChainEffectRuntime.MaterializedCandidate>)
                        invocation.getArgument(1)).get());
        AtomicReference<ChainPersistenceRecords
                .CandidateMaterializationFailureRecord> stored =
                new AtomicReference<>();
        when(failures.findCandidateMaterializationFailure(any(), any()))
                .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(failures.appendCandidateMaterializationFailure(any()))
                .thenAnswer(invocation -> {
                    ChainPersistenceRecords.AuthoritativeFact<
                            ChainPersistenceRecords
                                    .CandidateMaterializationFailureRecord> value =
                            invocation.getArgument(0);
                    stored.compareAndSet(null, value.fact());
                    var request = value.event();
                    return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                            new ChainPersistenceRecords.AuthorityEventRecord(
                                    request.eventId(), request.taskId(), 19,
                                    request.eventType(), request.transitionId(),
                                    request.sourceIdentitySha256(),
                                    request.committedAt()),
                            stored.get(), stored.get() != value.fact());
                });
        ProductChainWorkspaceCandidateAuthority authority =
                new ProductChainWorkspaceCandidateAuthority(
                        foundations, workflow, models, outcomes,
                        candidates, projects, mutationFence,
                        new ObjectMapper(), failures,
                        new com.yanban.api.project.ProjectStorageProperties());
        when(foundations.findTask("task.1")).thenReturn(Optional.of(
                new ChainPersistenceRecords.TaskRecord(
                        "task.1", "command.1", "instruction.1", null,
                        7L, 9L, 42L, null, "client.1", HASH,
                        8L, VERSION, 0L, NOW)));
        when(workflow.findWorkspaceCandidates("task.1"))
                .thenReturn(List.of());
        when(workflow.findActionBindings("task.1")).thenReturn(List.of(
                new ChainPersistenceRecords.ActionBindingRecord(
                        "action.1", "task.1", "action.event.1",
                        "proposal.1", 1, HASH, "key.1",
                        "instruction.1", "task-frame.1", "plan.1",
                        "revision.1", "step.1", "activation.1",
                        "workspace.1", ChainIdentity.NONE,
                        null, null, null, null, FENCE, NOW)));
        when(models.findProposal("proposal.1")).thenReturn(Optional.of(
                new ChainPersistenceRecords.ModelProposalRecord(
                        "proposal.1", "task.1", "invocation.1", 1,
                        ChainRole.EXECUTOR,
                        ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE,
                        new ChainPersistenceRecords.CanonicalJson(
                                1, HASH,
                                "{\"baseCandidateRef\":\"NONE\","
                                        + "\"targetFiles\":[\"README.md\"],"
                                        + "\"manifestChanges\":[]}"),
                        new ChainPersistenceRecords.CanonicalJson(
                                1, HASH, "[]"),
                        ChainContentKind.WORKSPACE_CHANGE_BODY.name(),
                        "content.1", NOW)));
        when(models.findContent("content.1")).thenReturn(Optional.of(
                new ChainPersistenceRecords.ContentRecord(
                        "content.1", "task.1", "invocation.1",
                        ChainContentKind.WORKSPACE_CHANGE_BODY,
                        "{\"replacements\":[{\"path\":\"README.md\","
                                + "\"text\":\"old text\"}]}",
                        CandidateTextPayload.fromText(
                                "{\"replacements\":[{\"path\":\"README.md\","
                                        + "\"text\":\"old text\"}]}"
                        ).contentHash().sha256(), "application/json", NOW)));
        when(projects.manifest(7L, 8L)).thenReturn(
                new ProjectManifestResponse(8L, VERSION, List.of(
                        new ProjectFileEntry(
                                "README.md", 8L, NOW, OLD_TEXT_HASH))));
        when(projects.readFile(7L, 8L, "README.md")).thenReturn(
                new ProjectFileResponse(
                        "README.md", "old text", 8L, NOW, OLD_TEXT_HASH));
        ChainEffectRuntime.FrozenMutation frozen =
                new ChainEffectRuntime.FrozenMutation(
                        ChainEffectRuntime.SourceKind.WORKSPACE_CHANGE,
                        "task.1", "action.1", "key.1", "proposal.1",
                        "instruction.1", "task-frame.1", "plan.1",
                        "revision.1", "step.1", "activation.1",
                        "workspace.1", ChainIdentity.NONE, HASH, FENCE);
        var mutation = new ChainEffectRuntime.CandidateMutation(
                frozen, "WORKSPACE_CHANGE_BODY", "content.1");

        var first = authority.materialize(mutation, ignored -> {
            throw new AssertionError("failed Candidate must not bind");
        });
        var replay = authority.materialize(mutation, ignored -> {
            throw new AssertionError("replay must not bind");
        });

        assertEquals(ChainEffectRuntime.CandidateDisposition.FAILED,
                first.disposition());
        assertEquals("CANDIDATE_NO_ACTUAL_CHANGE", first.errorCode());
        assertEquals(first, replay);
        verify(failures, times(1))
                .appendCandidateMaterializationFailure(any());
        verify(candidates, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    void staleBaseAndUnsupportedManifestChangesFailBeforeArtifactStore() {
        List<String> invalidPayloads = List.of(
                "{\"baseCandidateRef\":\"stale-candidate\","
                        + "\"targetFiles\":[\"README.md\"],"
                        + "\"manifestChanges\":[]}",
                "{\"baseCandidateRef\":\"NONE\","
                        + "\"targetFiles\":[\"README.md\"],"
                        + "\"manifestChanges\":[\"create:extra.md\"]}");
        for (String payload : invalidPayloads) {
            ChainFoundationRepository foundations = mock(
                    ChainFoundationRepository.class);
            ChainWorkflowRepository workflow = mock(
                    ChainWorkflowRepository.class);
            ChainModelRepository models = mock(ChainModelRepository.class);
            EffectOutcomeRepository outcomes = mock(
                    EffectOutcomeRepository.class);
            CandidateChangeArtifactService candidates = mock(
                    CandidateChangeArtifactService.class);
            ProjectService projects = mock(ProjectService.class);
            ProductChainTaskMutationFence mutationFence = mock(
                    ProductChainTaskMutationFence.class);
            ProductChainCandidateMaterializationFailureRepositoryAdapter failures =
                    mock(ProductChainCandidateMaterializationFailureRepositoryAdapter.class);
            when(failures.findCandidateMaterializationFailure(any(), any()))
                    .thenReturn(Optional.empty());
            when(mutationFence.materializeCurrent(any(), any())).thenAnswer(
                    invocation -> ((Supplier<ChainEffectRuntime.MaterializedCandidate>)
                            invocation.getArgument(1)).get());
            ProductChainWorkspaceCandidateAuthority authority =
                    new ProductChainWorkspaceCandidateAuthority(
                            foundations, workflow, models, outcomes,
                            candidates, projects, mutationFence,
                            new ObjectMapper(), failures,
                            new com.yanban.api.project.ProjectStorageProperties());
            when(workflow.findWorkspaceCandidates("task.1"))
                    .thenReturn(List.of());
            when(foundations.findTask("task.1")).thenReturn(Optional.of(
                    new ChainPersistenceRecords.TaskRecord(
                            "task.1", "command.1", "instruction.1", null,
                            7L, 9L, 42L, null, "client.1", HASH,
                            8L, VERSION, 0L, NOW)));
            when(projects.manifest(7L, 8L)).thenReturn(
                    new ProjectManifestResponse(8L, VERSION, List.of()));
            when(models.findProposal("proposal.1")).thenReturn(Optional.of(
                    new ChainPersistenceRecords.ModelProposalRecord(
                            "proposal.1", "task.1", "invocation.1", 1,
                            ChainRole.EXECUTOR,
                            ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE,
                            new ChainPersistenceRecords.CanonicalJson(
                                    1, HASH, payload),
                            new ChainPersistenceRecords.CanonicalJson(
                                    1, HASH, "[]"),
                            ChainContentKind.WORKSPACE_CHANGE_BODY.name(),
                            "content.1", NOW)));
            when(models.findContent("content.1")).thenReturn(Optional.of(
                    new ChainPersistenceRecords.ContentRecord(
                            "content.1", "task.1", "invocation.1",
                            ChainContentKind.WORKSPACE_CHANGE_BODY,
                            "{\"replacements\":[{\"path\":\"README.md\","
                                    + "\"text\":\"new text\"}]}",
                            CandidateTextPayload.fromText(
                                    "{\"replacements\":[{\"path\":\"README.md\","
                                            + "\"text\":\"new text\"}]}"
                            ).contentHash().sha256(), "application/json", NOW)));
            ChainEffectRuntime.FrozenMutation frozen =
                    new ChainEffectRuntime.FrozenMutation(
                            ChainEffectRuntime.SourceKind.WORKSPACE_CHANGE,
                            "task.1", "action.1", "key.1", "proposal.1",
                            "instruction.1", "task-frame.1", "plan.1",
                            "revision.1", "step.1", "activation.1",
                            "workspace.1", ChainIdentity.NONE, HASH, FENCE);

            assertThrows(IllegalStateException.class, () -> authority.materialize(
                    new ChainEffectRuntime.CandidateMutation(
                            frozen, "WORKSPACE_CHANGE_BODY", "content.1"),
                    ignored -> {
                        throw new AssertionError("invalid proposal was bound");
                    }));
            verify(candidates, never()).store(any(), any(), any(), any(), any());
        }
    }
}
