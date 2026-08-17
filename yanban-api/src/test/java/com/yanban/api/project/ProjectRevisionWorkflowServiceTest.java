package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.user.SysUser;
import com.yanban.api.user.SysUserRepository;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import com.yanban.core.agent.sandbox.CandidateTextPayload;
import com.yanban.core.agent.sandbox.CandidateValidationResult;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ParserVersionRef;
import com.yanban.core.research.ProjectManifestIdentity;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.ResearchEvidenceRef;
import com.yanban.core.research.SourceRange;
import com.yanban.core.research.TrustLabel;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
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
        "spring.datasource.url=jdbc:h2:mem:project_revision_workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class ProjectRevisionWorkflowServiceTest {
    @Autowired ProjectRevisionWorkflowService service;
    @Autowired ProjectRepository projects;
    @Autowired ProjectRevisionRepository revisions;
    @Autowired ProjectRevisionOperationRepository operations;
    @Autowired SysUserRepository users;
    @MockBean CandidateChangeArtifactService candidates;
    @MockBean CandidateValidationApplicationGate validationGate;
    @MockBean ProjectObjectStorage storage;

    private final Map<String, Map<String, byte[]>> objects = new HashMap<>();
    private final Map<String, List<ProjectObjectEntry>> manifests = new HashMap<>();
    private SysUser owner;
    private Project project;
    private String baseVersion;
    private CandidateArtifactResponse candidateResponse;

    @BeforeEach
    void setUp() {
        operations.deleteAll();
        revisions.deleteAll();
        projects.deleteAll();
        users.deleteAll();
        objects.clear(); manifests.clear();
        owner = users.saveAndFlush(new SysUser("owner-" + UUID.randomUUID(), "hash"));
        configureStorage();
        String prefix = "projects/" + owner.getId() + "/" + UUID.randomUUID();
        putSnapshot(prefix, Map.of(
                "a.txt", bytes("A"),
                "delete.txt", bytes("DELETE"),
                "keep.txt", bytes("KEEP"),
                "paper.pdf", bytes("%PDF-1.7\n%%EOF")));
        project = projects.saveAndFlush(Project.minioUpload(owner.getId(), "Study", prefix, "[\"**\"]", "[]"));
        baseVersion = version(manifests.get(prefix));
        candidateResponse = candidate(project.getId(), baseVersion);
        when(candidates.getCurrent(owner.getId(), 55L)).thenReturn(candidateResponse);
    }

    @Test
    void partialSelectionCreatesNewImmutableRevisionAndIdempotentlyReplays() throws Exception {
        ProjectRevisionOperationResponse applied = service.applyCandidate(owner.getId(), project.getId(), 55L,
                "apply-key-0001", baseVersion, new ApplyCandidateRequest(List.of(0, 1), "validation-1"));

        assertThat(applied.outcome()).isEqualTo(ProjectRevisionOperation.Outcome.SUCCEEDED);
        assertThat(applied.acceptedChangeIndexes()).containsExactly(0, 1);
        assertThat(applied.rejectedChangeIndexes()).containsExactly(2);
        Project current = projects.findById(project.getId()).orElseThrow();
        assertThat(current.getRootPath()).isNotEqualTo(project.getRootPath());
        assertThat(objects.get(current.getRootPath())).containsEntry("a.txt", bytes("A2"))
                .containsEntry("new.txt", bytes("NEW"))
                .containsEntry("delete.txt", bytes("DELETE"))
                .containsEntry("paper.pdf", bytes("%PDF-1.7\n%%EOF"));
        assertThat(objects.get(project.getRootPath())).containsEntry("a.txt", bytes("A"));
        assertThat(revisions.findByProjectIdAndUserIdOrderByCreatedAtDescIdDesc(project.getId(), owner.getId())).hasSize(2);

        ProjectRevisionOperationResponse replay = service.applyCandidate(owner.getId(), project.getId(), 55L,
                "apply-key-0001", "\"" + baseVersion + "\"", new ApplyCandidateRequest(List.of(0, 1), "validation-1"));
        assertThat(replay.operationId()).isEqualTo(applied.operationId());
        assertThat(revisions.findByProjectIdAndUserIdOrderByCreatedAtDescIdDesc(project.getId(), owner.getId())).hasSize(2);
        verify(validationGate, times(1)).requireSuccessful(owner.getId(), project.getId(), 55L,
                "validation-1", baseVersion, candidateResponse, List.of(0, 1));
        verify(validationGate, times(1)).markApplied("validation-1", applied.operationId(), applied.resultRevisionId());

        assertThatThrownBy(() -> service.applyCandidate(owner.getId(), project.getId(), 55L,
                "apply-key-0001", "f".repeat(64), new ApplyCandidateRequest(List.of(0, 1))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.applyCandidate(owner.getId(), project.getId(), 55L,
                "apply-key-0001", baseVersion, new ApplyCandidateRequest(List.of(0))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void exactSandboxProofAutomaticallyAppliesAllChangesWithoutManualGate() {
        ProjectRevisionOperationResponse applied = service.applyAutomatically(
                owner.getId(), project.getId(), 55L,
                "agent-auto-apply-test", baseVersion,
                "c".repeat(64), "sandbox-receipt-final");

        assertThat(applied.outcome())
                .isEqualTo(ProjectRevisionOperation.Outcome.SUCCEEDED);
        assertThat(applied.acceptedChangeIndexes())
                .containsExactly(0, 1, 2);
        assertThat(applied.rejectedChangeIndexes()).isEmpty();
        Project current = projects.findById(project.getId()).orElseThrow();
        assertThat(objects.get(current.getRootPath()))
                .containsEntry("a.txt", bytes("A2"))
                .containsEntry("new.txt", bytes("NEW"))
                .doesNotContainKey("delete.txt");
        verify(validationGate, never()).requireSuccessful(
                anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), any(), any());
        verify(validationGate, never()).markApplied(
                anyString(), anyLong(), anyLong());

        ProjectRevisionOperationResponse replay = service.applyAutomatically(
                owner.getId(), project.getId(), 55L,
                "agent-auto-apply-test", baseVersion,
                "c".repeat(64), "sandbox-receipt-final");
        assertThat(replay.operationId()).isEqualTo(applied.operationId());
        assertThat(revisions
                .findByProjectIdAndUserIdOrderByCreatedAtDescIdDesc(
                        project.getId(), owner.getId()))
                .hasSize(2);
    }

    @Test
    void exactReactWorkspaceCutPublishesImmutableRevisionAndReplays() {
        List<AutomaticProjectFileChange> changes = List.of(
                new AutomaticProjectFileChange(
                        "MODIFY", "a.txt", hash(bytes("A")),
                        hash(bytes("A-react")), "A-react"),
                new AutomaticProjectFileChange(
                        "ADD", "react.txt", null,
                        hash(bytes("REACT")), "REACT"));

        ProjectRevisionOperationResponse applied = service.applyWorkspaceAutomatically(
                owner.getId(), project.getId(), "react-engine-publish-test",
                baseVersion, "receipt.react.final", changes);

        assertThat(applied.outcome()).isEqualTo(ProjectRevisionOperation.Outcome.SUCCEEDED);
        assertThat(applied.acceptedChangeIndexes()).containsExactly(0, 1);
        Project current = projects.findById(project.getId()).orElseThrow();
        assertThat(objects.get(current.getRootPath()))
                .containsEntry("a.txt", bytes("A-react"))
                .containsEntry("react.txt", bytes("REACT"))
                .containsEntry("keep.txt", bytes("KEEP"));
        assertThat(objects.get(project.getRootPath())).containsEntry("a.txt", bytes("A"));

        ProjectRevisionOperationResponse replay = service.applyWorkspaceAutomatically(
                owner.getId(), project.getId(), "react-engine-publish-test",
                baseVersion, "receipt.react.final", changes);
        assertThat(replay.operationId()).isEqualTo(applied.operationId());
        assertThat(revisions.findByProjectIdAndUserIdOrderByCreatedAtDescIdDesc(
                project.getId(), owner.getId())).hasSize(2);
    }

    @Test
    void applyRejectsHistoricalValidatedBinaryCandidateBeforeGateOrStorage() {
        CandidateArtifactResponse response = candidate(
                project.getId(), baseVersion);
        CandidateFileChange binary = CandidateFileChange.add(
                new ProjectVersionRef(baseVersion),
                new ProjectRelativePath("paper.pdf"),
                CandidateTextPayload.fromText("%PDF-fake"),
                response.changes().get(0).evidenceRefs());
        when(response.changes()).thenReturn(List.of(binary));
        when(candidates.getCurrent(owner.getId(), 55L))
                .thenReturn(response);

        assertThatThrownBy(() -> service.applyCandidate(
                owner.getId(), project.getId(), 55L,
                "binary-apply-key", baseVersion,
                new ApplyCandidateRequest(List.of(0), "old-validation")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode()).isEqualTo(
                                    HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(error.getReason()).contains(
                                    "read-only");
                        });
        verify(validationGate, never()).requireSuccessful(
                anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void existingManagedProjectBootstrapsOneInitialRevisionOnFirstHistoryRead() throws Exception {
        assertThat(projects.findById(project.getId()).orElseThrow().getCurrentRevisionId()).isNull();

        List<ProjectRevisionResponse> first = service.listRevisions(owner.getId(), project.getId());
        List<ProjectRevisionResponse> second = service.listRevisions(owner.getId(), project.getId());

        assertThat(first).singleElement().satisfies(revision -> {
            assertThat(revision.current()).isTrue();
            assertThat(revision.sourceType()).isEqualTo(ProjectRevision.SourceType.UPLOAD);
            assertThat(revision.projectVersion()).isEqualTo(baseVersion);
        });
        assertThat(second).usingRecursiveComparison().isEqualTo(first);
        assertThat(revisions.findByProjectIdAndUserIdOrderByCreatedAtDescIdDesc(project.getId(), owner.getId()))
                .hasSize(1);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        service.exportRevision(owner.getId(), project.getId(), first.get(0).id(), archive);
        try (ZipInputStream zip = new ZipInputStream(
                new java.io.ByteArrayInputStream(archive.toByteArray()), StandardCharsets.UTF_8)) {
            List<String> names = new ArrayList<>();
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
            assertThat(names).containsExactly(
                    "a.txt", "delete.txt", "keep.txt", "paper.pdf");
        }
    }

    @Test
    void staleCasAndObjectFailureNeverPublishCurrentPointer() {
        String originalPrefix = project.getRootPath();
        assertThatThrownBy(() -> service.applyCandidate(owner.getId(), project.getId(), 55L,
                "apply-key-0002", "f".repeat(64), new ApplyCandidateRequest(List.of(0))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(projects.findById(project.getId()).orElseThrow().getRootPath()).isEqualTo(originalPrefix);

        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "storage failed"))
                .when(storage).storeBytes(anyString(), anyString(), any(byte[].class), anyString());
        assertThatThrownBy(() -> service.applyCandidate(owner.getId(), project.getId(), 55L,
                "apply-key-0003", baseVersion, new ApplyCandidateRequest(List.of(0))))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(projects.findById(project.getId()).orElseThrow().getRootPath()).isEqualTo(originalPrefix);
        assertThat(operations.findByUserIdAndProjectIdAndIdempotencyKey(owner.getId(), project.getId(),
                "apply-key-0003").orElseThrow().getOutcome()).isEqualTo(ProjectRevisionOperation.Outcome.FAILED);
    }

    @Test
    void applyRechecksChangeTypeAndDeleteBaseHashEvenWhenCandidateClaimsValidated() {
        String originalPrefix = project.getRootPath();
        ProjectVersionRef version = new ProjectVersionRef(baseVersion);
        CandidateArtifactResponse response = candidate(project.getId(), baseVersion);
        when(candidates.getCurrent(owner.getId(), 55L)).thenReturn(response);
        List<ResearchEvidenceRef> evidence = response.changes().get(0).evidenceRefs();

        CandidateFileChange conflictingAdd = CandidateFileChange.add(version, new ProjectRelativePath("a.txt"),
                CandidateTextPayload.fromText("overwrite"), evidence);
        when(response.changes()).thenReturn(List.of(conflictingAdd));
        assertThatThrownBy(() -> service.applyCandidate(owner.getId(), project.getId(), 55L,
                "type-conflict-1", baseVersion, new ApplyCandidateRequest(List.of(0))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        CandidateFileChange staleDelete = CandidateFileChange.delete(version, new ProjectRelativePath("delete.txt"),
                new FileHash("f".repeat(64)), evidence);
        when(response.changes()).thenReturn(List.of(staleDelete));
        assertThatThrownBy(() -> service.applyCandidate(owner.getId(), project.getId(), 55L,
                "delete-hash-1", baseVersion, new ApplyCandidateRequest(List.of(0))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(projects.findById(project.getId()).orElseThrow().getRootPath()).isEqualTo(originalPrefix);
    }

    @Test
    void historyRollbackAndZipRemainOwnerScopedAndPortable() throws Exception {
        ProjectRevisionOperationResponse applied = service.applyCandidate(owner.getId(), project.getId(), 55L,
                "apply-key-0004", baseVersion, new ApplyCandidateRequest(List.of(0, 1, 2)));
        List<ProjectRevisionResponse> history = service.listRevisions(owner.getId(), project.getId());
        assertThat(history).hasSize(2).anyMatch(ProjectRevisionResponse::current);
        ProjectRevisionResponse initial = history.stream().filter(item -> !item.current()).findFirst().orElseThrow();

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        service.exportRevision(owner.getId(), project.getId(), applied.resultRevisionId(), archive);
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive.toByteArray()), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
        }
        assertThat(names).containsExactly(
                        "a.txt", "keep.txt", "new.txt", "paper.pdf")
                .allMatch(name -> !name.contains("..")
                        && !name.contains(":"));

        ProjectRevisionOperationResponse rolledBack = service.rollback(owner.getId(), project.getId(), initial.id(),
                "rollback-key-1", applied.resultVersion());
        assertThat(rolledBack.resultRevisionId()).isEqualTo(initial.id());
        assertThat(projects.findById(project.getId()).orElseThrow().getRootPath())
                .isEqualTo(revisions.findById(initial.id()).orElseThrow().getObjectPrefix());

        ProjectRevisionOperationResponse rollbackReplay = service.rollback(owner.getId(), project.getId(), initial.id(),
                "rollback-key-1", "\"" + applied.resultVersion() + "\"");
        assertThat(rollbackReplay.operationId()).isEqualTo(rolledBack.operationId());
        assertThatThrownBy(() -> service.rollback(owner.getId(), project.getId(), initial.id(),
                "rollback-key-1", "f".repeat(64)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        SysUser stranger = users.saveAndFlush(new SysUser("stranger-" + UUID.randomUUID(), "hash"));
        assertThatThrownBy(() -> service.exportRevision(stranger.getId(), project.getId(), initial.id(),
                new ByteArrayOutputStream())).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void legacyHostProjectIsReadableElsewhereButCannotApplyRollbackOrExport() {
        Project legacy = projects.saveAndFlush(new Project(owner.getId(), "Legacy", ".", "C:\\research\\paper",
                "[\"**\"]", "[]"));
        CandidateArtifactResponse response = candidate(legacy.getId(), baseVersion);
        when(candidates.getCurrent(owner.getId(), 55L)).thenReturn(response);
        assertThatThrownBy(() -> service.applyCandidate(owner.getId(), legacy.getId(), 55L,
                "legacy-key-1", baseVersion, new ApplyCandidateRequest(List.of(0))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.listRevisions(owner.getId(), legacy.getId()))
                .isInstanceOf(ResponseStatusException.class);
    }

    private CandidateArtifactResponse candidate(long projectId, String version) {
        ProjectVersionRef projectVersion = new ProjectVersionRef(version);
        ResearchEvidenceRef evidence = new ResearchEvidenceRef(projectVersion, new ProjectRelativePath("a.txt"),
                new FileHash(hash(bytes("A"))), new SourceRange(1, 1), new ParserVersionRef("test-v1"),
                TrustLabel.UNTRUSTED_PROJECT_CONTENT);
        List<CandidateFileChange> changes = List.of(
                CandidateFileChange.modify(projectVersion, new ProjectRelativePath("a.txt"),
                        new FileHash(hash(bytes("A"))), CandidateTextPayload.fromText("A2"), List.of(evidence)),
                CandidateFileChange.add(projectVersion, new ProjectRelativePath("new.txt"),
                        CandidateTextPayload.fromText("NEW"), List.of(evidence)),
                CandidateFileChange.delete(projectVersion, new ProjectRelativePath("delete.txt"),
                        new FileHash(hash(bytes("DELETE"))), List.of(evidence)));
        CandidateArtifactResponse response = org.mockito.Mockito.mock(CandidateArtifactResponse.class);
        CandidateValidationResult validation = org.mockito.Mockito.mock(CandidateValidationResult.class);
        when(validation.valid()).thenReturn(true);
        when(response.projectId()).thenReturn(projectId);
        when(response.projectVersion()).thenReturn(projectVersion);
        when(response.fingerprint()).thenReturn(new CandidateFingerprint("c".repeat(64)));
        when(response.governanceStatus()).thenReturn(CandidateChangeSet.GovernanceStatus.VALIDATED);
        when(response.applicationStatus()).thenReturn(CandidateChangeSet.ApplicationStatus.NOT_APPLIED);
        when(response.validation()).thenReturn(validation);
        when(response.changes()).thenReturn(changes);
        return response;
    }

    private void configureStorage() {
        when(storage.createPrefix(anyLong())).thenAnswer(call -> "projects/" + call.getArgument(0) + "/" + UUID.randomUUID());
        when(storage.readManifest(anyString())).thenAnswer(call -> List.copyOf(manifests.getOrDefault(call.getArgument(0), List.of())));
        when(storage.readFile(anyString(), anyString(), anyLong())).thenAnswer(call -> {
            byte[] content = objects.getOrDefault(call.getArgument(0), Map.of()).get(call.getArgument(1));
            if (content == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "missing object");
            return content.clone();
        });
        when(storage.storeBytes(anyString(), anyString(), any(byte[].class), anyString())).thenAnswer(call -> {
            String prefix = call.getArgument(0), path = call.getArgument(1); byte[] content = call.getArgument(2);
            objects.computeIfAbsent(prefix, ignored -> new LinkedHashMap<>()).put(path, content.clone());
            return entry(path, content);
        });
        org.mockito.Mockito.doAnswer(call -> {
            manifests.put(call.getArgument(0), List.copyOf(call.getArgument(1))); return null;
        }).when(storage).writeManifest(anyString(), any());
    }

    private void putSnapshot(String prefix, Map<String, byte[]> files) {
        Map<String, byte[]> ordered = new LinkedHashMap<>();
        files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        objects.put(prefix, ordered);
        manifests.put(prefix, ordered.entrySet().stream().map(entry -> entry(entry.getKey(), entry.getValue())).toList());
    }

    private ProjectObjectEntry entry(String path, byte[] content) {
        return new ProjectObjectEntry(path, content.length, Instant.parse("2026-01-01T00:00:00Z"), hash(content));
    }
    private String version(List<ProjectObjectEntry> entries) {
        return ProjectManifestIdentity.derive(entries.stream().map(entry -> new ProjectManifestIdentity.Entry(
                new ProjectRelativePath(entry.path()), new FileHash(entry.sha256()), entry.sizeBytes())).toList()).value();
    }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private String hash(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
