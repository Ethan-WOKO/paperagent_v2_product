package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.SandboxExecutionProperties;
import com.yanban.api.artifact.AgentArtifactService;
import com.yanban.api.artifact.ArtifactResponse;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import com.yanban.core.agent.sandbox.CandidateValidationResult;
import com.yanban.core.research.ProjectVersionRef;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentChainCandidateValidationAuthorityTest {
    private static final long USER = 7L;
    private static final long PROJECT = 11L;
    private static final long SESSION = 13L;
    private static final long ARTIFACT = 17L;
    private static final String VERSION = "a".repeat(64);

    @Test
    void exactCandidateSandboxProofIsPersistedAsFormalValidation() {
        CandidateSandboxValidationRepository repository = mock(
                CandidateSandboxValidationRepository.class);
        CandidateSandboxValidationService responses = mock(
                CandidateSandboxValidationService.class);
        CandidateChangeArtifactService candidates = mock(
                CandidateChangeArtifactService.class);
        AgentArtifactService artifacts = mock(AgentArtifactService.class);
        AgentCandidateAutoApplicationService autoApplications = mock(
                AgentCandidateAutoApplicationService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SandboxExecutionProperties sandbox = new SandboxExecutionProperties();

        CandidateArtifactResponse candidate = mock(
                CandidateArtifactResponse.class);
        CandidateValidationResult candidateValidation = mock(
                CandidateValidationResult.class);
        when(candidate.projectId()).thenReturn(PROJECT);
        when(candidate.projectVersion()).thenReturn(
                new ProjectVersionRef(VERSION));
        when(candidate.fingerprint()).thenReturn(
                new CandidateFingerprint("b".repeat(64)));
        when(candidate.governanceStatus()).thenReturn(
                CandidateChangeSet.GovernanceStatus.VALIDATED);
        when(candidate.applicationStatus()).thenReturn(
                CandidateChangeSet.ApplicationStatus.NOT_APPLIED);
        when(candidate.validation()).thenReturn(candidateValidation);
        when(candidateValidation.valid()).thenReturn(true);
        when(candidate.changes()).thenReturn(List.of(
                mock(CandidateFileChange.class)));
        when(candidates.getCurrent(USER, ARTIFACT)).thenReturn(candidate);
        when(artifacts.getArtifact(USER, ARTIFACT)).thenReturn(
                new ArtifactResponse(ARTIFACT, USER, SESSION, "candidate",
                        "JSON", "{}", "CANDIDATE", List.of(), "ACTIVE",
                        null, null, null, Instant.EPOCH, Instant.EPOCH));
        when(autoApplications.proofChain(
                USER, PROJECT, VERSION, "task-chain", "plan-chain",
                "action-candidate", "workspace-chain", "step-final",
                ARTIFACT)).thenReturn(
                new AgentCandidateAutoApplicationService.VerificationProof(
                        "sandbox-receipt.formal", List.of("src/Main.java"),
                        List.of("runner", "src/Main.java"), "passed", "", 0,
                        Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                        List.of(new AgentCandidateAutoApplicationService
                                .VerifiedInputState(
                                "src/Main.java",
                                AgentCandidateAutoApplicationService
                                        .InputPresence.PRESENT,
                                "a".repeat(64)))));
        when(repository.findByUserIdAndProjectIdAndIdempotencyKey(
                USER, PROJECT, "chain-validation:test"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call ->
                call.getArgument(0));
        when(jdbc.queryForObject("select current_timestamp",
                LocalDateTime.class)).thenReturn(
                LocalDateTime.of(2026, 8, 8, 18, 0));
        when(responses.response(any())).thenAnswer(call -> {
            CandidateSandboxValidation value = call.getArgument(0);
            return new CandidateValidationResponse(
                    value.validationId(), value.projectId(),
                    value.artifactId(), value.projectVersion(),
                    value.candidateFingerprint(), List.of(0),
                    CandidateValidationProfile.valueOf(value.profile()),
                    value.status(), 0, false, sandbox.getProvider(),
                    "passed", "", false, value.requestDigest(),
                    value.receiptDigest(), value.errorCode(), null, null,
                    value.decisionStatus(), null, null, value.createdAt(),
                    value.updatedAt());
        });

        AgentChainCandidateValidationAuthority service =
                new AgentChainCandidateValidationAuthority(
                        repository, responses, candidates, artifacts,
                        autoApplications, sandbox,
                        new ObjectMapper().findAndRegisterModules(), jdbc);
        AgentChainCandidateValidationAuthority.Binding binding = service.bind(
                USER, PROJECT, ARTIFACT, VERSION, "task-chain",
                "plan-chain", "step-final", "action-candidate",
                "workspace-chain", "chain-validation:test");

        ArgumentCaptor<CandidateSandboxValidation> stored =
                ArgumentCaptor.forClass(CandidateSandboxValidation.class);
        verify(repository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().profile()).isEqualTo(
                CandidateValidationProfile.AGENT_CHAIN_EXACT_CANDIDATE.name());
        assertThat(stored.getValue().status()).isEqualTo("SUCCEEDED");
        assertThat(stored.getValue().brokerExecutionId()).hasSize(64);
        assertThat(stored.getValue().receiptDigest()).hasSize(64);
        assertThat(stored.getValue().receiptJson())
                .contains("\"executionId\":\"sandbox-receipt.formal\"")
                .contains("\"stdout\":\"\"")
                .contains("\"stderr\":\"\"");
        assertThat(binding.validationId()).isEqualTo(
                stored.getValue().validationId());
        assertThat(binding.requestDigest()).isEqualTo(
                stored.getValue().requestDigest());
        assertThat(binding.receiptDigest()).isEqualTo(
                stored.getValue().receiptDigest());
        assertThat(binding.sandboxReceiptRef())
                .isEqualTo("sandbox-receipt.formal");
        verify(autoApplications).proofChain(
                USER, PROJECT, VERSION, "task-chain", "plan-chain",
                "action-candidate", "workspace-chain", "step-final",
                ARTIFACT);
    }
}
