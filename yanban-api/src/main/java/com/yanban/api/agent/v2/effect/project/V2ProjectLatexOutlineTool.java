package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspacePort;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative, bounded LaTeX outline extraction over a V2 Workspace. */
final class V2ProjectLatexOutlineTool {
    static final String KIND = "project.latex.outline";
    static final String PARSER = "latex-outline@1";
    static final int MAX_PATHS = 20;
    static final int MAX_TOTAL_BYTES = 2_000_000;
    static final int MAX_ITEMS = 200;

    private static final Set<String> FIELDS = Set.of(
            "relativePaths", "includeFormulaReferences");
    private static final Pattern SECTION = Pattern.compile(
            "\\\\(part|chapter|section|subsection|subsubsection)\\*?\\{([^}]*)}");
    private static final Pattern LABEL = Pattern.compile(
            "\\\\label\\{([^}]+)}");
    private static final Pattern REFERENCE = Pattern.compile(
            "\\\\(?:ref|eqref)\\{([^}]+)}");
    private static final Pattern CITATION = Pattern.compile(
            "\\\\cite(?:[A-Za-z*]*)?(?:\\[[^]]*])?\\{([^}]+)}");
    private static final Pattern FLOAT = Pattern.compile(
            "\\\\begin\\{(figure|table|algorithm|equation|align)\\*?}");

    private final ObjectMapper json;

    V2ProjectLatexOutlineTool(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    String execute(
            WorkspacePort workspace,
            WorkspaceRef ref,
            ObjectNode arguments) {
        V2ProjectAnalysisToolSupport.requireAllowedFields(arguments, FIELDS);
        List<ProjectPath> paths = V2ProjectAnalysisToolSupport.paths(
                arguments, "relativePaths", MAX_PATHS, true,
                V2ProjectLatexOutlineTool::isTex);
        boolean includeReferences =
                V2ProjectAnalysisToolSupport.optionalBoolean(
                        arguments, "includeFormulaReferences", false);
        var input = V2ProjectAnalysisToolSupport.read(
                workspace, ref, paths, MAX_TOTAL_BYTES);

        ArrayNode items = json.createArrayNode();
        boolean malformed = false;
        boolean truncated = false;
        for (var source : input.sources()) {
            String[] lines = source.content().split("\\R", -1);
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index];
                malformed |= looksMalformed(line);
                addSections(items, source.path(), index + 1, line);
                addSingle(items, LABEL.matcher(line), "LABEL",
                        "identifier", source.path(), index + 1, line);
                if (includeReferences) {
                    addSingle(items, REFERENCE.matcher(line),
                            "FORMULA_REFERENCE", "identifier",
                            source.path(), index + 1, line);
                }
                addCitations(items, source.path(), index + 1, line);
                addSingle(items, FLOAT.matcher(line), "FLOAT",
                        "environment", source.path(), index + 1, line);
                if (items.size() >= MAX_ITEMS) {
                    truncated = true;
                    break;
                }
            }
            if (truncated) {
                break;
            }
        }
        if (malformed && items.isEmpty()) {
            throw V2ProjectAnalysisToolSupport.failed("malformed_latex");
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
        summary.put("partial", malformed);
        summary.put("truncated", truncated);
        summary.put("parseFailed", malformed);
        output.set("items", items);
        return V2ProjectAnalysisToolSupport.encode(json, output);
    }

    private static void addSections(
            ArrayNode items, String path, int lineNumber, String line) {
        Matcher matcher = SECTION.matcher(line);
        while (matcher.find() && items.size() < MAX_ITEMS) {
            ObjectNode item = location(
                    items, "SECTION", path, lineNumber, line);
            item.put("level", matcher.group(1));
            item.put("title", matcher.group(2));
        }
    }

    private static void addCitations(
            ArrayNode items, String path, int lineNumber, String line) {
        Matcher matcher = CITATION.matcher(line);
        while (matcher.find() && items.size() < MAX_ITEMS) {
            ObjectNode item = location(
                    items, "CITATION", path, lineNumber, line);
            ArrayNode keys = item.putArray("citationKeys");
            for (String key : matcher.group(1).split(",")) {
                if (!key.isBlank()) {
                    keys.add(key.trim());
                }
            }
            if (keys.isEmpty()) {
                items.remove(items.size() - 1);
            }
        }
    }

    private static void addSingle(
            ArrayNode items,
            Matcher matcher,
            String kind,
            String valueField,
            String path,
            int lineNumber,
            String line) {
        while (matcher.find() && items.size() < MAX_ITEMS) {
            ObjectNode item = location(
                    items, kind, path, lineNumber, line);
            item.put(valueField, matcher.group(1));
        }
    }

    private static ObjectNode location(
            ArrayNode items,
            String kind,
            String path,
            int lineNumber,
            String source) {
        ObjectNode item = items.addObject();
        item.put("kind", kind);
        item.put("path", path);
        item.put("line", lineNumber);
        item.put("excerpt",
                V2ProjectAnalysisToolSupport.snippet(source));
        return item;
    }

    private static boolean looksMalformed(String line) {
        return (line.contains("\\section{")
                || line.contains("\\label{")
                || line.contains("\\ref{")
                || line.contains("\\eqref{")
                || line.contains("\\cite{"))
                && !line.contains("}");
    }

    private static boolean isTex(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".tex");
    }
}
