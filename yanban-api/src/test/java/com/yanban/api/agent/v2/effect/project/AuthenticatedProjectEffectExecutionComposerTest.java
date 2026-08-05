package com.yanban.api.agent.v2.effect.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspaceErrorCode;
import io.paperagent.v2.workspace.WorkspaceException;
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
        assertFalse(exactLimitCapture.truncated());
        assertEquals("path: paper/main.md\ncontent:\n".length()
                        + exactLimit.length,
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
    void bibtexAuditExecutesThroughFrozenWorkspaceClaimAndReturnsReceipt() {
        WorkspacePort workspace = mock(WorkspacePort.class);
        ProjectPath bib = new ProjectPath("paper/references.bib");
        ProjectPath tex = new ProjectPath("paper/main.tex");
        when(workspace.read(ref(), bib)).thenReturn("""
                @article{used,
                  author = {Ada Author},
                  title = {A Result},
                  year = {2026}
                }
                """.getBytes(StandardCharsets.UTF_8));
        when(workspace.read(ref(), tex)).thenReturn(
                "See \\cite{used,missing}.".getBytes(StandardCharsets.UTF_8));
        var arguments = new io.paperagent.v2.contracts.ObjectValue(Map.of(
                "paths", new io.paperagent.v2.contracts.ListValue(List.of(
                        new io.paperagent.v2.contracts.TextValue(
                                "paper/references.bib"),
                        new io.paperagent.v2.contracts.TextValue(
                                "paper/main.tex"))),
                "includeUnusedEntries",
                new io.paperagent.v2.contracts.BooleanValue(true)));

        var outcome = executeSuccess(
                "project.bibtex.audit", arguments,
                "{\"includeUnusedEntries\":true,\"paths\":["
                        + "\"paper/references.bib\",\"paper/main.tex\"]}",
                workspace);

        var receipt = outcome.result().receipt();
        assertEquals(io.paperagent.v2.contracts.ReceiptStatus.SUCCESS,
                receipt.status());
        assertFalse(receipt.standardOutput().truncated());
        String output = receipt.standardOutput().inlineText().orElseThrow();
        assertTrue(output.contains("\"tool\":\"project.bibtex.audit\""));
        assertTrue(output.contains("\"code\":\"MISSING_CITATION_KEY\""));
        assertTrue(output.contains("\"citationKey\":\"missing\""));
        assertFalse(output.contains(rootPath()));
    }

    @Test
    void readOnlyAnalysisBundleDispatchesThroughFrozenWorkspaceClaims()
            throws Exception {
        WorkspacePort latexWorkspace = mock(WorkspacePort.class);
        when(latexWorkspace.read(ref(), new ProjectPath("paper/main.tex")))
                .thenReturn("\\section{Method}".getBytes(
                        StandardCharsets.UTF_8));
        assertSuccessfulToolReceipt(
                "project.latex.outline",
                new io.paperagent.v2.contracts.ObjectValue(Map.of(
                        "relativePaths",
                        new io.paperagent.v2.contracts.ListValue(List.of(
                                new io.paperagent.v2.contracts.TextValue(
                                        "paper/main.tex"))))),
                "{\"relativePaths\":[\"paper/main.tex\"]}",
                latexWorkspace);

        assertSuccessfulToolReceipt(
                "project.latex.crossref.audit",
                new io.paperagent.v2.contracts.ObjectValue(Map.of(
                        "relativePaths",
                        new io.paperagent.v2.contracts.ListValue(List.of(
                                new io.paperagent.v2.contracts.TextValue(
                                        "paper/main.tex"))))),
                "{\"relativePaths\":[\"paper/main.tex\"]}",
                latexWorkspace);
        assertSuccessfulToolReceipt(
                "project.latex.float.audit",
                new io.paperagent.v2.contracts.ObjectValue(Map.of(
                        "relativePaths",
                        new io.paperagent.v2.contracts.ListValue(List.of(
                                new io.paperagent.v2.contracts.TextValue(
                                        "paper/main.tex"))),
                        "checkAssetExistence",
                        new io.paperagent.v2.contracts.BooleanValue(false))),
                "{\"checkAssetExistence\":false,\"relativePaths\":["
                        + "\"paper/main.tex\"]}",
                latexWorkspace);
        assertSuccessfulToolReceipt(
                "project.latex.protected.inventory",
                new io.paperagent.v2.contracts.ObjectValue(Map.of(
                        "relativePaths",
                        new io.paperagent.v2.contracts.ListValue(List.of(
                                new io.paperagent.v2.contracts.TextValue(
                                        "paper/main.tex"))))),
                "{\"relativePaths\":[\"paper/main.tex\"]}",
                latexWorkspace);
        assertSuccessfulToolReceipt(
                "project.paper.acronym.audit",
                new io.paperagent.v2.contracts.ObjectValue(Map.of(
                        "relativePaths",
                        new io.paperagent.v2.contracts.ListValue(List.of(
                                new io.paperagent.v2.contracts.TextValue(
                                        "paper/main.tex"))))),
                "{\"relativePaths\":[\"paper/main.tex\"]}",
                latexWorkspace);
        assertSuccessfulToolReceipt(
                "project.paper.language.stats",
                new io.paperagent.v2.contracts.ObjectValue(Map.of(
                        "relativePaths",
                        new io.paperagent.v2.contracts.ListValue(List.of(
                                new io.paperagent.v2.contracts.TextValue(
                                        "paper/main.tex"))))),
                "{\"relativePaths\":[\"paper/main.tex\"]}",
                latexWorkspace);

        WorkspacePort codeWorkspace = mock(WorkspacePort.class);
        when(codeWorkspace.read(ref(), new ProjectPath("src/Main.java")))
                .thenReturn("class Main {}".getBytes(StandardCharsets.UTF_8));
        assertSuccessfulToolReceipt(
                "project.code.symbols",
                new io.paperagent.v2.contracts.ObjectValue(Map.of(
                        "relativePaths",
                        new io.paperagent.v2.contracts.ListValue(List.of(
                                new io.paperagent.v2.contracts.TextValue(
                                        "src/Main.java"))))),
                "{\"relativePaths\":[\"src/Main.java\"]}",
                codeWorkspace);

        WorkspacePort experimentWorkspace = mock(WorkspacePort.class);
        when(experimentWorkspace.read(
                ref(), new ProjectPath("results/metrics.csv")))
                .thenReturn("epoch,accuracy\n1,0.9".getBytes(
                        StandardCharsets.UTF_8));
        assertSuccessfulToolReceipt(
                "project.experiment.summary",
                new io.paperagent.v2.contracts.ObjectValue(Map.of(
                        "relativePaths",
                        new io.paperagent.v2.contracts.ListValue(List.of(
                                new io.paperagent.v2.contracts.TextValue(
                                        "results/metrics.csv"))))),
                "{\"relativePaths\":[\"results/metrics.csv\"]}",
                experimentWorkspace);

        WorkspacePort crossWorkspace = mock(WorkspacePort.class);
        when(crossWorkspace.read(ref(), new ProjectPath("paper/main.tex")))
                .thenReturn("accuracy".getBytes(StandardCharsets.UTF_8));
        when(crossWorkspace.read(
                ref(), new ProjectPath("results/metrics.csv")))
                .thenReturn("accuracy,0.9".getBytes(StandardCharsets.UTF_8));
        assertSuccessfulToolReceipt(
                "project.cross-material.search",
                new io.paperagent.v2.contracts.ObjectValue(Map.of(
                        "query", new io.paperagent.v2.contracts.TextValue(
                                "accuracy"),
                        "relativePaths",
                        new io.paperagent.v2.contracts.ListValue(List.of(
                                new io.paperagent.v2.contracts.TextValue(
                                        "paper/main.tex"),
                                new io.paperagent.v2.contracts.TextValue(
                                        "results/metrics.csv"))))),
                "{\"query\":\"accuracy\",\"relativePaths\":["
                        + "\"paper/main.tex\",\"results/metrics.csv\"]}",
                crossWorkspace);

        WorkspacePort documentWorkspace = mock(WorkspacePort.class);
        when(documentWorkspace.read(
                ref(), new ProjectPath("paper/report.docx")))
                .thenReturn(V2ProjectBinaryAssetFixtures.docx(
                        "A bounded document observation."));
        assertSuccessfulToolReceipt(
                "project.document.extract",
                new io.paperagent.v2.contracts.ObjectValue(Map.of(
                        "path", new io.paperagent.v2.contracts.TextValue(
                                "paper/report.docx"))),
                "{\"path\":\"paper/report.docx\"}",
                documentWorkspace);

        WorkspacePort spreadsheetWorkspace = mock(WorkspacePort.class);
        when(spreadsheetWorkspace.read(
                ref(), new ProjectPath("results/metrics.xlsx")))
                .thenReturn(V2ProjectBinaryAssetFixtures.xlsx());
        assertSuccessfulToolReceipt(
                "project.spreadsheet.inspect",
                new io.paperagent.v2.contracts.ObjectValue(Map.of(
                        "path", new io.paperagent.v2.contracts.TextValue(
                                "results/metrics.xlsx"))),
                "{\"path\":\"results/metrics.xlsx\"}",
                spreadsheetWorkspace);
    }

    @Test
    void readOnlyAnalysisBundleReturnsToolSpecificFailureReceipts() {
        WorkspacePort workspace = mock(WorkspacePort.class);
        var emptyPaths = new io.paperagent.v2.contracts.ObjectValue(Map.of(
                "relativePaths",
                new io.paperagent.v2.contracts.ListValue(List.of())));
        assertFailureReceipt("project.latex.outline", emptyPaths,
                "{\"relativePaths\":[]}",
                "PROJECT_LATEX_OUTLINE_FAILED", workspace);
        assertFailureReceipt("project.latex.crossref.audit", emptyPaths,
                "{\"relativePaths\":[]}",
                "PROJECT_LATEX_CROSSREF_AUDIT_FAILED", workspace);
        assertFailureReceipt("project.latex.float.audit", emptyPaths,
                "{\"relativePaths\":[]}",
                "PROJECT_LATEX_FLOAT_AUDIT_FAILED", workspace);
        assertFailureReceipt("project.latex.protected.inventory", emptyPaths,
                "{\"relativePaths\":[]}",
                "PROJECT_LATEX_PROTECTED_INVENTORY_FAILED", workspace);
        assertFailureReceipt("project.paper.acronym.audit", emptyPaths,
                "{\"relativePaths\":[]}",
                "PROJECT_PAPER_ACRONYM_AUDIT_FAILED", workspace);
        assertFailureReceipt("project.paper.language.stats", emptyPaths,
                "{\"relativePaths\":[]}",
                "PROJECT_PAPER_LANGUAGE_STATS_FAILED", workspace);
        assertFailureReceipt("project.code.symbols", emptyPaths,
                "{\"relativePaths\":[]}",
                "PROJECT_CODE_SYMBOLS_FAILED", workspace);
        assertFailureReceipt("project.experiment.summary", emptyPaths,
                "{\"relativePaths\":[]}",
                "PROJECT_EXPERIMENT_SUMMARY_FAILED", workspace);
        var emptyQuery = new io.paperagent.v2.contracts.ObjectValue(Map.of(
                "query", new io.paperagent.v2.contracts.TextValue("")));
        assertFailureReceipt("project.cross-material.search", emptyQuery,
                "{\"query\":\"\"}",
                "PROJECT_CROSS_MATERIAL_SEARCH_FAILED", workspace);
        var textPath = new io.paperagent.v2.contracts.ObjectValue(Map.of(
                "path", new io.paperagent.v2.contracts.TextValue(
                        "paper/report.txt")));
        assertFailureReceipt("project.document.extract", textPath,
                "{\"path\":\"paper/report.txt\"}",
                "PROJECT_DOCUMENT_EXTRACT_FAILED", workspace);
        var csvPath = new io.paperagent.v2.contracts.ObjectValue(Map.of(
                "path", new io.paperagent.v2.contracts.TextValue(
                        "results/metrics.csv")));
        assertFailureReceipt("project.spreadsheet.inspect", csvPath,
                "{\"path\":\"results/metrics.csv\"}",
                "PROJECT_SPREADSHEET_INSPECT_FAILED", workspace);
        var missingPdf = new io.paperagent.v2.contracts.ObjectValue(Map.of(
                "path", new io.paperagent.v2.contracts.TextValue(
                        "paper/missing.pdf")));
        assertFailureReceipt("project.document.extract", missingPdf,
                "{\"path\":\"paper/missing.pdf\"}",
                "PROJECT_DOCUMENT_EXTRACT_FAILED", workspace);
        var missingWorkbook = new io.paperagent.v2.contracts.ObjectValue(
                Map.of("path", new io.paperagent.v2.contracts.TextValue(
                        "results/missing.xlsx")));
        assertFailureReceipt("project.spreadsheet.inspect", missingWorkbook,
                "{\"path\":\"results/missing.xlsx\"}",
                "PROJECT_SPREADSHEET_INSPECT_FAILED", workspace);
    }

    @Test
    void replayedBinaryToolClaimDoesNotParseWorkspaceAgain() {
        WorkspacePort workspace = mock(WorkspacePort.class);
        var arguments = new io.paperagent.v2.contracts.ObjectValue(Map.of(
                "path", new io.paperagent.v2.contracts.TextValue(
                        "paper/report.pdf")));

        var outcome = execute(
                "project.document.extract", arguments,
                "{\"path\":\"paper/report.pdf\"}", workspace, true);

        assertTrue(outcome.replayed());
        assertEquals(io.paperagent.v2.contracts.ReceiptStatus.SUCCESS,
                outcome.result().receipt().status());
        verify(workspace, never()).read(
                ref(), new ProjectPath("paper/report.pdf"));
    }

    @Test
    void malformedBibtexReturnsSanitizedToolSpecificFailureReceipt() {
        WorkspacePort workspace = mock(WorkspacePort.class);
        ProjectPath bib = new ProjectPath("paper/references.bib");
        when(workspace.read(ref(), bib)).thenReturn(
                "@article{unfinished,".getBytes(StandardCharsets.UTF_8));
        var arguments = new io.paperagent.v2.contracts.ObjectValue(Map.of(
                "paths", new io.paperagent.v2.contracts.ListValue(List.of(
                        new io.paperagent.v2.contracts.TextValue(
                                "paper/references.bib")))));

        var outcome = executeSuccess(
                "project.bibtex.audit", arguments,
                "{\"paths\":[\"paper/references.bib\"]}", workspace);

        var receipt = outcome.result().receipt();
        assertEquals(io.paperagent.v2.contracts.ReceiptStatus.FAILURE,
                receipt.status());
        assertEquals("PROJECT_BIBTEX_AUDIT_FAILED",
                receipt.resultCode().orElseThrow());
        assertEquals("Project BibTeX audit failed",
                receipt.standardError().inlineText().orElseThrow());
        assertTrue(receipt.standardOutput().inlineText().isEmpty());
    }

    @Test
    void exact64KiBReadExecutesSuccessfullyWithCompleteReceipt() {
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
        assertFalse(outcome.result().receipt().standardOutput().truncated());
        assertEquals("path: paper.md\ncontent:\n".length() + 64 * 1024,
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

    @Test
    void candidateAuthorityDispatchesExactCompositionAndBindsReceiptOutput() {
        String arguments = "{\"operation\":\"compose\"}";
        var fixture = candidateFixture(
                new com.yanban.api.agent.v2.compatibility.project
                        .ProjectCandidateEffectAuthority(
                                ProjectCandidateCompositionEffect.KIND,
                                arguments, sha256(arguments),
                                7L, 8L, 9L, 42L, "version",
                                "improve", List.of("paper.md")));

        var outcome = fixture.composer.execute(
                7L, 42L, fixture.command);

        assertEquals(io.paperagent.v2.contracts.ReceiptStatus.SUCCESS,
                outcome.result().receipt().status());
        assertTrue(outcome.result().receipt().standardOutput()
                .inlineText().orElseThrow()
                .contains("\"diffFingerprint\":\"" + "d".repeat(64) + "\""));
        verify(fixture.composition).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.argThat(authority ->
                        authority.taskFrameId().equals(
                                new io.paperagent.v2.contracts.TaskFrameId(
                                        "candidate-task-frame"))
                                && authority.planId().equals(
                                        fixture.command.planId())
                                && authority.planRevisionId().equals(
                                        new io.paperagent.v2.contracts
                                                .PlanRevisionId(
                                                        "candidate-revision"))
                                && authority.stepId().equals(
                                        new io.paperagent.v2.contracts
                                                .PlanStepId(
                                                        "project-candidate-compose"))),
                org.mockito.ArgumentMatchers.eq(fixture.workspace),
                org.mockito.ArgumentMatchers.eq(ref()),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void mismatchedOrCrossBoundCandidateAuthorityFailsBeforeMutation() {
        String arguments = "{\"operation\":\"compose\"}";
        var crossUser = candidateFixture(
                new com.yanban.api.agent.v2.compatibility.project
                        .ProjectCandidateEffectAuthority(
                                ProjectCandidateCompositionEffect.KIND,
                                arguments, sha256(arguments),
                                99L, 8L, 9L, 42L, "version",
                                "improve", List.of("paper.md")));
        assertThrows(IllegalStateException.class, () -> crossUser.composer.execute(
                7L, 42L, crossUser.command));
        verifyNoInteractions(crossUser.claims, crossUser.workspaces,
                crossUser.composition);

        var mismatchedArguments = candidateFixture(
                new com.yanban.api.agent.v2.compatibility.project
                        .ProjectCandidateEffectAuthority(
                                ProjectCandidateCompositionEffect.KIND,
                                "{}", sha256("{}"),
                                7L, 8L, 9L, 42L, "version",
                                "improve", List.of("paper.md")));
        assertThrows(IllegalStateException.class,
                () -> mismatchedArguments.composer.execute(
                        7L, 42L, mismatchedArguments.command));
        verifyNoInteractions(mismatchedArguments.claims,
                mismatchedArguments.workspaces,
                mismatchedArguments.composition);
    }

    @Test
    void workspaceFailurePreservesSanitizedCodeAndStopsBeforeClaim() {
        String arguments = "{\"operation\":\"compose\"}";
        var fixture = candidateFixture(
                new com.yanban.api.agent.v2.compatibility.project
                        .ProjectCandidateEffectAuthority(
                                ProjectCandidateCompositionEffect.KIND,
                                arguments, sha256(arguments),
                                7L, 8L, 9L, 42L, "version",
                                "improve", List.of("paper.md")));
        when(fixture.workspace.inspectMaterialization(
                org.mockito.ArgumentMatchers.any())).thenThrow(
                        new WorkspaceException(
                                WorkspaceErrorCode.WORKSPACE_PARTIAL_STATE,
                                "inspectMaterialization"));

        ProjectEffectExecutionException failure = assertThrows(
                ProjectEffectExecutionException.class,
                () -> fixture.composer.execute(
                        7L, 42L, fixture.command));

        assertEquals(
                "workspace.WORKSPACE_PARTIAL_STATE",
                failure.stage());
        verifyNoInteractions(fixture.claims, fixture.composition);
    }

    private CandidateExecutionFixture candidateFixture(
            com.yanban.api.agent.v2.compatibility.project
                    .ProjectCandidateEffectAuthority authority) {
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
        var analysisAuthorities = mock(com.yanban.api.agent.v2.compatibility
                .project.ProjectAnalysisAuthoritySource.class);
        var candidateAuthorities = mock(com.yanban.api.agent.v2.compatibility
                .project.ProjectCandidateEffectGateway.class);
        var composition = mock(ProjectCandidateCompositionEffect.class);
        var naturalAuthorities = mock(
                com.yanban.api.agent.v2.effect
                        .NaturalLanguageEffectAuthoritySource.class);
        var naturalCandidates = mock(
                NaturalLanguageCandidateAuthorityStore.class);
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
        var stepId = new io.paperagent.v2.contracts.PlanStepId(
                "project-candidate-compose");
        var activationId = new io.paperagent.v2.contracts.EventId(
                "candidate-activation");
        when(recovery.planId()).thenReturn(planId);
        when(recovery.activation()).thenReturn(activation);
        var taskFrame = mock(io.paperagent.v2.contracts.TaskFrame.class);
        when(taskFrame.id()).thenReturn(
                new io.paperagent.v2.contracts.TaskFrameId(
                        "candidate-task-frame"));
        when(recovery.taskFrame()).thenReturn(taskFrame);
        var checkpoint = mock(
                io.paperagent.v2.persistence.VersionedCheckpoint.class);
        var checkpointValue = mock(
                io.paperagent.v2.contracts.Checkpoint.class);
        when(checkpointValue.revisionId()).thenReturn(
                new io.paperagent.v2.contracts.PlanRevisionId(
                        "candidate-revision"));
        when(checkpoint.checkpoint()).thenReturn(checkpointValue);
        when(recovery.checkpoint()).thenReturn(checkpoint);
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
        when(recoverer.recover(org.mockito.ArgumentMatchers.any()))
                .thenReturn(active);
        var toolCallId = new io.paperagent.v2.contracts.ToolCallId(
                "candidate-tool");
        var persistedIntent = new io.paperagent.v2.persistence
                .PersistedEffectIntent(
                        new io.paperagent.v2.contracts.EffectIntent(
                                toolCallId, planId, stepId,
                                ProjectCandidateCompositionEffect.KIND,
                                new io.paperagent.v2.contracts.ObjectValue(
                                        Map.of("operation",
                                                new io.paperagent.v2.contracts
                                                        .TextValue("compose")))),
                        "owner", 1L, activationId);
        when(intents.find(toolCallId)).thenReturn(
                io.paperagent.v2.persistence.PersistenceResult.found(
                        persistedIntent));
        when(candidateAuthorities.require(planId.value(), stepId.value()))
                .thenReturn(authority);
        var confirmed = mock(io.paperagent.v2.persistence
                .PersistedPlanExecutionContextConfirmed.class);
        var spec = mock(io.paperagent.v2.contracts
                .WorkspaceMaterializationSpec.class);
        when(confirmed.materializationSpec()).thenReturn(spec);
        when(executionContexts.inspect(planId)).thenReturn(
                io.paperagent.v2.persistence.PersistenceResult.found(
                        confirmed));
        WorkspacePort workspace = mock(WorkspacePort.class);
        var verified = mock(io.paperagent.v2.workspace
                .VerifiedWorkspaceMaterialization.class);
        when(verified.workspace()).thenReturn(ref());
        when(workspace.inspectMaterialization(spec)).thenReturn(verified);
        when(workspaces.create(7L, 42L)).thenReturn(workspace);
        when(composition.execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(workspace),
                org.mockito.ArgumentMatchers.eq(ref()),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.any())).thenReturn(
                        new ProjectCandidateCompositionEffect.CandidateResult(
                                null, null, "d".repeat(64)));
        when(claims.execute(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
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
        var composer = new AuthenticatedProjectEffectExecutionComposer(
                contexts, planIds, recoverer, intents, claims,
                executionContexts, workspaces, analysisAuthorities,
                candidateAuthorities, composition, json,
                naturalAuthorities, naturalCandidates);
        var command = new AuthenticatedProjectEffectExecutionCommand(
                planId, toolCallId,
                new io.paperagent.v2.runtime.execution.recovery.composition
                        .StepRecoveryLeaseAttempt(
                                "owner", "token", lease.expiresAt()));
        return new CandidateExecutionFixture(
                composer, command, claims, workspaces, composition, workspace,
                candidateAuthorities, naturalAuthorities, naturalCandidates);
    }

    private record CandidateExecutionFixture(
            AuthenticatedProjectEffectExecutionComposer composer,
            AuthenticatedProjectEffectExecutionCommand command,
            com.yanban.api.agent.v2.persistence
                    .ProductEffectExecutionClaimRepository claims,
            com.yanban.api.agent.v2.workspace
                    .AuthenticatedAgentTurnWorkspacePortFactory workspaces,
            ProjectCandidateCompositionEffect composition,
            WorkspacePort workspace,
            com.yanban.api.agent.v2.compatibility.project
                    .ProjectCandidateEffectGateway candidateAuthorities,
            com.yanban.api.agent.v2.effect
                    .NaturalLanguageEffectAuthoritySource naturalAuthorities,
            NaturalLanguageCandidateAuthorityStore naturalCandidates) {}

    private static WorkspaceRef ref() {
        return new WorkspaceRef(
                new WorkspaceId("workspace"),
                new ProjectVersionRef("8", "version"));
    }

    private AuthenticatedProjectEffectExecutionOutcome executeSuccess(
            String kind, io.paperagent.v2.contracts.ObjectValue arguments,
            String canonicalArguments, WorkspacePort workspace) {
        return execute(
                kind, arguments, canonicalArguments, workspace, false);
    }

    private AuthenticatedProjectEffectExecutionOutcome execute(
            String kind, io.paperagent.v2.contracts.ObjectValue arguments,
            String canonicalArguments, WorkspacePort workspace,
            boolean replay) {
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
                    var receipt = replay
                            ? new io.paperagent.v2.contracts.ExecutionReceipt(
                                    new io.paperagent.v2.contracts.ReceiptId(
                                            "replayed-receipt"),
                                    toolCallId,
                                    io.paperagent.v2.contracts.ReceiptStatus
                                            .SUCCESS,
                                    Instant.now(), Instant.now(),
                                    Optional.of(0), Optional.empty(),
                                    io.paperagent.v2.contracts.OutputCapture
                                            .inline("{}", false),
                                    io.paperagent.v2.contracts.OutputCapture
                                            .empty(),
                                    List.of(), Optional.empty(), List.of())
                            : request.execution().get();
                    return new com.yanban.api.agent.v2.persistence
                            .ProductEffectExecutionClaimResult(
                                    new io.paperagent.v2.persistence
                                            .PersistedEffectResult(
                                                    receipt, "owner", 1L),
                                    replay);
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

    private void assertSuccessfulToolReceipt(
            String kind,
            io.paperagent.v2.contracts.ObjectValue arguments,
            String canonicalArguments,
            WorkspacePort workspace) {
        var outcome = executeSuccess(
                kind, arguments, canonicalArguments, workspace);
        assertEquals(io.paperagent.v2.contracts.ReceiptStatus.SUCCESS,
                outcome.result().receipt().status());
        String output = outcome.result().receipt().standardOutput()
                .inlineText().orElseThrow();
        assertTrue(output.contains("\"tool\":\"" + kind + "\""));
        assertFalse(output.contains(rootPath()));
    }

    private void assertFailureReceipt(
            String kind,
            io.paperagent.v2.contracts.ObjectValue arguments,
            String canonicalArguments,
            String expectedCode,
            WorkspacePort workspace) {
        var outcome = executeSuccess(
                kind, arguments, canonicalArguments, workspace);
        assertEquals(io.paperagent.v2.contracts.ReceiptStatus.FAILURE,
                outcome.result().receipt().status());
        assertEquals(expectedCode,
                outcome.result().receipt().resultCode().orElseThrow());
        assertTrue(outcome.result().receipt().standardOutput()
                .inlineText().isEmpty());
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
