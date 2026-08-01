package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspaceFileStat;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V2ProjectPaperQualityToolBundleTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WorkspaceRef ref = new WorkspaceRef(
            new WorkspaceId("workspace-paper-quality"),
            new ProjectVersionRef("project-1", "version-1"));

    @Test
    void crossrefAuditReportsDuplicateMissingAndUnreferencedFacts()
            throws Exception {
        WorkspacePort workspace = workspace("paper/main.tex", """
                \\label{sec:dup}
                \\label{sec:dup}
                See \\ref{sec:missing}.
                \\label{sec:unused}
                """);
        ObjectNode arguments = paths("paper/main.tex");
        arguments.put("includeUnreferencedLabels", true);

        String first = new V2ProjectLatexCrossrefAuditTool(json)
                .execute(workspace, ref, arguments);
        String second = new V2ProjectLatexCrossrefAuditTool(json)
                .execute(workspace, ref, arguments);
        assertEquals(first, second);
        JsonNode result = json.readTree(first);
        assertTrue(codes(result).containsAll(List.of(
                "DUPLICATE_LABEL",
                "UNRESOLVED_REFERENCE",
                "UNREFERENCED_LABEL")));
        assertEquals(6, result.path("summary").path("issues").asInt());
        verifyReadOnly(workspace);
    }

    @Test
    void floatAuditLinksCaptionsLabelsReferencesAndLocalAssets()
            throws Exception {
        WorkspacePort workspace = workspace("paper/main.tex", """
                \\begin{figure}
                \\includegraphics{figures/accuracy}
                \\caption{Accuracy by epoch}
                \\label{fig:accuracy}
                \\end{figure}
                See \\ref{fig:accuracy}.
                \\begin{table}
                body
                \\end{table}
                """);
        ContentHash hash = new ContentHash("sha256", "a".repeat(64));
        when(workspace.list(ref)).thenReturn(List.of(
                new WorkspaceFileStat(
                        new ProjectPath("paper/main.tex"), 1, hash),
                new WorkspaceFileStat(
                        new ProjectPath("paper/figures/accuracy.png"),
                        1, hash)));

        JsonNode result = json.readTree(
                new V2ProjectLatexFloatAuditTool(json)
                        .execute(workspace, ref, paths("paper/main.tex")));
        assertEquals(2, result.path("summary").path("floats").asInt());
        JsonNode figure = result.path("floats").get(0);
        assertTrue(figure.path("referenced").asBoolean());
        assertTrue(figure.path("assets").get(0).path("exists").asBoolean());
        assertEquals("paper/figures/accuracy.png",
                figure.path("assets").get(0).path("resolvedPath").asText());
        assertTrue(codes(result).containsAll(List.of(
                "MISSING_CAPTION", "MISSING_LABEL")));
        verifyReadOnly(workspace);
    }

    @Test
    void protectedInventoryReturnsStableIdentifiersAndMathHashesOnly()
            throws Exception {
        WorkspacePort workspace = workspace("paper/main.tex", """
                \\label{sec:a} \\ref{sec:a} \\cite{paper-a,paper-b}
                The loss is $x+y$.
                \\begin{equation}
                """);
        ObjectNode arguments = paths("paper/main.tex");
        arguments.put("includeMathHashes", true);
        var tool = new V2ProjectLatexProtectedInventoryTool(json);

        String first = tool.execute(workspace, ref, arguments);
        assertEquals(first, tool.execute(workspace, ref, arguments));
        JsonNode result = json.readTree(first);
        assertEquals(6, result.path("summary").path("items").asInt());
        assertEquals(64, result.path("inventorySha256").asText().length());
        JsonNode math = java.util.stream.StreamSupport.stream(
                        result.path("items").spliterator(), false)
                .filter(item -> "MATH".equals(item.path("kind").asText()))
                .findFirst().orElseThrow();
        assertEquals(64, math.path("sha256").asText().length());
        assertFalse(first.contains("x+y"));
        verifyReadOnly(workspace);
    }

    @Test
    void acronymAuditDistinguishesLocalDefinitionsAndObservedProblems()
            throws Exception {
        WorkspacePort workspace = workspace("paper/main.tex", """
                RAG improves retrieval. API remains external.
                Retrieval Augmented Generation (RAG) is evaluated.
                A Rag variant appears here.
                """);
        ObjectNode arguments = paths("paper/main.tex");
        arguments.put("minimumAcronymLength", 2);

        JsonNode result = json.readTree(
                new V2ProjectPaperAcronymAuditTool(json)
                        .execute(workspace, ref, arguments));
        assertTrue(codes(result).containsAll(List.of(
                "USE_BEFORE_DEFINITION",
                "UNDEFINED_ACRONYM",
                "INCONSISTENT_CASING")));
        assertEquals(2, result.path("summary").path("acronyms").asInt());
        verifyReadOnly(workspace);
    }

    @Test
    void languageStatsReportsSectionsAndLongSentenceLocations()
            throws Exception {
        WorkspacePort workspace = workspace("paper/main.tex", """
                \\section{Introduction}
                One two three four five six seven eight nine ten eleven twelve.

                Short sentence.
                """);
        ObjectNode arguments = paths("paper/main.tex");
        arguments.put("longSentenceWordLikeUnits", 10);
        arguments.put("includeSections", true);

        JsonNode result = json.readTree(
                new V2ProjectPaperLanguageStatsTool(json)
                        .execute(workspace, ref, arguments));
        assertEquals(1, result.path("summary").path("sections").asInt());
        assertEquals(1,
                result.path("summary").path("longSentences").asInt());
        assertTrue(result.path("summary").path("wordLikeUnits").asInt()
                >= 14);
        assertEquals("Introduction",
                result.path("sections").get(0).path("title").asText());
        verifyReadOnly(workspace);
    }

    @Test
    void bundleRejectsTraversalUnsupportedFieldsAndInvalidBudgets() {
        WorkspacePort workspace = mock(WorkspacePort.class);
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectLatexCrossrefAuditTool(json).execute(
                        workspace, ref, paths("../outside.tex")));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectLatexFloatAuditTool(json).execute(
                        workspace, ref, paths("paper/main.md")));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectLatexProtectedInventoryTool(json).execute(
                        workspace, ref, paths("paper/main.txt")));
        ObjectNode acronyms = paths("paper/main.tex");
        acronyms.put("minimumAcronymLength", 1);
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectPaperAcronymAuditTool(json).execute(
                        workspace, ref, acronyms));
        ObjectNode language = paths("paper/main.tex");
        language.put("longSentenceWordLikeUnits", 9);
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectPaperLanguageStatsTool(json).execute(
                        workspace, ref, language));
    }

    @Test
    void protectedInventoryFingerprintChangesWithProtectedFacts()
            throws Exception {
        WorkspacePort workspace = mock(WorkspacePort.class);
        ProjectPath path = new ProjectPath("paper/main.tex");
        when(workspace.read(ref, path))
                .thenReturn("\\label{a}".getBytes(StandardCharsets.UTF_8))
                .thenReturn("\\label{b}".getBytes(StandardCharsets.UTF_8));
        var tool = new V2ProjectLatexProtectedInventoryTool(json);

        String first = json.readTree(tool.execute(
                        workspace, ref, paths("paper/main.tex")))
                .path("inventorySha256").asText();
        String second = json.readTree(tool.execute(
                        workspace, ref, paths("paper/main.tex")))
                .path("inventorySha256").asText();
        assertNotEquals(first, second);
    }

    @Test
    void everyToolRejectsBinaryInputAndEnforcesItsByteBudget() {
        ProjectPath path = new ProjectPath("paper/main.tex");
        WorkspacePort binary = mock(WorkspacePort.class);
        when(binary.read(ref, path)).thenReturn(new byte[]{0, 1, 2});
        ObjectNode arguments = paths("paper/main.tex");
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectLatexCrossrefAuditTool(json).execute(
                        binary, ref, arguments));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectLatexFloatAuditTool(json).execute(
                        binary, ref, arguments));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectLatexProtectedInventoryTool(json).execute(
                        binary, ref, arguments));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectPaperAcronymAuditTool(json).execute(
                        binary, ref, arguments));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectPaperLanguageStatsTool(json).execute(
                        binary, ref, arguments));

        WorkspacePort latexLarge = mock(WorkspacePort.class);
        when(latexLarge.read(ref, path)).thenReturn(new byte[
                V2ProjectLatexCrossrefAuditTool.MAX_TOTAL_BYTES + 1]);
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectLatexCrossrefAuditTool(json).execute(
                        latexLarge, ref, arguments));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectLatexFloatAuditTool(json).execute(
                        latexLarge, ref, arguments));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectLatexProtectedInventoryTool(json).execute(
                        latexLarge, ref, arguments));

        WorkspacePort paperLarge = mock(WorkspacePort.class);
        when(paperLarge.read(ref, path)).thenReturn(new byte[
                V2ProjectPaperAcronymAuditTool.MAX_TOTAL_BYTES + 1]);
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectPaperAcronymAuditTool(json).execute(
                        paperLarge, ref, arguments));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectPaperLanguageStatsTool(json).execute(
                        paperLarge, ref, arguments));
    }

    private WorkspacePort workspace(String path, String content) {
        WorkspacePort workspace = mock(WorkspacePort.class);
        when(workspace.read(ref, new ProjectPath(path)))
                .thenReturn(content.getBytes(StandardCharsets.UTF_8));
        return workspace;
    }

    private ObjectNode paths(String... paths) {
        ObjectNode value = json.createObjectNode();
        var array = value.putArray("relativePaths");
        for (String path : paths) {
            array.add(path);
        }
        return value;
    }

    private static List<String> codes(JsonNode result) {
        return java.util.stream.StreamSupport.stream(
                        result.path("issues").spliterator(), false)
                .map(item -> item.path("code").asText())
                .toList();
    }

    private static void verifyReadOnly(WorkspacePort workspace) {
        verify(workspace, never()).create(any(), any(), any());
        verify(workspace, never()).replace(any(), any(), any());
        verify(workspace, never()).delete(any(), any());
        verify(workspace, never()).move(any(), any(), any());
        verify(workspace, never()).cleanup(any());
    }
}
