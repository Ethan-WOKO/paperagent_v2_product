package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspacePort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic cross-reference consistency audit for bounded LaTeX files. */
final class V2ProjectLatexCrossrefAuditTool {
    static final String KIND = "project.latex.crossref.audit";
    static final String PARSER = "latex-crossref-audit@1";
    static final int MAX_PATHS = 20;
    static final int MAX_TOTAL_BYTES = 2_000_000;
    static final int MAX_OCCURRENCES = 1_000;
    static final int MAX_ISSUES = 400;

    private static final Set<String> FIELDS = Set.of(
            "relativePaths", "includeUnreferencedLabels");
    private static final Pattern LABEL = Pattern.compile(
            "\\\\label\\{([^{}]{1,200})}");
    private static final Pattern REFERENCE = Pattern.compile(
            "\\\\(ref|eqref|autoref|pageref|cref|Cref)\\{([^{}]{1,200})}");

    private final ObjectMapper json;

    V2ProjectLatexCrossrefAuditTool(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    String execute(
            WorkspacePort workspace,
            WorkspaceRef ref,
            ObjectNode arguments) {
        V2ProjectAnalysisToolSupport.requireAllowedFields(arguments, FIELDS);
        List<ProjectPath> paths = V2ProjectAnalysisToolSupport.paths(
                arguments, "relativePaths", MAX_PATHS, true,
                V2ProjectLatexCrossrefAuditTool::isTex);
        boolean includeUnreferenced =
                V2ProjectAnalysisToolSupport.optionalBoolean(
                        arguments, "includeUnreferencedLabels", true);
        var input = V2ProjectAnalysisToolSupport.read(
                workspace, ref, paths, MAX_TOTAL_BYTES);

        Map<String, List<Occurrence>> labels = new LinkedHashMap<>();
        Map<String, List<Occurrence>> references = new LinkedHashMap<>();
        int occurrences = 0;
        for (var source : input.sources()) {
            String[] lines = source.content().split("\\R", -1);
            for (int index = 0; index < lines.length; index++) {
                String line = V2ProjectAnalysisToolSupport
                        .withoutLatexComment(lines[index]);
                occurrences += collect(
                        LABEL.matcher(line), labels, 1,
                        source.path(), index + 1);
                occurrences += collect(
                        REFERENCE.matcher(line), references, 2,
                        source.path(), index + 1);
                if (occurrences > MAX_OCCURRENCES) {
                    throw V2ProjectAnalysisToolSupport.failed(
                            "occurrence_budget");
                }
            }
        }

        ArrayNode issues = json.createArrayNode();
        labels.forEach((identifier, locations) -> {
            if (locations.size() > 1) {
                for (Occurrence location : locations) {
                    addIssue(issues, "DUPLICATE_LABEL", identifier,
                            location, "label", locations.size());
                }
            }
        });
        references.forEach((identifier, locations) -> {
            if (!labels.containsKey(identifier)) {
                for (Occurrence location : locations) {
                    addIssue(issues, "UNRESOLVED_REFERENCE", identifier,
                            location, location.command(), 1);
                }
            }
        });
        if (includeUnreferenced) {
            labels.forEach((identifier, locations) -> {
                if (!references.containsKey(identifier)) {
                    for (Occurrence location : locations) {
                        addIssue(issues, "UNREFERENCED_LABEL", identifier,
                                location, "label", 1);
                    }
                }
            });
        }
        if (issues.size() > MAX_ISSUES) {
            throw V2ProjectAnalysisToolSupport.failed("output_budget");
        }

        ObjectNode output = json.createObjectNode();
        output.put("formatVersion", 1);
        output.put("tool", KIND);
        output.put("parser", PARSER);
        ArrayNode outputPaths = output.putArray("paths");
        paths.forEach(path -> outputPaths.add(path.value()));
        ObjectNode summary = output.putObject("summary");
        summary.put("files", input.sources().size());
        summary.put("labels", size(labels));
        summary.put("references", size(references));
        summary.put("issues", issues.size());
        summary.put("bytesInspected", input.bytesInspected());
        summary.put("complete", true);
        output.set("issues", issues);
        return V2ProjectAnalysisToolSupport.encode(json, output);
    }

    private static int collect(
            Matcher matcher,
            Map<String, List<Occurrence>> target,
            int identifierGroup,
            String path,
            int line) {
        int count = 0;
        while (matcher.find()) {
            String identifier = matcher.group(identifierGroup).trim();
            if (identifier.isEmpty()) {
                continue;
            }
            String command = identifierGroup == 1
                    ? "label" : matcher.group(1);
            target.computeIfAbsent(identifier,
                            ignored -> new ArrayList<>())
                    .add(new Occurrence(path, line, command));
            count++;
        }
        return count;
    }

    private static void addIssue(
            ArrayNode issues,
            String code,
            String identifier,
            Occurrence occurrence,
            String command,
            int occurrences) {
        ObjectNode issue = issues.addObject();
        issue.put("code", code);
        issue.put("identifier", identifier);
        issue.put("path", occurrence.path());
        issue.put("line", occurrence.line());
        issue.put("command", command);
        issue.put("occurrences", occurrences);
    }

    private static int size(Map<String, List<Occurrence>> occurrences) {
        return occurrences.values().stream().mapToInt(List::size).sum();
    }

    private static boolean isTex(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".tex");
    }

    private record Occurrence(String path, int line, String command) {
    }
}
