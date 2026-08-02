package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.CandidateArtifactEnvelope;
import com.yanban.api.agent.sandbox.SandboxBrokerClient;
import com.yanban.api.agent.sandbox.SandboxExecutionException;
import com.yanban.api.agent.sandbox.SandboxFailureCode;
import com.yanban.api.artifact.AgentArtifactService;
import com.yanban.api.artifact.ArtifactResponse;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import com.yanban.core.agent.sandbox.CandidateTextPayload;
import com.yanban.core.agent.sandbox.CandidateValidationResult;
import com.yanban.core.agent.sandbox.SandboxFileSnapshot;
import com.yanban.core.agent.sandbox.SandboxWorkspaceRef;
import com.yanban.core.agent.sandbox.SandboxWorkspaceSnapshot;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectManifestIdentity;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.ParserVersionRef;
import com.yanban.core.research.ResearchEvidenceRef;
import com.yanban.core.research.SourceRange;
import com.yanban.core.research.TrustLabel;
import com.yanban.sandbox.contract.SandboxDispatch;
import com.yanban.sandbox.contract.SandboxDispatchResponse;
import com.yanban.sandbox.contract.SandboxErrorCode;
import com.yanban.sandbox.contract.SandboxExecutionStatus;
import com.yanban.sandbox.contract.SandboxExecutionView;
import com.yanban.sandbox.contract.SandboxReceipt;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:candidate_validation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false", "yanban.sandbox.enabled=true",
        "yanban.sandbox.provider=docker-sbx",
        "yanban.sandbox.broker-token=01234567890123456789012345678901",
        "yanban.sandbox.dispatch-delay-ms=3600000",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class CandidateSandboxValidationIntegrationTest {
    private static final long USER = 7L;
    private static final long PROJECT = 11L;
    private static final long SESSION = 13L;
    private static final long ARTIFACT = 17L;
    private static final String VERSION = ProjectManifestIdentity.derive(List.of(
            new ProjectManifestIdentity.Entry(new ProjectRelativePath("a.txt"), new FileHash("1".repeat(64)), 4),
            new ProjectManifestIdentity.Entry(new ProjectRelativePath("pom.xml"), new FileHash("2".repeat(64)), 10))).value();
    private static final String FINGERPRINT = "b".repeat(64);

    @Autowired CandidateSandboxValidationService service;
    @Autowired CandidateSandboxValidationDispatcher dispatcher;
    @Autowired CandidateSandboxValidationRepository repository;
    @Autowired CandidateValidationApplicationGate gate;
    @MockBean CandidateChangeArtifactService candidates;
    @MockBean AgentArtifactService artifacts;
    @MockBean ProjectService projects;
    @MockBean SandboxBrokerClient broker;
    @MockBean CandidateValidationAnalysisProjectionService analysis;

    private CandidateArtifactResponse candidate;
    private final AtomicReference<SandboxDispatch> dispatch = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        candidate = candidate(FINGERPRINT);
        when(candidates.getCurrent(USER, ARTIFACT)).thenReturn(candidate);
        when(artifacts.getArtifact(USER, ARTIFACT)).thenReturn(artifact());
        ProjectManifestResponse manifest = manifest();
        when(projects.manifest(USER, PROJECT)).thenReturn(manifest);
        when(projects.materializeSandbox(any(), any(), any())).thenReturn(materialized(manifest));
        when(broker.dispatch(any())).thenAnswer(call -> {
            SandboxDispatch value = call.getArgument(0);
            dispatch.set(value);
            return new SandboxDispatchResponse("broker-" + value.stepId(), value.idempotencyKey(),
                    value.requestDigest(), value.fence(), SandboxExecutionStatus.ACCEPTED);
        });
    }

    @Test
    void successIsIdempotentDurableAndBoundToCandidateAndProjectVersion() {
        CandidateValidationResponse queued = create("same-key");
        assertThat(service.create(USER, PROJECT, ARTIFACT, "same-key", VERSION,
                request())).isEqualTo(queued);
        assertThat(repository.count()).isOne();

        dispatcher.reconcile(queued.validationId());
        SandboxDispatch sent = dispatch.get();
        assertThat(sent.files()).containsEntry("a.txt", "changed").containsEntry("pom.xml", "<project/>");
        assertThat(sent.argv()).containsExactly("mvn", "-o", "test");
        assertThat(sent.networkEnabled()).isFalse();
        when(broker.status("broker-" + sent.stepId())).thenReturn(view(sent, SandboxExecutionStatus.SUCCEEDED, 0,
                "tests passed", "", null));
        dispatcher.reconcile(queued.validationId());

        CandidateValidationResponse completed = service.list(USER, PROJECT, ARTIFACT).get(0);
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.provider()).isEqualTo("docker-sbx");
        assertThat(completed.stdout()).isEqualTo("tests passed");
        assertThat(completed.receiptDigest()).hasSize(64);
        gate.requireSuccessful(USER, PROJECT, ARTIFACT, completed.validationId(), VERSION, candidate, List.of(0));

        CandidateArtifactResponse changed = candidate("c".repeat(64));
        assertThatThrownBy(() -> gate.requireSuccessful(USER, PROJECT, ARTIFACT, completed.validationId(),
                VERSION, changed, List.of(0))).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> gate.requireSuccessful(USER, PROJECT, ARTIFACT, completed.validationId(),
                "d".repeat(64), candidate, List.of(0))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void javaSourceProfileUsesOnlyTheSelectedTrustedRelativePath() {
        CandidateArtifactResponse javaCandidate = javaCandidate();
        when(candidates.getCurrent(USER, ARTIFACT)).thenReturn(javaCandidate);
        CandidateValidationResponse queued = service.create(USER, PROJECT, ARTIFACT, "java-source-key", VERSION,
                new CreateCandidateValidationRequest(CandidateValidationProfile.JAVA_SOURCE_RUN, List.of(0), true));

        dispatcher.reconcile(queued.validationId());

        assertThat(dispatch.get().argv()).containsExactly("yanban-runner", "java", "Success.java");
    }

    @Test
    void documentOnlyCandidateCompletesLocallyWithoutE2bAndCanApply() {
        String documentVersion = ProjectManifestIdentity.derive(List.of(
                new ProjectManifestIdentity.Entry(
                        new ProjectRelativePath("research-plan.md"),
                        new FileHash("3".repeat(64)), 12))).value();
        CandidateArtifactResponse documentCandidate = candidate(
                FINGERPRINT, documentVersion, "research-plan.md", "3".repeat(64));
        when(candidates.getCurrent(USER, ARTIFACT)).thenReturn(documentCandidate);
        when(projects.manifest(USER, PROJECT)).thenReturn(new ProjectManifestResponse(
                PROJECT, documentVersion, List.of(new ProjectFileEntry(
                        "research-plan.md", 12, Instant.EPOCH, "3".repeat(64)))));

        CandidateValidationResponse result = service.create(
                USER, PROJECT, ARTIFACT, "document-key", documentVersion,
                new CreateCandidateValidationRequest(
                        CandidateValidationProfile.DOCUMENT_INTEGRITY, List.of(0), true));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.profile()).isEqualTo(CandidateValidationProfile.DOCUMENT_INTEGRITY);
        assertThat(result.provider()).isNull();
        assertThat(result.receiptDigest()).isNull();
        assertThat(result.analysisSummary()).contains("No code was executed");
        verify(projects, never()).materializeSandbox(any(), any(), any());
        verifyNoInteractions(broker);
        gate.requireSuccessful(USER, PROJECT, ARTIFACT, result.validationId(),
                documentVersion, documentCandidate, List.of(0));
    }

    @Test
    void binaryProjectAssetsCannotBeSelectedAsCandidateChanges() {
        String binaryVersion = ProjectManifestIdentity.derive(List.of(
                new ProjectManifestIdentity.Entry(
                        new ProjectRelativePath("paper.pdf"),
                        new FileHash("4".repeat(64)), 16))).value();
        CandidateArtifactResponse binaryCandidate = candidate(
                FINGERPRINT, binaryVersion, "paper.pdf", "4".repeat(64));
        when(candidates.getCurrent(USER, ARTIFACT))
                .thenReturn(binaryCandidate);
        when(projects.manifest(USER, PROJECT)).thenReturn(
                new ProjectManifestResponse(PROJECT, binaryVersion,
                        List.of(new ProjectFileEntry(
                                "paper.pdf", 16, Instant.EPOCH,
                                "4".repeat(64)))));

        assertThatThrownBy(() -> service.create(
                USER, PROJECT, ARTIFACT, "binary-key", binaryVersion,
                new CreateCandidateValidationRequest(
                        CandidateValidationProfile.DOCUMENT_INTEGRITY,
                        List.of(0), true)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode()).isEqualTo(
                                    HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(error.getReason()).contains(
                                    "read-only");
                        });
        verify(projects, never()).materializeSandbox(any(), any(), any());
        verifyNoInteractions(broker);
    }

    @Test
    void mavenProfileWithoutRootPomFailsBeforeE2b() {
        String documentVersion = ProjectManifestIdentity.derive(List.of(
                new ProjectManifestIdentity.Entry(
                        new ProjectRelativePath("research-plan.md"),
                        new FileHash("3".repeat(64)), 12))).value();
        CandidateArtifactResponse documentCandidate = candidate(
                FINGERPRINT, documentVersion, "research-plan.md", "3".repeat(64));
        when(candidates.getCurrent(USER, ARTIFACT)).thenReturn(documentCandidate);
        when(projects.manifest(USER, PROJECT)).thenReturn(new ProjectManifestResponse(
                PROJECT, documentVersion, List.of(new ProjectFileEntry(
                        "research-plan.md", 12, Instant.EPOCH, "3".repeat(64)))));

        assertThatThrownBy(() -> service.create(
                USER, PROJECT, ARTIFACT, "wrong-maven-key", documentVersion,
                new CreateCandidateValidationRequest(
                        CandidateValidationProfile.MAVEN_TEST, List.of(0), true)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode()).isEqualTo(
                                    HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(error.getReason()).contains("root pom.xml");
                        });
        verify(projects, never()).materializeSandbox(any(), any(), any());
        verifyNoInteractions(broker);
    }

    @Test
    void failedAndTimedOutReceiptsRemainVisibleButCannotApply() {
        CandidateValidationResponse failed = create("failure-key");
        dispatcher.reconcile(failed.validationId());
        SandboxDispatch first = dispatch.get();
        when(broker.status("broker-" + first.stepId())).thenReturn(view(first, SandboxExecutionStatus.FAILED, 1,
                "", "compile failed", SandboxErrorCode.PROVIDER_REJECTED));
        dispatcher.reconcile(failed.validationId());
        CandidateValidationResponse failedResult = service.list(USER, PROJECT, ARTIFACT).get(0);
        assertThat(failedResult.status()).isEqualTo("FAILED");
        assertThat(failedResult.stderr()).isEqualTo("compile failed");
        assertThatThrownBy(() -> gate.requireSuccessful(USER, PROJECT, ARTIFACT, failed.validationId(),
                VERSION, candidate, List.of(0))).isInstanceOf(ResponseStatusException.class);

        CandidateValidationResponse timeout = create("timeout-key");
        dispatcher.reconcile(timeout.validationId());
        SandboxDispatch second = dispatch.get();
        when(broker.status("broker-" + second.stepId())).thenReturn(view(second, SandboxExecutionStatus.TIMED_OUT,
                null, "partial", "timeout", SandboxErrorCode.TIMED_OUT));
        dispatcher.reconcile(timeout.validationId());
        CandidateValidationResponse timeoutResult = service.list(USER, PROJECT, ARTIFACT).get(0);
        assertThat(timeoutResult.status()).isEqualTo("TIMED_OUT");
        assertThat(timeoutResult.timedOut()).isTrue();
    }

    @Test
    void cancellationIntentSurvivesRunningStatusUntilBrokerCancels() {
        CandidateValidationResponse cancelled = create("cancel-key");
        dispatcher.reconcile(cancelled.validationId());
        SandboxDispatch sent = dispatch.get();
        service.cancel(USER, PROJECT, cancelled.validationId());
        when(broker.status("broker-" + sent.stepId()))
                .thenReturn(nonTerminalView(sent, SandboxExecutionStatus.RUNNING))
                .thenReturn(view(sent, SandboxExecutionStatus.CANCELLED,
                        null, "", "cancelled", SandboxErrorCode.CANCELLED));

        dispatcher.reconcile(cancelled.validationId());
        assertThat(service.list(USER, PROJECT, ARTIFACT).get(0).status()).isEqualTo("CANCEL_REQUESTED");
        dispatcher.reconcile(cancelled.validationId());
        assertThat(service.list(USER, PROJECT, ARTIFACT).get(0).status()).isEqualTo("CANCELLED");
        verify(broker, times(2)).cancel("broker-" + sent.stepId(), 1L);
    }

    @Test
    void rejectionKeepsCancellationIntentAndNeverTouchesProject() {
        CandidateValidationResponse rejected = create("reject-key");
        dispatcher.reconcile(rejected.validationId());
        SandboxDispatch sent = dispatch.get();
        clearInvocations(projects);
        CandidateValidationResponse rejection = service.reject(USER, PROJECT, rejected.validationId());
        assertThat(rejection.decisionStatus()).isEqualTo("REJECTED");
        assertThat(rejection.appliedRevisionId()).isNull();
        when(broker.status("broker-" + sent.stepId()))
                .thenReturn(nonTerminalView(sent, SandboxExecutionStatus.RUNNING))
                .thenReturn(view(sent, SandboxExecutionStatus.CANCELLED,
                        null, "", "cancelled", SandboxErrorCode.CANCELLED));

        dispatcher.reconcile(rejected.validationId());
        CandidateValidationResponse cancelling = service.list(USER, PROJECT, ARTIFACT).get(0);
        assertThat(cancelling.status()).isEqualTo("CANCEL_REQUESTED");
        assertThat(cancelling.decisionStatus()).isEqualTo("REJECTED");
        dispatcher.reconcile(rejected.validationId());
        CandidateValidationResponse completed = service.list(USER, PROJECT, ARTIFACT).get(0);
        assertThat(completed.status()).isEqualTo("CANCELLED");
        assertThat(completed.decisionStatus()).isEqualTo("REJECTED");
        verify(broker, times(2)).cancel("broker-" + sent.stepId(), 1L);
        verifyNoInteractions(projects);
        assertThatThrownBy(() -> gate.requireSuccessful(USER, PROJECT, ARTIFACT, rejected.validationId(),
                VERSION, candidate, List.of(0))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void sandboxUnavailableRetries() {
        CandidateValidationResponse unavailable = create("unavailable-key");
        org.mockito.Mockito.doThrow(new SandboxExecutionException(
                SandboxFailureCode.SANDBOX_UNAVAILABLE, "offline")).when(broker).dispatch(any());
        dispatcher.reconcile(unavailable.validationId());
        CandidateValidationResponse retry = service.list(USER, PROJECT, ARTIFACT).get(0);
        assertThat(retry.status()).isEqualTo("RETRY");
        assertThat(retry.errorCode()).isEqualTo("SANDBOX_UNAVAILABLE");
    }

    @Test
    void deterministicBrokerRejectionsBecomeTerminalFailures() {
        CandidateValidationResponse rejected = create("provider-rejected-key");
        org.mockito.Mockito.doThrow(new SandboxExecutionException(
                SandboxFailureCode.PROVIDER_REJECTED, "policy rejected")).when(broker).dispatch(any());
        dispatcher.reconcile(rejected.validationId());
        CandidateValidationResponse failed = service.list(USER, PROJECT, ARTIFACT).get(0);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("PROVIDER_REJECTED");
        assertThatThrownBy(() -> gate.requireSuccessful(USER, PROJECT, ARTIFACT, rejected.validationId(),
                VERSION, candidate, List.of(0))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void receiptConflictBecomesTerminalFailure() {
        CandidateValidationResponse conflicted = create("receipt-conflict-key");
        dispatcher.reconcile(conflicted.validationId());
        SandboxDispatch sent = dispatch.get();
        when(broker.status("broker-" + sent.stepId())).thenReturn(new SandboxExecutionView(
                "broker-" + sent.stepId(), sent.idempotencyKey(), "f".repeat(64), sent.fence(),
                SandboxExecutionStatus.RUNNING, null, null));
        dispatcher.reconcile(conflicted.validationId());
        CandidateValidationResponse failed = service.list(USER, PROJECT, ARTIFACT).get(0);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("RECEIPT_CONFLICT");
    }

    @Test
    void successfulTruncatedReceiptRemainsVisibleAndCanApply() {
        CandidateValidationResponse queued = create("truncated-key");
        dispatcher.reconcile(queued.validationId());
        SandboxDispatch sent = dispatch.get();
        when(broker.status("broker-" + sent.stepId())).thenReturn(view(sent, SandboxExecutionStatus.SUCCEEDED,
                0, "bounded output", "", null, true));
        dispatcher.reconcile(queued.validationId());

        CandidateValidationResponse completed = service.list(USER, PROJECT, ARTIFACT).get(0);
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.stdout()).isEqualTo("bounded output");
        assertThat(completed.outputTruncated()).isTrue();
        gate.requireSuccessful(USER, PROJECT, ARTIFACT, completed.validationId(), VERSION, candidate, List.of(0));
    }

    private CandidateValidationResponse create(String key) {
        return service.create(USER, PROJECT, ARTIFACT, key, VERSION, request());
    }
    private CreateCandidateValidationRequest request() {
        return new CreateCandidateValidationRequest(CandidateValidationProfile.MAVEN_TEST, List.of(0), true);
    }
    private CandidateArtifactResponse candidate(String fingerprint) {
        return candidate(fingerprint, VERSION, "a.txt", "1".repeat(64));
    }
    private CandidateArtifactResponse candidate(
            String fingerprint, String version, String path, String baseHash) {
        CandidateArtifactResponse value = org.mockito.Mockito.mock(CandidateArtifactResponse.class);
        CandidateValidationResult validation = org.mockito.Mockito.mock(CandidateValidationResult.class);
        ResearchEvidenceRef evidence = new ResearchEvidenceRef(new ProjectVersionRef(version),
                new ProjectRelativePath(path), new FileHash(baseHash), new SourceRange(1, 1),
                new ParserVersionRef("test-v1"), TrustLabel.UNTRUSTED_PROJECT_CONTENT);
        CandidateFileChange change = CandidateFileChange.modify(new ProjectVersionRef(version),
                new ProjectRelativePath(path), new FileHash(baseHash),
                CandidateTextPayload.fromText("changed"), List.of(evidence));
        when(validation.valid()).thenReturn(true);
        when(value.projectId()).thenReturn(PROJECT); when(value.projectVersion()).thenReturn(new ProjectVersionRef(version));
        when(value.fingerprint()).thenReturn(new CandidateFingerprint(fingerprint));
        when(value.governanceStatus()).thenReturn(CandidateChangeSet.GovernanceStatus.VALIDATED);
        when(value.applicationStatus()).thenReturn(CandidateChangeSet.ApplicationStatus.NOT_APPLIED);
        when(value.validation()).thenReturn(validation); when(value.changes()).thenReturn(List.of(change));
        return value;
    }
    private CandidateArtifactResponse javaCandidate() {
        CandidateArtifactResponse value = org.mockito.Mockito.mock(CandidateArtifactResponse.class);
        CandidateValidationResult validation = org.mockito.Mockito.mock(CandidateValidationResult.class);
        ResearchEvidenceRef evidence = new ResearchEvidenceRef(new ProjectVersionRef(VERSION),
                new ProjectRelativePath("a.txt"), new FileHash("1".repeat(64)), new SourceRange(1, 1),
                new ParserVersionRef("test-v1"), TrustLabel.UNTRUSTED_PROJECT_CONTENT);
        CandidateFileChange change = CandidateFileChange.add(new ProjectVersionRef(VERSION),
                new ProjectRelativePath("Success.java"), CandidateTextPayload.fromText(
                        "public class Success { public static void main(String[] args) { } }"), List.of(evidence));
        when(validation.valid()).thenReturn(true);
        when(value.projectId()).thenReturn(PROJECT); when(value.projectVersion()).thenReturn(new ProjectVersionRef(VERSION));
        when(value.fingerprint()).thenReturn(new CandidateFingerprint("e".repeat(64)));
        when(value.governanceStatus()).thenReturn(CandidateChangeSet.GovernanceStatus.VALIDATED);
        when(value.applicationStatus()).thenReturn(CandidateChangeSet.ApplicationStatus.NOT_APPLIED);
        when(value.validation()).thenReturn(validation); when(value.changes()).thenReturn(List.of(change));
        return value;
    }
    private ArtifactResponse artifact() {
        return new ArtifactResponse(ARTIFACT, USER, SESSION, "candidate.json", "JSON", "{}",
                AgentArtifactService.CANDIDATE_CHANGESET_SOURCE_TYPE, List.of(), "ACTIVE", null, null, null,
                Instant.now(), Instant.now());
    }
    private ProjectManifestResponse manifest() {
        return new ProjectManifestResponse(PROJECT, VERSION, List.of(
                new ProjectFileEntry("a.txt", 4, Instant.EPOCH, "1".repeat(64)),
                new ProjectFileEntry("pom.xml", 10, Instant.EPOCH, "2".repeat(64))));
    }
    private ProjectService.SandboxWorkspaceMaterialization materialized(ProjectManifestResponse manifest) {
        return new ProjectService.SandboxWorkspaceMaterialization(new SandboxWorkspaceSnapshot(
                new SandboxWorkspaceRef(PROJECT, new ProjectVersionRef(VERSION)), manifest.files().stream()
                .map(file -> new SandboxFileSnapshot(new ProjectRelativePath(file.path()),
                        new FileHash(file.sha256()), file.sizeBytes())).toList()),
                Map.of("a.txt", "base", "pom.xml", "<project/>"));
    }
    private SandboxExecutionView view(SandboxDispatch request, SandboxExecutionStatus status, Integer exitCode,
                                      String stdout, String stderr, SandboxErrorCode error) {
        return view(request, status, exitCode, stdout, stderr, error, false);
    }
    private SandboxExecutionView view(SandboxDispatch request, SandboxExecutionStatus status, Integer exitCode,
                                      String stdout, String stderr, SandboxErrorCode error, boolean outputTruncated) {
        String executionId = "broker-" + request.stepId();
        SandboxReceipt receipt = new SandboxReceipt(executionId, request.idempotencyKey(), request.requestDigest(),
                request.userId(), request.projectId(), request.sessionId(), request.planId(), request.stepId(),
                request.fence(), request.projectVersion(), request.policyDigest(), "docker-sbx", status, exitCode,
                stdout, stderr, outputTruncated, Map.of(), Instant.now().minusSeconds(1), Instant.now(), error);
        return new SandboxExecutionView(executionId, request.idempotencyKey(), request.requestDigest(),
                request.fence(), status, receipt, error);
    }
    private SandboxExecutionView nonTerminalView(SandboxDispatch request, SandboxExecutionStatus status) {
        return new SandboxExecutionView("broker-" + request.stepId(), request.idempotencyKey(),
                request.requestDigest(), request.fence(), status, null, null);
    }
}
