package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspacePort;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic bounded literal matching across authorized Project material. */
final class V2ProjectCrossMaterialSearchTool {
    static final String KIND = "project.cross-material.search";
    static final String PARSER = "cross-material-search@1";
    static final int MAX_PATHS = 50;
    static final int MAX_TOTAL_BYTES = 5_000_000;
    static final int MAX_MATCHES = 100;

    private static final Set<String> FIELDS = Set.of(
            "query", "relativePaths", "maxMatches");

    private final ObjectMapper json;

    V2ProjectCrossMaterialSearchTool(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    String execute(
            WorkspacePort workspace,
            WorkspaceRef ref,
            ObjectNode arguments) {
        V2ProjectAnalysisToolSupport.requireAllowedFields(arguments, FIELDS);
        String query = V2ProjectAnalysisToolSupport.requiredText(
                arguments, "query", 200);
        int maxMatches = V2ProjectAnalysisToolSupport.optionalInteger(
                arguments, "maxMatches", 20, 1, MAX_MATCHES);
        List<ProjectPath> requested = V2ProjectAnalysisToolSupport.paths(
                arguments, "relativePaths", MAX_PATHS, false,
                ignored -> true);
        boolean scoped = !requested.isEmpty();
        List<ProjectPath> paths = scoped
                ? requested
                : workspace.list(ref).stream()
                        .sorted(java.util.Comparator.comparing(
                                value -> value.path().value()))
                        .map(value -> value.path())
                        .toList();
        if (paths.size() > MAX_PATHS && !scoped) {
            throw V2ProjectAnalysisToolSupport.failed("path_budget");
        }

        ArrayNode matches = json.createArrayNode();
        LinkedHashSet<String> matchingPaths = new LinkedHashSet<>();
        List<String> inspectedPaths = new ArrayList<>();
        long bytesInspected = 0;
        boolean partial = false;
        boolean truncated = false;
        String needle = query.toLowerCase(Locale.ROOT);
        for (ProjectPath path : paths) {
            byte[] bytes = workspace.read(ref, path);
            bytesInspected += bytes.length;
            if (bytesInspected > MAX_TOTAL_BYTES) {
                throw V2ProjectAnalysisToolSupport.failed("input_budget");
            }
            String content;
            try {
                content = V2ProjectAnalysisToolSupport.utf8(bytes);
            } catch (V2ProjectAnalysisToolSupport.ToolException invalidText) {
                if (scoped) {
                    throw invalidText;
                }
                partial = true;
                continue;
            }
            inspectedPaths.add(path.value());
            String[] lines = content.split("\\R", -1);
            for (int line = 0; line < lines.length; line++) {
                if (!lines[line].toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                ObjectNode match = matches.addObject();
                match.put("path", path.value());
                match.put("line", line + 1);
                match.put("snippet",
                        V2ProjectAnalysisToolSupport.snippet(lines[line]));
                matchingPaths.add(path.value());
                if (matches.size() >= maxMatches) {
                    truncated = true;
                    break;
                }
            }
            if (truncated) {
                break;
            }
        }

        ObjectNode output = json.createObjectNode();
        output.put("formatVersion", 1);
        output.put("tool", KIND);
        output.put("parser", PARSER);
        output.put("query", query);
        ArrayNode outputPaths = output.putArray("paths");
        inspectedPaths.forEach(outputPaths::add);
        ObjectNode summary = output.putObject("summary");
        summary.put("filesInspected", inspectedPaths.size());
        summary.put("matches", matches.size());
        summary.put("distinctMatchingFiles", matchingPaths.size());
        summary.put("crossMaterialLink", matchingPaths.size() >= 2);
        summary.put("bytesInspected", bytesInspected);
        summary.put("partial", partial);
        summary.put("truncated", truncated);
        ArrayNode linkedPaths = output.putArray("linkedPaths");
        if (matchingPaths.size() >= 2) {
            matchingPaths.forEach(linkedPaths::add);
        }
        output.set("matches", matches);
        return V2ProjectAnalysisToolSupport.encode(json, output);
    }
}
