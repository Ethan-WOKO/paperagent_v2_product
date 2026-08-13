package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.V2SandboxInputFingerprint;
import com.yanban.api.agent.v2.chain.effect.ProjectCandidateEffectAuthority;
import com.yanban.api.agent.v2.effect.project.NaturalLanguageCandidateAuthorityStore;
import com.yanban.api.agent.v2.persistence.V2EffectHistorySource;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import com.yanban.core.agent.sandbox.CandidateTextPayload;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ParserVersionRef;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.ResearchEvidenceRef;
import com.yanban.core.research.SourceRange;
import com.yanban.core.research.TrustLabel;
import io.paperagent.v2.contracts.ArtifactRef;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentCandidateAutoApplicationServiceTest {
    private static final Long USER_ID = 7L;
    private static final Long PROJECT_ID = 8L;
    private static final Long TURN_ID = 9L;
    private static final Long ARTIFACT_ID = 10L;
    private static final String PLAN_ID = "plan-auto-apply";
    private static final String VERSION = "a".repeat(64);
    private static final String BASE_TEXT = "return total / item_count;";
    private static final String FIXED_TEXT = "return total / len(items);";

    private final NaturalLanguageCandidateAuthorityStore authorities =
            mock(NaturalLanguageCandidateAuthorityStore.class);
    private final CandidateChangeArtifactService candidates =
            mock(CandidateChangeArtifactService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final V2EffectHistorySource effects =
            mock(V2EffectHistorySource.class);
    private final ChainWorkflowRepository chainWorkflow =
            mock(ChainWorkflowRepository.class);
    private final ProjectRevisionWorkflowService revisions =
            mock(ProjectRevisionWorkflowService.class);
    private final AgentCandidateAutoApplicationService service =
            new AgentCandidateAutoApplicationService(
                    authorities, candidates, projects, effects,
                    chainWorkflow, revisions);

    private CandidateArtifactResponse candidate;

    @BeforeEach
    void setUp() {
        when(authorities.require(PLAN_ID)).thenReturn(
                new ProjectCandidateEffectAuthority(
                        "project.candidate.compose", "{}", "b".repeat(64),
                        USER_ID, PROJECT_ID, 11L, TURN_ID, VERSION,
                        "fix test", List.of("src/test.py")));
        candidate = candidate();
        when(candidates.getCurrent(USER_ID, ARTIFACT_ID))
                .thenReturn(candidate);
        when(projects.readFile(USER_ID, PROJECT_ID, "src/test.py"))
                .thenReturn(new ProjectFileResponse(
                        "src/test.py", BASE_TEXT, BASE_TEXT.length(),
                        Instant.EPOCH, sha(BASE_TEXT)));
        when(projects.manifest(USER_ID, PROJECT_ID)).thenReturn(
                new ProjectManifestResponse(PROJECT_ID, VERSION, List.of(
                        new ProjectFileEntry(
                                "src/test.py", BASE_TEXT.length(),
                                Instant.EPOCH, sha(BASE_TEXT)))));
    }

    @Test
    void exactSuccessfulFinalSandboxRunIsAppliedAutomatically() {
        String proof = V2SandboxInputFingerprint.artifactReference(
                Map.of("src/test.py", FIXED_TEXT)).value();
        List<V2EffectHistorySource.Entry> history = List.of(
                entry("project.candidate.compose", Map.of(),
                        receipt(ReceiptStatus.SUCCESS, 0, null)),
                entry("sandbox.execute", paths("src/test.py"),
                        receipt(ReceiptStatus.SUCCESS, 0, proof)));
        when(effects.inspect(new PlanId(PLAN_ID))).thenReturn(history);
        ProjectRevisionOperationResponse applied =
                mock(ProjectRevisionOperationResponse.class);
        when(revisions.applyAutomatically(
                USER_ID, PROJECT_ID, ARTIFACT_ID,
                "agent-auto-apply:" + sha(PLAN_ID).substring(0, 32),
                VERSION, "c".repeat(64), "receipt-final"))
                .thenReturn(applied);

        org.assertj.core.api.Assertions.assertThat(service.apply(
                USER_ID, TURN_ID, PLAN_ID, ARTIFACT_ID)).isSameAs(applied);
    }

    @Test
    void failedDuplicateCandidateDoesNotHideEarlierSuccessfulCandidate() {
        String proof = V2SandboxInputFingerprint.artifactReference(
                Map.of("src/test.py", FIXED_TEXT)).value();
        List<V2EffectHistorySource.Entry> history = List.of(
                entry("project.candidate.compose", Map.of(),
                        receipt(ReceiptStatus.SUCCESS, 0, null)),
                entry("project.candidate.compose", Map.of(),
                        receipt(ReceiptStatus.FAILURE, 1, null)),
                entry("sandbox.execute", paths("src/test.py"),
                        receipt(ReceiptStatus.SUCCESS, 0, proof)));
        when(effects.inspect(new PlanId(PLAN_ID))).thenReturn(history);
        ProjectRevisionOperationResponse applied =
                mock(ProjectRevisionOperationResponse.class);
        when(revisions.applyAutomatically(
                USER_ID, PROJECT_ID, ARTIFACT_ID,
                "agent-auto-apply:" + sha(PLAN_ID).substring(0, 32),
                VERSION, "c".repeat(64), "receipt-final"))
                .thenReturn(applied);

        org.assertj.core.api.Assertions.assertThat(service.apply(
                USER_ID, TURN_ID, PLAN_ID, ARTIFACT_ID)).isSameAs(applied);
    }

    @Test
    void differentSandboxInputNeverChangesTheProject() {
        String wrongProof = V2SandboxInputFingerprint.artifactReference(
                Map.of("src/test.py", BASE_TEXT)).value();
        List<V2EffectHistorySource.Entry> history = List.of(
                entry("project.candidate.compose", Map.of(),
                        receipt(ReceiptStatus.SUCCESS, 0, null)),
                entry("sandbox.execute", paths("src/test.py"),
                        receipt(ReceiptStatus.SUCCESS, 0, wrongProof)));
        when(effects.inspect(new PlanId(PLAN_ID))).thenReturn(history);

        assertThatThrownBy(() -> service.apply(
                USER_ID, TURN_ID, PLAN_ID, ARTIFACT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox_input_fingerprint");
        verify(revisions, never()).applyAutomatically(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void wrongStateProofNeverFallsBackToCorrectLegacyProof() {
        String wrongState = V2SandboxInputFingerprint
                .stateArtifactReference(
                        Map.of("src/test.py", BASE_TEXT), Set.of())
                .value();
        String correctLegacy = V2SandboxInputFingerprint.artifactReference(
                Map.of("src/test.py", FIXED_TEXT)).value();
        ExecutionReceipt sandboxReceipt = receipt(
                ReceiptStatus.SUCCESS, 0, null);
        when(sandboxReceipt.artifactReferences()).thenReturn(List.of(
                new ArtifactRef(wrongState),
                new ArtifactRef(correctLegacy)));
        List<V2EffectHistorySource.Entry> history = List.of(
                entry("project.candidate.compose", Map.of(),
                        receipt(ReceiptStatus.SUCCESS, 0, null)),
                entry("sandbox.execute", paths("src/test.py"),
                        sandboxReceipt));
        when(effects.inspect(new PlanId(PLAN_ID))).thenReturn(history);

        assertThatThrownBy(() -> service.proof(PLAN_ID, ARTIFACT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox_input_fingerprint");
    }

    @Test
    void failedFinalSandboxRunNeverChangesTheProject() {
        List<V2EffectHistorySource.Entry> history = List.of(
                entry("project.candidate.compose", Map.of(),
                        receipt(ReceiptStatus.SUCCESS, 0, null)),
                entry("sandbox.execute", paths("src/test.py"),
                        receipt(ReceiptStatus.FAILURE, 1, null)));
        when(effects.inspect(new PlanId(PLAN_ID))).thenReturn(history);

        assertThatThrownBy(() -> service.apply(
                USER_ID, TURN_ID, PLAN_ID, ARTIFACT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox_result");
        verify(revisions, never()).applyAutomatically(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void formalChainCandidateMayBeValidatedByALaterStep() {
        String proof = V2SandboxInputFingerprint.artifactReference(
                Map.of("src/test.py", FIXED_TEXT)).value();
        List<V2EffectHistorySource.Entry> history = List.of(
                entry("validation-sandbox", "step-verify",
                        "sandbox.execute", paths("src/test.py"),
                        receipt(ReceiptStatus.SUCCESS, 0, proof)));
        when(effects.inspect(new PlanId(PLAN_ID))).thenReturn(history);
        chainProofBindings("validation-sandbox", "step-verify",
                "c".repeat(64));

        org.assertj.core.api.Assertions.assertThat(service.proofChain(
                USER_ID, PROJECT_ID, VERSION, "task-chain", PLAN_ID,
                "candidate-action", "workspace-chain", "step-verify",
                ARTIFACT_ID))
                .extracting(AgentCandidateAutoApplicationService
                        .VerificationProof::receiptId)
                .isEqualTo("receipt-final");
    }

    @Test
    void formalChainProofRejectsSandboxFromAnotherStep() {
        String proof = V2SandboxInputFingerprint.artifactReference(
                Map.of("src/test.py", FIXED_TEXT)).value();
        List<V2EffectHistorySource.Entry> history = List.of(
                entry("wrong-sandbox", "step-other",
                        "sandbox.execute", paths("src/test.py"),
                        receipt(ReceiptStatus.SUCCESS, 0, proof)));
        when(effects.inspect(new PlanId(PLAN_ID))).thenReturn(history);
        chainProofBindings("wrong-sandbox", "step-other",
                "c".repeat(64));

        assertThatThrownBy(() -> service.proofChain(
                USER_ID, PROJECT_ID, VERSION, "task-chain", PLAN_ID,
                "candidate-action", "workspace-chain", "step-verify",
                ARTIFACT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox_order");
    }

    @Test
    void formalChainProofRejectsSandboxBoundToAnotherCandidate() {
        String proof = V2SandboxInputFingerprint.artifactReference(
                Map.of("src/test.py", FIXED_TEXT)).value();
        List<V2EffectHistorySource.Entry> history = List.of(
                entry("validation-sandbox", "step-verify",
                        "sandbox.execute", paths("src/test.py"),
                        receipt(ReceiptStatus.SUCCESS, 0, proof)));
        when(effects.inspect(new PlanId(PLAN_ID))).thenReturn(history);
        chainProofBindings("validation-sandbox", "step-verify",
                "d".repeat(64));

        assertThatThrownBy(() -> service.proofChain(
                USER_ID, PROJECT_ID, VERSION, "task-chain", PLAN_ID,
                "candidate-action", "workspace-chain", "step-verify",
                ARTIFACT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chain_sandbox_binding");
    }

    @Test
    void formalChainProofRejectsCrossTaskCandidateAndSandboxBindings() {
        String proof = V2SandboxInputFingerprint.artifactReference(
                Map.of("src/test.py", FIXED_TEXT)).value();
        List<V2EffectHistorySource.Entry> history = List.of(
                entry("validation-sandbox", "step-verify",
                        "sandbox.execute", paths("src/test.py"),
                        receipt(ReceiptStatus.SUCCESS, 0, proof)));
        when(effects.inspect(new PlanId(PLAN_ID))).thenReturn(history);
        Instant now = Instant.EPOCH;
        when(chainWorkflow.findWorkspaceCandidates("task-chain"))
                .thenReturn(List.of(
                        new ChainPersistenceRecords.WorkspaceCandidateRecord(
                                "workspace-candidate", "task-other",
                                "candidate-event", "candidate-action",
                                "workspace-chain", VERSION, ARTIFACT_ID,
                                "c".repeat(64), "e".repeat(64),
                                "f".repeat(64), now)));

        assertThatThrownBy(() -> service.proofChain(
                USER_ID, PROJECT_ID, VERSION, "task-chain", PLAN_ID,
                "candidate-action", "workspace-chain", "step-verify",
                ARTIFACT_ID))
                .hasMessageContaining("chain_candidate_binding");

        when(chainWorkflow.findWorkspaceCandidates("task-chain"))
                .thenReturn(List.of(
                        new ChainPersistenceRecords.WorkspaceCandidateRecord(
                                "workspace-candidate", "task-chain",
                                "candidate-event", "candidate-action",
                                "workspace-chain", VERSION, ARTIFACT_ID,
                                "c".repeat(64), "e".repeat(64),
                                "f".repeat(64), now)));
        when(chainWorkflow.findActionBindings("task-chain"))
                .thenReturn(List.of(
                        new ChainPersistenceRecords.ActionBindingRecord(
                                "validation-sandbox", "task-other",
                                "action-event", "proposal", 1,
                                "1".repeat(64), "key", "instruction",
                                "task-frame", PLAN_ID, "revision",
                                "step-verify", "activation",
                                "workspace-chain", "c".repeat(64),
                                "intent", null, null, null,
                                "2".repeat(64), now)));

        assertThatThrownBy(() -> service.proofChain(
                USER_ID, PROJECT_ID, VERSION, "task-chain", PLAN_ID,
                "candidate-action", "workspace-chain", "step-verify",
                ARTIFACT_ID))
                .hasMessageContaining("chain_sandbox_binding");
    }

    @Test
    void addedFileRequiresStateProofAndReturnsPresentHashOnly() {
        candidate = candidate(CandidateFileChange.Type.ADD);
        when(candidates.getCurrent(USER_ID, ARTIFACT_ID))
                .thenReturn(candidate);
        when(projects.manifest(USER_ID, PROJECT_ID)).thenReturn(
                new ProjectManifestResponse(PROJECT_ID, VERSION, List.of()));
        String stateProof = V2SandboxInputFingerprint
                .stateArtifactReference(
                        Map.of("src/test.py", FIXED_TEXT), Set.of())
                .value();
        List<V2EffectHistorySource.Entry> stateHistory = List.of(
                entry("project.candidate.compose", Map.of(),
                        receipt(ReceiptStatus.SUCCESS, 0, null)),
                entry("sandbox.execute", paths("src/test.py"),
                        receipt(ReceiptStatus.SUCCESS, 0, stateProof)));
        when(effects.inspect(new PlanId(PLAN_ID))).thenReturn(stateHistory);

        AgentCandidateAutoApplicationService.VerificationProof proof =
                service.proof(PLAN_ID, ARTIFACT_ID);

        org.assertj.core.api.Assertions.assertThat(proof.verifiedInputs())
                .containsExactly(
                        new AgentCandidateAutoApplicationService
                                .VerifiedInputState(
                                "src/test.py",
                                AgentCandidateAutoApplicationService
                                        .InputPresence.PRESENT,
                                sha(FIXED_TEXT)));

        String legacy = V2SandboxInputFingerprint.artifactReference(
                Map.of("src/test.py", FIXED_TEXT)).value();
        List<V2EffectHistorySource.Entry> legacyHistory = List.of(
                entry("project.candidate.compose", Map.of(),
                        receipt(ReceiptStatus.SUCCESS, 0, null)),
                entry("sandbox.execute", paths("src/test.py"),
                        receipt(ReceiptStatus.SUCCESS, 0, legacy)));
        when(effects.inspect(new PlanId(PLAN_ID))).thenReturn(legacyHistory);
        assertThatThrownBy(() -> service.proof(PLAN_ID, ARTIFACT_ID))
                .hasMessageContaining("sandbox_input_fingerprint");
    }

    @Test
    void deletedFileRequiresExplicitAbsentStateProof() {
        candidate = candidate(CandidateFileChange.Type.DELETE);
        when(candidates.getCurrent(USER_ID, ARTIFACT_ID))
                .thenReturn(candidate);
        String stateProof = V2SandboxInputFingerprint
                .stateArtifactReference(
                        Map.of(), Set.of("src/test.py"))
                .value();
        List<V2EffectHistorySource.Entry> stateHistory = List.of(
                entry("project.candidate.compose", Map.of(),
                        receipt(ReceiptStatus.SUCCESS, 0, null)),
                entry("sandbox.execute", paths("src/test.py"),
                        receipt(ReceiptStatus.SUCCESS, 0, stateProof)));
        when(effects.inspect(new PlanId(PLAN_ID))).thenReturn(stateHistory);

        AgentCandidateAutoApplicationService.VerificationProof proof =
                service.proof(PLAN_ID, ARTIFACT_ID);

        org.assertj.core.api.Assertions.assertThat(proof.verifiedInputs())
                .containsExactly(
                        new AgentCandidateAutoApplicationService
                                .VerifiedInputState(
                                "src/test.py",
                                AgentCandidateAutoApplicationService
                                        .InputPresence.ABSENT, null));

        String legacy = V2SandboxInputFingerprint.artifactReference(
                Map.of("src/test.py", BASE_TEXT)).value();
        List<V2EffectHistorySource.Entry> legacyHistory = List.of(
                entry("project.candidate.compose", Map.of(),
                        receipt(ReceiptStatus.SUCCESS, 0, null)),
                entry("sandbox.execute", paths("src/test.py"),
                        receipt(ReceiptStatus.SUCCESS, 0, legacy)));
        when(effects.inspect(new PlanId(PLAN_ID))).thenReturn(legacyHistory);
        assertThatThrownBy(() -> service.proof(PLAN_ID, ARTIFACT_ID))
                .hasMessageContaining("sandbox_input_fingerprint");
    }

    private void chainProofBindings(
            String sandboxActionId, String sandboxStepId,
            String sandboxBaseCandidate) {
        Instant now = Instant.EPOCH;
        when(chainWorkflow.findWorkspaceCandidates("task-chain"))
                .thenReturn(List.of(
                        new ChainPersistenceRecords.WorkspaceCandidateRecord(
                                "workspace-candidate", "task-chain",
                                "candidate-event", "candidate-action",
                                "workspace-chain", VERSION, ARTIFACT_ID,
                                "c".repeat(64), "e".repeat(64),
                                "f".repeat(64), now)));
        when(chainWorkflow.findActionBindings("task-chain"))
                .thenReturn(List.of(
                        new ChainPersistenceRecords.ActionBindingRecord(
                                sandboxActionId, "task-chain", "action-event",
                                "proposal", 1, "1".repeat(64), "key",
                                "instruction", "task-frame", PLAN_ID,
                                "revision", sandboxStepId, "activation",
                                "workspace-chain", sandboxBaseCandidate,
                                "intent", null, null, null,
                                "2".repeat(64), now)));
    }

    private CandidateArtifactResponse candidate() {
        return candidate(CandidateFileChange.Type.MODIFY);
    }

    private CandidateArtifactResponse candidate(
            CandidateFileChange.Type type) {
        CandidateArtifactResponse value = mock(CandidateArtifactResponse.class);
        ProjectVersionRef version = new ProjectVersionRef(VERSION);
        ResearchEvidenceRef evidence = new ResearchEvidenceRef(
                version, new ProjectRelativePath("src/test.py"),
                new FileHash(sha(BASE_TEXT)), new SourceRange(1, 1),
                new ParserVersionRef("test-v1"),
                TrustLabel.UNTRUSTED_PROJECT_CONTENT);
        CandidateFileChange change = switch (type) {
            case ADD -> CandidateFileChange.add(
                    version, new ProjectRelativePath("src/test.py"),
                    CandidateTextPayload.fromText(FIXED_TEXT),
                    List.of(evidence));
            case MODIFY -> CandidateFileChange.modify(
                    version, new ProjectRelativePath("src/test.py"),
                    new FileHash(sha(BASE_TEXT)),
                    CandidateTextPayload.fromText(FIXED_TEXT),
                    List.of(evidence));
            case DELETE -> CandidateFileChange.delete(
                    version, new ProjectRelativePath("src/test.py"),
                    new FileHash(sha(BASE_TEXT)), List.of(evidence));
        };
        when(value.projectId()).thenReturn(PROJECT_ID);
        when(value.projectVersion()).thenReturn(version);
        when(value.fingerprint()).thenReturn(
                new CandidateFingerprint("c".repeat(64)));
        when(value.changes()).thenReturn(List.of(change));
        return value;
    }

    private static Map<String, io.paperagent.v2.contracts.ContractValue>
            paths(String... values) {
        return Map.of("paths", new ListValue(java.util.Arrays.stream(values)
                .map(TextValue::new).map(value ->
                        (io.paperagent.v2.contracts.ContractValue) value)
                .toList()));
    }

    private static V2EffectHistorySource.Entry entry(
            String kind,
            Map<String, io.paperagent.v2.contracts.ContractValue> arguments,
            ExecutionReceipt receipt) {
        return entry("call-" + kind + "-" + arguments.size(), "step-1",
                kind, arguments, receipt);
    }

    private static V2EffectHistorySource.Entry entry(
            String toolCallId,
            String stepId,
            String kind,
            Map<String, io.paperagent.v2.contracts.ContractValue> arguments,
            ExecutionReceipt receipt) {
        PersistedEffectIntent persisted = mock(PersistedEffectIntent.class);
        when(persisted.intent()).thenReturn(new EffectIntent(
                new ToolCallId(toolCallId),
                new PlanId(PLAN_ID), new PlanStepId(stepId), kind,
                new ObjectValue(arguments)));
        if (receipt == null) {
            return new V2EffectHistorySource.Entry(persisted, null);
        }
        PersistedEffectResult result = mock(PersistedEffectResult.class);
        when(result.receipt()).thenReturn(receipt);
        return new V2EffectHistorySource.Entry(persisted, result);
    }

    private static ExecutionReceipt receipt(
            ReceiptStatus status, int exitCode, String proof) {
        ExecutionReceipt receipt = mock(ExecutionReceipt.class);
        when(receipt.id()).thenReturn(new ReceiptId("receipt-final"));
        when(receipt.status()).thenReturn(status);
        when(receipt.exitCode()).thenReturn(Optional.of(exitCode));
        when(receipt.startedAt()).thenReturn(Instant.EPOCH);
        when(receipt.endedAt()).thenReturn(Instant.EPOCH.plusSeconds(1));
        when(receipt.standardOutput()).thenReturn(
                io.paperagent.v2.contracts.OutputCapture.inline(
                        "sandbox output", false));
        when(receipt.standardError()).thenReturn(
                io.paperagent.v2.contracts.OutputCapture.empty());
        when(receipt.artifactReferences()).thenReturn(proof == null
                ? List.of() : List.of(new ArtifactRef(proof)));
        return receipt;
    }

    private static String sha(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
