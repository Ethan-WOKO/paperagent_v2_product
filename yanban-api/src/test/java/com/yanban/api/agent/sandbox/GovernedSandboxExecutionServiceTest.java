package com.yanban.api.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.SandboxPlanAuthorityResolver;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentPlanExecutionLease;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateTextPayload;
import com.yanban.core.agent.sandbox.CandidateValidationResult;
import com.yanban.core.agent.sandbox.SandboxFileSnapshot;
import com.yanban.core.agent.sandbox.SandboxWorkspaceRef;
import com.yanban.core.agent.sandbox.SandboxWorkspaceSnapshot;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectManifestIdentity;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GovernedSandboxExecutionServiceTest {

    @Test
    void dispatchCollapsesPlanBasenameAndCandidateCanonicalPathBeforeMaterialization() {
        long userId = 24L;
        long projectId = 38L;
        String path = "src/main/java/xhs_1111.java";
        String current = "class xhs_1111 { static int value = 1; }";
        String proposed = "class xhs_1111 { static int value = 2; }";
        String currentHash = sha256(current);
        String version = ProjectManifestIdentity.derive(List.of(
                new ProjectManifestIdentity.Entry(ProjectRelativePath.of(path), new FileHash(currentHash),
                        current.getBytes(StandardCharsets.UTF_8).length))).value();

        SandboxExecutionProperties properties = new SandboxExecutionProperties();
        properties.setEnabled(true);
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(userId, projectId)).thenReturn(new ProjectManifestResponse(
                projectId, version, List.of(new ProjectFileEntry(
                path, current.getBytes(StandardCharsets.UTF_8).length, Instant.EPOCH, currentHash))));
        SandboxWorkspaceSnapshot snapshot = new SandboxWorkspaceSnapshot(
                new SandboxWorkspaceRef(projectId, new ProjectVersionRef(version)),
                List.of(new SandboxFileSnapshot(ProjectRelativePath.of(path), new FileHash(currentHash),
                        current.getBytes(StandardCharsets.UTF_8).length)));
        var materialized = new ProjectService.SandboxWorkspaceMaterialization(snapshot, Map.of(path, current));
        when(projects.materializeSandbox(userId, projectId, Set.of(path))).thenReturn(materialized);
        when(projects.materializeSandbox(userId, projectId, Set.of("xhs_1111.java", path)))
                .thenReturn(materialized);

        AgentPlanExecutionLease lease = mock(AgentPlanExecutionLease.class);
        when(lease.fence()).thenReturn(1L);
        SandboxPlanAuthorityResolver.Resolution authority = mock(SandboxPlanAuthorityResolver.Resolution.class);
        when(authority.userId()).thenReturn(userId);
        when(authority.projectId()).thenReturn(projectId);
        when(authority.sessionId()).thenReturn(175L);
        when(authority.planId()).thenReturn(135L);
        when(authority.stepId()).thenReturn(338L);
        when(authority.projectVersion()).thenReturn(version);
        when(authority.policyDigest()).thenReturn("b".repeat(64));
        when(authority.remainingExecutions()).thenReturn(1);
        when(authority.lease()).thenReturn(lease);

        CandidateTextPayload candidateText = mock(CandidateTextPayload.class);
        when(candidateText.text()).thenReturn(proposed);
        CandidateFileChange change = mock(CandidateFileChange.class);
        when(change.type()).thenReturn(CandidateFileChange.Type.MODIFY);
        when(change.relativePath()).thenReturn(ProjectRelativePath.of(path));
        when(change.baseFileHash()).thenReturn(new FileHash(currentHash));
        when(change.candidateText()).thenReturn(candidateText);
        CandidateValidationResult validation = mock(CandidateValidationResult.class);
        when(validation.valid()).thenReturn(true);
        CandidateArtifactResponse candidate = mock(CandidateArtifactResponse.class);
        when(candidate.projectId()).thenReturn(projectId);
        when(candidate.projectVersion()).thenReturn(new ProjectVersionRef(version));
        when(candidate.governanceStatus()).thenReturn(CandidateChangeSet.GovernanceStatus.VALIDATED);
        when(candidate.applicationStatus()).thenReturn(CandidateChangeSet.ApplicationStatus.NOT_APPLIED);
        when(candidate.validation()).thenReturn(validation);
        when(candidate.changes()).thenReturn(List.of(change));

        GovernedSandboxExecutionService service = new GovernedSandboxExecutionService(
                properties, new SandboxCommandPolicy(), projects);
        var dispatch = service.prepare(authority,
                new GovernedSandboxExecutionService.Request(
                        "worker24-candidate-overlay", Set.of("xhs_1111.java", path),
                        List.of("java", "xhs_1111.java")),
                candidate);

        assertThat(dispatch.files()).containsEntry(path, proposed).doesNotContainValue(current);
        assertThat(dispatch.files()).containsOnlyKeys(path);
        assertThat(dispatch.argv()).containsExactly("java", path);
        assertThat(candidate.applicationStatus()).isEqualTo(CandidateChangeSet.ApplicationStatus.NOT_APPLIED);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
