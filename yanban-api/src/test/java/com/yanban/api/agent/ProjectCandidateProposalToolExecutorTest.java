package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolErrorCode;
import com.yanban.core.tool.ToolExecutionContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProjectCandidateProposalToolExecutorTest {
    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 42L;
    private static final String VERSION = "a".repeat(64);
    private static final String HASH = "b".repeat(64);
    private final ProjectService projects = mock(ProjectService.class);
    private final CandidateChangeArtifactService candidates = mock(CandidateChangeArtifactService.class);
    private final CandidateProposalEvidenceSelector selector = mock(CandidateProposalEvidenceSelector.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectCandidateProposalToolExecutor executor =
            new ProjectCandidateProposalToolExecutor(projects, candidates, selector, json);

    @AfterEach
    void clear() { ToolExecutionContext.clear(); }

    @Test
    void schemaAndDescriptorExposeOnlyProposalFieldsToProjectProfile() {
        ToolDescriptor descriptor = executor.descriptor();
        assertThat(descriptor.supportedProfiles()).containsExactly(ToolDescriptor.CapabilityProfile.PROJECT);
        assertThat(descriptor.sideEffectType()).isEqualTo(ToolDescriptor.SideEffectType.CREATE);
        assertThat(descriptor.requiredPermissions()).containsExactly("project:candidate-propose");
        assertThat(executor.definition().parameters().toString())
                .contains("changes", "relativePath", "baseFileHash", "replacementText", "parserVersion")
                .doesNotContain("projectId", "userId", "verified", "evidenceRefIds");
    }

    @Test
    void rejectsWithoutExactRuntimeScopeAndDoesNotPersist() throws Exception {
        ToolCall call = call(validArguments());
        assertThat(executor.execute(call).errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);

        verify(candidates, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsAuthorityFieldsAndAssistantStyleJsonBeforePersistence() throws Exception {
        var arguments = validArguments();
        arguments.put("projectId", 99L);
        ToolResultScope scope = openScope();
        try (scope) {
            assertThat(executor.execute(call(arguments)).errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
        }
        verify(candidates, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    void delegatesValidatedIntentToArtifactOnlyServiceAndReturnsNotAppliedResponse() throws Exception {
        when(projects.manifest(USER_ID, PROJECT_ID)).thenReturn(new ProjectManifestResponse(PROJECT_ID, VERSION, List.of()));
        EvidenceRef evidence = new EvidenceRef("trusted-plan:42:1", EvidenceSourceType.PROJECT, "PROJECT",
                "paper/main.tex", "tool:x", null, HASH, "trusted", VERSION, HASH, 2, 4,
                "project-read-file@1", EvidenceVersionStatus.VERIFIED);
        when(selector.select(any(), any(), any())).thenReturn(new CandidateProposalEvidenceSelector.Selection(
                new EvidenceLedger(List.of(evidence)), List.of(List.of(evidence.id()))));
        CandidateArtifactResponse response = mock(CandidateArtifactResponse.class);
        when(response.schemaVersion()).thenReturn("YANBAN_CANDIDATE_ARTIFACT_V1");
        when(response.artifactId()).thenReturn(11L);
        when(response.projectId()).thenReturn(PROJECT_ID);
        when(candidates.store(eq(USER_ID), eq(3L), any(), any(), any())).thenReturn(response);

        try (ToolResultScope ignored = openScope()) {
            var result = executor.execute(call(validArguments()));
            assertThat(result.success()).isTrue();
            assertThat(result.sideEffects()).containsExactly("proposal-only", "NOT_APPLIED");
        }
        verify(candidates).store(eq(USER_ID), eq(3L), any(), any(), any());
    }

    @Test
    void treatsBlankOptionalBaseHashAsAbsentForAddProposal() throws Exception {
        when(projects.manifest(USER_ID, PROJECT_ID)).thenReturn(
                new ProjectManifestResponse(PROJECT_ID, VERSION, List.of()));
        EvidenceRef evidence = new EvidenceRef("trusted-plan:42:1", EvidenceSourceType.PROJECT, "PROJECT",
                "paper/main.tex", "tool:x", null, HASH, "trusted", VERSION, HASH, 2, 4,
                "project-read-file@1", EvidenceVersionStatus.VERIFIED);
        when(selector.select(any(), any(), any())).thenReturn(new CandidateProposalEvidenceSelector.Selection(
                new EvidenceLedger(List.of(evidence)), List.of(List.of(evidence.id()))));
        CandidateArtifactResponse response = mock(CandidateArtifactResponse.class);
        when(response.schemaVersion()).thenReturn("YANBAN_CANDIDATE_ARTIFACT_V1");
        when(response.artifactId()).thenReturn(12L);
        when(response.projectId()).thenReturn(PROJECT_ID);
        when(candidates.store(eq(USER_ID), eq(3L), any(), any(), any())).thenReturn(response);
        var arguments = validArguments();
        var change = (com.fasterxml.jackson.databind.node.ObjectNode) arguments.path("changes").get(0);
        change.put("type", "ADD").put("relativePath", "acceptance/new.md").put("baseFileHash", "");

        try (ToolResultScope ignored = openScope()) {
            assertThat(executor.execute(call(arguments)).success()).isTrue();
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode validArguments() {
        var evidence = json.createObjectNode().put("relativePath", "paper/main.tex").put("fileHash", HASH)
                .put("startLine", 2).put("endLine", 4).put("parserVersion", "project-read-file@1");
        var change = json.createObjectNode().put("type", "MODIFY").put("relativePath", "paper/main.tex")
                .put("baseFileHash", HASH).put("replacementText", "updated\n");
        change.putArray("evidence").add(evidence);
        var root = json.createObjectNode();
        root.putArray("changes").add(change);
        return root;
    }

    private ToolCall call(com.fasterxml.jackson.databind.JsonNode args) {
        return new ToolCall("proposal-1", ProjectCandidateProposalToolExecutor.TOOL_NAME, args);
    }

    private ToolResultScope openScope() {
        ToolExecutionContext.setCurrentUserId(USER_ID);
        ToolExecutionContext.setCurrentProjectId(PROJECT_ID);
        ToolExecutionContext.setResolvedAllowedTools(Set.of(ProjectCandidateProposalToolExecutor.TOOL_NAME));
        AgentRuntimeRequest request = new AgentRuntimeRequest(AgentStrategy.SINGLE_STEP_REACT, 3L, List.of(), USER_ID,
                "propose", "test", "test", 0.0, 1000, 3, true, null, "key", "url", null,
                AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of(ProjectCandidateProposalToolExecutor.TOOL_NAME), 1, 1, "test"),
                1, 1, "trace", null, null).withProjectContext(new ProjectRuntimeContext(USER_ID, PROJECT_ID));
        return new ToolResultScope(CandidateProposalExecutionScope.open(request, List.of()));
    }

    private record ToolResultScope(CandidateProposalExecutionScope delegate) implements AutoCloseable {
        @Override public void close() { delegate.close(); }
    }
}
