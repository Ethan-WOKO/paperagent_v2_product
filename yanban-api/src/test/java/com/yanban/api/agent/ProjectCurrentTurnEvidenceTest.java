package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectCurrentTurnEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String PROJECT_VERSION = "b".repeat(64);
    private static final String FILE_HASH = "a".repeat(64);

    @Test
    void historicalProjectToolResultCannotSatisfyThisTurnEvidenceGate() {
        ChatMessage historicalTool = new ChatMessage("tool", legacyTool("src/Old.java", FILE_HASH), null, "old-call");
        AgentRuntimeResult runtime = new AgentRuntimeResult(true, "No new inspection.", List.of(historicalTool, ChatMessage.assistant("No new inspection.")),
                1, null, List.of(), List.of(), null, null, null);
        EvidenceLedger ledger = AgentService.projectEvidenceFromRuntime(json, runtime, new ProjectRuntimeContext(7L, 42L), 1);

        assertThat(ledger.evidence()).isEmpty();
        AgentRuntimeResult limited = runtime.insufficientProjectEvidence(ledger);
        assertThat(limited.success()).isFalse();
        assertThat(limited.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(limited.assistantContent()).contains("Insufficient Project evidence");
    }

    @Test
    void currentToolResultCreatesProjectEvidence() {
        AgentRuntimeResult runtime = new AgentRuntimeResult(true, "Observed.", List.of(
                ChatMessage.system("history"),
                new ChatMessage("tool", tool("src/Main.java", FILE_HASH), null, "new-call"),
                ChatMessage.assistant("Observed.")), 1, null, List.of(), List.of(), null, null, null);

        EvidenceLedger ledger = AgentService.projectEvidenceFromRuntime(json, runtime, new ProjectRuntimeContext(7L, 42L), 1);

        assertThat(ledger.evidence()).singleElement().satisfies(ref -> {
            assertThat(ref.file()).isEqualTo("src/Main.java");
            assertThat(ref.projectVersion()).isEqualTo(PROJECT_VERSION);
            assertThat(ref.fileHash()).isEqualTo(FILE_HASH);
            assertThat(ref.version()).isEqualTo(FILE_HASH);
            assertThat(ref.startLine()).isEqualTo(3);
            assertThat(ref.endLine()).isEqualTo(7);
            assertThat(ref.parserVersion()).isEqualTo("project-read-file@1");
            assertThat(ref.versionStatus()).isEqualTo(EvidenceVersionStatus.VERIFIED);
            assertThat(ref.sourceType()).isEqualTo(EvidenceSourceType.PROJECT);
        });
    }

    @Test
    void exactDuplicateCurrentToolResultIsRepresentedOnce() {
        ChatMessage duplicate = new ChatMessage("tool", tool("src/Main.java", FILE_HASH), null, "same-call");
        AgentRuntimeResult runtime = new AgentRuntimeResult(true, "Observed.", List.of(
                ChatMessage.system("history"), duplicate, duplicate, ChatMessage.assistant("Observed.")),
                1, null, List.of(), List.of(), null, null, null);

        EvidenceLedger ledger = AgentService.projectEvidenceFromRuntime(
                json, runtime, new ProjectRuntimeContext(7L, 42L), 1);

        assertThat(ledger.evidence()).singleElement().satisfies(ref -> {
            assertThat(ref.file()).isEqualTo("src/Main.java");
            assertThat(ref.chunk()).isEqualTo("tool:same-call");
        });
    }

    private String tool(String path, String hash) {
        return "{\"projectId\":42,\"projectVersion\":\"" + PROJECT_VERSION
                + "\",\"relativePath\":\"" + path + "\",\"hash\":\"" + hash
                + "\",\"version\":\"" + hash + "\",\"startLine\":3,\"endLine\":7,\"trust\":\"UNTRUSTED\"}";
    }

    private String legacyTool(String path, String hash) {
        return "{\"projectId\":42,\"relativePath\":\"" + path + "\",\"hash\":\"" + hash
                + "\",\"version\":\"" + hash + "\",\"trust\":\"UNTRUSTED\"}";
    }
}
