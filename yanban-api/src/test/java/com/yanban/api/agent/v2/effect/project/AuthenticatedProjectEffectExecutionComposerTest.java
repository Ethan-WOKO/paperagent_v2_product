package com.yanban.api.agent.v2.effect.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspaceFileStat;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class AuthenticatedProjectEffectExecutionComposerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AuthenticatedProjectEffectExecutionComposer composer =
            new AuthenticatedProjectEffectExecutionComposer(
                    mock(com.yanban.api.agent.v2
                            .AgentTurnProductContextResolver.class),
                    mock(com.yanban.agent.v2.adapter.bootstrap
                            .ProductPlanIdDerivation.class),
                    mock(io.paperagent.v2.runtime.execution.recovery.composition
                            .StepRecoverer.class),
                    mock(io.paperagent.v2.persistence
                            .EffectIntentRepository.class),
                    mock(com.yanban.api.agent.v2.persistence
                            .ProductEffectExecutionClaimRepository.class),
                    mock(io.paperagent.v2.persistence
                            .PlanExecutionContextRepository.class),
                    mock(com.yanban.api.agent.v2.workspace
                            .AuthenticatedAgentTurnWorkspacePortFactory.class),
                    mock(com.yanban.api.agent.v2.compatibility.project
                            .ProjectAnalysisAuthoritySource.class),
                    json);

    @Test
    void exactReadIsBoundedUtf8AndBinaryOrOversizeFailsClosed() {
        WorkspacePort workspace = mock(WorkspacePort.class);
        WorkspaceRef ref = ref();
        ProjectPath path = new ProjectPath("paper/main.md");
        when(workspace.read(ref, path)).thenReturn(
                "evidence".getBytes(StandardCharsets.UTF_8));
        var arguments = json.createObjectNode().put(
                "path", "paper/main.md");

        String output = composer.read(workspace, ref, arguments);
        assertEquals("path: paper/main.md\ncontent:\nevidence", output);

        byte[] exactLimit = "x".repeat(64 * 1024)
                .getBytes(StandardCharsets.UTF_8);
        when(workspace.read(ref, path)).thenReturn(exactLimit);
        OutputCapture exactLimitCapture = AuthenticatedProjectEffectExecutionComposer
                .capture(composer.read(workspace, ref, arguments));
        assertTrue(exactLimitCapture.truncated());
        assertEquals(OutputCapture.MAX_INLINE_CHARACTERS,
                exactLimitCapture.inlineText().orElseThrow().length());

        when(workspace.read(ref, path)).thenReturn(new byte[]{1});
        assertThrows(IllegalStateException.class,
                () -> composer.read(workspace, ref, arguments));
        when(workspace.read(ref, path)).thenReturn(
                new byte[64 * 1024 + 1]);
        assertThrows(IllegalStateException.class,
                () -> composer.read(workspace, ref, arguments));
        assertThrows(RuntimeException.class, () -> composer.read(
                workspace, ref,
                json.createObjectNode().put("path", "../secret")));
    }

    @Test
    void receiptCapturePreservesSmallOutputAndTruncatesOnUtf16Boundary() {
        String small = "path: paper.md\ncontent:\nevidence";
        OutputCapture complete =
                AuthenticatedProjectEffectExecutionComposer.capture(small);
        assertEquals(small, complete.inlineText().orElseThrow());
        assertFalse(complete.truncated());

        String prefix = "x".repeat(
                OutputCapture.MAX_INLINE_CHARACTERS - 1);
        OutputCapture truncated =
                AuthenticatedProjectEffectExecutionComposer.capture(
                        prefix + "\uD83D\uDE00" + "tail");
        assertTrue(truncated.truncated());
        assertEquals(OutputCapture.MAX_INLINE_CHARACTERS - 1,
                truncated.inlineText().orElseThrow().length());
        assertFalse(Character.isHighSurrogate(truncated.inlineText()
                .orElseThrow().charAt(
                        truncated.inlineText().orElseThrow().length() - 1)));
    }

    @Test
    void literalSearchIsSortedBoundedAndDoesNotReturnNonmatchingContent() {
        WorkspacePort workspace = mock(WorkspacePort.class);
        WorkspaceRef ref = ref();
        ProjectPath first = new ProjectPath("a.md");
        ProjectPath second = new ProjectPath("b.md");
        WorkspaceFileStat firstStat = stat(first);
        WorkspaceFileStat secondStat = stat(second);
        when(workspace.list(ref)).thenReturn(List.of(secondStat, firstStat));
        when(workspace.read(ref, first)).thenReturn(
                "needle alpha".getBytes(StandardCharsets.UTF_8));
        when(workspace.read(ref, second)).thenReturn(
                "unrelated".getBytes(StandardCharsets.UTF_8));
        var arguments = json.createObjectNode()
                .put("maxResults", 1).put("query", "needle");

        String output = composer.search(workspace, ref, arguments);

        assertTrue(output.contains("\"path\":\"a.md\""));
        assertTrue(output.contains("needle alpha"));
        assertFalse(output.contains("b.md"));
        assertThrows(IllegalStateException.class,
                () -> composer.search(workspace, ref,
                        json.createObjectNode()
                                .put("maxResults", 21)
                                .put("query", "needle")));
    }

    @Test
    void projectSearchExecutesThroughClaimAndReturnsSuccessfulReceipt() {
        WorkspacePort workspace = mock(WorkspacePort.class);
        WorkspaceRef ref = ref();
        ProjectPath path = new ProjectPath("paper.md");
        when(workspace.list(ref)).thenReturn(List.of(stat(path)));
        when(workspace.read(ref, path)).thenReturn(
                "needle evidence".getBytes(StandardCharsets.UTF_8));
        var arguments = new io.paperagent.v2.contracts.ObjectValue(Map.of(
                "query", new io.paperagent.v2.contracts.TextValue("needle"),
                "maxResults", new io.paperagent.v2.contracts.NumberValue(
                        java.math.BigDecimal.ONE)));

        var outcome = executeSuccess(
                "project.search", arguments,
                "{\"maxResults\":1,\"query\":\"needle\"}", workspace);

        assertEquals(io.paperagent.v2.contracts.ReceiptStatus.SUCCESS,
                outcome.result().receipt().status());
        assertFalse(outcome.result().receipt().standardOutput().truncated());
        assertTrue(outcome.result().receipt().standardOutput()
                .inlineText().orElseThrow().contains("needle evidence"));
    }

    @Test
    void exact64KiBReadExecutesSuccessfullyWithTruncatedReceipt() {
        WorkspacePort workspace = mock(WorkspacePort.class);
        WorkspaceRef ref = ref();
        ProjectPath path = new ProjectPath("paper.md");
        when(workspace.read(ref, path)).thenReturn(
                "x".repeat(64 * 1024).getBytes(StandardCharsets.UTF_8));
        var arguments = new io.paperagent.v2.contracts.ObjectValue(Map.of(
                "path", new io.paperagent.v2.contracts.TextValue("paper.md")));

        var outcome = executeSuccess(
                "project.read", arguments,
                "{\"path\":\"paper.md\"}", workspace);

        assertEquals(io.paperagent.v2.contracts.ReceiptStatus.SUCCESS,
                outcome.result().receipt().status());
        assertTrue(outcome.result().receipt().standardOutput().truncated());
        assertEquals(OutputCapture.MAX_INLINE_CHARACTERS,
                outcome.result().receipt().standardOutput()
                        .inlineText().orElseThrow().length());
        assertFalse(outcome.result().receipt().standardOutput()
                .inlineText().orElseThrow().contains(rootPath()));
    }

    @Test
    void wrongPersistedAuthorityRejectsBeforeClaimOrWorkspaceRead() {
        var contexts = mock(com.yanban.api.agent.v2
                .AgentTurnProductContextResolver.class);
        var planIds = new com.yanban.agent.v2.adapter.bootstrap
                .ProductPlanIdDerivation();
        var recoverer = mock(io.paperagent.v2.runtime.execution.recovery
                .composition.StepRecoverer.class);
        var intents = mock(io.paperagent.v2.persistence
                .EffectIntentRepository.class);
        var claims = mock(com.yanban.api.agent.v2.persistence
                .ProductEffectExecutionClaimRepository.class);
        var executionContexts = mock(io.paperagent.v2.persistence
                .PlanExecutionContextRepository.class);
        var workspaces = mock(com.yanban.api.agent.v2.workspace
                .AuthenticatedAgentTurnWorkspacePortFactory.class);
        var authorities = mock(com.yanban.api.agent.v2.compatibility.project
                .ProjectAnalysisAuthoritySource.class);
        var identity = new com.yanban.core.agent.AgentRunIdentity(
                "AGENT_TURN", "turn-42", 7L, 9L, 8L);
        var planId = planIds.derive(identity);
        when(contexts.resolve(7L, 42L)).thenReturn(
                new com.yanban.api.agent.v2.VerifiedAgentTurnProductContext(
                        identity, Optional.of("version")));
        var recovery = mock(io.paperagent.v2.persistence
                .PersistedStepRecoveryActive.class);
        var activation = mock(io.paperagent.v2.persistence
                .PersistedStepActivation.class);
        var stepId = new io.paperagent.v2.contracts.PlanStepId(
                "project-read-01");
        var activationEvent = mock(
                io.paperagent.v2.contracts.EventEnvelope.class);
        var activationEventId = new io.paperagent.v2.contracts.EventId(
                "activation");
        when(activation.stepId()).thenReturn(stepId);
        when(activation.activationEvent()).thenReturn(activationEvent);
        when(activationEvent.id()).thenReturn(activationEventId);
        when(recovery.planId()).thenReturn(planId);
        when(recovery.activation()).thenReturn(activation);
        var lease = new io.paperagent.v2.persistence.LeaseRecord(
                planId, "owner", "token", 1L,
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(60));
        var active = new io.paperagent.v2.runtime.execution.recovery
                .composition.RecoveredActiveStep(
                        recovery, lease,
                        io.paperagent.v2.runtime.execution.recovery.composition
                                .StepRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY);
        when(recoverer.recover(
                org.mockito.ArgumentMatchers.any())).thenReturn(active);
        var toolCallId = new io.paperagent.v2.contracts.ToolCallId("tool");
        var intent = new io.paperagent.v2.contracts.EffectIntent(
                toolCallId, planId, stepId, "project.read",
                new io.paperagent.v2.contracts.ObjectValue(Map.of(
                        "path", new io.paperagent.v2.contracts.TextValue(
                                "paper.md"))));
        when(intents.find(toolCallId)).thenReturn(
                io.paperagent.v2.persistence.PersistenceResult.found(
                        new io.paperagent.v2.persistence.PersistedEffectIntent(
                                intent, "owner", 1L, activationEventId)));
        when(authorities.require(planId.value(), stepId.value()))
                .thenReturn(new com.yanban.api.agent.v2.compatibility.project
                        .ProjectAnalysisEffectAuthority(
                                "project.search", "{}", "a".repeat(64)));
        var composer = new AuthenticatedProjectEffectExecutionComposer(
                contexts, planIds, recoverer, intents, claims,
                executionContexts, workspaces, authorities, json);
        var attempt = new io.paperagent.v2.runtime.execution.recovery
                .composition.StepRecoveryLeaseAttempt(
                        "owner", "token", lease.expiresAt());

        assertThrows(IllegalStateException.class, () -> composer.execute(
                7L, 42L, new AuthenticatedProjectEffectExecutionCommand(
                        planId, toolCallId, attempt)));

        verifyNoInteractions(claims, executionContexts, workspaces);
    }

    @Test
    void realWorkspaceRejectsSymlinkWhenSupportedAndNonManifestRead(
            @TempDir Path root) throws Exception {
        byte[] evidence = "evidence".getBytes(StandardCharsets.UTF_8);
        String evidenceHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(evidence));
        var source = new ProjectVersionRef("8", "version");
        var provider = new io.paperagent.v2.workspace.LocalWorkspaceProvider(
                root, requested -> new io.paperagent.v2.workspace
                        .ProjectVersionSnapshot(
                                source,
                                List.of(new io.paperagent.v2.workspace
                                        .ProjectFileSnapshot(
                                                new ProjectPath("paper.md"),
                                                evidence,
                                                new ContentHash(
                                                        "sha256",
                                                        evidenceHash),
                                                Map.of())),
                                Map.of()));
        var spec = new io.paperagent.v2.contracts
                .WorkspaceMaterializationSpec(
                        new WorkspaceId("workspace-real"), source,
                        new io.paperagent.v2.contracts
                                .WorkspaceMaterializationLimits(
                                        8, 128 * 1024, 8));
        var verified = provider.materialize(spec);
        Path container;
        try (var children = Files.list(root)) {
            container = children.findFirst().orElseThrow();
        }
        Path outside = root.resolve("outside.txt");
        Files.writeString(outside, "secret");
        try {
            Files.createSymbolicLink(
                    container.resolve("data").resolve("link.md"), outside);
            assertThrows(RuntimeException.class, () -> composer.read(
                    provider, verified.workspace(),
                    json.createObjectNode().put("path", "link.md")));
        } catch (java.nio.file.FileSystemException unsupported) {
            if (!System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("windows")) {
                throw unsupported;
            }
        }
        assertThrows(RuntimeException.class, () -> composer.read(
                provider, verified.workspace(),
                json.createObjectNode().put("path", "missing.md")));
    }

    private static WorkspaceRef ref() {
        return new WorkspaceRef(
                new WorkspaceId("workspace"),
                new ProjectVersionRef("8", "version"));
    }

    private AuthenticatedProjectEffectExecutionOutcome executeSuccess(
            String kind, io.paperagent.v2.contracts.ObjectValue arguments,
            String canonicalArguments, WorkspacePort workspace) {
        var contexts = mock(com.yanban.api.agent.v2
                .AgentTurnProductContextResolver.class);
        var planIds = new com.yanban.agent.v2.adapter.bootstrap
                .ProductPlanIdDerivation();
        var recoverer = mock(io.paperagent.v2.runtime.execution.recovery
                .composition.StepRecoverer.class);
        var intents = mock(io.paperagent.v2.persistence
                .EffectIntentRepository.class);
        var claims = mock(com.yanban.api.agent.v2.persistence
                .ProductEffectExecutionClaimRepository.class);
        var executionContexts = mock(io.paperagent.v2.persistence
                .PlanExecutionContextRepository.class);
        var workspaces = mock(com.yanban.api.agent.v2.workspace
                .AuthenticatedAgentTurnWorkspacePortFactory.class);
        var authorities = mock(com.yanban.api.agent.v2.compatibility.project
                .ProjectAnalysisAuthoritySource.class);
        var identity = new com.yanban.core.agent.AgentRunIdentity(
                "AGENT_TURN", "turn-42", 7L, 9L, 8L);
        var planId = planIds.derive(identity);
        when(contexts.resolve(7L, 42L)).thenReturn(
                new com.yanban.api.agent.v2.VerifiedAgentTurnProductContext(
                        identity, Optional.of("version")));
        var recovery = mock(io.paperagent.v2.persistence
                .PersistedStepRecoveryActive.class);
        var activation = mock(io.paperagent.v2.persistence
                .PersistedStepActivation.class);
        var event = mock(io.paperagent.v2.contracts.EventEnvelope.class);
        var stepId = new io.paperagent.v2.contracts.PlanStepId("step");
        var activationId = new io.paperagent.v2.contracts.EventId(
                "activation");
        when(recovery.planId()).thenReturn(planId);
        when(recovery.activation()).thenReturn(activation);
        when(activation.stepId()).thenReturn(stepId);
        when(activation.activationEvent()).thenReturn(event);
        when(event.id()).thenReturn(activationId);
        var lease = new io.paperagent.v2.persistence.LeaseRecord(
                planId, "owner", "token", 1L,
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(60));
        var active = new io.paperagent.v2.runtime.execution.recovery
                .composition.RecoveredActiveStep(
                        recovery, lease,
                        io.paperagent.v2.runtime.execution.recovery
                                .composition.StepRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY);
        when(recoverer.recover(
                org.mockito.ArgumentMatchers.any())).thenReturn(active);
        var toolCallId = new io.paperagent.v2.contracts.ToolCallId("tool");
        var persistedIntent = new io.paperagent.v2.persistence
                .PersistedEffectIntent(
                        new io.paperagent.v2.contracts.EffectIntent(
                                toolCallId, planId, stepId, kind, arguments),
                        "owner", 1L, activationId);
        when(intents.find(toolCallId)).thenReturn(
                io.paperagent.v2.persistence.PersistenceResult.found(
                        persistedIntent));
        when(authorities.require(planId.value(), stepId.value())).thenReturn(
                new com.yanban.api.agent.v2.compatibility.project
                        .ProjectAnalysisEffectAuthority(
                                kind, canonicalArguments,
                                sha256(canonicalArguments)));
        var confirmed = mock(io.paperagent.v2.persistence
                .PersistedPlanExecutionContextConfirmed.class);
        var spec = mock(io.paperagent.v2.contracts
                .WorkspaceMaterializationSpec.class);
        when(confirmed.materializationSpec()).thenReturn(spec);
        when(executionContexts.inspect(planId)).thenReturn(
                io.paperagent.v2.persistence.PersistenceResult.found(
                        confirmed));
        var verified = mock(io.paperagent.v2.workspace
                .VerifiedWorkspaceMaterialization.class);
        when(verified.workspace()).thenReturn(ref());
        when(workspace.inspectMaterialization(spec)).thenReturn(verified);
        when(workspaces.create(7L, 42L)).thenReturn(workspace);
        when(claims.execute(
                org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                    var request = (com.yanban.api.agent.v2.persistence
                            .ProductEffectExecutionClaimRequest)
                            call.getArgument(0);
                    var receipt = request.execution().get();
                    return new com.yanban.api.agent.v2.persistence
                            .ProductEffectExecutionClaimResult(
                                    new io.paperagent.v2.persistence
                                            .PersistedEffectResult(
                                                    receipt, "owner", 1L),
                                    false);
                });
        var target = new AuthenticatedProjectEffectExecutionComposer(
                contexts, planIds, recoverer, intents, claims,
                executionContexts, workspaces, authorities, json);
        return target.execute(
                7L, 42L, new AuthenticatedProjectEffectExecutionCommand(
                        planId, toolCallId,
                        new io.paperagent.v2.runtime.execution.recovery
                                .composition.StepRecoveryLeaseAttempt(
                                        "owner", "token",
                                        lease.expiresAt())));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String rootPath() {
        return Path.of("").toAbsolutePath().toString();
    }

    private static WorkspaceFileStat stat(ProjectPath path) {
        return new WorkspaceFileStat(
                path, 10,
                new ContentHash("sha256", "a".repeat(64)));
    }
}
