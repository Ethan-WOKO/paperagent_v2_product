package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V2ProjectBibtexAuditToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WorkspaceRef ref = new WorkspaceRef(
            new WorkspaceId("workspace-1"),
            new ProjectVersionRef("project-1", "version-1"));

    @Test
    void returnsDeterministicStructuredIssuesWithoutMutatingWorkspace()
            throws Exception {
        WorkspacePort workspace = mock(WorkspacePort.class);
        String bib = """
                @article{used,
                  title={Used},
                  author={Author},
                  year={2025}
                }
                @article{missing,
                  title={Missing fields}
                }
                @article{duplicate,
                  title={First},
                  author={Author},
                  year={2024}
                }
                @article{duplicate,
                  title={Second},
                  author={Author},
                  year={2024}
                }
                @article{unused,
                  title={Unused},
                  author={Author},
                  year={2023}
                }
                """;
        String tex = "Related work cites \\cite{used,absent}.";
        when(workspace.read(ref, new ProjectPath("paper/references.bib")))
                .thenReturn(bib.getBytes(StandardCharsets.UTF_8));
        when(workspace.read(ref, new ProjectPath("paper/main.tex")))
                .thenReturn(tex.getBytes(StandardCharsets.UTF_8));
        ObjectNode arguments = json.createObjectNode();
        arguments.putArray("paths")
                .add("paper/references.bib")
                .add("paper/main.tex");
        arguments.put("includeUnusedEntries", true);

        var tool = new V2ProjectBibtexAuditTool(json);
        String first = tool.execute(workspace, ref, arguments);
        String second = tool.execute(workspace, ref, arguments);
        assertEquals(first, second);
        JsonNode result = json.readTree(first);
        assertEquals(1, result.path("formatVersion").asInt());
        assertEquals("project.bibtex.audit", result.path("tool").asText());
        assertEquals("bibtex-audit@1", result.path("parser").asText());
        assertEquals(5, result.path("summary").path("entries").asInt());
        assertEquals(2, result.path("summary").path("citations").asInt());
        assertTrue(codes(result).containsAll(List.of(
                "DUPLICATE_KEY",
                "MISSING_REQUIRED_FIELD",
                "UNUSED_ENTRY",
                "MISSING_CITATION_KEY")));
        assertTrue(result.path("issues").toString().contains(
                "paper/references.bib"));
        assertTrue(result.path("issues").toString().contains(
                "paper/main.tex"));

        verify(workspace, never()).create(any(), any(), any());
        verify(workspace, never()).replace(any(), any(), any());
        verify(workspace, never()).delete(any(), any());
        verify(workspace, never()).move(any(), any(), any());
        verify(workspace, never()).cleanup(any());
    }

    @Test
    void rejectsInvalidPathsUnsupportedFilesMalformedInputAndReadFailure() {
        var tool = new V2ProjectBibtexAuditTool(json);
        WorkspacePort workspace = mock(WorkspacePort.class);

        assertThrows(V2ProjectBibtexAuditTool.AuditException.class,
                () -> tool.execute(workspace, ref,
                        arguments("../outside.bib")));
        assertThrows(V2ProjectBibtexAuditTool.AuditException.class,
                () -> tool.execute(workspace, ref,
                        arguments("paper/data.csv")));

        ObjectNode duplicates = json.createObjectNode();
        duplicates.putArray("paths")
                .add("paper/references.bib")
                .add("paper/references.bib");
        assertThrows(V2ProjectBibtexAuditTool.AuditException.class,
                () -> tool.execute(workspace, ref, duplicates));

        when(workspace.read(ref, new ProjectPath("paper/broken.bib")))
                .thenReturn("@article{broken,".getBytes(
                        StandardCharsets.UTF_8));
        assertThrows(V2ProjectBibtexAuditTool.AuditException.class,
                () -> tool.execute(workspace, ref,
                        arguments("paper/broken.bib")));

        when(workspace.read(ref, new ProjectPath("paper/invalid.bib")))
                .thenReturn(new byte[]{(byte) 0xC3, (byte) 0x28});
        assertThrows(V2ProjectBibtexAuditTool.AuditException.class,
                () -> tool.execute(workspace, ref,
                        arguments("paper/invalid.bib")));

        when(workspace.read(ref, new ProjectPath("paper/missing.bib")))
                .thenThrow(new IllegalStateException("missing"));
        assertThrows(IllegalStateException.class,
                () -> tool.execute(workspace, ref,
                        arguments("paper/missing.bib")));
    }

    @Test
    void enforcesInputAndOutputBudgets() throws Exception {
        var tool = new V2ProjectBibtexAuditTool(json);
        WorkspacePort workspace = mock(WorkspacePort.class);
        when(workspace.read(ref, new ProjectPath("paper/large.bib")))
                .thenReturn(new byte[
                        V2ProjectBibtexAuditTool.MAX_TOTAL_BYTES + 1]);
        assertThrows(V2ProjectBibtexAuditTool.AuditException.class,
                () -> tool.execute(workspace, ref,
                        arguments("paper/large.bib")));

        StringBuilder many = new StringBuilder();
        for (int index = 0;
                index < V2ProjectBibtexAuditTool.MAX_ISSUES + 10;
                index++) {
            many.append("@article{key").append(index).append(",\n")
                    .append("  title={Only title}\n")
                    .append("}\n");
        }
        when(workspace.read(ref, new ProjectPath("paper/many.bib")))
                .thenReturn(many.toString().getBytes(StandardCharsets.UTF_8));
        JsonNode result = json.readTree(tool.execute(
                workspace, ref, arguments("paper/many.bib")));
        assertEquals(V2ProjectBibtexAuditTool.MAX_ISSUES,
                result.path("summary").path("issues").asInt());
        assertTrue(result.path("summary").path("truncated").asBoolean());
    }

    private ObjectNode arguments(String path) {
        ObjectNode value = json.createObjectNode();
        value.putArray("paths").add(path);
        return value;
    }

    private static List<String> codes(JsonNode result) {
        java.util.ArrayList<String> codes = new java.util.ArrayList<>();
        result.path("issues").forEach(
                issue -> codes.add(issue.path("code").asText()));
        return List.copyOf(codes);
    }
}
