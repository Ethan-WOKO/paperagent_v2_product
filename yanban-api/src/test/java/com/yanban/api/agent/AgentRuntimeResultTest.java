package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.sandbox.CandidateIntent;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRuntimeResultTest {

    @Test
    void legacyConstructorsRemainCompatibleAndStructuredIntentDefaultsToAbsent() {
        AgentRuntimeResult result = new AgentRuntimeResult(true, "answer", List.of(), 1,
                null, List.of(), List.of(), null, null, null);

        assertThat(result.candidateChangeSet()).isNull();
        assertThat(result.candidateIntent()).isNull();
        assertThat(result.candidateArtifact()).isNull();
    }

    @Test
    void internalIntentSurvivesRuntimeProjectionsButIsNotSerialized() throws Exception {
        CandidateIntent intent = new CandidateIntent(42L, new ProjectVersionRef("a".repeat(64)), List.of(
                new CandidateIntent.FileIntent(CandidateIntent.Type.ADD,
                        new ProjectRelativePath("new.txt"), null, "full text", List.of("e1"))));
        AgentRuntimeResult projected = new AgentRuntimeResult(true, "answer", List.of(), 1,
                null, List.of(), List.of(), null, null, null)
                .withCandidateIntent(intent)
                .withEvidenceLedger(EvidenceLedger.empty())
                .withTrustedEvidenceLedger(EvidenceLedger.empty())
                .withCompletionVerification(new CompletionVerification(CompletionStatus.VERIFIED,
                        List.of("verified"), List.of(), false, 0))
                .withCoordination(AgentStrategy.SINGLE_STEP_REACT, AgentStopReason.COMPLETED,
                        "VERIFIED", false, null);

        assertThat(projected.candidateIntent()).isSameAs(intent);
        assertThat(new ObjectMapper().writeValueAsString(projected)).doesNotContain("candidateIntent", "full text");
    }
}
