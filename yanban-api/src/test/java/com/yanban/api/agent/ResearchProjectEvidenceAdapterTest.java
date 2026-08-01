package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResearchProjectEvidenceAdapterTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mapsOnlyAttestedResearchEnvelopeAndPreservesCompleteVersionBinding() {
        AgentRuntimeResult result = new AgentRuntimeResult(true, "observed", transcript("project_latex_outline", "a".repeat(64),
                "SERVER_ATTESTED_METADATA"), 1, null, List.of(), List.of(), null, null, null);

        EvidenceLedger ledger = AgentService.projectEvidenceFromRuntime(json, result, new ProjectRuntimeContext(7L, 42L), 0);

        assertThat(ledger.evidence()).singleElement().satisfies(ref -> {
            assertThat(ref.id()).startsWith("trusted-tool:42:paper/main.tex:");
            assertThat(ref.version()).isEqualTo("a".repeat(64));
            assertThat(ref.file()).isEqualTo("paper/main.tex");
            assertThat(ref.projectVersion()).isEqualTo("b".repeat(64));
            assertThat(ref.fileHash()).isEqualTo("a".repeat(64));
            assertThat(ref.startLine()).isEqualTo(2);
            assertThat(ref.endLine()).isEqualTo(2);
            assertThat(ref.parserVersion()).isEqualTo("latex-outline@1");
            assertThat(ref.versionStatus()).isEqualTo(EvidenceVersionStatus.VERIFIED);
        });
    }

    @Test
    void legacyEvidenceIsExplicitlyUnversioned() {
        EvidenceRef legacy = new EvidenceRef("legacy:1", EvidenceSourceType.PROJECT, "PROJECT",
                "paper.tex", "line:1", null, "a".repeat(64), "old data");

        assertThat(legacy.versionStatus()).isEqualTo(EvidenceVersionStatus.LEGACY_UNVERSIONED);
        assertThat(legacy.projectVersion()).isNull();
    }

    @Test
    void malformedVersionBindingCannotBecomeVerifiedOrStale() {
        assertThatThrownBy(() -> new EvidenceRef("bad:version", EvidenceSourceType.PROJECT, "PROJECT",
                "paper.tex", "tool:1", null, "a".repeat(64), "bad", "m", "a".repeat(64),
                1, 1, "parser@1", EvidenceVersionStatus.VERIFIED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceRef("bad:hash", EvidenceSourceType.PROJECT, "PROJECT",
                "paper.tex", "tool:1", null, "bad", "bad", "b".repeat(64), "h1",
                1, 1, "parser@1", EvidenceVersionStatus.STALE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void staleStatusPreservesCompleteProvenanceAndIsNotVerified() {
        EvidenceRef verified = new EvidenceRef("typed:1", EvidenceSourceType.PROJECT, "PROJECT", "paper.tex",
                "tool:1", null, "a".repeat(64), "test", "b".repeat(64), "a".repeat(64),
                4, 7, "parser@1", EvidenceVersionStatus.VERIFIED);

        EvidenceRef stale = verified.stale();

        assertThat(stale.versionStatus()).isEqualTo(EvidenceVersionStatus.STALE);
        assertThat(stale.projectVersion()).isEqualTo(verified.projectVersion());
        assertThat(stale.fileHash()).isEqualTo(verified.fileHash());
        assertThat(stale.startLine()).isEqualTo(4);
        assertThat(stale.endLine()).isEqualTo(7);
    }

    @Test
    void chatOrLookalikeJsonCannotManufactureTrustedResearchEvidence() {
        AgentRuntimeResult chat = new AgentRuntimeResult(true, "text", List.of(ChatMessage.tool("r1",
                envelope("a".repeat(64), "SERVER_ATTESTED_METADATA"))), 1, null, List.of(), List.of(), null, null, null);
        AgentRuntimeResult badTrust = new AgentRuntimeResult(true, "text", transcript("project_latex_outline", "a".repeat(64),
                "UNTRUSTED_PROJECT_CONTENT"), 1, null, List.of(), List.of(), null, null, null);
        AgentRuntimeResult otherTool = new AgentRuntimeResult(true, "text", transcript("project_read_file", "a".repeat(64),
                "SERVER_ATTESTED_METADATA"), 1, null, List.of(), List.of(), null, null, null);

        assertThat(AgentService.projectEvidenceFromRuntime(json, chat, new ProjectRuntimeContext(7L, 42L), 0).evidence()).isEmpty();
        assertThat(AgentService.projectEvidenceFromRuntime(json, badTrust, new ProjectRuntimeContext(7L, 42L), 0).evidence()).isEmpty();
        assertThat(AgentService.projectEvidenceFromRuntime(json, otherTool, new ProjectRuntimeContext(7L, 42L), 0).evidence()).isEmpty();
    }

    @Test
    void historicalResearchCallCannotAuthorizeAReplayedCurrentToolResult() {
        List<ChatMessage> messages = List.of(
                new ChatMessage("assistant", null, List.of(new ToolCall("r1", "function",
                        new ToolCall.FunctionCall("project_latex_outline", "{}"))), null),
                ChatMessage.assistant("current turn"),
                ChatMessage.tool("r1", envelope("a".repeat(64), "SERVER_ATTESTED_METADATA")));
        AgentRuntimeResult result = new AgentRuntimeResult(true, "text", messages, 1, null, List.of(), List.of(), null, null, null);

        assertThat(AgentService.projectEvidenceFromRuntime(json, result, new ProjectRuntimeContext(7L, 42L), 1).evidence()).isEmpty();
    }

    private List<ChatMessage> transcript(String toolName, String hash, String trust) {
        return List.of(new ChatMessage("assistant", null, List.of(new ToolCall("r1", "function",
                new ToolCall.FunctionCall(toolName, "{}"))), null), ChatMessage.tool("r1", envelope(hash, trust)));
    }

    private String envelope(String hash, String trust) {
        return "{\"status\":\"COMPLETE\",\"items\":[],\"evidenceRefs\":[{\"projectVersion\":\"" + "b".repeat(64)
                + "\",\"relativePath\":\"paper/main.tex\",\"fileHash\":\"" + hash
                + "\",\"range\":{\"startLine\":2,\"endLine\":2},\"parserVersion\":\"latex-outline@1\",\"trustLabel\":\"" + trust
                + "\"}],\"partial\":false,\"truncated\":false,\"parseFailed\":false}";
    }
}
