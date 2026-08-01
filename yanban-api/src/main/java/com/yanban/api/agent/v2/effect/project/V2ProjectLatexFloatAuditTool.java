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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded figure/table inventory and local-reference audit for LaTeX. */
final class V2ProjectLatexFloatAuditTool {
    static final String KIND = "project.latex.float.audit";
    static final String PARSER = "latex-float-audit@1";
    static final int MAX_PATHS = 20;
    static final int MAX_TOTAL_BYTES = 2_000_000;
    static final int MAX_FLOATS = 200;
    static final int MAX_ISSUES = 500;

    private static final Set<String> FIELDS = Set.of(
            "relativePaths", "checkAssetExistence");
    private static final Pattern FLOAT = Pattern.compile(
            "\\\\begin\\{(figure|table)\\*?}(.*?)"
                    + "\\\\end\\{\\1\\*?}",
            Pattern.DOTALL);
    private static final Pattern CAPTION = Pattern.compile(
            "\\\\caption(?:\\s*\\[[^]]*])?\\s*\\{([^{}]*)}",
            Pattern.DOTALL);
    private static final Pattern LABEL = Pattern.compile(
            "\\\\label\\{([^{}]{1,200})}");
    private static final Pattern GRAPHIC = Pattern.compile(
            "\\\\includegraphics(?:\\s*\\[[^]]*])?\\s*"
                    + "\\{([^{}]{1,1024})}");
    private static final Pattern REFERENCE = Pattern.compile(
            "\\\\(?:ref|autoref|pageref|cref|Cref)"
                    + "\\{([^{}]{1,200})}");
    private static final List<String> GRAPHIC_EXTENSIONS = List.of(
            "", ".pdf", ".png", ".jpg", ".jpeg", ".eps", ".svg");

    private final ObjectMapper json;

