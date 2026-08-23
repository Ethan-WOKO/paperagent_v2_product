package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspacePort;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Bounded XLSX inspection without formula, macro, or external-link execution. */
final class V2ProjectSpreadsheetInspectTool {
    static final String KIND = "project.spreadsheet.inspect";
    static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    static final int MAX_SHEETS = 20;
    static final int MAX_ROWS_PER_SHEET = 100;
    static final int MAX_COLUMNS_PER_SHEET = 50;
    private static final int DEFAULT_ROWS = 20;
    private static final int DEFAULT_COLUMNS = 20;
    private static final int MAX_HEADER_CELLS = 100;
    private static final int MAX_SAMPLE_CELLS = 200;
    private static final int MAX_FORMULA_SCAN_CELLS = 100_000;
    private static final Set<String> FIELDS = Set.of(
            "path", "sheetNames", "maxRowsPerSheet",
            "maxColumnsPerSheet");

    private final ObjectMapper json;

    V2ProjectSpreadsheetInspectTool(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    String execute(
            WorkspacePort workspace,
            WorkspaceRef ref,
            ObjectNode arguments) {
        V2ProjectAnalysisToolSupport.requireAllowedFields(arguments, FIELDS);
        ProjectPath path = V2ProjectAnalysisToolSupport.path(
                arguments, "path", value -> V2ProjectAnalysisToolSupport
                        .extension(value).equals("xlsx"));
        int maxRows = V2ProjectAnalysisToolSupport.optionalInteger(
                arguments, "maxRowsPerSheet", DEFAULT_ROWS,
                1, MAX_ROWS_PER_SHEET);
        int maxColumns = V2ProjectAnalysisToolSupport.optionalInteger(
                arguments, "maxColumnsPerSheet", DEFAULT_COLUMNS,
                1, MAX_COLUMNS_PER_SHEET);
        List<String> requestedSheets = sheetNames(arguments);
        byte[] bytes = V2ProjectAnalysisToolSupport.readBytes(
                workspace, ref, path, MAX_INPUT_BYTES);
        return inspect(path, bytes, requestedSheets, maxRows, maxColumns);
    }

    String execute(ProjectPath path, byte[] bytes) {
        if (path == null
                || !V2ProjectAnalysisToolSupport.extension(path.value()).equals("xlsx")
                || bytes == null || bytes.length == 0
                || bytes.length > MAX_INPUT_BYTES) {
            throw V2ProjectAnalysisToolSupport.failed("input_budget");
        }
        return inspect(path, bytes, List.of(), DEFAULT_ROWS, DEFAULT_COLUMNS);
    }

    private String inspect(ProjectPath path, byte[] bytes,
            List<String> requestedSheets, int maxRows, int maxColumns) {
        try (OPCPackage value = OPCPackage.open(
                new ByteArrayInputStream(bytes));
                XSSFWorkbook workbook = new XSSFWorkbook(value)) {
            var signals = V2ProjectOoxmlSupport.inspect(value);
            List<Integer> sheetIndexes = selectedSheets(
                    workbook, requestedSheets);
            MutableBudget budget = new MutableBudget();
            ArrayNode sheets = json.createArrayNode();
            boolean truncated = requestedSheets.isEmpty()
                    && workbook.getNumberOfSheets() > MAX_SHEETS;
            int formulaCellsObserved = 0;
            for (int index : sheetIndexes) {
                SheetResult result = inspectSheet(
                        workbook, index, maxRows, maxColumns, budget);
                sheets.add(result.output());
                truncated |= result.truncated();
                formulaCellsObserved += result.formulaCellsObserved();
            }

            ObjectNode output = json.createObjectNode();
            output.put("formatVersion", 1);
            output.put("tool", KIND);
            output.put("path", path.value());
            output.put("mediaType",
                    "application/vnd.openxmlformats-officedocument."
                            + "spreadsheetml.sheet");
            ObjectNode parser = output.putObject("parser");
            parser.put("name", "poi-ooxml-xlsx@1");
            parser.put("formatVersion", 1);
            ObjectNode metadata = output.putObject("metadata");
            metadata.put("sheetCount", workbook.getNumberOfSheets());
            metadata.put("selectedSheetCount", sheetIndexes.size());
            metadata.put("externalRelationshipCount",
                    signals.externalRelationshipCount());
            metadata.put("externalLinksResolved", false);
            metadata.put("macrosPresent", signals.macrosPresent());
            metadata.put("macrosExecuted", false);
            metadata.put("formulasEvaluated", false);
            ObjectNode summary = output.putObject("summary");
            summary.put("bytesInspected", bytes.length);
            summary.put("sampleCellsReturned", budget.samples);
            summary.put("headerCellsReturned", budget.headers);
            summary.put("formulaCellsObserved", formulaCellsObserved);
            summary.put("formulaPresence",
                    formulaCellsObserved > 0
                            ? "PRESENT"
                            : budget.formulaScanTruncated
                                    ? "UNKNOWN_TRUNCATED"
                                    : "NOT_OBSERVED");
            summary.put("partial", truncated
                    || budget.sampleTruncated
                    || budget.formulaScanTruncated);
            summary.put("truncated", truncated
                    || budget.sampleTruncated
                    || budget.formulaScanTruncated);
            summary.put("parseFailed", false);
            output.set("sheets", sheets);
            return V2ProjectAnalysisToolSupport.encode(json, output);
        } catch (V2ProjectAnalysisToolSupport.ToolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw V2ProjectAnalysisToolSupport.failed(
                    "parse_spreadsheet");
        }
    }

    private SheetResult inspectSheet(
            XSSFWorkbook workbook,
            int sheetIndex,
            int maxRows,
            int maxColumns,
            MutableBudget budget) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        ObjectNode output = json.createObjectNode();
        output.put("name", sheet.getSheetName());
        output.put("index", sheetIndex);
        output.put("visibility",
                workbook.getSheetVisibility(sheetIndex).name());

        int maximumColumns = 0;
        int formulaCells = 0;
        boolean scanTruncated = false;
        for (Row row : sheet) {
            maximumColumns = Math.max(
                    maximumColumns, Math.max(0, row.getLastCellNum()));
            for (Cell cell : row) {
                if (budget.formulaScanned >= MAX_FORMULA_SCAN_CELLS) {
                    budget.formulaScanTruncated = true;
                    scanTruncated = true;
                    break;
                }
                budget.formulaScanned++;
                if (cell.getCellType() == CellType.FORMULA) {
                    formulaCells++;
                }
            }
            if (scanTruncated) {
                break;
            }
        }

        ObjectNode dimensions = output.putObject("dimensions");
        dimensions.put("firstRow",
                sheet.getPhysicalNumberOfRows() == 0
                        ? 0 : sheet.getFirstRowNum() + 1);
        dimensions.put("lastRow",
                sheet.getPhysicalNumberOfRows() == 0
                        ? 0 : sheet.getLastRowNum() + 1);
        dimensions.put("physicalRows", sheet.getPhysicalNumberOfRows());
        dimensions.put("maximumObservedColumns", maximumColumns);

        ArrayNode headers = output.putArray("headers");
        boolean headerTruncated = false;
        Row header = firstPhysicalRow(sheet);
        if (header != null) {
            int columns = Math.min(
                    maxColumns, Math.max(0, header.getLastCellNum()));
            for (int column = 0; column < columns; column++) {
                Cell cell = header.getCell(column);
                if (cell == null || cell.getCellType() == CellType.BLANK) {
                    continue;
                }
                if (budget.headers >= MAX_HEADER_CELLS) {
                    budget.sampleTruncated = true;
                    headerTruncated = true;
                    break;
                }
                headers.add(cell(json.createObjectNode(), cell, true));
                budget.headers++;
            }
        }

        ArrayNode samples = output.putArray("samples");
        int rowsReturned = 0;
        outer:
        for (Row row : sheet) {
            if (rowsReturned >= maxRows) {
                budget.sampleTruncated = true;
                break;
            }
            rowsReturned++;
            int columns = Math.min(
                    maxColumns, Math.max(0, row.getLastCellNum()));
            if (row.getLastCellNum() > maxColumns) {
                budget.sampleTruncated = true;
            }
            for (int column = 0; column < columns; column++) {
                Cell value = row.getCell(column);
                if (value == null || value.getCellType() == CellType.BLANK) {
                    continue;
                }
                if (budget.samples >= MAX_SAMPLE_CELLS) {
                    budget.sampleTruncated = true;
                    break outer;
                }
                samples.add(cell(json.createObjectNode(), value, false));
                budget.samples++;
            }
        }
        if (sheet.getPhysicalNumberOfRows() > rowsReturned) {
            budget.sampleTruncated = true;
        }
        ObjectNode sheetSummary = output.putObject("summary");
        sheetSummary.put("rowsSampled", rowsReturned);
        sheetSummary.put("cellsSampled", samples.size());
        sheetSummary.put("formulaCellsObserved", formulaCells);
        sheetSummary.put("formulaPresence",
                formulaCells > 0
                        ? "PRESENT"
                        : scanTruncated
                                ? "UNKNOWN_TRUNCATED"
                                : "NOT_OBSERVED");
        boolean truncated = scanTruncated
                || headerTruncated
                || sheet.getPhysicalNumberOfRows() > rowsReturned
                || maximumColumns > maxColumns
                || budget.samples >= MAX_SAMPLE_CELLS;
        sheetSummary.put("partial", truncated);
        sheetSummary.put("truncated", truncated);
        return new SheetResult(output, truncated, formulaCells);
    }

