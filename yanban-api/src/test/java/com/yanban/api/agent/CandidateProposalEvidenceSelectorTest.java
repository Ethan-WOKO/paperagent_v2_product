package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ToolCall;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CandidateProposalEvidenceSelectorTest {
    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 42L;
    private static final String VERSION = "a".repeat(64);
    private static final String HASH = "b".repeat(64);
    private final ProjectService projects = mock(ProjectService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final CandidateProposalEvidenceSelector selector = new CandidateProposalEvidenceSelector(
            json, new ProjectEvidenceValidator(projects));

    @Test
    void resolvesOnePortableSelectorToServerAttestedCurrentEvidence() throws Exception {
        arrangeManifest(PROJECT_ID, VERSION, HASH);
        CandidateProposalExecutionScope.Context scope = scope(List.of(
                assistantCall("read-1", "project_read_file"),
                ChatMessage.tool("read-1", json.writeValueAsString(readResult(PROJECT_ID, VERSION, HASH)))));

        CandidateProposalEvidenceSelector.Selection selected = selector.select(scope,
                new ProjectVersionRef(VERSION), List.of(List.of(portable(HASH))));

        assertThat(selected.evidenceIds()).hasSize(1);
        assertThat(selected.evidenceIds().get(0)).hasSize(1);
        assertThat(selected.evidenceIds().get(0).get(0)).startsWith("trusted-tool:42:paper/main.tex:");
        assertThat(selected.ledger().evidence()).singleElement().satisfies(ref -> {
            assertThat(ref.projectVersion()).isEqualTo(VERSION);
            assertThat(ref.versionStatus()).isEqualTo(EvidenceVersionStatus.VERIFIED);
        });
    }

    @Test
    void repeatedExactTrustedReadsResolveToLatestEquivalentObservation() throws Exception {
        arrangeManifest(PROJECT_ID, VERSION, HASH);
        CandidateProposalExecutionScope.Context scope = scope(List.of(
                assistantCall("read-1", "project_read_file"),
                ChatMessage.tool("read-1", json.writeValueAsString(readResult(PROJECT_ID, VERSION, HASH))),
                assistantCall("read-2", "project_read_file"),
                ChatMessage.tool("read-2", json.writeValueAsString(readResult(PROJECT_ID, VERSION, HASH)))));

        CandidateProposalEvidenceSelector.Selection selected = selector.select(scope,
                new ProjectVersionRef(VERSION), List.of(List.of(portable(HASH))));

        assertThat(selected.evidenceIds()).singleElement().satisfies(ids ->
                assertThat(ids).singleElement().asString().endsWith(":read-2"));
    }

    @Test
    void rejectsStaleCrossProjectLegacyAndAssistantAuthoredEvidence() throws Exception {
        arrangeManifest(PROJECT_ID, VERSION, HASH);
        EvidenceRef legacy = new EvidenceRef("legacy", "PROJECT", "paper/main.tex", "tool:x", null, HASH, "legacy");
        EvidenceRef crossProject = verified("trusted-plan:99:1", 99L, VERSION, HASH);
        CandidateProposalExecutionScope.Context scope = new CandidateProposalExecutionScope.Context(
                USER_ID, PROJECT_ID, 3L,
                List.of(ChatMessage.assistant(json.writeValueAsString(readResult(PROJECT_ID, VERSION, HASH)))),
                0, new EvidenceLedger(List.of(legacy, crossProject)),
                Set.of("project_read_file", ProjectCandidateProposalToolExecutor.TOOL_NAME));

        assertThatThrownBy(() -> selector.select(scope, new ProjectVersionRef(VERSION),
                List.of(List.of(portable(HASH)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no exact current trusted match");
    }

    @Test
    void rejectsStaleVersionAndSelectorMismatch() throws Exception {
        arrangeManifest(PROJECT_ID, VERSION, HASH);
        EvidenceRef stale = verified("trusted-plan:42:1", PROJECT_ID, "c".repeat(64), HASH);
        CandidateProposalExecutionScope.Context scope = new CandidateProposalExecutionScope.Context(
                USER_ID, PROJECT_ID, 3L, List.of(), 0, new EvidenceLedger(List.of(stale)),
                Set.of(ProjectCandidateProposalToolExecutor.TOOL_NAME));

        assertThatThrownBy(() -> selector.select(scope, new ProjectVersionRef(VERSION),
                List.of(List.of(portable(HASH)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no exact current trusted match");
    }

    @Test
    void rejectsToolOutputThatDoesNotExposeParserProvenance() throws Exception {
        arrangeManifest(PROJECT_ID, VERSION, HASH);
        var incomplete = new java.util.LinkedHashMap<>(readResult(PROJECT_ID, VERSION, HASH));
        incomplete.remove("parserVersion");
        CandidateProposalExecutionScope.Context scope = scope(List.of(
                assistantCall("read-1", "project_read_file"),
                ChatMessage.tool("read-1", json.writeValueAsString(incomplete))));

        assertThatThrownBy(() -> selector.select(scope, new ProjectVersionRef(VERSION),
                List.of(List.of(portable(HASH)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no exact current trusted match");
    }

    @Test
    void explainsThatAReadSubrangeMustBeObservedExactlyBeforeProposal() throws Exception {
        arrangeManifest(PROJECT_ID, VERSION, HASH);
        CandidateProposalExecutionScope.Context scope = scope(List.of(
                assistantCall("read-1", "project_read_file"),
                ChatMessage.tool("read-1", json.writeValueAsString(readResult(PROJECT_ID, VERSION, HASH)))));
        var subrange = new CandidateProposalEvidenceSelector.PortableEvidenceSelector(
                new ProjectRelativePath("paper/main.tex"), new FileHash(HASH), 3, 3,
                ProjectReadFileToolExecutor.PARSER_VERSION);

        assertThatThrownBy(() -> selector.select(scope, new ProjectVersionRef(VERSION),
                List.of(List.of(subrange))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a subrange of a larger read is not accepted")
                .hasMessageContaining("Exact observed ranges for this file/hash/parser: 2-4")
                .hasMessageContaining("Re-read exactly lines 3-3");
    }

    private CandidateProposalExecutionScope.Context scope(List<ChatMessage> messages) {
        return new CandidateProposalExecutionScope.Context(USER_ID, PROJECT_ID, 3L, messages, 0,
                EvidenceLedger.empty(), Set.of("project_read_file", ProjectCandidateProposalToolExecutor.TOOL_NAME));
    }

    private ChatMessage assistantCall(String id, String name) {
        return new ChatMessage("assistant", null,
                List.of(new ToolCall(id, "function", new ToolCall.FunctionCall(name, "{}"))), null);
    }

    private java.util.Map<String, Object> readResult(long projectId, String version, String hash) {
        return java.util.Map.of("projectId", projectId, "projectVersion", version,
                "relativePath", "paper/main.tex", "hash", hash, "startLine", 2, "endLine", 4,
                "parserVersion", ProjectReadFileToolExecutor.PARSER_VERSION);
    }

    private CandidateProposalEvidenceSelector.PortableEvidenceSelector portable(String hash) {
        return new CandidateProposalEvidenceSelector.PortableEvidenceSelector(
                new ProjectRelativePath("paper/main.tex"), new FileHash(hash), 2, 4, "project-read-file@1");
    }

    private EvidenceRef verified(String id, long projectId, String version, String hash) {
        return new EvidenceRef(id, EvidenceSourceType.PROJECT, "PROJECT", "paper/main.tex", "tool:x", null,
                hash, "trusted", version, hash, 2, 4, "project-read-file@1", EvidenceVersionStatus.VERIFIED);
    }

    private void arrangeManifest(long projectId, String version, String hash) {
        when(projects.manifest(USER_ID, PROJECT_ID)).thenReturn(new ProjectManifestResponse(projectId, version,
                List.of(new ProjectFileEntry("paper/main.tex", 10, Instant.EPOCH, hash))));
    }
}
