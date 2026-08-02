package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspacePort;
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

class V2ProjectDocumentSpreadsheetToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WorkspaceRef ref = new WorkspaceRef(
            new WorkspaceId("workspace-binary"),
            new ProjectVersionRef("project-1", "version-1"));

    @Test
    void extractsPdfPagesWithMetadataAndExplicitTruncation()
            throws Exception {
        WorkspacePort workspace = mock(WorkspacePort.class);
        ProjectPath path = new ProjectPath("paper/report.pdf");
        when(workspace.read(ref, path)).thenReturn(
                V2ProjectBinaryAssetFixtures.pdf(
                        "Evidence on page one", "Evidence on page two"));
        ObjectNode arguments = json.createObjectNode();
        arguments.put("path", path.value());
        arguments.put("maxLocations", 1);
        arguments.put("maxCharacters", 2_000);

        JsonNode result = json.readTree(
                new V2ProjectDocumentExtractTool(json)
                        .execute(workspace, ref, arguments));

        assertEquals("project.document.extract",
                result.path("tool").asText());
        assertEquals(path.value(), result.path("path").asText());
        assertEquals("pdfbox-text@1",
                result.path("parser").path("name").asText());
        assertEquals(2, result.path("metadata").path("pageCount").asInt());
        assertFalse(result.path("metadata").path("ocrApplied").asBoolean());
        assertEquals(1, result.path("locations").size());
        assertEquals(1, result.path("locations").get(0)
                .path("page").asInt());
        assertTrue(result.path("locations").get(0)
                .path("text").asText().contains("page one"));
        assertTrue(result.path("summary").path("partial").asBoolean());
        assertTrue(result.path("summary").path("truncated").asBoolean());
        assertFalse(result.path("summary").path("parseFailed").asBoolean());
        assertReadOnly(workspace);
    }

    @Test
    void extractsDocxParagraphsAndCellsWithoutResolvingExternalLinks()
            throws Exception {
        WorkspacePort workspace = mock(WorkspacePort.class);
        ProjectPath path = new ProjectPath("paper/report.docx");
        when(workspace.read(ref, path)).thenReturn(
                V2ProjectBinaryAssetFixtures.docx(
                        "The method converged after five epochs."));
        ObjectNode arguments = arguments(path.value());

        JsonNode result = json.readTree(
                new V2ProjectDocumentExtractTool(json)
                        .execute(workspace, ref, arguments));

        assertEquals("poi-ooxml-docx@1",
                result.path("parser").path("name").asText());
        assertEquals("Frozen DOCX report",
                result.path("metadata").path("title").asText());
        assertTrue(result.path("metadata")
                .path("externalRelationshipCount").asInt() > 0);
        assertFalse(result.path("metadata")
                .path("externalResourcesLoaded").asBoolean());
        assertFalse(result.path("metadata")
                .path("macrosExecuted").asBoolean());
        assertTrue(result.path("locations").toString()
                .contains("five epochs"));
        assertTrue(result.path("locations").toString()
                .contains("TABLE_CELL"));
        assertFalse(result.path("summary").path("parseFailed").asBoolean());
        assertReadOnly(workspace);
    }

    @Test
    void inspectsWorkbookTypesAndSignalsWithoutExecutingActiveContent()
            throws Exception {
        WorkspacePort workspace = mock(WorkspacePort.class);
        ProjectPath path = new ProjectPath("results/metrics.xlsx");
        when(workspace.read(ref, path)).thenReturn(
                V2ProjectBinaryAssetFixtures.xlsx());
        ObjectNode arguments = arguments(path.value());
        arguments.putArray("sheetNames").add("Summary");
        arguments.put("maxRowsPerSheet", 10);
        arguments.put("maxColumnsPerSheet", 10);

        String encoded = new V2ProjectSpreadsheetInspectTool(json)
                .execute(workspace, ref, arguments);
        JsonNode result = json.readTree(encoded);

        assertEquals("project.spreadsheet.inspect",
                result.path("tool").asText());
        assertEquals(path.value(), result.path("path").asText());
        assertEquals(2, result.path("metadata").path("sheetCount").asInt());
        assertTrue(result.path("metadata")
                .path("externalRelationshipCount").asInt() > 0);
        assertTrue(result.path("metadata").path("macrosPresent").asBoolean());
        assertFalse(result.path("metadata")
                .path("externalLinksResolved").asBoolean());
        assertFalse(result.path("metadata")
                .path("formulasEvaluated").asBoolean());
        assertEquals("PRESENT",
                result.path("summary").path("formulaPresence").asText());
        assertTrue(result.path("sheets").get(0).path("headers")
                .toString().contains("accuracy"));
        assertTrue(result.path("sheets").get(0).path("samples")
                .toString().contains("\"valueType\":\"FORMULA\""));
        assertFalse(encoded.contains("B2+0.04"));
        assertFalse(result.path("summary").path("parseFailed").asBoolean());
        assertReadOnly(workspace);
    }

    @Test
    void reportsSpreadsheetSamplingAsPartialWhenRowsAreBounded()
            throws Exception {
        WorkspacePort workspace = mock(WorkspacePort.class);
        ProjectPath path = new ProjectPath("results/metrics.xlsx");
        when(workspace.read(ref, path)).thenReturn(
                V2ProjectBinaryAssetFixtures.xlsx());
        ObjectNode arguments = arguments(path.value());
        arguments.putArray("sheetNames").add("Summary");
        arguments.put("maxRowsPerSheet", 1);

        JsonNode result = json.readTree(
                new V2ProjectSpreadsheetInspectTool(json)
                        .execute(workspace, ref, arguments));

        assertTrue(result.path("summary").path("partial").asBoolean());
        assertTrue(result.path("summary").path("truncated").asBoolean());
        assertTrue(result.path("sheets").get(0).path("summary")
                .path("truncated").asBoolean());
    }

    @Test
    void capsLargeWorkbookOutputAsPartialInsteadOfFailing()
            throws Exception {
        WorkspacePort workspace = mock(WorkspacePort.class);
        ProjectPath path = new ProjectPath("results/grid.xlsx");
        when(workspace.read(ref, path)).thenReturn(
                V2ProjectBinaryAssetFixtures.xlsxGrid(30, 20));
        ObjectNode arguments = arguments(path.value());
        arguments.put("maxRowsPerSheet", 100);
        arguments.put("maxColumnsPerSheet", 50);

        String encoded = new V2ProjectSpreadsheetInspectTool(json)
                .execute(workspace, ref, arguments);
        JsonNode result = json.readTree(encoded);

        assertEquals(200, result.path("summary")
                .path("sampleCellsReturned").asInt());
        assertTrue(result.path("summary").path("partial").asBoolean());
        assertTrue(result.path("summary").path("truncated").asBoolean());
        assertTrue(encoded.length()
                < io.paperagent.v2.contracts.OutputCapture
                        .MAX_INLINE_CHARACTERS);
    }

    @Test
    void rejectsUnsupportedInvalidMalformedMissingAndOversizedInputs()
            throws Exception {
        WorkspacePort workspace = mock(WorkspacePort.class);
        var documents = new V2ProjectDocumentExtractTool(json);
        var spreadsheets = new V2ProjectSpreadsheetInspectTool(json);

        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> documents.execute(
                        workspace, ref, arguments("../report.pdf")));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> documents.execute(
                        workspace, ref, arguments("paper/report.txt")));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> spreadsheets.execute(
                        workspace, ref, arguments("results/metrics.csv")));

        ProjectPath malformedPdf = new ProjectPath("paper/broken.pdf");
        when(workspace.read(ref, malformedPdf)).thenReturn(
                "%PDF-not-valid".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> documents.execute(
                        workspace, ref, arguments(malformedPdf.value())));

        ProjectPath malformedXlsx = new ProjectPath("results/broken.xlsx");
        when(workspace.read(ref, malformedXlsx)).thenReturn(
                new byte[]{'P', 'K', 1, 2, 3});
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> spreadsheets.execute(
                        workspace, ref, arguments(malformedXlsx.value())));

        ProjectPath disguisedDocx = new ProjectPath("paper/disguised.docx");
        when(workspace.read(ref, disguisedDocx)).thenReturn(
                V2ProjectBinaryAssetFixtures.pdf("not a DOCX package"));
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> documents.execute(
                        workspace, ref, arguments(disguisedDocx.value())));

        ProjectPath workbook = new ProjectPath("results/metrics.xlsx");
        when(workspace.read(ref, workbook)).thenReturn(
                V2ProjectBinaryAssetFixtures.xlsx());
        ObjectNode missingSheet = arguments(workbook.value());
        missingSheet.putArray("sheetNames").add("Absent");
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> spreadsheets.execute(workspace, ref, missingSheet));

        ProjectPath large = new ProjectPath("paper/large.pdf");
        when(workspace.read(ref, large)).thenReturn(
                new byte[V2ProjectDocumentExtractTool.MAX_INPUT_BYTES + 1]);
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> documents.execute(
                        workspace, ref, arguments(large.value())));

        ProjectPath largeWorkbook = new ProjectPath("results/large.xlsx");
        when(workspace.read(ref, largeWorkbook)).thenReturn(
                new byte[V2ProjectSpreadsheetInspectTool.MAX_INPUT_BYTES + 1]);
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> spreadsheets.execute(
                        workspace, ref, arguments(largeWorkbook.value())));

        ProjectPath missing = new ProjectPath("paper/missing.pdf");
        when(workspace.read(ref, missing)).thenThrow(
                new IllegalStateException("missing test fixture"));
        assertThrows(IllegalStateException.class,
                () -> documents.execute(
                        workspace, ref, arguments(missing.value())));

        ObjectNode extra = arguments("paper/report.pdf");
        extra.put("hostPath", "C:/not-authority");
        assertThrows(V2ProjectAnalysisToolSupport.ToolException.class,
                () -> documents.execute(workspace, ref, extra));
    }

    private ObjectNode arguments(String path) {
        ObjectNode value = json.createObjectNode();
        value.put("path", path);
        return value;
    }

    private static void assertReadOnly(WorkspacePort workspace) {
        verify(workspace, never()).create(any(), any(), any());
        verify(workspace, never()).replace(any(), any(), any());
        verify(workspace, never()).delete(any(), any());
        verify(workspace, never()).move(any(), any(), any());
        verify(workspace, never()).cleanup(any());
    }
}
