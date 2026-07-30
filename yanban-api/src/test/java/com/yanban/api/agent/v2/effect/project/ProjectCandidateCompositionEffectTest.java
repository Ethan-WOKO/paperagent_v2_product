package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.v2.compatibility.project.*;
import com.yanban.api.artifact.AgentArtifactService;
import com.yanban.api.artifact.ArtifactResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import io.paperagent.v2.contracts.*;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.providers.*;
import io.paperagent.v2.workspace.WorkspacePort;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectCandidateCompositionEffectTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void repairUsesPlanBoundProviderAndPreservesOtherFailedCandidateText() throws Exception {
        var gateway = mock(ProjectCandidateEffectGateway.class);
        var candidates = mock(CandidateChangeArtifactService.class);
        var provider = mock(ModelProvider.class);
        var projects = mock(ProjectService.class);
        var workspace = mock(WorkspacePort.class);
        var ref = new WorkspaceRef(new WorkspaceId("workspace"),
                new ProjectVersionRef("8", "a".repeat(64)));
        String broken = "import ch.qos.logback.core.rolling.TimeBasedFileNamingAndTriggeringPolicy;\n"
                + "public class Sort { public static void main(String[] a) {} }\n";
        String repaired = "public class Sort { public static void main(String[] a) {} }\n";
        Map<String, String> source = new LinkedHashMap<>();
        source.put("Sort.java", broken);
        source.put("README.md", "candidate readme");
        String replacementsDigest = sha(json.writeValueAsString(new TreeMap<>(source)));
        Map<String, ContractValue> argumentValues = new TreeMap<>();
        argumentValues.put("operation", new TextValue("repair"));
        argumentValues.put("sourceCandidateArtifactId", new NumberValue(BigDecimal.valueOf(35)));
        argumentValues.put("sourceCandidateFingerprint", new TextValue("b".repeat(64)));
        argumentValues.put("selectedChangeIndex", new NumberValue(BigDecimal.ZERO));
        argumentValues.put("selectedPath", new TextValue("Sort.java"));
        argumentValues.put("failedReceiptDigest", new TextValue("c".repeat(64)));
        argumentValues.put("originalProjectVersion", new TextValue("a".repeat(64)));
        argumentValues.put("attempt", new NumberValue(BigDecimal.ONE));
        argumentValues.put("maxAttempts", new NumberValue(BigDecimal.ONE));
        argumentValues.put("sourceReplacementsSha256", new TextValue(replacementsDigest));
        var intent = new PersistedEffectIntent(new EffectIntent(
                new ToolCallId("tool"), new PlanId("plan"),
                new PlanStepId("project-candidate-compose"),
                ProjectCandidateCompositionEffect.KIND, new ObjectValue(argumentValues)),
                "owner", 1L, new EventId("activation"));
        String authorityJson = "{\"operation\":\"repair\"}";
        when(gateway.require("plan", "project-candidate-compose")).thenReturn(
                new ProjectCandidateEffectAuthority(ProjectCandidateCompositionEffect.KIND,
                        authorityJson, sha(authorityJson), 7L, 8L, 9L, 42L,
                        "a".repeat(64), "repair", List.of("Sort.java", "README.md"),
                        new ProjectCandidateEffectAuthority.RepairAuthority(
                                "55bc85eb-e38b-4a91-8669-1a6b4ba92646", 35L,
                                "b".repeat(64), 0, "Sort.java", "c".repeat(64),
                                "a".repeat(64), 1, 1, source, replacementsDigest,
                                "package does not exist")));
        when(provider.complete(any())).thenReturn(new ModelResponse(
                Optional.of(json.writeValueAsString(Map.of(
                        "replacementText", repaired, "mavenCoordinates", List.of()))),
                List.of(), FinishReason.STOP, new UsageMetadata(1, 1, 0, Map.of()), Map.of()));
        Map<String, byte[]> files = new HashMap<>();
        files.put("Sort.java", "original sort".getBytes(StandardCharsets.UTF_8));
        files.put("README.md", "original readme".getBytes(StandardCharsets.UTF_8));
        when(workspace.read(eq(ref), any())).thenAnswer(call ->
                files.get(call.<ProjectPath>getArgument(1).value()));
        doAnswer(call -> {
            files.put(call.<ProjectPath>getArgument(1).value(), call.getArgument(2)); return null;
        }).when(workspace).replace(eq(ref), any(), any());
        when(workspace.diff(eq(ref), any(), any())).thenAnswer(call -> new WorkspaceDiff(
                call.getArgument(1), ref, List.of(
                new WorkspaceDiffEntry(DiffKind.MODIFY, new ProjectPath("Sort.java"),
                        Optional.empty(), Optional.of(new ContentHash("sha256", sha("original sort"))),
                        Optional.of(new ContentHash("sha256", sha(repaired))), Map.of()),
                new WorkspaceDiffEntry(DiffKind.MODIFY, new ProjectPath("README.md"),
                        Optional.empty(), Optional.of(new ContentHash("sha256", sha("original readme"))),
                        Optional.of(new ContentHash("sha256", sha("candidate readme"))), Map.of())),
                call.getArgument(2)));
        var effect = new ProjectCandidateCompositionEffect(
                gateway, candidates, provider, projects, json);
        var authority = new ProjectCandidateCompositionEffect.ModelAuthority(
                new TaskFrameId("task-frame"), new PlanId("plan"),
                new PlanRevisionId("revision"), new PlanStepId("project-candidate-compose"));

        effect.execute(intent, authority, workspace, ref, 7L, 42L, 8L, Instant.EPOCH);

        assertEquals(repaired, new String(files.get("Sort.java"), StandardCharsets.UTF_8));
        assertEquals("candidate readme",
                new String(files.get("README.md"), StandardCharsets.UTF_8));
        var request = org.mockito.ArgumentCaptor.forClass(ModelRequest.class);
        verify(provider).complete(request.capture());
        assertEquals(Optional.of(new PlanId("plan")), request.getValue().planId());
        verify(gateway).bindPrepared(eq("plan"),
                eq(Map.of("Sort.java", repaired, "README.md", "candidate readme")),
                eq(List.of()), anyString());

        clearInvocations(provider, gateway);
        files.put("Sort.java", "original sort".getBytes(StandardCharsets.UTF_8));
        files.put("README.md", "original readme".getBytes(StandardCharsets.UTF_8));
        when(provider.complete(any())).thenReturn(new ModelResponse(
                Optional.of(json.writeValueAsString(Map.of(
                        "replacementText", broken, "mavenCoordinates",
                        List.of("ch.qos.logback:logback-core:1.5.18")))),
                List.of(), FinishReason.STOP, new UsageMetadata(1, 1, 0, Map.of()), Map.of()));
        effect.execute(intent, authority, workspace, ref, 7L, 42L, 8L, Instant.EPOCH);
        verify(gateway).bindPrepared(eq("plan"),
                eq(Map.of("Sort.java", broken, "README.md", "candidate readme")),
                eq(List.of("ch.qos.logback:logback-core:1.5.18")), anyString());

        clearInvocations(provider, gateway);
        files.put("Sort.java", "original sort".getBytes(StandardCharsets.UTF_8));
        files.put("README.md", "original readme".getBytes(StandardCharsets.UTF_8));
        when(provider.complete(any())).thenReturn(new ModelResponse(
                Optional.of(json.writeValueAsString(Map.of(
                        "replacementText", broken, "mavenCoordinates", List.of()))),
                List.of(), FinishReason.STOP, new UsageMetadata(1, 1, 0, Map.of()), Map.of()));
        assertThrows(IllegalStateException.class,
                () -> effect.execute(intent, authority, workspace, ref,
                        7L, 42L, 8L, Instant.EPOCH));
        verify(gateway, never()).bindPrepared(anyString(), anyMap(), anyList(), anyString());
    }

    @Test
    void exactFrozenReplacementMutatesOnlyWorkspaceAndProducesStableModifyDiff() {
        Fixture fixture = fixture("{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new text\"}]}");

        var result = fixture.effect.execute(
                fixture.intent, fixture.modelAuthority, fixture.workspace,
                fixture.ref, 7L, 42L, 8L, Instant.parse("2026-01-01T00:00:00Z"));

        assertNull(result.artifactId());
        assertEquals(64, result.diffFingerprint().length());
        assertArrayEquals("new text".getBytes(StandardCharsets.UTF_8),
                fixture.files.get("README.md"));
        var modelRequest = org.mockito.ArgumentCaptor.forClass(
                ModelRequest.class);
        verify(fixture.provider).complete(modelRequest.capture());
        assertEquals(Optional.of(
                        fixture.modelAuthority.taskFrameId()),
                modelRequest.getValue().taskFrameId());
        assertEquals(Optional.of(fixture.modelAuthority.planId()),
                modelRequest.getValue().planId());
        assertEquals(Optional.of(
                        fixture.modelAuthority.planRevisionId()),
                modelRequest.getValue().planRevisionId());
        assertEquals(Optional.of(fixture.modelAuthority.stepId()),
                modelRequest.getValue().stepId());
        verify(fixture.gateway).bindPrepared(
                eq("plan"), eq(Map.of("README.md", "new text")), eq(List.of()),
                eq(result.diffFingerprint()));
        verifyNoInteractions(fixture.candidates, fixture.projects);
    }

    @Test
    void extraPathMalformedOversizedAndUnchangedProviderOutputFailClosed() {
        for (String output : List.of(
                "{\"replacements\":[{\"path\":\"extra.md\",\"text\":\"new\"}]}",
                "{\"replacements\":[]}",
                "{\"replacements\":[{\"path\":\"README.md\",\"text\":\"old text\"}]}",
                "{\"replacements\":[{\"path\":\"README.md\",\"text\":\""
                        + "x".repeat(64 * 1024 + 1) + "\"}]}")) {
            Fixture fixture = fixture(output);
            assertThrows(IllegalStateException.class, () -> fixture.effect.execute(
                    fixture.intent, fixture.modelAuthority,
                    fixture.workspace, fixture.ref,
                    7L, 42L, 8L, Instant.now()));
            assertArrayEquals("old text".getBytes(StandardCharsets.UTF_8),
                    fixture.files.get("README.md"));
        }
    }

    @Test
    void providerFailureAndCrossBoundAuthorityFailBeforeCandidatePublication() {
        Fixture providerFailure = fixture(null);
        assertThrows(IllegalStateException.class, () -> providerFailure.effect.execute(
                providerFailure.intent, providerFailure.modelAuthority,
                providerFailure.workspace, providerFailure.ref,
                7L, 42L, 8L, Instant.now()));
        Fixture crossUser = fixture("{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new\"}]}");
        assertThrows(IllegalStateException.class, () -> crossUser.effect.execute(
                crossUser.intent, crossUser.modelAuthority,
                crossUser.workspace, crossUser.ref,
                99L, 42L, 8L, Instant.now()));
        verifyNoInteractions(providerFailure.candidates, crossUser.candidates);
    }

    @Test
    void terminalPublicationUsesExistingCandidatePipelineAndBindsExactFingerprints() {
        Fixture fixture = fixture(
                "{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new text\"}]}");
        fixture.effect.execute(
                fixture.intent, fixture.modelAuthority,
                fixture.workspace, fixture.ref,
                7L, 42L, 8L, Instant.parse("2026-01-01T00:00:00Z"));
        when(fixture.projects.manifest(7L, 8L)).thenReturn(
                new com.yanban.api.project.ProjectManifestResponse(
                        8L, "a".repeat(64), List.of(
                        new com.yanban.api.project.ProjectFileEntry(
                                "README.md", 8, Instant.now(), sha("old text")))));
        when(fixture.projects.readFile(7L, 8L, "README.md")).thenReturn(
                new com.yanban.api.project.ProjectFileResponse(
                        "README.md", "old text", 8, Instant.now(), sha("old text")));
        var artifact = mock(com.yanban.api.agent.sandbox.CandidateArtifactResponse.class);
        when(artifact.artifactId()).thenReturn(42L);
        when(artifact.fingerprint()).thenReturn(
                new com.yanban.core.agent.sandbox.CandidateFingerprint("c".repeat(64)));
        when(fixture.candidates.store(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(artifact);

        var published = fixture.effect.publish("plan", 7L, 42L);

        assertEquals(42L, published.artifactId());
        verify(fixture.gateway).bindCandidate(
                eq("plan"), eq(42L), eq("c".repeat(64)), eq(published.diffFingerprint()));
        verify(fixture.projects, never()).delete(anyLong(), anyLong());
    }

    @Test
    void staleVersionAndCandidatePersistenceFailurePublishNothing() {
        Fixture stale = fixture(
                "{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new text\"}]}");
        stale.effect.execute(
                stale.intent, stale.modelAuthority, stale.workspace, stale.ref,
                7L, 42L, 8L, Instant.parse("2026-01-01T00:00:00Z"));
        when(stale.projects.manifest(7L, 8L)).thenReturn(
                new com.yanban.api.project.ProjectManifestResponse(
                        8L, "stale-version", List.of()));
        assertThrows(IllegalStateException.class, () -> stale.effect.publish(
                "plan", 7L, 42L));
        verifyNoInteractions(stale.candidates);
        verify(stale.gateway, never()).bindCandidate(anyString(), anyLong(), anyString(), anyString());

        Fixture persistenceFailure = fixture(
                "{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new text\"}]}");
        persistenceFailure.effect.execute(persistenceFailure.intent,
                persistenceFailure.modelAuthority,
                persistenceFailure.workspace, persistenceFailure.ref,
                7L, 42L, 8L, Instant.parse("2026-01-01T00:00:00Z"));
        when(persistenceFailure.projects.manifest(7L, 8L)).thenReturn(
                new com.yanban.api.project.ProjectManifestResponse(
                        8L, "a".repeat(64), List.of(
                        new com.yanban.api.project.ProjectFileEntry(
                                "README.md", 8, Instant.now(), sha("old text")))));
        when(persistenceFailure.projects.readFile(7L, 8L, "README.md")).thenReturn(
                new com.yanban.api.project.ProjectFileResponse(
                        "README.md", "old text", 8, Instant.now(), sha("old text")));
        when(persistenceFailure.candidates.store(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new IllegalStateException("persistence unavailable"));

        assertThrows(IllegalStateException.class, () -> persistenceFailure.effect.publish(
                "plan", 7L, 42L));
        verify(persistenceFailure.gateway, never()).bindCandidate(
                anyString(), anyLong(), anyString(), anyString());
        verify(persistenceFailure.projects, never()).delete(anyLong(), anyLong());
    }

    @Test
    void missingOrTamperedDurablePreparationPublishesNothing() {
        Fixture missing = fixture(
                "{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new text\"}]}");
        when(missing.projects.manifest(7L, 8L)).thenReturn(
                new com.yanban.api.project.ProjectManifestResponse(
                        8L, "a".repeat(64), List.of()));
        when(missing.gateway.requirePrepared("plan")).thenReturn(null);
        assertThrows(RuntimeException.class,
                () -> missing.effect.publish("plan", 7L, 42L));
        verifyNoInteractions(missing.candidates);

        Fixture extra = fixture(
                "{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new text\"}]}");
        when(extra.projects.manifest(7L, 8L)).thenReturn(
                new com.yanban.api.project.ProjectManifestResponse(
                        8L, "a".repeat(64), List.of()));
        when(extra.gateway.requirePrepared("plan")).thenReturn(
                new ProjectCandidateEffectGateway.PreparedCandidate(
                        Map.of("README.md", "new text", "extra.md", "extra"),
                        "d".repeat(64)));
        assertThrows(IllegalStateException.class,
                () -> extra.effect.publish("plan", 7L, 42L));
        verifyNoInteractions(extra.candidates);
    }

    @Test
    void realCandidateServiceAcceptsPublishedIntentAndEvidenceAsValidatedNotApplied() {
        String originalHash = sha("old text");
        String version = com.yanban.core.research.ProjectManifestIdentity.derive(
                List.of(new com.yanban.core.research.ProjectManifestIdentity.Entry(
                        new ProjectRelativePath("README.md"),
                        new FileHash(originalHash), 8))).value();
        Fixture fixture = fixture(
                "{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new text\"}]}",
                version);
        AgentArtifactService artifacts = mock(AgentArtifactService.class);
        var realCandidates = new CandidateChangeArtifactService(
                artifacts, fixture.projects, json);
        var realEffect = new ProjectCandidateCompositionEffect(
                fixture.gateway, realCandidates, fixture.provider,
                fixture.projects, json);
        var snapshot = new com.yanban.core.agent.sandbox.SandboxWorkspaceSnapshot(
                new com.yanban.core.agent.sandbox.SandboxWorkspaceRef(
                        8L, new com.yanban.core.research.ProjectVersionRef(version)),
                List.of(new com.yanban.core.agent.sandbox.SandboxFileSnapshot(
                        new ProjectRelativePath("README.md"),
                        new FileHash(originalHash), 8)));
        var materialized = new ProjectService.SandboxWorkspaceMaterialization(
                snapshot, Map.of("README.md", "old text"));
        when(fixture.projects.manifest(7L, 8L)).thenReturn(
                new com.yanban.api.project.ProjectManifestResponse(
                        8L, version, List.of(
                        new com.yanban.api.project.ProjectFileEntry(
                                "README.md", 8, Instant.now(), originalHash))));
        when(fixture.projects.readFile(7L, 8L, "README.md")).thenReturn(
                new com.yanban.api.project.ProjectFileResponse(
                        "README.md", "old text", 8, Instant.now(), originalHash));
        when(fixture.projects.materializeSandbox(
                eq(7L), eq(8L), eq(Set.of("README.md"))))
                .thenReturn(materialized);
        AtomicReference<ArtifactResponse> persisted = new AtomicReference<>();
        when(artifacts.createCandidateArtifact(
                eq(7L), eq(9L), anyString(), anyString())).thenAnswer(call -> {
                    var artifact = new ArtifactResponse(
                            42L, 7L, 9L, call.getArgument(2), "TEXT",
                            call.getArgument(3),
                            CandidateChangeArtifactService.SOURCE_TYPE,
                            List.of(), "ACTIVE", null, null, null,
                            Instant.EPOCH, Instant.EPOCH);
                    persisted.set(artifact);
                    return artifact;
                });

        realEffect.execute(
                fixture.intent, fixture.modelAuthority, fixture.workspace,
                fixture.ref, 7L, 42L, 8L, Instant.EPOCH);
        var published = realEffect.publish("plan", 7L, 42L);
        when(artifacts.getArtifact(7L, 42L)).thenAnswer(
                ignored -> persisted.get());
        var reviewed = realCandidates.getCurrent(7L, 42L);

        assertEquals(com.yanban.core.agent.sandbox.CandidateChangeSet
                .GovernanceStatus.VALIDATED, reviewed.governanceStatus());
        assertEquals(com.yanban.core.agent.sandbox.CandidateChangeSet
                .ApplicationStatus.NOT_APPLIED, reviewed.applicationStatus());
        assertEquals(8L, reviewed.projectId());
        assertEquals(version, reviewed.projectVersion().value());
        assertEquals(List.of("README.md"), reviewed.changes().stream()
                .map(change -> change.relativePath().value()).toList());
        assertEquals(originalHash,
                reviewed.changes().get(0).baseFileHash().sha256());
        assertEquals(reviewed.fingerprint().sha256(),
                published.candidateFingerprint());
        assertEquals(64, published.diffFingerprint().length());
        verify(fixture.gateway).bindCandidate(
                "plan", 42L, reviewed.fingerprint().sha256(),
                published.diffFingerprint());
    }

    @Test
    void binaryAndMissingWorkspaceInputsFailBeforeProviderOrCandidate() {
        Fixture binary = fixture(
                "{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new\"}]}");
        when(binary.workspace.read(
                binary.ref, new ProjectPath("README.md")))
                .thenReturn(new byte[]{1});
        assertThrows(IllegalStateException.class, () -> binary.effect.execute(
                binary.intent, binary.modelAuthority,
                binary.workspace, binary.ref,
                7L, 42L, 8L, Instant.now()));
        verifyNoInteractions(binary.candidates, binary.projects);

        Fixture missing = fixture(
                "{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new\"}]}");
        when(missing.workspace.read(
                missing.ref, new ProjectPath("README.md")))
                .thenThrow(new IllegalArgumentException("missing"));
        assertThrows(IllegalArgumentException.class, () -> missing.effect.execute(
                missing.intent, missing.modelAuthority,
                missing.workspace, missing.ref,
                7L, 42L, 8L, Instant.now()));
        verifyNoInteractions(missing.candidates, missing.projects);
    }

    private Fixture fixture(String output) {
        return fixture(output, "a".repeat(64));
    }

    private Fixture fixture(String output, String version) {
        var gateway = mock(ProjectCandidateEffectGateway.class);
        var candidates = mock(CandidateChangeArtifactService.class);
        var provider = mock(ModelProvider.class);
        var projects = mock(ProjectService.class);
        var workspace = mock(WorkspacePort.class);
        var ref = new WorkspaceRef(new WorkspaceId("workspace"),
                new ProjectVersionRef("8", version));
        String authorityJson = "{\"operation\":\"compose\"}";
        when(gateway.require("plan", "project-candidate-compose")).thenReturn(
                new ProjectCandidateEffectAuthority(
                        ProjectCandidateCompositionEffect.KIND, authorityJson,
                        sha(authorityJson), 7L, 8L, 9L, 42L, version,
                        "improve", List.of("README.md")));
        if (output == null) {
            when(provider.complete(any())).thenReturn(new ProviderFailure(
                    ProviderFailureCode.UNAVAILABLE, "provider", Map.of()));
        } else {
            when(provider.complete(any())).thenReturn(new ModelResponse(
                    Optional.of(output), List.of(), FinishReason.STOP,
                    new UsageMetadata(1, 1, 0, Map.of()), Map.of()));
        }
        Map<String, byte[]> files = new HashMap<>();
        files.put("README.md", "old text".getBytes(StandardCharsets.UTF_8));
        when(workspace.read(eq(ref), any())).thenAnswer(call ->
                files.get(call.<ProjectPath>getArgument(1).value()));
        doAnswer(call -> {
            files.put(call.<ProjectPath>getArgument(1).value(), call.getArgument(2));
            return null;
        }).when(workspace).replace(eq(ref), any(), any());
        when(workspace.diff(eq(ref), any(), any())).thenAnswer(call -> {
            byte[] before = "old text".getBytes(StandardCharsets.UTF_8);
            byte[] after = files.get("README.md");
            return new WorkspaceDiff(call.getArgument(1), ref,
                    Arrays.equals(before, after) ? List.of() : List.of(
                    new WorkspaceDiffEntry(DiffKind.MODIFY,
                            new ProjectPath("README.md"), Optional.empty(),
                            Optional.of(new ContentHash("sha256", sha(before))),
                            Optional.of(new ContentHash("sha256", sha(after))), Map.of())),
                    call.getArgument(2));
        });
        AtomicReference<ProjectCandidateEffectGateway.PreparedCandidate> prepared =
                new AtomicReference<>();
        doAnswer(call -> {
            prepared.set(new ProjectCandidateEffectGateway.PreparedCandidate(
                    call.getArgument(1), call.getArgument(2), call.getArgument(3)));
            return null;
        }).when(gateway).bindPrepared(eq("plan"), anyMap(), anyList(), anyString());
        when(gateway.requirePrepared("plan")).thenAnswer(ignored -> prepared.get());
        var effect = new ProjectCandidateCompositionEffect(
                gateway, candidates, provider, projects, json);
        var intent = new PersistedEffectIntent(new EffectIntent(
                new ToolCallId("tool"), new PlanId("plan"),
                new PlanStepId("project-candidate-compose"),
                ProjectCandidateCompositionEffect.KIND,
                new ObjectValue(Map.of("operation", new TextValue("compose")))),
                "owner", 1L, new EventId("activation"));
        var modelAuthority =
                new ProjectCandidateCompositionEffect.ModelAuthority(
                        new TaskFrameId("task-frame"),
                        new PlanId("plan"),
                        new PlanRevisionId("revision"),
                        new PlanStepId("project-candidate-compose"));
        return new Fixture(effect, gateway, candidates, provider, projects,
                workspace, ref, files, intent, modelAuthority);
    }

    private static String sha(String value) {
        return sha(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String sha(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private record Fixture(ProjectCandidateCompositionEffect effect,
            ProjectCandidateEffectGateway gateway,
            CandidateChangeArtifactService candidates, ModelProvider provider,
            ProjectService projects,
            WorkspacePort workspace, WorkspaceRef ref, Map<String, byte[]> files,
            PersistedEffectIntent intent,
            ProjectCandidateCompositionEffect.ModelAuthority modelAuthority) {}
}
