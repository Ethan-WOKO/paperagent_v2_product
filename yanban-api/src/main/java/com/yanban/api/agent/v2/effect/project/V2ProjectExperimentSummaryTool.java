package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspacePort;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded observation-only summaries of experiment assets. */
final class V2ProjectExperimentSummaryTool {
    static final String KIND = "project.experiment.summary";
    static final String PARSER = "experiment-summary@1";
    static final int MAX_PATHS = 30;
    static final int MAX_TOTAL_BYTES = 5_000_000;
    static final int MAX_ITEMS = 300;

    private static final Set<String> FIELDS = Set.of(
            "relativePaths", "metricNames", "maxRowsPerFile");
    private static final Set<String> SUPPORTED = Set.of(
            "csv", "json", "yaml", "yml", "txt", "md", "log");
    private static final Pattern SIMPLE_YAML = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9_.-]*)\\s*:\\s*(\\S.*)$");

    private final ObjectMapper json;

    V2ProjectExperimentSummaryTool(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    String execute(
            WorkspacePort workspace,
            WorkspaceRef ref,
            ObjectNode arguments) {
        V2ProjectAnalysisToolSupport.requireAllowedFields(arguments, FIELDS);
        List<ProjectPath> paths = V2ProjectAnalysisToolSupport.paths(
                arguments, "relativePaths", MAX_PATHS, true,
                V2ProjectExperimentSummaryTool::supported);
        Set<String> metrics = metricNames(arguments);
        int maxRows = V2ProjectAnalysisToolSupport.optionalInteger(
                arguments, "maxRowsPerFile", 100, 1, 500);
        var input = V2ProjectAnalysisToolSupport.read(
                workspace, ref, paths, MAX_TOTAL_BYTES);

        ArrayNode items = json.createArrayNode();
        ArrayNode parseFailures = json.createArrayNode();
        boolean truncated = false;
        for (var source : input.sources()) {
            String extension = V2ProjectAnalysisToolSupport.extension(
                    source.path());
            boolean parsed = switch (extension) {
                case "csv" -> summarizeCsv(
                        items, source.path(), source.content(),
                        metrics, maxRows);
                case "json" -> summarizeJson(
                        items, source.path(), source.content(), metrics);
                case "yaml", "yml" -> summarizeYaml(
                        items, source.path(), source.content(), metrics);
                default -> summarizeText(
                        items, source.path(), source.content(),
                        extension, metrics, maxRows);
            };
            if (!parsed) {
                ObjectNode failure = parseFailures.addObject();
                failure.put("path", source.path());
                failure.put("code", "PARSE_FAILED");
            }
            if (items.size() >= MAX_ITEMS) {
                truncated = true;
                break;
            }
        }
        if (!parseFailures.isEmpty() && items.isEmpty()) {
            throw V2ProjectAnalysisToolSupport.failed(
                    "malformed_experiment_input");
        }

        ObjectNode output = json.createObjectNode();
        output.put("formatVersion", 1);
        output.put("tool", KIND);
        output.put("parser", PARSER);
        ArrayNode outputPaths = output.putArray("paths");
        paths.forEach(path -> outputPaths.add(path.value()));
        ObjectNode summary = output.putObject("summary");
        summary.put("files", input.sources().size());
        summary.put("items", items.size());
        summary.put("bytesInspected", input.bytesInspected());
        summary.put("partial", !parseFailures.isEmpty());
        summary.put("truncated", truncated);
        summary.put("parseFailed", !parseFailures.isEmpty());
        ArrayNode requested = summary.putArray("requestedMetrics");
        metrics.forEach(requested::add);
        output.set("items", items);
        output.set("parseFailures", parseFailures);
        return V2ProjectAnalysisToolSupport.encode(json, output);
    }

    private boolean summarizeCsv(
            ArrayNode items,
            String path,
            String content,
            Set<String> metrics,
            int maxRows) {
        String[] lines = content.split("\\R", -1);
        if (lines.length == 0 || lines[0].isBlank()
                || lines[0].contains("\"")) {
            return false;
        }
        String[] headers = lines[0].split(",", -1);
        Set<String> distinct = new HashSet<>();
        for (String header : headers) {
            if (header.isBlank() || !distinct.add(header.trim())) {
                return false;
            }
        }
        int availableRows = lines.length - 1;
        while (availableRows > 0 && lines[availableRows].isBlank()) {
            availableRows--;
        }
        int rows = Math.min(maxRows, availableRows);
        for (int row = 1; row <= rows; row++) {
            if (lines[row].contains("\"")
                    || lines[row].split(",", -1).length != headers.length) {
                return false;
            }
        }
        for (int column = 0;
                column < headers.length && items.size() < MAX_ITEMS;
                column++) {
            String metric = headers[column].trim();
            if (!metrics.isEmpty() && !metrics.contains(metric)) {
                continue;
            }
            String value = "observedRows=" + rows;
            if (rows > 0) {
                String[] cells = lines[rows].split(",", -1);
                value += "; lastObserved=" + cells[column].trim();
            }
            add(items, "CSV_METRIC", metric, value,
                    path, 1, lines[0]);
        }
        return true;
    }

    private boolean summarizeJson(
            ArrayNode items,
            String path,
            String content,
            Set<String> metrics) {
        try {
            JsonNode root = json.readTree(content);
            if (root == null || !root.isObject()) {
                return false;
            }
            var fields = root.fields();
            while (fields.hasNext() && items.size() < MAX_ITEMS) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!metrics.isEmpty()
                        && !metrics.contains(field.getKey())) {
                    continue;
                }
                if (field.getValue().isContainerNode()) {
                    continue;
                }
                String value = field.getValue().isTextual()
                        ? field.getValue().textValue()
                        : field.getValue().toString();
                add(items, "JSON_METRIC", field.getKey(), value,
                        path, 1, field.getKey() + ": " + value);
            }
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static boolean summarizeYaml(
            ArrayNode items,
            String path,
            String content,
            Set<String> metrics) {
        String[] lines = content.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].isBlank()
                    || lines[index].strip().startsWith("#")) {
                continue;
            }
            Matcher matcher = SIMPLE_YAML.matcher(lines[index]);
            if (!matcher.matches()) {
                return false;
            }
            String key = matcher.group(1);
            if (!metrics.isEmpty() && !metrics.contains(key)) {
                continue;
            }
            add(items, "YAML_METRIC", key, matcher.group(2).trim(),
                    path, index + 1, lines[index]);
            if (items.size() >= MAX_ITEMS) {
                break;
            }
        }
        return true;
    }

    private static boolean summarizeText(
            ArrayNode items,
            String path,
            String content,
            String extension,
            Set<String> metrics,
            int maxRows) {
        if (!metrics.isEmpty()) {
            return false;
        }
        String[] lines = content.split("\\R", -1);
        int limit = Math.min(maxRows, lines.length);
        for (int index = 0; index < limit; index++) {
            if (!lines[index].isBlank()) {
                add(items, "REPORT_" + extension.toUpperCase(), null,
                        lines[index].trim(), path, index + 1, lines[index]);
                break;
            }
        }
        return true;
    }

    private static void add(
            ArrayNode items,
            String assetType,
            String metricName,
            String value,
            String path,
            int line,
            String source) {
        if (items.size() >= MAX_ITEMS) {
            return;
        }
        ObjectNode item = items.addObject();
        item.put("assetType", assetType);
        if (metricName != null) {
            item.put("metricName", metricName);
        }
        item.put("value", V2ProjectAnalysisToolSupport.snippet(value));
        item.put("path", path);
        item.put("line", line);
        item.put("excerpt", V2ProjectAnalysisToolSupport.snippet(source));
    }

    private static Set<String> metricNames(ObjectNode arguments) {
        if (!arguments.has("metricNames")) {
            return Set.of();
        }
        if (!arguments.path("metricNames").isArray()
                || arguments.path("metricNames").size() < 1
                || arguments.path("metricNames").size() > 30) {
            throw V2ProjectAnalysisToolSupport.failed("arguments");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (var item : arguments.path("metricNames")) {
            if (!item.isTextual() || item.textValue().isBlank()
                    || item.textValue().length() > 80
                    || !result.add(item.textValue())) {
                throw V2ProjectAnalysisToolSupport.failed("arguments");
            }
        }
        return java.util.Collections.unmodifiableSet(result);
    }

    private static boolean supported(String path) {
        return SUPPORTED.contains(
                V2ProjectAnalysisToolSupport.extension(path));
    }
}
