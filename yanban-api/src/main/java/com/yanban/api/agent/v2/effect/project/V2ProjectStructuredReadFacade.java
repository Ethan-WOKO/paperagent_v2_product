package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.workspace.WorkspacePort;
import java.util.Locale;
import java.util.Objects;

/**
 * Product-side facade that reuses the bounded V2 document parsers behind
 * gateways which expose one automatic Project read operation.
 */
public final class V2ProjectStructuredReadFacade {
    private final ObjectMapper json;
    private final V2ProjectDocumentExtractTool documents;
    private final V2ProjectSpreadsheetInspectTool spreadsheets;

    public V2ProjectStructuredReadFacade(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
        this.documents = new V2ProjectDocumentExtractTool(json);
        this.spreadsheets = new V2ProjectSpreadsheetInspectTool(json);
    }

    public boolean supports(String path) {
        return switch (extension(path)) {
            case "pdf", "docx", "xlsx" -> true;
            default -> false;
        };
    }

    public Result read(WorkspacePort workspace, WorkspaceRef ref, String path) {
        ObjectNode arguments = json.createObjectNode().put("path", path);
        final String content;
        try {
            content = switch (extension(path)) {
                case "pdf", "docx" -> documents.execute(workspace, ref, arguments);
                case "xlsx" -> spreadsheets.execute(workspace, ref, arguments);
                default -> throw new ReadException("unsupported_format");
            };
        } catch (V2ProjectAnalysisToolSupport.ToolException failure) {
            throw new ReadException(failure.stage());
        }
        return result(content);
    }

    public Result readBytes(String path, byte[] bytes) {
        final String content;
        try {
            ProjectPath projectPath = new ProjectPath(path);
            content = switch (extension(path)) {
                case "pdf", "docx" -> documents.execute(projectPath, bytes);
                case "xlsx" -> spreadsheets.execute(projectPath, bytes);
                default -> throw new ReadException("unsupported_format");
            };
        } catch (V2ProjectAnalysisToolSupport.ToolException failure) {
            throw new ReadException(failure.stage());
        } catch (IllegalArgumentException invalid) {
            throw new ReadException("arguments");
        }
        return result(content);
    }

    private Result result(String content) {
        try {
            var parsed = json.readTree(content);
            return new Result(content,
                    parsed.path("summary").path("truncated").asBoolean(false));
        } catch (Exception invalid) {
            throw new ReadException("encoding");
        }
    }

    private static String extension(String path) {
        if (path == null) return "";
        int separator = path.lastIndexOf('.');
        return separator < 0 ? ""
                : path.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    public record Result(String content, boolean truncated) { }

    public static final class ReadException extends RuntimeException {
        private final String stage;

        private ReadException(String stage) {
            super("PROJECT_STRUCTURED_READ_FAILED", null, false, false);
            this.stage = stage;
        }

        public String stage() {
            return stage;
        }
    }
}
