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
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** Bounded PDF/Word extraction over an authenticated frozen Workspace. */
final class V2ProjectDocumentExtractTool {
    static final String KIND = "project.document.extract";
    static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    static final int MAX_CHARACTERS = 60_000;
    static final int MAX_LOCATIONS = 200;
    private static final int DEFAULT_CHARACTERS = 20_000;
    private static final int DEFAULT_LOCATIONS = MAX_LOCATIONS;
    private static final long MAX_PDF_MEMORY_BYTES = 64L * 1024 * 1024;
    private static final Set<String> FIELDS = Set.of(
            "path", "cursor", "maxCharacters", "maxLocations",
            "includeMetadata");

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
        Cursor cursor = cursor(arguments);
        byte[] bytes = V2ProjectAnalysisToolSupport.readBytes(
                workspace, ref, path, MAX_INPUT_BYTES);
        Request request = new Request(
                path, cursor, maxCharacters, maxLocations, includeMetadata);
        return extract(request, bytes);
    }

    String execute(ProjectPath path, byte[] bytes) {
        return execute(path, bytes, null, DEFAULT_LOCATIONS);
    }

    String execute(
            ProjectPath path, byte[] bytes, String cursor,
            int maxLocations) {
        if (path == null || !supported(path.value()) || bytes == null
                || bytes.length == 0 || bytes.length > MAX_INPUT_BYTES
                || maxLocations < 1 || maxLocations > MAX_LOCATIONS) {
            throw V2ProjectAnalysisToolSupport.failed("input_budget");
        }
        return extract(new Request(path, Cursor.parse(cursor),
                DEFAULT_CHARACTERS, maxLocations, true), bytes);
    }

    private String extract(Request request, byte[] bytes) {
        try {
            return switch (V2ProjectAnalysisToolSupport.extension(
                    request.path().value())) {
                case "pdf" -> extractPdf(request, bytes);
                case "doc" -> extractDoc(request, bytes);
                case "docx" -> extractDocx(request, bytes);
                default -> throw V2ProjectAnalysisToolSupport.failed(
                        "arguments");
            };
        } catch (V2ProjectAnalysisToolSupport.ToolException failure) {
            throw failure;
        } catch (EncryptedDocumentException failure) {
            throw V2ProjectAnalysisToolSupport.failed("encrypted_document");
        } catch (Exception failure) {
            throw V2ProjectAnalysisToolSupport.failed("parse_document");
        }
    }

    private String extractDoc(Request request, byte[] bytes)
            throws IOException {
        try (HWPFDocument document = new HWPFDocument(
                new ByteArrayInputStream(bytes))) {
            TextLocations locations = new TextLocations(
                    json.createArrayNode(), request.maxCharacters(),
                    request.maxLocations(), request.cursor());
            Range range = document.getRange();
            int paragraphCount = range.numParagraphs();
            for (int index = 0; index < paragraphCount; index++) {
                var paragraph = range.getParagraph(index);
                if (paragraph.isInTable()) {
                    continue;
                }
                if (!locations.addParagraph(
                        index + 1, paragraph.text())) {
                    break;
                }
            }

            int tableCount = 0;
            TableIterator tables = new TableIterator(range);
            outer:
            while (tables.hasNext()) {
                var table = tables.next();
                tableCount++;
                for (int row = 0; row < table.numRows(); row++) {
                    var tableRow = table.getRow(row);
                    for (int column = 0;
                            column < tableRow.numCells(); column++) {
                        if (!locations.addTableCell(tableCount, row + 1,
                                column + 1,
                                tableRow.getCell(column).text())) {
                            break outer;
                        }
                    }
                }
            }

            ObjectNode metadata = json.createObjectNode();
            metadata.put("paragraphCount", paragraphCount);
            metadata.put("tableCount", tableCount);
            metadata.put("macrosPresent",
                    containsMacros(document.getDirectory(), 0));
            metadata.put("externalResourcesLoaded", false);
            metadata.put("macrosExecuted", false);
            if (request.includeMetadata()) {
                SummaryInformation information =
                        document.getSummaryInformation();
                if (information != null) {
                    putMetadata(metadata, "title", information.getTitle());
                    putMetadata(metadata, "author", information.getAuthor());
                    putMetadata(metadata, "subject", information.getSubject());
                    putMetadata(metadata, "keywords",
                            information.getKeywords());
                }
            }
            return output(request, bytes.length, "application/msword",
                    "poi-hwpf-doc@1", metadata, locations);
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
                    request.maxLocations(), request.cursor());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                if (!locations.addPage(
                        page, stripper.getText(document))) {
                    break;
                }
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
                    "pdfbox-text@1", metadata, locations);
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
                    request.maxLocations(), request.cursor());
            List<org.apache.poi.xwpf.usermodel.XWPFParagraph> paragraphs =
                    document.getParagraphs();
            for (int index = 0; index < paragraphs.size(); index++) {
                if (!locations.addParagraph(index + 1,
                        paragraphs.get(index).getText())) {
                    break;
                }
            }
            List<org.apache.poi.xwpf.usermodel.XWPFTable> tables =
                    document.getTables();
            outer:
            for (int table = 0; table < tables.size(); table++) {
                var rows = tables.get(table).getRows();
                for (int row = 0; row < rows.size(); row++) {
                    var cells = rows.get(row).getTableCells();
                    for (int column = 0; column < cells.size(); column++) {
                        if (!locations.addTableCell(
                                table + 1, row + 1, column + 1,
                                cells.get(column).getText())) {
                            break outer;
                        }
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
                    "poi-ooxml-docx@1", metadata, locations);
        }
    }

    private String output(
            Request request,
            int bytes,
            String mediaType,
            String parser,
            ObjectNode metadata,
            TextLocations locations) {
        locations.requireCursorReached();
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
        summary.put("partial", !request.cursor().isStart()
                || locations.hasMore());
        summary.put("truncated", locations.hasMore());
        summary.put("hasMore", locations.hasMore());
        summary.put("cursor", request.cursor().encode());
        if (locations.nextCursor() != null) {
            summary.put("nextCursor", locations.nextCursor().encode());
        }
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

    private static boolean containsMacros(
            DirectoryEntry directory, int depth) {
        if (directory == null || depth > 32) {
            return false;
        }
        for (Entry entry : directory) {
            String name = entry.getName();
            if (name != null && (name.equalsIgnoreCase("Macros")
                    || name.equalsIgnoreCase("VBA")
                    || name.equalsIgnoreCase("_VBA_PROJECT_CUR")
                    || name.equalsIgnoreCase("VBA_PROJECT"))) {
                return true;
            }
            if (entry instanceof DirectoryEntry child
                    && containsMacros(child, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean supported(String path) {
        String extension = V2ProjectAnalysisToolSupport.extension(path);
        return extension.equals("pdf") || extension.equals("doc")
                || extension.equals("docx");
    }

    private static Cursor cursor(ObjectNode arguments) {
        var value = arguments.get("cursor");
        if (value == null || value.isNull()) {
            return Cursor.START;
        }
        if (!value.isTextual()) {
            throw V2ProjectAnalysisToolSupport.failed("arguments");
        }
        return Cursor.parse(value.textValue());
    }

    private record Request(
            ProjectPath path,
            Cursor cursor,
            int maxCharacters,
            int maxLocations,
            boolean includeMetadata) {
    }

    private record Cursor(int location, int character) {
        private static final Cursor START = new Cursor(0, 0);

        private Cursor {
            if (location < 0 || location > 1_000_000
                    || character < 0 || character > MAX_INPUT_BYTES) {
                throw V2ProjectAnalysisToolSupport.failed("arguments");
            }
        }

        private static Cursor parse(String value) {
            if (value == null || value.isBlank()) {
                return START;
            }
            if (!value.matches("v1:[0-9]{1,7}:[0-9]{1,8}")) {
                throw V2ProjectAnalysisToolSupport.failed("arguments");
            }
            String[] parts = value.split(":", -1);
            try {
                return new Cursor(Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
            } catch (NumberFormatException invalid) {
                throw V2ProjectAnalysisToolSupport.failed("arguments");
            }
        }

        private String encode() {
            return "v1:" + location + ":" + character;
        }

        private boolean isStart() {
            return location == 0 && character == 0;
        }
    }

    private static final class TextLocations {
        private final ArrayNode values;
        private final int maximumCharacters;
        private final int maximumLocations;
        private final Cursor cursor;
        private int characters;
        private int sourceLocation;
        private boolean cursorReached;
        private Cursor nextCursor;

        private TextLocations(
                ArrayNode values,
                int maximumCharacters,
                int maximumLocations,
                Cursor cursor) {
            this.values = values;
            this.maximumCharacters = maximumCharacters;
            this.maximumLocations = maximumLocations;
            this.cursor = cursor;
        }

        private boolean addPage(int page, String raw) {
            return add("PAGE", raw, value -> value.put("page", page));
        }

        private boolean addParagraph(int paragraph, String raw) {
            return add("PARAGRAPH", raw,
                    value -> value.put("paragraph", paragraph));
        }

        private boolean addTableCell(
                int table, int row, int column, String raw) {
            return add("TABLE_CELL", raw, value -> {
                value.put("table", table);
                value.put("row", row);
                value.put("column", column);
            });
        }

        private boolean add(
                String kind,
                String raw,
                java.util.function.Consumer<ObjectNode> location) {
            if (nextCursor != null) {
                return false;
            }
            String normalized = V2ProjectAnalysisToolSupport.boundedText(
                    raw, Math.max(1, raw == null ? 1 : raw.length()));
            if (normalized.isEmpty()) {
                return true;
            }
            int currentLocation = sourceLocation++;
            if (currentLocation < cursor.location()) {
                return true;
            }
            if (currentLocation > cursor.location()
                    && !cursorReached) {
                throw V2ProjectAnalysisToolSupport.failed("cursor");
            }
            int startCharacter = currentLocation == cursor.location()
                    ? cursor.character() : 0;
            if (!cursorReached) {
                if (startCharacter >= normalized.length()) {
                    throw V2ProjectAnalysisToolSupport.failed("cursor");
                }
                cursorReached = true;
            }
            if (values.size() >= maximumLocations
                    || characters >= maximumCharacters) {
                nextCursor = new Cursor(currentLocation, startCharacter);
                return false;
            }
            String remainingText = normalized.substring(startCharacter);
            int remaining = maximumCharacters - characters;
            String text = V2ProjectAnalysisToolSupport.boundedText(
                    remainingText, remaining);
            ObjectNode value = values.addObject();
            value.put("kind", kind);
            location.accept(value);
            value.put("text", text);
            characters += text.length();
            if (text.length() < remainingText.length()) {
                nextCursor = new Cursor(currentLocation,
                        startCharacter + text.length());
                return false;
            }
            return true;
        }

        private void requireCursorReached() {
            if (!cursorReached && !cursor.isStart()) {
                throw V2ProjectAnalysisToolSupport.failed("cursor");
            }
        }

        private int returned() {
            return values.size();
        }

        private int characters() {
            return characters;
        }

        private boolean hasMore() {
            return nextCursor != null;
        }

        private Cursor nextCursor() {
            return nextCursor;
        }

        private ArrayNode values() {
            return values;
        }
    }
}
