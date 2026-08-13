package com.yanban.api.agent.v2.chain.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import com.yanban.core.agent.sandbox.CandidateReviewDiff;
import com.yanban.core.agent.sandbox.CandidateTextPayload;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.WorkspaceCandidateRecord;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductChainActionWorkspaceAuthorityFactoryTest {
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
    private static final String FENCE = "b".repeat(64);
    private static final String CANDIDATE = "c".repeat(64);
    private static final String BASE_HASH = "d".repeat(64);

    @Test
    void noneBaseUsesTheTaskFrozenProjectWithoutReadingCandidateAuthority() {
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        CandidateChangeArtifactService candidates = mock(
                CandidateChangeArtifactService.class);
        ProjectService projects = projects();
        var factory = new ProductChainActionWorkspaceAuthorityFactory(
                workflow, candidates, projects);

        ChainActionWorkspaceAuthority authority = factory.create(
                task(), action(ChainIdentity.NONE),
                List.of("paper.md"), List.of());

        assertEquals(ChainIdentity.NONE,
                authority.baseCandidate().candidateIdentity());
        assertEquals("project:version.1",
                authority.baseCandidate().baseProjectVersion());
        assertNull(authority.baseCandidate().artifactId());
        assertEquals(List.of(), authority.baseCandidate().changes());
        verify(workflow, never()).findWorkspaceCandidates("task.1");
    }

    @Test
    void canonicalizesUniqueBasenameScopesAgainstTheProjectManifest() {
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        CandidateChangeArtifactService candidates = mock(
                CandidateChangeArtifactService.class);
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 8L)).thenReturn(
                new ProjectManifestResponse(
                        8L, "project:version.1", List.of(
                                new ProjectFileEntry(
                                        "src/main/java/Sort.java", 1,
                                        NOW, "a".repeat(64)))));
        var factory = new ProductChainActionWorkspaceAuthorityFactory(
                workflow, candidates, projects);

        ChainActionWorkspaceAuthority authority = factory.create(
                task(), action(ChainIdentity.NONE),
                List.of("Sort.java"), List.of());

        assertEquals(List.of("src/main/java/Sort.java"),
                authority.readScopes());
    }

    @Test
    void staleProjectVersionIsRejectedBeforeCandidateLookup() {
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        CandidateChangeArtifactService candidates = mock(
                CandidateChangeArtifactService.class);
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 8L)).thenReturn(
                new ProjectManifestResponse(
                        8L, "project:version.2", List.of()));
        var factory = new ProductChainActionWorkspaceAuthorityFactory(
                workflow, candidates, projects);

        assertThrows(IllegalStateException.class, () -> factory.create(
                task(), action(CANDIDATE),
                List.of("paper.md"), List.of()));

        verify(workflow, never()).findWorkspaceCandidates("task.1");
    }

    @Test
    void exactBindingBuildsItsAttestedTypedModifyOverlay() {
        Fixture fixture = fixture(CandidateFileChange.Type.MODIFY, CANDIDATE);

        ChainActionWorkspaceAuthority authority = fixture.factory.create(
                task(), action(CANDIDATE),
                List.of("paper.md"), List.of("paper.md"));

        assertEquals(CANDIDATE,
                authority.baseCandidate().candidateIdentity());
        assertEquals(91L, authority.baseCandidate().artifactId());
        assertEquals(List.of(new ChainActionWorkspaceAuthority.TypedChange(
                        ChainActionWorkspaceAuthority.ChangeType.MODIFY,
                        "paper.md", BASE_HASH,
                        CandidateTextPayload.fromText("replacement")
                                .contentHash().sha256(),
                        "replacement")),
                authority.baseCandidate().changes());
        verify(fixture.candidates).getCurrent(7L, 91L);
    }

    @Test
    void mismatchedArtifactFailsClosedAndAddIsTyped() {
        Fixture mismatched = fixture(
                CandidateFileChange.Type.MODIFY, "e".repeat(64));
        assertThrows(IllegalStateException.class, () ->
                mismatched.factory.create(
                        task(), action(CANDIDATE),
                        List.of("paper.md"), List.of()));

        Fixture add = fixture(CandidateFileChange.Type.ADD, CANDIDATE);
        ChainActionWorkspaceAuthority added = add.factory.create(
                task(), action(CANDIDATE),
                List.of("paper.md"), List.of("paper.md"));
        assertEquals(ChainActionWorkspaceAuthority.ChangeType.ADD,
                added.baseCandidate().changes().get(0).type());
    }

    @Test
    void exactBindingBuildsItsAttestedTypedDeleteOverlay() {
        Fixture fixture = fixture(CandidateFileChange.Type.DELETE, CANDIDATE);

        ChainActionWorkspaceAuthority deleted = fixture.factory.create(
                task(), action(CANDIDATE),
                List.of(), List.of("paper.md"));

        assertEquals(List.of(new ChainActionWorkspaceAuthority.TypedChange(
                        ChainActionWorkspaceAuthority.ChangeType.DELETE,
                        "paper.md", BASE_HASH, null, null)),
                deleted.baseCandidate().changes());
    }

    private static Fixture fixture(
            CandidateFileChange.Type changeType,
            String artifactFingerprint) {
        ChainWorkflowRepository workflow = mock(ChainWorkflowRepository.class);
        CandidateChangeArtifactService candidates = mock(
                CandidateChangeArtifactService.class);
        ProjectService projects = projects(changeType
                == CandidateFileChange.Type.ADD);
        ProjectVersionRef version = new ProjectVersionRef("project:version.1");
        FileHash baseHash = changeType == CandidateFileChange.Type.ADD
                ? null : new FileHash(BASE_HASH);
        CandidateTextPayload text = CandidateTextPayload.fromText("replacement");
        CandidateReviewDiff.Entry diffEntry = new CandidateReviewDiff.Entry(
                changeType,
                new ProjectRelativePath("paper.md"), baseHash,
                changeType == CandidateFileChange.Type.DELETE
                        ? null : text.contentHash(),
                changeType == CandidateFileChange.Type.DELETE
                        ? null : text.text());
        String diffDigest = diffDigest(version.value(), diffEntry);
        WorkspaceCandidateRecord binding = new WorkspaceCandidateRecord(
                "candidate-binding.1", "task.1", "event.candidate.1",
                "action.previous", "workspace.1", version.value(),
                91L, CANDIDATE, diffDigest, "a".repeat(64), NOW);
        when(workflow.findWorkspaceCandidates("task.1"))
                .thenReturn(List.of(binding));
        var sourceAction = mock(
                io.paperagent.v2.chain.ChainPersistenceRecords
                        .ActionBindingRecord.class);
        when(sourceAction.actionId()).thenReturn("action.previous");
        when(sourceAction.taskId()).thenReturn("task.1");
        when(sourceAction.workspaceId()).thenReturn("workspace.1");
        when(sourceAction.versionFenceSha256()).thenReturn("a".repeat(64));
        when(workflow.findActionBindings("task.1"))
                .thenReturn(List.of(sourceAction));

        CandidateArtifactResponse candidate = mock(
                CandidateArtifactResponse.class);
        CandidateReviewDiff reviewDiff = mock(CandidateReviewDiff.class);
        CandidateFileChange change = mock(CandidateFileChange.class);
        when(candidate.artifactId()).thenReturn(91L);
        when(candidate.projectId()).thenReturn(8L);
        when(candidate.projectVersion()).thenReturn(version);
        when(candidate.governanceStatus()).thenReturn(
                CandidateChangeSet.GovernanceStatus.VALIDATED);
        when(candidate.fingerprint()).thenReturn(
                new CandidateFingerprint(artifactFingerprint));
        when(candidate.reviewDiff()).thenReturn(reviewDiff);
        when(candidate.changes()).thenReturn(List.of(change));
        when(reviewDiff.entries()).thenReturn(List.of(diffEntry));
        when(reviewDiff.format()).thenReturn(CandidateReviewDiff.FORMAT);
        when(reviewDiff.projectVersion()).thenReturn(version);
        when(reviewDiff.sourceCandidateFingerprint()).thenReturn(
                new CandidateFingerprint(artifactFingerprint));
        when(change.type()).thenReturn(changeType);
        when(change.projectVersion()).thenReturn(version);
        when(change.relativePath()).thenReturn(
                new ProjectRelativePath("paper.md"));
        when(change.baseFileHash()).thenReturn(baseHash);
        when(change.resultFileHash()).thenReturn(
                changeType == CandidateFileChange.Type.DELETE
                        ? null : text.contentHash());
        when(change.candidateText()).thenReturn(
                changeType == CandidateFileChange.Type.DELETE ? null : text);
        when(candidates.getCurrent(7L, 91L)).thenReturn(candidate);
        return new Fixture(
                new ProductChainActionWorkspaceAuthorityFactory(
                        workflow, candidates, projects), candidates);
    }

    private static TaskRecord task() {
        return new TaskRecord(
                "task.1", "command.1", "instruction.1", null,
                7L, 9L, 42L, null, "client.1", "f".repeat(64),
                8L, "project:version.1", 0L, NOW);
    }

    private static ProjectService projects() {
        return projects(false);
    }

    private static ProjectService projects(boolean empty) {
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 8L)).thenReturn(
                new ProjectManifestResponse(
                        8L, "project:version.1", empty ? List.of() : List.of(
                        new ProjectFileEntry(
                                "paper.md", 8, NOW, BASE_HASH))));
        return projects;
    }

    private static ChainEffectRuntime.FrozenMutation action(
            String candidateIdentity) {
        return new ChainEffectRuntime.FrozenMutation(
                ChainEffectRuntime.SourceKind.TOOL_ACTION,
                "task.1", "action.1", "key.1", "proposal.1",
                "instruction.1", "task-frame.1", "plan.1",
                "revision.1", "step.1", "activation.1",
                "workspace.1", candidateIdentity, "a".repeat(64), FENCE);
    }

    private static String diffDigest(
            String version, CandidateReviewDiff.Entry entry) {
        String canonical = version + '\0' + entry.type()
                + '\0' + entry.relativePath().value()
                + '\0' + (entry.baseFileHash() == null
                ? ChainIdentity.NONE : entry.baseFileHash().sha256())
                + '\0' + (entry.resultFileHash() == null
                ? ChainIdentity.NONE : entry.resultFileHash().sha256());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record Fixture(
            ProductChainActionWorkspaceAuthorityFactory factory,
            CandidateChangeArtifactService candidates) {
    }
}