    private static ObjectNode cell(
            ObjectNode output, Cell cell, boolean header) {
        output.put("row", cell.getRowIndex() + 1);
        output.put("column", cell.getColumnIndex() + 1);
        output.put("reference", cell.getAddress().formatAsString());
        output.put("header", header);
        CellType type = cell.getCellType();
        output.put("valueType", type.name());
        output.put("formulaPresent", type == CellType.FORMULA);
        if (type == CellType.FORMULA) {
            output.put("cachedValueType",
                    cell.getCachedFormulaResultType().name());
            return output;
        }
        String value = switch (type) {
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toString()
                    : BigDecimal.valueOf(cell.getNumericCellValue())
                            .stripTrailingZeros().toPlainString();
            case ERROR -> Byte.toString(cell.getErrorCellValue());
            case BLANK, _NONE, FORMULA -> "";
        };
        output.put("value",
                V2ProjectAnalysisToolSupport.boundedText(value, 300));
        return output;
    }

    private static Row firstPhysicalRow(Sheet sheet) {
        var rows = sheet.rowIterator();
        return rows.hasNext() ? rows.next() : null;
    }

    private static List<String> sheetNames(ObjectNode arguments) {
        if (!arguments.has("sheetNames")) {
            return List.of();
        }
        if (!arguments.path("sheetNames").isArray()
                || arguments.path("sheetNames").size() < 1
                || arguments.path("sheetNames").size() > MAX_SHEETS) {
            throw V2ProjectAnalysisToolSupport.failed("arguments");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (var item : arguments.path("sheetNames")) {
            if (!item.isTextual() || item.textValue().isBlank()
                    || item.textValue().length() > 100
                    || !values.add(item.textValue())) {
                throw V2ProjectAnalysisToolSupport.failed("arguments");
            }
        }
        return List.copyOf(values);
    }

    private static List<Integer> selectedSheets(
            XSSFWorkbook workbook, List<String> requested) {
        if (requested.isEmpty()) {
            List<Integer> result = new ArrayList<>();
            int limit = Math.min(workbook.getNumberOfSheets(), MAX_SHEETS);
            for (int index = 0; index < limit; index++) {
                result.add(index);
            }
            return List.copyOf(result);
        }
        List<Integer> result = new ArrayList<>();
        for (String name : requested) {
            int index = workbook.getSheetIndex(name);
            if (index < 0) {
                throw V2ProjectAnalysisToolSupport.failed("missing_sheet");
            }
            result.add(index);
        }
        return List.copyOf(result);
    }

    private record SheetResult(
            ObjectNode output,
            boolean truncated,
            int formulaCellsObserved) {
    }

    private static final class MutableBudget {
        private int samples;
        private int headers;
        private int formulaScanned;
        private boolean sampleTruncated;
        private boolean formulaScanTruncated;
    }
}
