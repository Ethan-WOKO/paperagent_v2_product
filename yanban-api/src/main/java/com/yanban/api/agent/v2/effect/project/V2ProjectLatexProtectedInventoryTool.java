package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspacePort;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable, content-minimizing inventory of LaTeX protected elements. */
final class V2ProjectLatexProtectedInventoryTool {
    static final String KIND = "project.latex.protected.inventory";
    static final String PARSER = "latex-protected-inventory@1";
    static final int MAX_PATHS = 20;
    static final int MAX_TOTAL_BYTES = 2_000_000;
    static final int MAX_ITEMS = 1_000;

    private static final Set<String> FIELDS = Set.of(
            "relativePaths", "includeMathHashes");
    private static final Pattern CITATION = Pattern.compile(
            "\\\\cite(?:[A-Za-z*]*)?(?:\\[[^]]*])?"
                    + "\\{([^{}]{1,1000})}");
    private static final Pattern REFERENCE = Pattern.compile(
            "\\\\(ref|eqref|autoref|pageref|cref|Cref)"
                    + "\\{([^{}]{1,200})}");
    private static final Pattern LABEL = Pattern.compile(
            "\\\\label\\{([^{}]{1,200})}");
    private static final Pattern ENVIRONMENT = Pattern.compile(
            "\\\\begin\\{(equation|align|gather|multline|figure|table|"
                    + "algorithm|theorem|proof|verbatim|lstlisting|minted)"
                    + "\\*?}");
    private static final Pattern DOLLAR_MATH = Pattern.compile(
            "\\${1,2}([^$\\r\\n]+)\\${1,2}");
    private static final Pattern DELIMITED_MATH = Pattern.compile(
            "\\\\\\((.*?)\\\\\\)|\\\\\\[(.*?)\\\\\\]");

    private final ObjectMapper json;

    V2ProjectLatexProtectedInventoryTool(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    String execute(
            WorkspacePort workspace,
            WorkspaceRef ref,
            ObjectNode arguments) {
        V2ProjectAnalysisToolSupport.requireAllowedFields(arguments, FIELDS);
        List<ProjectPath> paths = V2ProjectAnalysisToolSupport.paths(
                arguments, "relativePaths", MAX_PATHS, true,
                V2ProjectLatexProtectedInventoryTool::isTex);
        boolean includeMathHashes =
                V2ProjectAnalysisToolSupport.optionalBoolean(
                        arguments, "includeMathHashes", true);
        var input = V2ProjectAnalysisToolSupport.read(
                workspace, ref, paths, MAX_TOTAL_BYTES);

        ArrayNode items = json.createArrayNode();
        Map<String, Integer> counts = new TreeMap<>();
        for (var source : input.sources()) {
            String[] lines = source.content().split("\\R", -1);
            for (int index = 0; index < lines.length; index++) {
                String line = V2ProjectAnalysisToolSupport
                        .withoutLatexComment(lines[index]);
                addCitationItems(items, counts, source.path(),
                        index + 1, CITATION.matcher(line));
                addIdentifierItems(items, counts, source.path(),
                        index + 1, "REFERENCE", REFERENCE.matcher(line), 2);
                addIdentifierItems(items, counts, source.path(),
                        index + 1, "LABEL", LABEL.matcher(line), 1);
                addIdentifierItems(items, counts, source.path(),
                        index + 1, "ENVIRONMENT",
                        ENVIRONMENT.matcher(line), 1);
                if (includeMathHashes) {
                    addMath(items, counts, source.path(), index + 1,
                            DOLLAR_MATH.matcher(line));
                    addMath(items, counts, source.path(), index + 1,
                            DELIMITED_MATH.matcher(line));
                }
                if (items.size() > MAX_ITEMS) {
                    throw V2ProjectAnalysisToolSupport.failed(
                            "item_budget");
                }
            }
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
        summary.put("mathHashesIncluded", includeMathHashes);
        ObjectNode countsNode = summary.putObject("countsByKind");
        counts.forEach(countsNode::put);
        output.put("inventorySha256",
                V2ProjectAnalysisToolSupport.sha256(items.toString()));
        output.set("items", items);
        return V2ProjectAnalysisToolSupport.encode(json, output);
    }

    private static void addCitationItems(
            ArrayNode items,
            Map<String, Integer> counts,
            String path,
            int line,
            Matcher matcher) {
        while (matcher.find()) {
            for (String raw : matcher.group(1).split(",")) {
                String key = raw.trim();
                if (!key.isEmpty()) {
                    addItem(items, counts, "CITATION", path, line,
                            key, null);
                }
            }
        }
    }

    private static void addIdentifierItems(
            ArrayNode items,
            Map<String, Integer> counts,
            String path,
            int line,
            String kind,
            Matcher matcher,
            int group) {
        while (matcher.find()) {
            String identifier = matcher.group(group).trim();
            if (!identifier.isEmpty()) {
                addItem(items, counts, kind, path, line,
                        identifier, kind.equals("REFERENCE")
                                ? matcher.group(1) : null);
            }
        }
    }

    private static void addMath(
            ArrayNode items,
            Map<String, Integer> counts,
            String path,
            int line,
            Matcher matcher) {
        while (matcher.find()) {
            String token = matcher.group(0);
            ObjectNode item = addItem(items, counts, "MATH", path, line,
                    null, null);
            item.put("characters", token.length());
            item.put("sha256",
                    V2ProjectAnalysisToolSupport.sha256(token));
        }
    }

    private static ObjectNode addItem(
            ArrayNode items,
            Map<String, Integer> counts,
            String kind,
            String path,
            int line,
            String identifier,
            String command) {
        ObjectNode item = items.addObject();
        item.put("kind", kind);
        item.put("path", path);
        item.put("line", line);
        if (identifier != null) {
            item.put("identifier", identifier);
        }
        if (command != null) {
            item.put("command", command);
        }
        counts.merge(kind, 1, Integer::sum);
        return item;
    }

    private static boolean isTex(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".tex");
    }
}