    V2ProjectLatexFloatAuditTool(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    String execute(
            WorkspacePort workspace,
            WorkspaceRef ref,
            ObjectNode arguments) {
        V2ProjectAnalysisToolSupport.requireAllowedFields(arguments, FIELDS);
        List<ProjectPath> paths = V2ProjectAnalysisToolSupport.paths(
                arguments, "relativePaths", MAX_PATHS, true,
                V2ProjectLatexFloatAuditTool::isTex);
        boolean checkAssets = V2ProjectAnalysisToolSupport.optionalBoolean(
                arguments, "checkAssetExistence", true);
        var input = V2ProjectAnalysisToolSupport.read(
                workspace, ref, paths, MAX_TOTAL_BYTES);
        Set<String> workspacePaths = new LinkedHashSet<>();
        if (checkAssets) {
            workspace.list(ref).stream()
                    .map(stat -> stat.path().value())
                    .sorted()
                    .forEach(workspacePaths::add);
        }

        Set<String> referencedLabels = new LinkedHashSet<>();
        for (var source : input.sources()) {
            Matcher references = REFERENCE.matcher(stripComments(
                    source.content()));
            while (references.find()) {
                referencedLabels.add(references.group(1).trim());
            }
        }

        ArrayNode floats = json.createArrayNode();
        ArrayNode issues = json.createArrayNode();
        for (var source : input.sources()) {
            String content = stripComments(source.content());
            Matcher matcher = FLOAT.matcher(content);
            while (matcher.find()) {
                if (floats.size() >= MAX_FLOATS) {
                    throw V2ProjectAnalysisToolSupport.failed(
                            "float_budget");
                }
                String environment = matcher.group(1);
                String body = matcher.group(2);
                int line = V2ProjectAnalysisToolSupport.lineNumber(
                        content, matcher.start());
                String caption = first(CAPTION.matcher(body));
                String label = first(LABEL.matcher(body));
                List<String> graphics = all(GRAPHIC.matcher(body));
                ObjectNode item = floats.addObject();
                item.put("kind", environment.toUpperCase(Locale.ROOT));
                item.put("path", source.path());
                item.put("line", line);
                item.put("environment", environment);
                item.put("hasCaption", caption != null);
                if (caption != null) {
                    item.put("caption",
                            V2ProjectAnalysisToolSupport.snippet(caption));
                }
                item.put("hasLabel", label != null);
                if (label != null) {
                    item.put("label", label);
                }
                boolean referenced = label != null
                        && referencedLabels.contains(label);
                item.put("referenced", referenced);
                ArrayNode assetItems = item.putArray("assets");
                for (String graphic : graphics) {
                    ObjectNode asset = assetItems.addObject();
                    asset.put("declaredPath", graphic);
                    String resolved = checkAssets
                            ? resolveAsset(source.path(), graphic,
                                    workspacePaths)
                            : null;
                    asset.put("existenceChecked", checkAssets);
                    asset.put("exists", !checkAssets || resolved != null);
                    if (resolved != null) {
                        asset.put("resolvedPath", resolved);
                    }
                    if (checkAssets && resolved == null) {
                        addIssue(issues, "MISSING_ASSET", source.path(),
                                line, label, graphic);
                    }
                }
                if (caption == null) {
                    addIssue(issues, "MISSING_CAPTION", source.path(),
                            line, label, null);
                }
                if (label == null) {
                    addIssue(issues, "MISSING_LABEL", source.path(),
                            line, null, null);
                } else if (!referenced) {
                    addIssue(issues, "UNREFERENCED_FLOAT", source.path(),
                            line, label, null);
                }
                if (issues.size() > MAX_ISSUES) {
                    throw V2ProjectAnalysisToolSupport.failed(
                            "output_budget");
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
        summary.put("floats", floats.size());
        summary.put("issues", issues.size());
        summary.put("bytesInspected", input.bytesInspected());
        summary.put("assetExistenceChecked", checkAssets);
        summary.put("complete", true);
        output.set("floats", floats);
        output.set("issues", issues);
        return V2ProjectAnalysisToolSupport.encode(json, output);
    }

    private static String stripComments(String content) {
        String[] lines = content.split("\\R", -1);
        StringBuilder result = new StringBuilder(content.length());
        for (String line : lines) {
            result.append(V2ProjectAnalysisToolSupport
                    .withoutLatexComment(line)).append('\n');
        }
        return result.toString();
    }

    private static String first(Matcher matcher) {
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).strip();
        return value.isEmpty() ? null : value;
    }

    private static List<String> all(Matcher matcher) {
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            String value = matcher.group(1).strip();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static String resolveAsset(
            String sourcePath,
            String declared,
            Set<String> workspacePaths) {
        if (declared.isBlank() || declared.startsWith("/")
                || declared.startsWith("\\")
                || declared.contains("..")
                || declared.contains("\\")) {
            return null;
        }
        String parent = sourcePath.contains("/")
                ? sourcePath.substring(0, sourcePath.lastIndexOf('/'))
                : "";
        List<String> bases = parent.isEmpty()
                ? List.of(declared)
                : List.of(parent + "/" + declared, declared);
        boolean hasExtension = !V2ProjectAnalysisToolSupport
                .extension(declared).isEmpty();
        for (String base : bases) {
            for (String extension : hasExtension
                    ? List.of("") : GRAPHIC_EXTENSIONS) {
                String candidate = base + extension;
                try {
                    ProjectPath normalized = new ProjectPath(candidate);
                    if (normalized.value().equals(candidate)
                            && workspacePaths.contains(candidate)) {
                        return candidate;
                    }
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void addIssue(
            ArrayNode issues,
            String code,
            String path,
            int line,
            String label,
            String declaredAsset) {
        ObjectNode issue = issues.addObject();
        issue.put("code", code);
        issue.put("path", path);
        issue.put("line", line);
        if (label != null) {
            issue.put("label", label);
        }
        if (declaredAsset != null) {
            issue.put("declaredAsset", declaredAsset);
        }
    }

    private static boolean isTex(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".tex");
    }
}
