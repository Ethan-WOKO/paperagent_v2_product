package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V2ProjectAnalysisToolBundleTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WorkspaceRef ref = new WorkspaceRef(
            new WorkspaceId("workspace-analysis"),
            new ProjectVersionRef("project-1", "version-1"));

    @Test
    void latexOutlineReturnsBoundedLocationsDeterministically() throws Exception {
        WorkspacePort workspace = mock(WorkspacePort.class);
        String content = """
                \\section{Method}
                \\label{sec:method}
                See \\eqref{eq:loss} and \\cite{paper-a,paper-b}.
                \\begin{figure}
                """;
        when(workspace.read(ref, new ProjectPath("paper/main.tex")))
                .thenReturn(content.getBytes(StandardCharsets.UTF_8));
        ObjectNode arguments = paths("paper/main.tex");
        arguments.put("includeFormulaReferences", true);
        var tool = new V2ProjectLatexOutlineTool(json);

        String first = tool.execute(workspace, ref, arguments);
        assertEquals(first, tool.execute(workspace, ref, arguments));
        JsonNode result = json.readTree(first);
        assertEquals("project.latex.outline",
                result.path("tool").asText());
        assertTrue(kinds(result, "items").containsAll(List.of(
                "SECTION", "LABEL", "FORMULA_REFERENCE",
                "CITATION", "FLOAT")));
        assertFalse(result.path("summary").path("parseFailed").asBoolean());
        verifyReadOnly(workspace);
    }

    @Test
    void codeSymbolsReportsTypesEntryPointsParametersAndDependencies()
            throws Exception {
        WorkspacePort workspace = mock(WorkspacePort.class);
        String content = """
                import java.util.List;
                class Main {
                    public static void main(String[] args) {
                    }
                }
                """;
        when(workspace.read(ref, new ProjectPath("src/Main.java")))
                .thenReturn(content.getBytes(StandardCharsets.UTF_8));
        ObjectNode arguments = paths("src/Main.java");
        arguments.put("includeDependencies", true);

        JsonNode result = json.readTree(
                new V2ProjectCodeSymbolsTool(json)
                        .execute(workspace, ref, arguments));
        assertEquals("project.code.symbols", result.path("tool").asText());
        assertTrue(kinds(result, "items").containsAll(List.of(
                "DEPENDENCY", "CLASS", "ENTRY_POINT")));
        JsonNode main = java.util.stream.StreamSupport.stream(
                        result.path("items").spliterator(), false)
                .filter(item -> "main".equals(item.path("name").asText()))
                .findFirst().orElseThrow();
        assertEquals(List.of("String[] args"), texts(main.path("parameters")));
        verifyReadOnly(workspace);
    }

    @Test
    void experimentSummaryReportsObservedCsvAndJsonValues() throws Exception {
        WorkspacePort workspace = mock(WorkspacePort.class);
        when(workspace.read(ref, new ProjectPath("results/metrics.csv")))
                .thenReturn("epoch,accuracy\n1,0.91\n2,0.94\n"
                        .getBytes(StandardCharsets.UTF_8));
        when(workspace.read(ref, new ProjectPath("results/config.json")))
                .thenReturn("{\"loss\":0.12,\"nested\":{\"x\":1}}"
                        .getBytes(StandardCharsets.UTF_8));
        ObjectNode arguments = paths(
                "results/metrics.csv", "results/config.json");
        arguments.putArray("metricNames").add("accuracy").add("loss");
        arguments.put("maxRowsPerFile", 2);

        JsonNode result = json.readTree(
                new V2ProjectExperimentSummaryTool(json)
                        .execute(workspace, ref, arguments));
        assertEquals("project.experiment.summary",
                result.path("tool").asText());
        assertEquals(List.of("accuracy", "loss"),
                texts(result.path("items"), "metricName"));
        assertTrue(result.path("items").toString().contains("0.94"));
        assertTrue(result.path("items").toString().contains("0.12"));
        assertFalse(result.path("summary").path("parseFailed").asBoolean());
        verifyReadOnly(workspace);
    }

    @Test
    void crossMaterialSearchLinksOnlyDistinctMatchingFiles() throws Exception {
        WorkspacePort workspace = mock(WorkspacePort.class);
        when(workspace.read(ref, new ProjectPath("paper/main.tex")))
                .thenReturn("Accuracy is the primary metric."
                        .getBytes(StandardCharsets.UTF_8));
        when(workspace.read(ref, new ProjectPath("results/metrics.csv")))
                .thenReturn("epoch,accuracy\n1,0.94"
                        .getBytes(StandardCharsets.UTF_8));
        ObjectNode arguments = json.createObjectNode();
        arguments.put("query", "accuracy");
        arguments.putArray("relativePaths")
                .add("paper/main.tex")
                .add("results/metrics.csv");
        arguments.put("maxMatches", 10);

        JsonNode result = json.readTree(
                new V2ProjectCrossMaterialSearchTool(json)
                        .execute(workspace, ref, arguments));
        assertEquals(2, result.path("summary").path("matches").asInt());
        assertEquals(2, result.path("summary")
                .path("distinctMatchingFiles").asInt());
        assertTrue(result.path("summary").path("crossMaterialLink")
                .asBoolean());
        assertEquals(List.of("paper/main.tex", "results/metrics.csv"),
                texts(result.path("linkedPaths")));
        verifyReadOnly(workspace);
    }

    @Test
    void eachToolRejectsTraversalUnsupportedOrMalformedInput() {
        WorkspacePort workspace = mock(WorkspacePort.class);
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectLatexOutlineTool(json).execute(
                        workspace, ref, paths("../outside.tex")));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectCodeSymbolsTool(json).execute(
                        workspace, ref, paths("src/Main.class")));

        when(workspace.read(ref, new ProjectPath("results/bad.json")))
                .thenReturn("{bad".getBytes(StandardCharsets.UTF_8));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectExperimentSummaryTool(json).execute(
                        workspace, ref, paths("results/bad.json")));

        when(workspace.read(ref, new ProjectPath("paper/binary.dat")))
                .thenReturn(new byte[]{0, 1, 2});
        ObjectNode search = json.createObjectNode();
        search.put("query", "accuracy");
        search.putArray("relativePaths").add("paper/binary.dat");
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectCrossMaterialSearchTool(json).execute(
                        workspace, ref, search));
    }

    @Test
    void eachToolEnforcesItsInputBudget() {
        WorkspacePort workspace = mock(WorkspacePort.class);
        when(workspace.read(ref, new ProjectPath("paper/large.tex")))
                .thenReturn(new byte[
                        V2ProjectLatexOutlineTool.MAX_TOTAL_BYTES + 1]);
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectLatexOutlineTool(json).execute(
                        workspace, ref, paths("paper/large.tex")));

        when(workspace.read(ref, new ProjectPath("src/Large.java")))
                .thenReturn(new byte[
                        V2ProjectCodeSymbolsTool.MAX_TOTAL_BYTES + 1]);
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectCodeSymbolsTool(json).execute(
                        workspace, ref, paths("src/Large.java")));

        when(workspace.read(ref, new ProjectPath("results/large.csv")))
                .thenReturn(new byte[
                        V2ProjectExperimentSummaryTool.MAX_TOTAL_BYTES + 1]);
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectExperimentSummaryTool(json).execute(
                        workspace, ref, paths("results/large.csv")));

        when(workspace.read(ref, new ProjectPath("paper/large.txt")))
                .thenReturn(new byte[
                        V2ProjectCrossMaterialSearchTool.MAX_TOTAL_BYTES + 1]);
        ObjectNode search = json.createObjectNode();
        search.put("query", "needle");
        search.putArray("relativePaths").add("paper/large.txt");
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> new V2ProjectCrossMaterialSearchTool(json).execute(
                        workspace, ref, search));
    }

    @Test
    void unscopedCrossMaterialSearchUsesSortedWorkspacePaths() throws Exception {
        WorkspacePort workspace = mock(WorkspacePort.class);
        var hash = new io.paperagent.v2.contracts.ContentHash(
                "sha256", "a".repeat(64));
        when(workspace.list(ref)).thenReturn(List.of(
                new WorkspaceFileStat(new ProjectPath("z.txt"), 6, hash),
                new WorkspaceFileStat(new ProjectPath("a.txt"), 6, hash)));
        when(workspace.read(ref, new ProjectPath("a.txt")))
                .thenReturn("needle".getBytes(StandardCharsets.UTF_8));
        when(workspace.read(ref, new ProjectPath("z.txt")))
                .thenReturn("needle".getBytes(StandardCharsets.UTF_8));
        ObjectNode arguments = json.createObjectNode();
        arguments.put("query", "needle");

        JsonNode result = json.readTree(
                new V2ProjectCrossMaterialSearchTool(json)
                        .execute(workspace, ref, arguments));
        assertEquals(List.of("a.txt", "z.txt"),
                texts(result.path("paths")));
    }

    private ObjectNode paths(String... paths) {
        ObjectNode value = json.createObjectNode();
        var array = value.putArray("relativePaths");
        for (String path : paths) {
            array.add(path);
        }
        return value;
    }

    private static List<String> kinds(JsonNode result, String field) {
        return texts(result.path(field), "kind");
    }

    private static List<String> texts(JsonNode array) {
        return java.util.stream.StreamSupport.stream(
                        array.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private static List<String> texts(JsonNode array, String field) {
        return java.util.stream.StreamSupport.stream(
                        array.spliterator(), false)
                .map(item -> item.path(field).asText())
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
