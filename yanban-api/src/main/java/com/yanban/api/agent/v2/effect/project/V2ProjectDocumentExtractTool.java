package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspacePort;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** Bounded PDF/DOCX extraction over an authenticated frozen Workspace. */
final class V2ProjectDocumentExtractTool {
    static final String KIND = "project.document.extract";
    static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    static final int MAX_CHARACTERS = 60_000;
    static final int MAX_LOCATIONS = 200;
    private static final int DEFAULT_CHARACTERS = 20_000;
    private static final int DEFAULT_LOCATIONS = 50;
    private static final long MAX_PDF_MEMORY_BYTES = 64L * 1024 * 1024;
    private static final Set<String> FIELDS = Set.of(
            "path", "maxCharacters", "maxLocations", "includeMetadata");

    private final ObjectMapper json;

    V2ProjectDocumentExtractTool(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    String execute(
            WorkspacePort workspace,
            WorkspaceRef ref,
            ObjectNode arguments) {
        V2ProjectAnalysisToolSupport.requireAllowedFields(arguments, FIELDS);
        ProjectPath path = V2ProjectAnalysisToolSupport.path(
                arguments, "path", V2ProjectDocumentExtractTool::supported);
        int maxCharacters = V2ProjectAnalysisToolSupport.optionalInteger(
                arguments, "maxCharacters", DEFAULT_CHARACTERS,
                1_000, MAX_CHARACTERS);
        int maxLocations = V2ProjectAnalysisToolSupport.optionalInteger(
                arguments, "maxLocations", DEFAULT_LOCATIONS,
                1, MAX_LOCATIONS);
        boolean includeMetadata = V2ProjectAnalysisToolSupport.optionalBoolean(
                arguments, "includeMetadata", true);
        byte[] bytes = V2ProjectAnalysisToolSupport.readBytes(
                workspace, ref, path, MAX_INPUT_BYTES);
        Request request = new Request(
                path, maxCharacters, maxLocations, includeMetadata);
        return extract(request, bytes);
    }

    String execute(ProjectPath path, byte[] bytes) {
        if (path == null || !supported(path.value()) || bytes == null
                || bytes.length == 0 || bytes.length > MAX_INPUT_BYTES) {
            throw V2ProjectAnalysisToolSupport.failed("input_budget");
        }
        return extract(new Request(path, DEFAULT_CHARACTERS,
                DEFAULT_LOCATIONS, true), bytes);
    }

    private String extract(Request request, byte[] bytes) {
        try {
            return V2ProjectAnalysisToolSupport.extension(request.path().value())
                    .equals("pdf")
                    ? extractPdf(request, bytes)
                    : extractDocx(request, bytes);
        } catch (V2ProjectAnalysisToolSupport.ToolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw V2ProjectAnalysisToolSupport.failed("parse_document");
        }
    }

    private String extractPdf(Request request, byte[] bytes)
            throws IOException {
        try (PDDocument document = PDDocument.load(
                new ByteArrayInputStream(bytes),
                MemoryUsageSetting.setupMainMemoryOnly(
                        MAX_PDF_MEMORY_BYTES))) {
            if (document.isEncrypted()) {
                throw V2ProjectAnalysisToolSupport.failed(
                        "encrypted_document");
            }
            int pageCount = document.getNumberOfPages();
            TextLocations locations = new TextLocations(
                    json.createArrayNode(), request.maxCharacters(),
                    request.maxLocations());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            boolean capped = false;
            for (int page = 1; page <= pageCount; page++) {
                if (locations.full()) {
                    locations.truncate();
                    capped = true;
                    break;
                }
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                locations.addPage(page, stripper.getText(document));
            }
            ObjectNode metadata = json.createObjectNode();
            metadata.put("pageCount", pageCount);
            metadata.put("encrypted", false);
            metadata.put("ocrApplied", false);
            if (request.includeMetadata()) {
                PDDocumentInformation information =
                        document.getDocumentInformation();
                putMetadata(metadata, "title", information.getTitle());
                putMetadata(metadata, "author", information.getAuthor());
                putMetadata(metadata, "subject", information.getSubject());
                putMetadata(metadata, "keywords", information.getKeywords());
            }
            return output(
                    request, bytes.length, "application/pdf",
                    "pdfbox-text@1", metadata, locations,
                    capped);
        }
    }

    private String extractDocx(Request request, byte[] bytes)
            throws Exception {
        try (OPCPackage value = OPCPackage.open(
                new ByteArrayInputStream(bytes));
                XWPFDocument document = new XWPFDocument(value)) {
            var signals = V2ProjectOoxmlSupport.inspect(value);
            TextLocations locations = new TextLocations(
                    json.createArrayNode(), request.maxCharacters(),
                    request.maxLocations());
            List<org.apache.poi.xwpf.usermodel.XWPFParagraph> paragraphs =
                    document.getParagraphs();
            for (int index = 0; index < paragraphs.size(); index++) {
                if (locations.full()) {
                    locations.truncate();
                    break;
                }
                locations.addParagraph(index + 1,
                        paragraphs.get(index).getText());
            }
            List<org.apache.poi.xwpf.usermodel.XWPFTable> tables =
                    document.getTables();
            outer:
            for (int table = 0; table < tables.size(); table++) {
                var rows = tables.get(table).getRows();
                for (int row = 0; row < rows.size(); row++) {
                    var cells = rows.get(row).getTableCells();
                    for (int column = 0; column < cells.size(); column++) {
                        if (locations.full()) {
                            locations.truncate();
                            break outer;
                        }
                        locations.addTableCell(
                                table + 1, row + 1, column + 1,
                                cells.get(column).getText());
                    }
                }
            }
            ObjectNode metadata = json.createObjectNode();
            metadata.put("paragraphCount", paragraphs.size());
            metadata.put("tableCount", tables.size());
            metadata.put("externalRelationshipCount",
                    signals.externalRelationshipCount());
            metadata.put("macrosPresent", signals.macrosPresent());
            metadata.put("externalResourcesLoaded", false);
            metadata.put("macrosExecuted", false);
            if (request.includeMetadata()) {
                var core = document.getProperties().getCoreProperties();
                putMetadata(metadata, "title", core.getTitle());
                putMetadata(metadata, "creator", core.getCreator());
                putMetadata(metadata, "subject", core.getSubject());
            }
            return output(
                    request, bytes.length,
                    "application/vnd.openxmlformats-officedocument."
                            + "wordprocessingml.document",
                    "poi-ooxml-docx@1", metadata, locations,
                    locations.truncated());
        }
    }

    private String output(
            Request request,
            int bytes,
            String mediaType,
            String parser,
            ObjectNode metadata,
            TextLocations locations,
            boolean knownAdditionalLocations) {
        if (knownAdditionalLocations) {
            locations.truncate();
        }
        ObjectNode output = json.createObjectNode();
        output.put("formatVersion", 1);
        output.put("tool", KIND);
        output.put("path", request.path().value());
        output.put("mediaType", mediaType);
        ObjectNode parserNode = output.putObject("parser");
        parserNode.put("name", parser);
        parserNode.put("formatVersion", 1);
        output.set("metadata", metadata);
        ObjectNode summary = output.putObject("summary");
        summary.put("bytesInspected", bytes);
        summary.put("locationsReturned", locations.returned());
        summary.put("charactersReturned", locations.characters());
        summary.put("textAvailable", locations.characters() > 0);
        summary.put("partial", locations.truncated());
        summary.put("truncated", locations.truncated());
        summary.put("parseFailed", false);
        output.set("locations", locations.values());
        return V2ProjectAnalysisToolSupport.encode(json, output);
    }

    private static void putMetadata(
            ObjectNode output, String field, String value) {
        String bounded = V2ProjectAnalysisToolSupport.boundedText(value, 500);
        if (!bounded.isEmpty()) {
            output.put(field, bounded);
        }
    }

    private static boolean supported(String path) {
        String extension = V2ProjectAnalysisToolSupport.extension(path);
        return extension.equals("pdf") || extension.equals("docx");
    }

    private record Request(
            ProjectPath path,
            int maxCharacters,
            int maxLocations,
            boolean includeMetadata) {
    }

    private static final class TextLocations {
        private final ArrayNode values;
        private final int maximumCharacters;
        private final int maximumLocations;
        private int characters;
        private boolean truncated;

        private TextLocations(
                ArrayNode values,
                int maximumCharacters,
                int maximumLocations) {
            this.values = values;
            this.maximumCharacters = maximumCharacters;
            this.maximumLocations = maximumLocations;
        }

        private void addPage(int page, String raw) {
            add("PAGE", raw, value -> value.put("page", page));
        }

        private void addParagraph(int paragraph, String raw) {
            add("PARAGRAPH", raw,
                    value -> value.put("paragraph", paragraph));
        }

        private void addTableCell(
                int table, int row, int column, String raw) {
            add("TABLE_CELL", raw, value -> {
                value.put("table", table);
                value.put("row", row);
                value.put("column", column);
            });
        }

        private void add(
                String kind,
                String raw,
                java.util.function.Consumer<ObjectNode> location) {
            String normalized = V2ProjectAnalysisToolSupport.boundedText(
                    raw, Math.max(1, maximumCharacters - characters));
            if (normalized.isEmpty()) {
                return;
            }
            if (values.size() >= maximumLocations
                    || characters >= maximumCharacters) {
                truncated = true;
                return;
            }
            int remaining = maximumCharacters - characters;
            if (raw != null && raw.length() > remaining) {
                truncated = true;
            }
            String text = V2ProjectAnalysisToolSupport.boundedText(
                    normalized, remaining);
            if (text.length() < normalized.length()) {
                truncated = true;
            }
            ObjectNode value = values.addObject();
            value.put("kind", kind);
            location.accept(value);
            value.put("text", text);
            characters += text.length();
        }

        private boolean full() {
            return values.size() >= maximumLocations
                    || characters >= maximumCharacters;
        }

        private void truncate() {
            truncated = true;
        }

        private int returned() {
            return values.size();
        }

        private int characters() {
            return characters;
        }

        private boolean truncated() {
            return truncated;
        }

        private ArrayNode values() {
            return values;
        }
    }
}
