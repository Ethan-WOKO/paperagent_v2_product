package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspacePort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative local-definition and casing audit for paper acronyms. */
final class V2ProjectPaperAcronymAuditTool {
    static final String KIND = "project.paper.acronym.audit";
    static final String PARSER = "paper-acronym-audit@1";
    static final int MAX_PATHS = 30;
    static final int MAX_TOTAL_BYTES = 3_000_000;
    static final int MAX_WORDS = 50_000;
    static final int MAX_ACRONYMS = 500;
    static final int MAX_ISSUES = 500;

    private static final Set<String> FIELDS = Set.of(
            "relativePaths", "minimumAcronymLength");
    private static final Pattern DEFINITION = Pattern.compile(
            "([A-Za-z][A-Za-z-]*(?:\\s+[A-Za-z][A-Za-z-]*){1,7})"
                    + "\\s*\\(([A-Z][A-Z0-9-]{1,9})\\)");
    private static final Pattern WORD = Pattern.compile(
            "(?U)\\b[A-Za-z][A-Za-z0-9-]{1,19}\\b");
    private static final Pattern LATEX_COMMAND = Pattern.compile(
            "\\\\[A-Za-z@]+\\*?(?:\\s*\\[[^]]*])?");

    private final ObjectMapper json;

    V2ProjectPaperAcronymAuditTool(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    String execute(
            WorkspacePort workspace,
            WorkspaceRef ref,
            ObjectNode arguments) {
        V2ProjectAnalysisToolSupport.requireAllowedFields(arguments, FIELDS);
        List<ProjectPath> paths = V2ProjectAnalysisToolSupport.paths(
                arguments, "relativePaths", MAX_PATHS, true,
                V2ProjectPaperAcronymAuditTool::isPaperText);
        int minimumLength = V2ProjectAnalysisToolSupport.optionalInteger(
                arguments, "minimumAcronymLength", 2, 2, 8);
        var input = V2ProjectAnalysisToolSupport.read(
                workspace, ref, paths, MAX_TOTAL_BYTES);

        Map<String, List<Definition>> definitions = new LinkedHashMap<>();
        Map<String, List<Occurrence>> uppercaseUses = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> forms = new LinkedHashMap<>();
        int wordCount = 0;
        int ordinal = 0;
        for (var source : input.sources()) {
            String[] lines = source.content().split("\\R", -1);
            for (int index = 0; index < lines.length; index++) {
                ordinal++;
                String line = clean(lines[index]);
                Matcher definitionMatcher = DEFINITION.matcher(line);
                while (definitionMatcher.find()) {
                    String acronym = definitionMatcher.group(2);
                    if (letters(acronym) >= minimumLength) {
                        String key = acronym.toLowerCase(Locale.ROOT);
                        definitions.computeIfAbsent(key,
                                        ignored -> new ArrayList<>())
                                .add(new Definition(acronym,
                                        V2ProjectAnalysisToolSupport.snippet(
                                                definitionMatcher.group(1)),
                                        source.path(), index + 1, ordinal));
                    }
                }
                Matcher wordMatcher = WORD.matcher(line);
                while (wordMatcher.find()) {
                    if (++wordCount > MAX_WORDS) {
                        throw V2ProjectAnalysisToolSupport.failed(
                                "word_budget");
                    }
                    String word = wordMatcher.group();
                    String key = word.toLowerCase(Locale.ROOT);
                    forms.computeIfAbsent(key,
                                    ignored -> new LinkedHashSet<>())
                            .add(word);
                    if (isUpperAcronym(word, minimumLength)) {
                        uppercaseUses.computeIfAbsent(key,
                                        ignored -> new ArrayList<>())
                                .add(new Occurrence(
                                        word, source.path(), index + 1,
                                        ordinal));
                    }
                }
            }
        }
        if (uppercaseUses.size() > MAX_ACRONYMS) {
            throw V2ProjectAnalysisToolSupport.failed("acronym_budget");
        }

        ArrayNode acronyms = json.createArrayNode();
        ArrayNode issues = json.createArrayNode();
        uppercaseUses.forEach((key, uses) -> {
            Definition firstDefinition = definitions
                    .getOrDefault(key, List.of()).stream()
                    .findFirst().orElse(null);
            Occurrence firstUse = uses.get(0);
            ObjectNode item = acronyms.addObject();
            item.put("acronym", firstUse.form());
            item.put("uses", uses.size());
            item.put("defined", firstDefinition != null);
            item.put("firstUsePath", firstUse.path());
            item.put("firstUseLine", firstUse.line());
            if (firstDefinition != null) {
                item.put("definition", firstDefinition.expansion());
                item.put("definitionPath", firstDefinition.path());
                item.put("definitionLine", firstDefinition.line());
            }
            ArrayNode casing = item.putArray("observedForms");
            forms.getOrDefault(key, new LinkedHashSet<>())
                    .forEach(casing::add);
            if (firstDefinition == null) {
                addIssue(issues, "UNDEFINED_ACRONYM", firstUse,
                        firstUse.form());
            } else if (firstUse.ordinal() < firstDefinition.ordinal()) {
                addIssue(issues, "USE_BEFORE_DEFINITION", firstUse,
                        firstUse.form());
            }
            List<Definition> localDefinitions = definitions
                    .getOrDefault(key, List.of());
            long differentDefinitions = localDefinitions.stream()
                    .map(Definition::expansion).distinct().count();
            if (differentDefinitions > 1) {
                addIssue(issues, "MULTIPLE_DEFINITIONS", firstUse,
                        firstUse.form());
            }
            if (forms.getOrDefault(key, new LinkedHashSet<>()).size() > 1) {
                addIssue(issues, "INCONSISTENT_CASING", firstUse,
                        firstUse.form());
            }
        });
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
        summary.put("acronyms", acronyms.size());
        summary.put("definitions", definitions.values().stream()
                .mapToInt(List::size).sum());
        summary.put("issues", issues.size());
        summary.put("bytesInspected", input.bytesInspected());
        summary.put("minimumAcronymLength", minimumLength);
        output.set("acronyms", acronyms);
        output.set("issues", issues);
        return V2ProjectAnalysisToolSupport.encode(json, output);
    }

    private static String clean(String line) {
        return LATEX_COMMAND.matcher(
                V2ProjectAnalysisToolSupport.withoutLatexComment(line))
                .replaceAll(" ");
    }

    private static void addIssue(
            ArrayNode issues,
            String code,
            Occurrence location,
            String acronym) {
        ObjectNode issue = issues.addObject();
        issue.put("code", code);
        issue.put("acronym", acronym);
        issue.put("path", location.path());
        issue.put("line", location.line());
    }

    private static boolean isUpperAcronym(
            String value, int minimumLength) {
        return value.length() <= 10
                && letters(value) >= minimumLength
                && value.equals(value.toUpperCase(Locale.ROOT))
                && value.chars().anyMatch(Character::isLetter);
    }

    private static int letters(String value) {
        return (int) value.chars().filter(Character::isLetter).count();
    }

    private static boolean isPaperText(String path) {
        return switch (V2ProjectAnalysisToolSupport.extension(path)) {
            case "tex", "md", "txt" -> true;
            default -> false;
        };
    }

    private record Definition(
            String acronym,
            String expansion,
            String path,
            int line,
            int ordinal) {
    }

    private record Occurrence(
            String form,
            String path,
            int line,
            int ordinal) {
    }
}
