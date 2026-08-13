package com.yanban.api.agent.v2.effect.project;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.v2.chain.effect.ProjectCandidateEffectAuthority;
import com.yanban.api.project.ProjectFileResponse;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.api.project.ProjectStorageProperties;
import com.yanban.core.agent.sandbox.CandidateFingerprint;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.DiffKind;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.WorkspaceDiff;
import io.paperagent.v2.contracts.WorkspaceDiffEntry;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectCandidateCompositionEffectTest {
    private static final String VERSION = "a".repeat(64);
    private static final String AUTHORITY_JSON =
            "{\"operation\":\"compose\",\"paths\":[\"README.md\"],"
                    + "\"replacements\":[{\"path\":\"README.md\","
                    + "\"text\":\"new text\"}]}";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void chainExecutionUsesExactFormalReplacementWithoutLegacyStore() {
        Fixture fixture = fixture("old text", "new text");

        ProjectCandidateCompositionEffect.CandidateResult result =
                fixture.effect.executeChain(
                        fixture.intent, fixture.authority, chainAuthority(),
                        fixture.workspace, fixture.ref,
                        7L, 42L, 8L, Instant.EPOCH);

        assertArrayEquals("new text".getBytes(StandardCharsets.UTF_8),
                fixture.files.get("README.md"));
        assertEquals(64, result.diffFingerprint().length());
        verifyNoInteractions(fixture.store);
        verify(fixture.provider, never()).complete(any());
    }

    @Test
    void chainExecutionAcceptsReplacementAboveFormerLocalLimit() {
        String replacement = "x".repeat(65 * 1024);
        Fixture fixture = fixture("old text", replacement);

        fixture.effect.executeChain(
                fixture.intent, fixture.authority,
                chainAuthority(
                        "README.md", replacement, "tool",
                        "NONE", null, Map.of()),
                fixture.workspace, fixture.ref,
                7L, 42L, 8L, Instant.EPOCH);

        assertArrayEquals(replacement.getBytes(StandardCharsets.UTF_8),
                fixture.files.get("README.md"));
    }

    @Test
    void chainExecutionRejectsNoProgressAndRestoresWorkspace() {
        Fixture fixture = fixture("new text", "new text");

        ProjectCandidateCompositionEffect.CandidateCompositionException failure =
                assertThrows(
                        ProjectCandidateCompositionEffect
                                .CandidateCompositionException.class, () ->
                                fixture.effect.executeChain(
                                        fixture.intent, fixture.authority,
                                        chainAuthority(), fixture.workspace,
                                        fixture.ref, 7L, 42L, 8L,
                                        Instant.EPOCH));
        assertEquals(
                ProjectCandidateCompositionEffect.CandidateCompositionException
                        .NO_ACTUAL_CHANGE,
                failure.code());

        assertArrayEquals("new text".getBytes(StandardCharsets.UTF_8),
                fixture.files.get("README.md"));
        verify(fixture.store, never()).bindPrepared(
                anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void consecutiveChainActionsUseOnlyTheirRebasedActionDiff() {
        Fixture first = fixture(
                Map.of("README.md", "old text"),
                "README.md", "first text", "action-1");
        Fixture sameFile = fixture(
                Map.of("README.md", "first text"),
                "README.md", "second text", "action-2");
        Fixture differentFile = fixture(
                Map.of("README.md", "first text", "notes.md", "old note"),
                "notes.md", "new note", "action-3");

        first.effect.executeChain(
                first.intent, first.authority,
                chainAuthority(
                        "README.md", "first text", "action-1",
                        "NONE", null, Map.of()),
                first.workspace, first.ref,
                7L, 42L, 8L, Instant.EPOCH);
        sameFile.effect.executeChain(
                sameFile.intent, sameFile.authority,
                chainAuthority(
                        "README.md", "second text", "action-2",
                        "b".repeat(64), 51L,
                        Map.of("README.md", "first text")),
                sameFile.workspace, sameFile.ref,
                7L, 42L, 8L, Instant.EPOCH);
        differentFile.effect.executeChain(
                differentFile.intent, differentFile.authority,
                chainAuthority(
                        "notes.md", "new note", "action-3",
                        "c".repeat(64), 52L,
                        Map.of("README.md", "first text")),
                differentFile.workspace, differentFile.ref,
                7L, 42L, 8L, Instant.EPOCH);

        assertArrayEquals("second text".getBytes(StandardCharsets.UTF_8),
                sameFile.files.get("README.md"));
        assertArrayEquals("first text".getBytes(StandardCharsets.UTF_8),
                differentFile.files.get("README.md"));
        assertArrayEquals("new note".getBytes(StandardCharsets.UTF_8),
                differentFile.files.get("notes.md"));
    }

    @Test
    void publishUsesPreparedFullTextAndBindsCandidateIdentity() {
        Fixture fixture = fixture("old text", "new text");
        String expectedDiff = sha(VERSION + '\0' + DiffKind.MODIFY
                + '\0' + "README.md" + '\0' + sha("old text")
                + '\0' + sha("new text"));
        when(fixture.store.candidateArtifactId("plan"))
                .thenReturn(Optional.empty());
        when(fixture.store.require("plan")).thenReturn(authority());
        when(fixture.store.requirePrepared("plan")).thenReturn(
                new NaturalLanguageCandidateAuthorityStore.Prepared(
                        "step", Map.of("README.md", "new text"),
                        expectedDiff));
        when(fixture.projects.manifest(7L, 8L)).thenReturn(
                new ProjectManifestResponse(8L, VERSION, List.of()));
        when(fixture.projects.readFile(7L, 8L, "README.md"))
                .thenReturn(new ProjectFileResponse(
                        "README.md", "old text", 8L, Instant.EPOCH,
                        sha("old text")));
        CandidateArtifactResponse candidate = mock(
                CandidateArtifactResponse.class);
        when(candidate.artifactId()).thenReturn(55L);
        when(candidate.fingerprint()).thenReturn(
                new CandidateFingerprint("b".repeat(64)));
        when(fixture.candidates.store(
                eq(7L), eq(9L), any(), any(), any()))
                .thenReturn(candidate);

        ProjectCandidateCompositionEffect.CandidateResult result =
                fixture.effect.publishNatural(
                        "plan", 7L, 42L, fixture.store);

        assertEquals(55L, result.artifactId());
        assertEquals("b".repeat(64), result.candidateFingerprint());
        assertEquals(expectedDiff, result.diffFingerprint());
        verify(fixture.store).bindCandidate(
                "plan", 55L, "b".repeat(64), expectedDiff);
    }

    @Test
    void staleProjectVersionRejectsBeforeCandidateStore() {
        Fixture fixture = fixture("old text", "new text");
        when(fixture.store.candidateArtifactId("plan"))
                .thenReturn(Optional.empty());
        when(fixture.store.require("plan")).thenReturn(authority());
        when(fixture.projects.manifest(7L, 8L)).thenReturn(
                new ProjectManifestResponse(
                        8L, "c".repeat(64), List.of()));

        assertThrows(IllegalStateException.class, () ->
                fixture.effect.publishNatural(
                        "plan", 7L, 42L, fixture.store));

        verify(fixture.candidates, never()).store(
                anyLong(), anyLong(), any(), any(), any());
    }

    private Fixture fixture(String original, String replacement) {
        return fixture(
                Map.of("README.md", original),
                "README.md", replacement, "tool");
    }

    private Fixture fixture(
            Map<String, String> baseline,
            String targetPath,
            String replacement,
            String actionId) {
        NaturalLanguageCandidateAuthorityStore store = mock(
                NaturalLanguageCandidateAuthorityStore.class);
        CandidateChangeArtifactService candidates = mock(
                CandidateChangeArtifactService.class);
        ModelProvider provider = mock(ModelProvider.class);
        ProjectService projects = mock(ProjectService.class);
        WorkspacePort workspace = mock(WorkspacePort.class);
        WorkspaceRef ref = new WorkspaceRef(
                new WorkspaceId("workspace"),
                new ProjectVersionRef("8", VERSION));
        Map<String, byte[]> files = new LinkedHashMap<>();
        baseline.forEach((path, text) -> files.put(
                path, text.getBytes(StandardCharsets.UTF_8)));
        String original = baseline.get(targetPath);
        when(store.require("plan", "step")).thenReturn(authority());
        when(workspace.read(eq(ref), any())).thenAnswer(call ->
                files.get(call.<ProjectPath>getArgument(1).value()));
        org.mockito.Mockito.doAnswer(call -> {
            files.put(call.<ProjectPath>getArgument(1).value(),
                    call.getArgument(2));
            return null;
        }).when(workspace).replace(eq(ref), any(), any());
        when(workspace.diff(eq(ref), any(), any())).thenAnswer(call -> {
            List<WorkspaceDiffEntry> entries = new ArrayList<>();
            entries.add(new WorkspaceDiffEntry(
                    DiffKind.MODIFY, new ProjectPath(targetPath),
                    Optional.empty(),
                    Optional.of(new ContentHash("sha256", sha(original))),
                    Optional.of(new ContentHash("sha256", sha(replacement))),
                    Map.of()));
            return new WorkspaceDiff(
                    call.<DiffId>getArgument(1), ref, entries,
                    call.getArgument(2));
        });
        ProjectCandidateCompositionEffect effect =
                new ProjectCandidateCompositionEffect(
                        store, candidates, provider, projects, json,
                        new ProjectStorageProperties());
        PersistedEffectIntent intent = new PersistedEffectIntent(
                new EffectIntent(
                        new ToolCallId(actionId), new PlanId("plan"),
                        new PlanStepId("step"),
                        ProjectCandidateCompositionEffect.KIND,
                        new ObjectValue(Map.of(
                                "operation", new TextValue("compose"),
                                "paths", new ListValue(List.of(
                                new TextValue(targetPath))),
                                "replacements", new ListValue(List.of(
                                new ObjectValue(Map.of(
                                        "path", new TextValue(targetPath),
                                        "text", new TextValue(replacement)))))))),
                "owner", 1L, new EventId("activation"));
        ProjectCandidateCompositionEffect.ModelAuthority modelAuthority =
                new ProjectCandidateCompositionEffect.ModelAuthority(
                        new TaskFrameId("task-frame"), new PlanId("plan"),
                        new PlanRevisionId("revision"),
                        new PlanStepId("step"));
        return new Fixture(
                effect, intent, modelAuthority, workspace, ref, files,
                store, candidates, provider, projects);
    }

    private static ProjectCandidateEffectAuthority authority() {
        return new ProjectCandidateEffectAuthority(
                ProjectCandidateCompositionEffect.KIND,
                AUTHORITY_JSON, sha(AUTHORITY_JSON),
                7L, 8L, 9L, 42L, VERSION,
                "improve", List.of("README.md"));
    }

    private static ProjectCandidateEffectAuthority chainAuthority() {
        return chainAuthority(
                "README.md", "new text", "tool",
                "NONE", null, Map.of());
    }

    private static ProjectCandidateEffectAuthority chainAuthority(
            String path, String replacement, String actionId,
            String candidateIdentity, Long artifactId,
            Map<String, String> overlay) {
        String authorityJson = "{\"operation\":\"compose\",\"paths\":[\""
                + path + "\"],\"replacements\":[{\"path\":\"" + path
                + "\",\"text\":\"" + replacement + "\"}]}";
        return new ProjectCandidateEffectAuthority(
                ProjectCandidateCompositionEffect.KIND,
                authorityJson, sha(authorityJson),
                7L, 8L, 9L, 42L, VERSION,
                "improve", List.of(path), null,
                new com.yanban.api.agent.v2.chain.effect
                        .ChainActionWorkspaceAuthority(
                        actionId, "f".repeat(64), "workspace",
                        List.of(path), List.of(path),
                        new com.yanban.api.agent.v2.chain.effect
                                .ChainActionWorkspaceAuthority
                                .BaseCandidateAuthority(
                                candidateIdentity, VERSION, artifactId,
                                overlay.entrySet().stream().map(entry ->
                                        new com.yanban.api.agent.v2.chain.effect
                                                .ChainActionWorkspaceAuthority
                                                .TypedChange(
                                                com.yanban.api.agent.v2.chain.effect
                                                        .ChainActionWorkspaceAuthority
                                                        .ChangeType.MODIFY,
                                                entry.getKey(),
                                                "a".repeat(64),
                                                sha(entry.getValue()),
                                                entry.getValue()))
                                        .toList())));
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record Fixture(
            ProjectCandidateCompositionEffect effect,
            PersistedEffectIntent intent,
            ProjectCandidateCompositionEffect.ModelAuthority authority,
            WorkspacePort workspace,
            WorkspaceRef ref,
            Map<String, byte[]> files,
            NaturalLanguageCandidateAuthorityStore store,
            CandidateChangeArtifactService candidates,
            ModelProvider provider,
            ProjectService projects) {
    }
}
