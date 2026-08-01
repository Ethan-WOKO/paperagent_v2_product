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

/** Conservative Java, Python, and MATLAB symbol extraction. */
final class V2ProjectCodeSymbolsTool {
    static final String KIND = "project.code.symbols";
    static final String PARSER = "code-symbols@1";
    static final int MAX_PATHS = 30;
    static final int MAX_TOTAL_BYTES = 3_000_000;
    static final int MAX_ITEMS = 500;

    private static final Set<String> FIELDS = Set.of(
            "relativePaths", "symbolQuery", "includeDependencies");
    private static final Pattern JAVA_TYPE = Pattern.compile(
            "\\b(class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
                    + "(?:\\s*\\(([^)]*)\\))?");
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|final|abstract|"
                    + "synchronized|native|strictfp)\\s+)*"
                    + "[A-Za-z_$][A-Za-z0-9_$<>?,.\\[\\]\\s]*\\s+"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(([^)]*)\\)"
                    + "\\s*(?:\\{|throws\\b|;)");
    private static final Pattern PYTHON = Pattern.compile(
            "^\\s*(class|def)\\s+([A-Za-z_][A-Za-z0-9_]*)"
                    + "\\s*(?:\\(([^)]*)\\))?");
    private static final Pattern MATLAB = Pattern.compile(
            "^\\s*function(?:\\s+[^=]+\\s*=)?\\s*"
                    + "([A-Za-z][A-Za-z0-9_]*)\\s*(?:\\(([^)]*)\\))?");
    private static final Pattern JAVA_IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([^;]+);");
    private static final Pattern PYTHON_IMPORT = Pattern.compile(
            "^\\s*(?:from\\s+([^\\s]+)\\s+import|import\\s+([^#]+))");

    private final ObjectMapper json;

    V2ProjectCodeSymbolsTool(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    String execute(
            WorkspacePort workspace,
            WorkspaceRef ref,
            ObjectNode arguments) {
        V2ProjectAnalysisToolSupport.requireAllowedFields(arguments, FIELDS);
        List<ProjectPath> paths = V2ProjectAnalysisToolSupport.paths(
                arguments, "relativePaths", MAX_PATHS, true,
                V2ProjectCodeSymbolsTool::supported);
        String query = "";
        if (arguments.has("symbolQuery")) {
            query = V2ProjectAnalysisToolSupport.requiredText(
                    arguments, "symbolQuery", 160);
        }
        boolean includeDependencies =
                V2ProjectAnalysisToolSupport.optionalBoolean(
                        arguments, "includeDependencies", false);
        var input = V2ProjectAnalysisToolSupport.read(
                workspace, ref, paths, MAX_TOTAL_BYTES);

        ArrayNode items = json.createArrayNode();
        boolean truncated = false;
        for (var source : input.sources()) {
            String extension = V2ProjectAnalysisToolSupport.extension(
                    source.path());
            String[] lines = source.content().split("\\R", -1);
            for (int index = 0; index < lines.length; index++) {
                addSymbol(items, extension, query, source.path(),
                        index + 1, lines[index]);
                if (includeDependencies) {
                    addDependency(items, extension, source.path(),
                            index + 1, lines[index]);
                }
                if (items.size() >= MAX_ITEMS) {
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
        ArrayNode outputPaths = output.putArray("paths");
        paths.forEach(path -> outputPaths.add(path.value()));
        ObjectNode summary = output.putObject("summary");
        summary.put("files", input.sources().size());
        summary.put("items", items.size());
        summary.put("bytesInspected", input.bytesInspected());
        summary.put("truncated", truncated);
        summary.put("typeInference", false);
        summary.put("completeCallGraph", false);
        output.set("items", items);
        return V2ProjectAnalysisToolSupport.encode(json, output);
    }

    private static void addSymbol(
            ArrayNode items,
            String extension,
            String query,
            String path,
            int lineNumber,
            String line) {
        Matcher matcher;
        String kind;
        String name;
        String parameters;
        if ("java".equals(extension)) {
            matcher = JAVA_TYPE.matcher(line);
            if (matcher.find()) {
                kind = matcher.group(1).toUpperCase(Locale.ROOT);
                name = matcher.group(2);
                parameters = matcher.group(3);
            } else {
                matcher = JAVA_METHOD.matcher(line);
                if (!matcher.find()) {
                    return;
                }
                name = matcher.group(1);
                if (isJavaControlKeyword(name)) {
                    return;
                }
                kind = "main".equals(name) ? "ENTRY_POINT" : "METHOD";
                parameters = matcher.group(2);
            }
        } else if ("py".equals(extension)) {
            matcher = PYTHON.matcher(line);
            if (!matcher.find()) {
                return;
            }
            kind = "class".equals(matcher.group(1))
                    ? "CLASS"
                    : "FUNCTION";
            name = matcher.group(2);
            parameters = matcher.group(3);
        } else {
            matcher = MATLAB.matcher(line);
            if (!matcher.find()) {
                return;
            }
            kind = "FUNCTION";
            name = matcher.group(1);
            parameters = matcher.group(2);
        }
        if (!query.isEmpty() && !name.contains(query)) {
            return;
        }
        ObjectNode item = location(
                items, kind, name, path, lineNumber, line);
        ArrayNode parameterArray = item.putArray("parameters");
        if (parameters != null && !parameters.isBlank()) {
            for (String parameter : parameters.split(",")) {
                if (!parameter.isBlank()) {
                    parameterArray.add(
                            V2ProjectAnalysisToolSupport.snippet(parameter));
                }
            }
        }
    }

    private static void addDependency(
            ArrayNode items,
            String extension,
            String path,
            int lineNumber,
            String line) {
        Matcher matcher;
        String dependency;
        if ("java".equals(extension)) {
            matcher = JAVA_IMPORT.matcher(line);
            if (!matcher.find()) {
                return;
            }
            dependency = matcher.group(1).trim();
        } else if ("py".equals(extension)) {
            matcher = PYTHON_IMPORT.matcher(line);
            if (!matcher.find()) {
                return;
            }
            dependency = matcher.group(1) != null
                    ? matcher.group(1).trim()
                    : matcher.group(2).trim();
        } else {
            return;
        }
        if (dependency.isEmpty()) {
            return;
        }
        ObjectNode item = location(
                items, "DEPENDENCY", dependency,
                path, lineNumber, line);
        item.put("dependency", dependency);
        item.putArray("parameters");
    }

    private static ObjectNode location(
            ArrayNode items,
            String kind,
            String name,
            String path,
            int lineNumber,
            String source) {
        ObjectNode item = items.addObject();
        item.put("kind", kind);
        item.put("name", name);
        item.put("path", path);
        item.put("line", lineNumber);
        item.put("excerpt",
                V2ProjectAnalysisToolSupport.snippet(source));
        return item;
    }

    private static boolean isJavaControlKeyword(String value) {
        return Set.of("if", "for", "while", "switch", "catch", "return",
                "new").contains(value);
    }

    private static boolean supported(String path) {
        return Set.of("java", "py", "m").contains(
                V2ProjectAnalysisToolSupport.extension(path));
    }
}
