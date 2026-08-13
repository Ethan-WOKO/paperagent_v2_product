package com.yanban.api.agent.v2.chain.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.CandidateIntent;
import com.yanban.api.agent.v2.chain.persistence.ProductChainCandidateMaterializationFailureRepositoryAdapter;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectFileResponse;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.api.project.ProjectStorageProperties;
import com.yanban.core.agent.sandbox.CandidateFileChange;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import com.yanban.core.agent.sandbox.CandidateChangeSet;
import com.yanban.core.agent.sandbox.CandidateReviewDiff;
import com.yanban.core.agent.sandbox.CandidateTextPayload;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProductChainWorkspaceCandidateAuthorityChangeTest {
    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
    private static final String VERSION = "d".repeat(64);
    private static final String FENCE = "b".repeat(64);
    private static final String AUTHORITY_HASH = "a".repeat(64);
    private static final String OUTPUT_FINGERPRINT = "c".repeat(64);

    @ParameterizedTest
    @MethodSource("singleChanges")
    void materializesEachCanonicalChangeType(
            CandidateIntent.Type type,
            String target,
            String body,
            CandidateIntent.Type expectedType,
            String expectedPath,
            String expectedText) {
        Fixture fixture = new Fixture(List.of(
                file("README.md", "evidence"),
                file("src/Main.java", "old")));

        ChainEffectRuntime.MaterializedCandidate result = fixture.execute(
                ChainIdentity.NONE, target, body, null);

        assertEquals(ChainEffectRuntime.CandidateDisposition.COMMITTED,
                result.disposition());
        CandidateIntent.FileIntent change = fixture.intent().changes().get(0);
        assertEquals(expectedType, change.type());
        assertEquals(expectedPath, change.relativePath().value());
        assertEquals(expectedText, change.replacementText());
        if (type == CandidateIntent.Type.ADD) {
            assertNull(change.baseFileHash());
        } else {
            assertEquals(hash("old"), change.baseFileHash().sha256());
        }
    }

    private static Stream<Arguments> singleChanges() {
        return Stream.of(
                Arguments.of(CandidateIntent.Type.ADD, "New.java",
                        body("ADD", "New.java", ChainIdentity.NONE, ""),
                        CandidateIntent.Type.ADD, "New.java", ""),
                Arguments.of(CandidateIntent.Type.MODIFY, "Main.java",
                        body("MODIFY", "Main.java", hash("old"), "new"),
                        CandidateIntent.Type.MODIFY, "src/Main.java", "new"),
                Arguments.of(CandidateIntent.Type.DELETE, "Main.java",
                        body("DELETE", "Main.java", hash("old"), null),
                        CandidateIntent.Type.DELETE, "src/Main.java", null));
    }

    @ParameterizedTest
    @MethodSource("invalidChanges")
    void rejectsInvalidExistenceBaselineBodyAndPath(
            String target, String body, String expectedCode) {
        Fixture fixture = new Fixture(List.of(
                file("README.md", "evidence"),
                file("src/Main.java", "old")));

        ChainEffectRuntime.MaterializedCandidate result = fixture.execute(
                ChainIdentity.NONE, target, body, null);

        assertEquals(ChainEffectRuntime.CandidateDisposition.FAILED,
                result.disposition());
        assertEquals(expectedCode, result.errorCode());
        assertNull(fixture.intent());
    }

    private static Stream<Arguments> invalidChanges() {
        return Stream.of(
                Arguments.of("src/Main.java",
                        body("ADD", "src/Main.java", ChainIdentity.NONE, "x"),
                        "CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS"),
                Arguments.of("missing.java",
                        body("MODIFY", "missing.java", "a".repeat(64), "x"),
                        "CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS"),
                Arguments.of("missing.java",
                        body("DELETE", "missing.java", "a".repeat(64), null),
                        "CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS"),
                Arguments.of("src/Main.java",
                        body("MODIFY", "src/Main.java", "a".repeat(64), "x"),
                        "CANDIDATE_REPLACEMENT_BUNDLE_INVALID"),
                Arguments.of("src/Main.java",
                        "{\"changes\":[{\"expectedBaselineSha256\":\""
                                + hash("old")
                                + "\",\"path\":\"src/Main.java\",\"text\":\"\",\"type\":\"DELETE\"}]}",
                        "CANDIDATE_REPLACEMENT_BUNDLE_INVALID"),
                Arguments.of("SRC/main.java",
                        body("ADD", "SRC/main.java", ChainIdentity.NONE, "x"),
                        "CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS"));
    }

    @ParameterizedTest
    @MethodSource("mergeCases")
    void normalizesMultiStepCandidateAgainstOriginalProject(
            List<ProjectFileEntry> manifest,
            CandidateReviewDiff.Entry baseEntry,
            String target,
            String body,
            CandidateIntent.Type expectedType,
            String expectedText) {
        Fixture fixture = new Fixture(manifest);
        String baseKey = "e".repeat(64);
        fixture.base(baseKey, baseEntry);

        ChainEffectRuntime.MaterializedCandidate result = fixture.execute(
                baseKey, target, body, baseKey);

        assertEquals(ChainEffectRuntime.CandidateDisposition.COMMITTED,
                result.disposition());
        CandidateIntent.FileIntent change = fixture.intent().changes().get(0);
        assertEquals(expectedType, change.type());
        assertEquals(expectedText, change.replacementText());
        if (expectedType == CandidateIntent.Type.ADD) {
            assertNull(change.baseFileHash());
        } else {
            assertEquals(hash("old"), change.baseFileHash().sha256());
        }
    }

    private static Stream<Arguments> mergeCases() {
        return Stream.of(
                Arguments.of(List.of(file("README.md", "evidence")),
                        entry(CandidateFileChange.Type.ADD, "New.java", null,
                                "first"),
                        "New.java", body("MODIFY", "New.java",
                                hash("first"), "second"),
                        CandidateIntent.Type.ADD, "second"),
                Arguments.of(List.of(file("README.md", "evidence"),
                                file("src/Main.java", "old")),
                        entry(CandidateFileChange.Type.MODIFY,
                                "src/Main.java", hash("old"), "first"),
                        "Main.java", body("DELETE", "Main.java",
                                hash("first"), null),
                        CandidateIntent.Type.DELETE, null),
                Arguments.of(List.of(file("README.md", "evidence"),
                                file("src/Main.java", "old")),
                        entry(CandidateFileChange.Type.DELETE,
                                "src/Main.java", hash("old"), null),
                        "src/Main.java", body("ADD", "src/Main.java",
                                ChainIdentity.NONE, "second"),
                        CandidateIntent.Type.MODIFY, "second"));
    }

    @Test
    void rejectsWhenMultiStepChangesCancelToOriginalState() {
        Fixture fixture = new Fixture(List.of(
                file("README.md", "evidence")));
        String baseKey = "e".repeat(64);
        fixture.base(baseKey, entry(
                CandidateFileChange.Type.ADD, "New.java", null, "first"));

        ChainEffectRuntime.MaterializedCandidate result = fixture.execute(
                baseKey, "New.java",
                body("DELETE", "New.java", hash("first"), null), baseKey);

        assertEquals(ChainEffectRuntime.CandidateDisposition.FAILED,
                result.disposition());
        assertEquals("CANDIDATE_NO_ACTUAL_CHANGE", result.errorCode());
        assertNull(fixture.intent());
    }

    @Test
    void acceptsReplacementAboveFormerLocalLimitWhenProjectAllowsIt() {
        Fixture fixture = new Fixture(List.of(
                file("README.md", "evidence")), 128L * 1024);
        String replacement = "x".repeat(65 * 1024);

        ChainEffectRuntime.MaterializedCandidate result = fixture.execute(
                ChainIdentity.NONE, "Large.txt",
                body("ADD", "Large.txt", ChainIdentity.NONE, replacement),
                null);

        assertEquals(ChainEffectRuntime.CandidateDisposition.COMMITTED,
                result.disposition());
        assertEquals(replacement,
                fixture.intent().changes().get(0).replacementText());
    }

    @Test
    void rejectsReplacementAboveConfiguredProjectLimit() {
        Fixture fixture = new Fixture(List.of(
                file("README.md", "evidence")), 1024);
        String replacement = "x".repeat(1025);

        ChainEffectRuntime.MaterializedCandidate result = fixture.execute(
                ChainIdentity.NONE, "Large.txt",
                body("ADD", "Large.txt", ChainIdentity.NONE, replacement),
                null);

        assertEquals(ChainEffectRuntime.CandidateDisposition.FAILED,
                result.disposition());
        assertEquals("CANDIDATE_REPLACEMENT_TOO_LARGE", result.errorCode());
        assertNull(fixture.intent());
    }

    @Test
    void rejectsBodyDigestBindingProjectionAndAliasDrift() {
        Fixture bodyDrift = new Fixture(List.of(
                file("README.md", "evidence")));
        bodyDrift.contentHashOverride = "f".repeat(64);
        assertThrows(IllegalStateException.class, () -> bodyDrift.execute(
                ChainIdentity.NONE, "New.java",
                body("ADD", "New.java", ChainIdentity.NONE, "x"), null));

        Fixture bindingDrift = new Fixture(List.of(
                file("README.md", "evidence")));
        String baseKey = "e".repeat(64);
        bindingDrift.base(baseKey, entry(
                CandidateFileChange.Type.ADD, "New.java", null, "first"));
        bindingDrift.replaceBaseDiffDigest("f".repeat(64));
        assertThrows(IllegalStateException.class, () -> bindingDrift.execute(
                baseKey, "New.java", body("MODIFY", "New.java",
                        hash("first"), "second"), baseKey));

        Fixture projectionDrift = new Fixture(List.of(
                file("README.md", "evidence")));
        projectionDrift.base(baseKey, entry(
                CandidateFileChange.Type.ADD, "New.java", null, "first"));
        when(projectionDrift.baseChange.type()).thenReturn(
                CandidateFileChange.Type.MODIFY);
        assertThrows(IllegalStateException.class, () -> projectionDrift.execute(
                baseKey, "New.java", body("MODIFY", "New.java",
                        hash("first"), "second"), baseKey));

        Fixture aliasDrift = new Fixture(List.of(
                file("README.md", "evidence"),
                file("src/Main.java", "old")));
        String twoChanges = "{\"changes\":["
                + "{\"expectedBaselineSha256\":\"" + hash("old")
                + "\",\"path\":\"src/Main.java\",\"text\":\"a\",\"type\":\"MODIFY\"},"
                + "{\"expectedBaselineSha256\":\"" + hash("old")
                + "\",\"path\":\"Main.java\",\"text\":\"b\",\"type\":\"MODIFY\"}]}";
        assertFailed(aliasDrift.executeMany(
                ChainIdentity.NONE,
                List.of("src/Main.java", "Main.java"), twoChanges, null));
    }

    private static void assertFailed(
            ChainEffectRuntime.MaterializedCandidate result) {
        assertEquals(ChainEffectRuntime.CandidateDisposition.FAILED,
                result.disposition());
    }

    private static ProjectFileEntry file(String path, String text) {
        return new ProjectFileEntry(
                path, text.getBytes(StandardCharsets.UTF_8).length,
                NOW, hash(text));
    }

    private static CandidateReviewDiff.Entry entry(
            CandidateFileChange.Type type, String path,
            String baseHash, String text) {
        return new CandidateReviewDiff.Entry(
                type, new ProjectRelativePath(path),
                baseHash == null ? null : new FileHash(baseHash),
                text == null ? null
                        : CandidateTextPayload.fromText(text).contentHash(),
                text);
    }

    private static String body(
            String type, String path, String baseline, String text) {
        return "{\"changes\":[{\"expectedBaselineSha256\":\""
                + baseline + "\",\"path\":\"" + path + "\""
                + (text == null ? "" : ",\"text\":\"" + text + "\"")
                + ",\"type\":\"" + type + "\"}]}";
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class Fixture {
        private final ChainFoundationRepository foundations = mock(
                ChainFoundationRepository.class);
        private final ChainWorkflowRepository workflow = mock(
                ChainWorkflowRepository.class);
        private final ChainModelRepository models = mock(
                ChainModelRepository.class);
        private final EffectOutcomeRepository outcomes = mock(
                EffectOutcomeRepository.class);
        private final CandidateChangeArtifactService candidates = mock(
                CandidateChangeArtifactService.class);
        private final ProjectService projects = mock(ProjectService.class);
        private final ProductChainTaskMutationFence fence = mock(
                ProductChainTaskMutationFence.class);
        private final ProductChainCandidateMaterializationFailureRepositoryAdapter
                failures = mock(
                ProductChainCandidateMaterializationFailureRepositoryAdapter.class);
        private final ProductChainWorkspaceCandidateAuthority authority;
        private final List<ChainPersistenceRecords.WorkspaceCandidateRecord>
                bindings = new ArrayList<>();
        private final AtomicReference<CandidateIntent> storedIntent =
                new AtomicReference<>();
        private final List<ProjectFileEntry> manifest;
        private String baseKey;
        private CandidateFileChange baseChange;
        private String contentHashOverride;

        private Fixture(List<ProjectFileEntry> manifest) {
            this(manifest, new ProjectStorageProperties().getMaxFileBytes());
        }

        private Fixture(List<ProjectFileEntry> manifest, long maxFileBytes) {
            this.manifest = List.copyOf(manifest);
            when(fence.materializeCurrent(any(), any())).thenAnswer(
                    invocation -> ((Supplier<ChainEffectRuntime.MaterializedCandidate>)
                            invocation.getArgument(1)).get());
            when(foundations.findTask("task.1")).thenReturn(Optional.of(
                    new ChainPersistenceRecords.TaskRecord(
                            "task.1", "command.1", "instruction.1", null,
                            7L, 9L, 42L, null, "client.1", AUTHORITY_HASH,
                            8L, VERSION, 0L, NOW)));
            when(projects.manifest(7L, 8L)).thenReturn(
                    new ProjectManifestResponse(8L, VERSION, this.manifest));
            for (ProjectFileEntry file : this.manifest) {
                String text = switch (file.path()) {
                    case "README.md" -> "evidence";
                    case "src/Main.java" -> "old";
                    default -> throw new IllegalArgumentException(file.path());
                };
                when(projects.readFile(7L, 8L, file.path())).thenReturn(
                        new ProjectFileResponse(
                                file.path(), text, file.sizeBytes(), NOW,
                                file.sha256()));
            }
            when(workflow.findWorkspaceCandidates("task.1")).thenAnswer(
                    ignored -> List.copyOf(bindings));
            when(workflow.findActionBindings("task.1")).thenReturn(List.of(
                    actionBinding(ChainIdentity.NONE)));
            when(failures.findCandidateMaterializationFailure(any(), any()))
                    .thenReturn(Optional.empty());
            when(failures.appendCandidateMaterializationFailure(any()))
                    .thenAnswer(invocation -> {
                        ChainPersistenceRecords.AuthoritativeFact<
                                ChainPersistenceRecords
                                        .CandidateMaterializationFailureRecord> value =
                                invocation.getArgument(0);
                        var event = value.event();
                        return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                                new ChainPersistenceRecords.AuthorityEventRecord(
                                        event.eventId(), event.taskId(), 1L,
                                        event.eventType(), event.transitionId(),
                                        event.sourceIdentitySha256(),
                                        event.committedAt()),
                                value.fact(), false);
                    });
            when(candidates.store(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> outputCandidate(
                            invocation.getArgument(3)));
            ProjectStorageProperties storage = new ProjectStorageProperties();
            storage.setMaxFileBytes(maxFileBytes);
            authority = new ProductChainWorkspaceCandidateAuthority(
                    foundations, workflow, models, outcomes, candidates,
                    projects, fence, new ObjectMapper(), failures,
                    storage);
        }

        private void base(
                String candidateKey, CandidateReviewDiff.Entry entry) {
            baseKey = candidateKey;
            CandidateArtifactResponse base = mock(
                    CandidateArtifactResponse.class);
            CandidateFingerprint fingerprint = new CandidateFingerprint(
                    candidateKey);
            when(base.fingerprint()).thenReturn(fingerprint);
            when(base.artifactId()).thenReturn(44L);
            when(base.projectId()).thenReturn(8L);
            when(base.projectVersion()).thenReturn(
                    new ProjectVersionRef(VERSION));
            when(base.governanceStatus()).thenReturn(
                    CandidateChangeSet.GovernanceStatus.VALIDATED);
            when(base.reviewDiff()).thenReturn(CandidateReviewDiff.fromJson(
                    CandidateReviewDiff.FORMAT, fingerprint,
                    new ProjectVersionRef(VERSION), List.of(entry)));
            CandidateFileChange change = mock(CandidateFileChange.class);
            baseChange = change;
            when(change.type()).thenReturn(entry.type());
            when(change.projectVersion()).thenReturn(
                    new ProjectVersionRef(VERSION));
            when(change.relativePath()).thenReturn(entry.relativePath());
            when(change.baseFileHash()).thenReturn(entry.baseFileHash());
            when(change.resultFileHash()).thenReturn(entry.resultFileHash());
            when(change.candidateText()).thenReturn(
                    entry.replacementText() == null ? null
                            : CandidateTextPayload.fromText(
                            entry.replacementText()));
            when(base.changes()).thenReturn(List.of(change));
            when(candidates.getCurrent(7L, 44L)).thenReturn(base);
            bindings.add(new ChainPersistenceRecords.WorkspaceCandidateRecord(
                    "workspace-candidate.base", "task.1", "event.base",
                    "action.base", "workspace.1", VERSION, 44L,
                    candidateKey, diffDigest(entry), FENCE, NOW));
        }

        private ChainEffectRuntime.MaterializedCandidate execute(
                String candidateKey, String target, String body,
                String expectedBaseRef) {
            return executeMany(candidateKey, List.of(target), body,
                    expectedBaseRef);
        }

        private ChainEffectRuntime.MaterializedCandidate executeMany(
                String candidateKey, List<String> targets, String body,
                String expectedBaseRef) {
            String payload = "{\"baseCandidateRef\":\""
                    + (expectedBaseRef == null ? candidateKey : expectedBaseRef)
                    + "\",\"manifestChanges\":[],\"targetFiles\":["
                    + targets.stream().map(value -> "\"" + value + "\"")
                    .collect(java.util.stream.Collectors.joining(","))
                    + "]}";
            when(models.findProposal("proposal.1")).thenReturn(Optional.of(
                    new ChainPersistenceRecords.ModelProposalRecord(
                            "proposal.1", "task.1", "invocation.1", 1,
                            ChainRole.EXECUTOR,
                            ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE,
                            new ChainPersistenceRecords.CanonicalJson(
                                    1, AUTHORITY_HASH, payload),
                            new ChainPersistenceRecords.CanonicalJson(
                                    1, AUTHORITY_HASH, "[]"),
                            ChainContentKind.WORKSPACE_CHANGE_BODY.name(),
                            "content.1", NOW)));
            when(models.findContent("content.1")).thenReturn(Optional.of(
                    new ChainPersistenceRecords.ContentRecord(
                            "content.1", "task.1", "invocation.1",
                            ChainContentKind.WORKSPACE_CHANGE_BODY,
                            body, contentHashOverride == null
                            ? hash(body) : contentHashOverride,
                            "application/json", NOW)));
            List<ChainPersistenceRecords.ActionBindingRecord> actions =
                    new ArrayList<>();
            actions.add(actionBinding(candidateKey));
            if (baseKey != null) {
                actions.add(new ChainPersistenceRecords.ActionBindingRecord(
                        "action.base", "task.1", "event.action.base",
                        "proposal.base", 1, AUTHORITY_HASH, "key.base",
                        "instruction.1", "task-frame.1", "plan.1",
                        "revision.1", "step.1", "activation.base",
                        "workspace.1", ChainIdentity.NONE,
                        null, null, null, null, FENCE, NOW));
            }
            when(workflow.findActionBindings("task.1")).thenReturn(actions);
            ChainEffectRuntime.FrozenMutation frozen =
                    new ChainEffectRuntime.FrozenMutation(
                            ChainEffectRuntime.SourceKind.WORKSPACE_CHANGE,
                            "task.1", "action.1", "key.1", "proposal.1",
                            "instruction.1", "task-frame.1", "plan.1",
                            "revision.1", "step.1", "activation.1",
                            "workspace.1", candidateKey, AUTHORITY_HASH, FENCE);
            return authority.materialize(
                    new ChainEffectRuntime.CandidateMutation(
                            frozen, "WORKSPACE_CHANGE_BODY", "content.1"),
                    materialized -> {
                        ChainPersistenceRecords.WorkspaceCandidateRecord value =
                                new ChainPersistenceRecords.WorkspaceCandidateRecord(
                                        "workspace-candidate.output", "task.1",
                                        "event.output", "action.1", "workspace.1",
                                        VERSION, materialized.artifactId(),
                                        materialized.candidateFingerprint(),
                                        materialized.diffDigest(), FENCE, NOW);
                        bindings.add(value);
                        return value;
                    });
        }

        private ChainPersistenceRecords.ActionBindingRecord actionBinding(
                String candidateKey) {
            return new ChainPersistenceRecords.ActionBindingRecord(
                    "action.1", "task.1", "event.action", "proposal.1", 1,
                    AUTHORITY_HASH, "key.1", "instruction.1", "task-frame.1",
                    "plan.1", "revision.1", "step.1", "activation.1",
                    "workspace.1", candidateKey, null, null, null, null,
                    FENCE, NOW);
        }

        private CandidateArtifactResponse outputCandidate(CandidateIntent intent) {
            storedIntent.set(intent);
            CandidateArtifactResponse candidate = mock(
                    CandidateArtifactResponse.class);
            CandidateFingerprint fingerprint = new CandidateFingerprint(
                    OUTPUT_FINGERPRINT);
            List<CandidateReviewDiff.Entry> entries = intent.changes().stream()
                    .map(change -> new CandidateReviewDiff.Entry(
                            CandidateFileChange.Type.valueOf(
                                    change.type().name()),
                            change.relativePath(), change.baseFileHash(),
                            change.replacementText() == null ? null
                                    : CandidateTextPayload.fromText(
                                    change.replacementText()).contentHash(),
                            change.replacementText()))
                    .toList();
            when(candidate.projectId()).thenReturn(8L);
            when(candidate.projectVersion()).thenReturn(
                    new ProjectVersionRef(VERSION));
            when(candidate.artifactId()).thenReturn(55L);
            when(candidate.fingerprint()).thenReturn(fingerprint);
            when(candidate.reviewDiff()).thenReturn(
                    CandidateReviewDiff.fromJson(
                            CandidateReviewDiff.FORMAT, fingerprint,
                            new ProjectVersionRef(VERSION), entries));
            return candidate;
        }

        private CandidateIntent intent() {
            return storedIntent.get();
        }

        private void replaceBaseDiffDigest(String digest) {
            ChainPersistenceRecords.WorkspaceCandidateRecord base =
                    bindings.remove(0);
            bindings.add(0,
                    new ChainPersistenceRecords.WorkspaceCandidateRecord(
                            base.workspaceCandidateId(), base.taskId(),
                            base.eventId(), base.actionId(), base.workspaceId(),
                            base.baseProjectVersion(), base.artifactId(),
                            base.candidateFingerprint(), digest,
                            base.versionFenceSha256(), base.createdAt()));
        }

        private static String diffDigest(CandidateReviewDiff.Entry entry) {
            return hash(VERSION + '\0' + entry.type() + '\0'
                    + entry.relativePath().value() + '\0'
                    + (entry.baseFileHash() == null ? ChainIdentity.NONE
                    : entry.baseFileHash().sha256()) + '\0'
                    + (entry.resultFileHash() == null ? ChainIdentity.NONE
                    : entry.resultFileHash().sha256()));
        }
    }
}
