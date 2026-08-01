package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, read-only BibTeX audit over an authenticated V2 Workspace. */
final class V2ProjectBibtexAuditTool {
    static final String KIND = "project.bibtex.audit";
    static final String PARSER = "bibtex-audit@1";
    static final int MAX_PATHS = 20;
    static final int MAX_TOTAL_BYTES = 2_000_000;
    static final int MAX_ISSUES = 50;
    private static final int MAX_KEYS = 10_000;
    private static final int MAX_KEY_CHARACTERS = 256;
    private static final List<String> REQUIRED_FIELDS =
            List.of("title", "author", "year");
    private static final Pattern ENTRY = Pattern.compile(
            "^\\s*@[A-Za-z]+\\s*\\{\\s*([^,\\s]+)\\s*,");
    private static final Pattern FIELD = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9_-]*)\\s*=");
    private static final Pattern CITE = Pattern.compile(
            "\\\\cite(?:[A-Za-z*]*)?(?:\\[[^]]*])?\\{([^}]+)}");

    private final ObjectMapper json;

    V2ProjectBibtexAuditTool(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    String execute(
            WorkspacePort workspace,
            WorkspaceRef ref,
            ObjectNode arguments) {
        Request request = request(arguments);
        List<Source> sources = new ArrayList<>();
        long inspectedBytes = 0;
        int bibFiles = 0;
        int texFiles = 0;
        for (ProjectPath path : request.paths()) {
            byte[] bytes = workspace.read(ref, path);
            inspectedBytes += bytes.length;
            if (inspectedBytes > MAX_TOTAL_BYTES) {
                throw failed("input_budget");
            }
            sources.add(new Source(path.value(), utf8(bytes)));
            if (isBib(path.value())) {
                bibFiles++;
            } else {
                texFiles++;
            }
        }

        LinkedHashMap<String, Occurrence> entries = new LinkedHashMap<>();
        LinkedHashMap<String, CitationOccurrence> citations =
                new LinkedHashMap<>();
        List<Issue> issues = new ArrayList<>();
        MutableCounts counts = new MutableCounts();
        for (Source source : sources) {
            if (isBib(source.path())) {
                parseBib(source, entries, issues, counts);
            } else {
                parseTex(source, citations, counts);
            }
        }
        for (Occurrence occurrence : entries.values()) {
            List<String> missingFields = REQUIRED_FIELDS.stream()
                    .filter(field -> !occurrence.fields().contains(field))
                    .toList();
            if (!missingFields.isEmpty()) {
                add(issues, counts, new Issue(
                        "MISSING_REQUIRED_FIELD", occurrence.key(),
                        occurrence.path(), occurrence.line(),
                        "missing required fields: "
                                + String.join(", ", missingFields),
                        missingFields));
            }
        }
        if (request.includeUnusedEntries()) {
            for (Occurrence occurrence : entries.values()) {
                if (!citations.containsKey(occurrence.key())) {
                    add(issues, counts, new Issue(
                            "UNUSED_ENTRY", occurrence.key(),
                            occurrence.path(), occurrence.line(),
                            "entry is not cited by supplied LaTeX files",
                            List.of()));
                }
            }
        }
        for (CitationOccurrence citation : citations.values()) {
            if (!entries.containsKey(citation.key())) {
                add(issues, counts, new Issue(
                        "MISSING_CITATION_KEY", citation.key(),
                        citation.path(), citation.line(),
                        "citation key is absent from supplied BibTeX files",
                        List.of()));
            }
        }

        ObjectNode output = json.createObjectNode();
        output.put("formatVersion", 1);
        output.put("tool", KIND);
        output.put("parser", PARSER);
        ArrayNode paths = output.putArray("paths");
        request.paths().forEach(path -> paths.add(path.value()));
        ObjectNode summary = output.putObject("summary");
        summary.put("files", sources.size());
        summary.put("bibFiles", bibFiles);
        summary.put("texFiles", texFiles);
        summary.put("entries", counts.entries);
        summary.put("citations", counts.citations);
        summary.put("issues", issues.size());
        summary.put("bytesInspected", inspectedBytes);
        summary.put("truncated", counts.truncated);
        ArrayNode issueArray = output.putArray("issues");
        issues.forEach(issue -> {
            ObjectNode item = issueArray.addObject();
            item.put("code", issue.code());
            if (issue.key() != null) {
                item.put("citationKey", issue.key());
            }
            item.put("path", issue.path());
            item.put("line", issue.line());
            item.put("detail", issue.detail());
            if (!issue.missingFields().isEmpty()) {
                ArrayNode missingFields = item.putArray("missingFields");
                issue.missingFields().forEach(missingFields::add);
            }
        });
        try {
            String encoded = json.writeValueAsString(output);
            if (encoded.length() > OutputCapture.MAX_INLINE_CHARACTERS) {
                throw failed("output_budget");
            }
            return encoded;
        } catch (JsonProcessingException exception) {
            throw failed("encoding");
        }
    }

    private static Request request(ObjectNode arguments) {
        if (arguments == null
                || !arguments.has("paths")
                || arguments.size() < 1
                || arguments.size() > 2
                || !arguments.path("paths").isArray()
                || arguments.path("paths").size() < 1
                || arguments.path("paths").size() > MAX_PATHS
                || !allowedFields(arguments)) {
            throw failed("arguments");
        }
        boolean includeUnused = false;
        if (arguments.has("includeUnusedEntries")) {
            if (!arguments.path("includeUnusedEntries").isBoolean()) {
                throw failed("arguments");
            }
            includeUnused = arguments.path(
                    "includeUnusedEntries").booleanValue();
        }
        LinkedHashSet<ProjectPath> paths = new LinkedHashSet<>();
        for (var value : arguments.path("paths")) {
            if (!value.isTextual()
                    || value.textValue().isEmpty()
                    || value.textValue().length() > 1_024) {
                throw failed("arguments");
            }
            ProjectPath path;
            try {
                path = new ProjectPath(value.textValue());
            } catch (RuntimeException invalid) {
                throw failed("arguments");
            }
            if (!path.value().equals(value.textValue())
                    || !(isBib(path.value()) || isTex(path.value()))
                    || !paths.add(path)) {
                throw failed("arguments");
            }
        }
        return new Request(List.copyOf(paths), includeUnused);
    }

    private static boolean allowedFields(ObjectNode arguments) {
        var names = arguments.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!"paths".equals(name)
                    && !"includeUnusedEntries".equals(name)) {
                return false;
            }
        }
        return true;
    }

    private static void parseBib(
            Source source,
            Map<String, Occurrence> entries,
            List<Issue> issues,
            MutableCounts counts) {
        String[] lines = source.content().split("\\R", -1);
        Occurrence current = null;
        for (int index = 0; index < lines.length; index++) {
            Matcher start = ENTRY.matcher(lines[index]);
            if (start.find()) {
                if (current != null) {
                    throw failed("malformed_bibtex");
                }
                String key = key(start.group(1));
                current = new Occurrence(
                        key, source.path(), index + 1,
                        new LinkedHashSet<>());
                counts.entries++;
                if (counts.entries > MAX_KEYS) {
                    throw failed("input_budget");
                }
                Occurrence previous = entries.putIfAbsent(key, current);
                if (previous != null) {
                    add(issues, counts, new Issue(
                            "DUPLICATE_KEY", key, source.path(),
                            index + 1, "duplicate citation key",
                            List.of()));
                }
                continue;
            }
            if (current != null) {
                Matcher field = FIELD.matcher(lines[index]);
                if (field.find()) {
                    current.fields().add(
                            field.group(1).toLowerCase(Locale.ROOT));
                }
                if (lines[index].trim().equals("}")) {
                    current = null;
                }
            }
        }
        if (current != null) {
            throw failed("malformed_bibtex");
        }
    }

    private static void parseTex(
            Source source,
            Map<String, CitationOccurrence> citations,
            MutableCounts counts) {
        String[] lines = source.content().split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            Matcher matcher = CITE.matcher(lines[index]);
            while (matcher.find()) {
                for (String raw : matcher.group(1).split(",")) {
                    String trimmed = raw.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    String key = key(trimmed);
                    counts.citations++;
                    if (counts.citations > MAX_KEYS) {
                        throw failed("input_budget");
                    }
                    citations.putIfAbsent(key, new CitationOccurrence(
                            key, source.path(), index + 1));
                }
            }
        }
    }

    private static void add(
            List<Issue> issues,
            MutableCounts counts,
            Issue issue) {
        if (issues.size() >= MAX_ISSUES) {
            counts.truncated = true;
            return;
        }
        issues.add(issue);
    }

    private static String key(String value) {
        if (value == null || value.isBlank()
                || value.length() > MAX_KEY_CHARACTERS
                || value.codePoints().anyMatch(character ->
                character < 0x20 || Character.isWhitespace(character))) {
            throw failed("malformed_bibtex");
        }
        return value;
    }

    private static String utf8(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (value.codePoints().anyMatch(character ->
                    character < 0x20
                            && character != '\t'
                            && character != '\n'
                            && character != '\r')) {
                throw failed("malformed_utf8");
            }
            return value;
        } catch (CharacterCodingException exception) {
            throw failed("malformed_utf8");
        }
    }

    private static boolean isBib(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".bib");
    }

    private static boolean isTex(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".tex");
    }

    private static AuditException failed(String stage) {
        return new AuditException(stage);
    }

    static final class AuditException extends RuntimeException {
        private final String stage;

        AuditException(String stage) {
            super("PROJECT_BIBTEX_AUDIT_FAILED");
            this.stage = stage;
        }

        String stage() {
            return stage;
        }
    }

    private record Request(
            List<ProjectPath> paths,
            boolean includeUnusedEntries) {
    }

    private record Source(String path, String content) {
    }

    private record Occurrence(
            String key,
            String path,
            int line,
            Set<String> fields) {
    }

    private record CitationOccurrence(
            String key,
            String path,
            int line) {
    }

    private record Issue(
            String code,
            String key,
            String path,
            int line,
            String detail,
            List<String> missingFields) {
    }

    private static final class MutableCounts {
        private int entries;
        private int citations;
        private boolean truncated;
    }
}
