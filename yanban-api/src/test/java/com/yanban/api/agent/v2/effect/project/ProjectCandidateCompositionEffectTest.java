package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.v2.compatibility.project.*;
import com.yanban.api.project.ProjectService;
import io.paperagent.v2.contracts.*;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.providers.*;
import io.paperagent.v2.workspace.WorkspacePort;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectCandidateCompositionEffectTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exactFrozenReplacementMutatesOnlyWorkspaceAndProducesStableModifyDiff() {
        Fixture fixture = fixture("{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new text\"}]}");

        var result = fixture.effect.execute(fixture.intent, fixture.workspace,
                fixture.ref, 7L, 42L, 8L, Instant.parse("2026-01-01T00:00:00Z"));

        assertNull(result.artifactId());
        assertEquals(64, result.diffFingerprint().length());
        assertArrayEquals("new text".getBytes(StandardCharsets.UTF_8),
                fixture.files.get("README.md"));
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
                    fixture.intent, fixture.workspace, fixture.ref,
                    7L, 42L, 8L, Instant.now()));
            assertArrayEquals("old text".getBytes(StandardCharsets.UTF_8),
                    fixture.files.get("README.md"));
        }
    }

    @Test
    void providerFailureAndCrossBoundAuthorityFailBeforeCandidatePublication() {
        Fixture providerFailure = fixture(null);
        assertThrows(IllegalStateException.class, () -> providerFailure.effect.execute(
                providerFailure.intent, providerFailure.workspace, providerFailure.ref,
                7L, 42L, 8L, Instant.now()));
        Fixture crossUser = fixture("{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new\"}]}");
        assertThrows(IllegalStateException.class, () -> crossUser.effect.execute(
                crossUser.intent, crossUser.workspace, crossUser.ref,
                99L, 42L, 8L, Instant.now()));
        verifyNoInteractions(providerFailure.candidates, crossUser.candidates);
    }

    @Test
    void terminalPublicationUsesExistingCandidatePipelineAndBindsExactFingerprints() {
        Fixture fixture = fixture(
                "{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new text\"}]}");
        fixture.effect.execute(fixture.intent, fixture.workspace, fixture.ref,
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

        var published = fixture.effect.publish("plan", 7L, 42L,
                fixture.workspace, fixture.ref, Instant.parse("2026-01-01T00:00:01Z"));

        assertEquals(42L, published.artifactId());
        verify(fixture.gateway).bindCandidate(
                eq("plan"), eq(42L), eq("c".repeat(64)), eq(published.diffFingerprint()));
        verify(fixture.projects, never()).delete(anyLong(), anyLong());
    }

    @Test
    void staleVersionAndCandidatePersistenceFailurePublishNothing() {
        Fixture stale = fixture(
                "{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new text\"}]}");
        stale.effect.execute(stale.intent, stale.workspace, stale.ref,
                7L, 42L, 8L, Instant.parse("2026-01-01T00:00:00Z"));
        when(stale.projects.manifest(7L, 8L)).thenReturn(
                new com.yanban.api.project.ProjectManifestResponse(
                        8L, "stale-version", List.of()));
        assertThrows(IllegalStateException.class, () -> stale.effect.publish(
                "plan", 7L, 42L, stale.workspace, stale.ref, Instant.now()));
        verifyNoInteractions(stale.candidates);
        verify(stale.gateway, never()).bindCandidate(anyString(), anyLong(), anyString(), anyString());

        Fixture persistenceFailure = fixture(
                "{\"replacements\":[{\"path\":\"README.md\",\"text\":\"new text\"}]}");
        persistenceFailure.effect.execute(persistenceFailure.intent,
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
                "plan", 7L, 42L, persistenceFailure.workspace,
                persistenceFailure.ref, Instant.now()));
        verify(persistenceFailure.gateway, never()).bindCandidate(
                anyString(), anyLong(), anyString(), anyString());
        verify(persistenceFailure.projects, never()).delete(anyLong(), anyLong());
    }

    private Fixture fixture(String output) {
        var gateway = mock(ProjectCandidateEffectGateway.class);
        var candidates = mock(CandidateChangeArtifactService.class);
        var provider = mock(ModelProvider.class);
        var projects = mock(ProjectService.class);
        var workspace = mock(WorkspacePort.class);
        var ref = new WorkspaceRef(new WorkspaceId("workspace"),
                new ProjectVersionRef("8", "a".repeat(64)));
        String authorityJson = "{\"operation\":\"compose\"}";
        when(gateway.require("plan", "project-candidate-compose")).thenReturn(
                new ProjectCandidateEffectAuthority(
                        ProjectCandidateCompositionEffect.KIND, authorityJson,
                        sha(authorityJson), 7L, 8L, 9L, 42L, "a".repeat(64),
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
        var effect = new ProjectCandidateCompositionEffect(
                gateway, candidates, provider, projects, json);
        var intent = new PersistedEffectIntent(new EffectIntent(
                new ToolCallId("tool"), new PlanId("plan"),
                new PlanStepId("project-candidate-compose"),
                ProjectCandidateCompositionEffect.KIND,
                new ObjectValue(Map.of("operation", new TextValue("compose")))),
                "owner", 1L, new EventId("activation"));
        return new Fixture(effect, gateway, candidates, projects, workspace, ref, files, intent);
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
            CandidateChangeArtifactService candidates, ProjectService projects,
            WorkspacePort workspace, WorkspaceRef ref, Map<String, byte[]> files,
            PersistedEffectIntent intent) {}
}
