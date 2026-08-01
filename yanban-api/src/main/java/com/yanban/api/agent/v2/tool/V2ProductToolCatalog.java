package com.yanban.api.agent.v2.tool;

import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.contracts.ToolId;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Single product-owned catalog for every tool exposed to natural-language V2.
 *
 * <p>The schemas guide model calls and support isolated contract tests. They
 * do not replace effect ownership, authorization, Workspace, or execution
 * validation.
 */
public final class V2ProductToolCatalog {
    private static final List<Entry> ENTRIES = List.of(
            literatureSearch(),
            projectRead(),
            projectSearch(),
            projectBibtexAudit(),
            projectCandidateCompose(),
            sandboxExecute());
    private static final Map<String, Entry> BY_ALIAS = indexByAlias();
    private static final Map<ToolId, Entry> BY_ID = indexById();

    private V2ProductToolCatalog() {
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static List<ToolDescriptor> descriptors() {
        return ENTRIES.stream().map(Entry::descriptor).toList();
    }

    public static Optional<ToolId> toolIdForPublicAlias(String alias) {
        Entry entry = alias == null ? null : BY_ALIAS.get(alias);
        return entry == null
                ? Optional.empty()
                : Optional.of(entry.descriptor().id());
    }

    public static Optional<ToolDescriptor> descriptor(ToolId id) {
        Entry entry = id == null ? null : BY_ID.get(id);
        return entry == null
                ? Optional.empty()
                : Optional.of(entry.descriptor());
    }

    public static ToolDescriptor requireDescriptor(ToolId id) {
        return descriptor(id).orElseThrow(() ->
                new IllegalArgumentException(
                        "NATURAL_LANGUAGE_CAPABILITY_UNAVAILABLE"));
    }

    public static boolean supports(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return false;
        }
        try {
            return BY_ID.containsKey(new ToolId(toolId));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static boolean acceptsArguments(
            ToolId id, ObjectValue arguments) {
        return descriptor(id)
                .map(ToolDescriptor::parameterSchema)
                .map(schema -> V2ToolArgumentSchemaValidator.accepts(
                        schema, arguments))
                .orElse(false);
    }

    private static Entry literatureSearch() {
        return entry(
                "literature_search",
                "Search scholarly literature using the product retrieval service.",
                "literature.search",
                "Search scholarly literature. Query is required; topK is 1-20, "
                        + "yearFrom is 1000-3000, and includeBibtex is optional. "
                        + "A successful Receipt proves task creation or queueing, "
                        + "not that final papers have already been returned.",
                objectSchema(
                        Map.of(
                                "query", stringSchema(1, 2_000),
                                "topK", integerSchema(1, 20),
                                "yearFrom", integerSchema(1_000, 3_000),
                                "includeBibtex", booleanSchema()),
                        List.of("query")));
    }

    private static Entry projectRead() {
        return entry(
                "project_read",
                "Read one text file from the authenticated frozen Project version.",
                "project.read",
                "Read one exact UTF-8 text path from the authenticated frozen "
                        + "Project Workspace. This proves reading only; it does "
                        + "not prove compilation, execution, tests, or changes.",
                objectSchema(
                        Map.of("path", stringSchema(1, 1_024)),
                        List.of("path")));
    }

    private static Entry projectSearch() {
        return entry(
                "project_search",
                "Search text inside the authenticated frozen Project version.",
                "project.search",
                "Search all frozen Project text files for one literal query. "
                        + "query is 1-256 characters and maxResults is 1-20. "
                        + "This operation is read-only.",
                objectSchema(
                        Map.of(
                                "query", stringSchema(1, 256),
                                "maxResults", integerSchema(1, 20)),
                        List.of("query", "maxResults")));
    }

    private static Entry projectBibtexAudit() {
        return entry(
                "project_bibtex_audit",
                "Audit BibTeX entries and LaTeX citation usage in the frozen Project.",
                "project.bibtex.audit",
                "Audit one to twenty exact .bib or .tex files in the "
                        + "authenticated frozen Project Workspace. Detect "
                        + "duplicate citation keys, missing title/author/year "
                        + "fields, missing cited keys, and optionally unused "
                        + "entries. This is a bounded first-version syntax "
                        + "audit; it does not verify DOI metadata, citation "
                        + "quality, compilation, or modify any file. Example "
                        + "input: {\"paths\":[\"paper/references.bib\","
                        + "\"paper/main.tex\"],\"includeUnusedEntries\":true}. "
                        + "A successful Receipt returns summary counts and "
                        + "issue locations only.",
                objectSchema(
                        Map.of(
                                "paths", arraySchema(
                                        stringSchema(
                                                1, 1_024,
                                                "(?i).+\\.(bib|tex)"),
                                        1, 20, true),
                                "includeUnusedEntries", booleanSchema()),
                        List.of("paths")),
                Set.of(Capability.READ_PROJECT));
    }

    private static Entry projectCandidateCompose() {
        return entry(
                "project_candidate",
                "Create reviewed candidate file changes in an isolated Workspace.",
                "project.candidate.compose",
                "Prepare full-text replacements for one to four exact Project "
                        + "paths in the isolated Workspace and create the only "
                        + "durable source for a reviewable Candidate. It never "
                        + "applies a Candidate or changes the original ProjectVersion; "
                        + "sandbox.execute cannot create a Candidate.",
                objectSchema(
                        Map.of(
                                "operation", constantStringSchema("compose"),
                                "paths", arraySchema(
                                        stringSchema(1, 1_024), 1, 4, true)),
                        List.of("operation", "paths")));
    }

    private static Entry sandboxExecute() {
        return entry(
                "sandbox_execute",
                "Execute bounded code or commands in the isolated Sandbox.",
                "sandbox.execute",
                "Run selected Project paths in the existing isolated E2B "
                        + "Sandbox. When a prior completed Plan Step created a "
                        + "Candidate, run that resulting isolated Workspace "
                        + "instead of recreating the Candidate. This proves "
                        + "execution only and cannot create or update a Project "
                        + "Candidate. Supported argv profiles include "
                        + "yanban-runner java/python/c/cpp, Maven test/verify, "
                        + "direct Java source launch, direct javac, and bounded "
                        + "git checks. Prefer yanban-runner java path.java for "
                        + "Java compile-and-run. Direct javac accepts only one "
                        + "or more normalized .java source paths and no flags. "
                        + "Direct java accepts only -version or one normalized "
                        + ".java source path; it does not accept a compiled class "
                        + "name. Java runner arguments may append "
                        + "--dependency=group:artifact:version; dependencies are "
                        + "prepared before offline run.",
                objectSchema(
                        Map.of(
                                "paths", arraySchema(
                                        stringSchema(1, 1_024), 1, 32, true),
                                "argv", arraySchema(
                                        stringSchema(1, 4_096), 1, 64, false)),
                        List.of("paths", "argv")));
    }

    private static Entry entry(
            String alias,
            String publicDescription,
            String id,
            String modelDescription,
            ObjectValue schema) {
        return entry(
                alias,
                publicDescription,
                id,
                modelDescription,
                schema,
                Set.of());
    }

    private static Entry entry(
            String alias,
            String publicDescription,
            String id,
            String modelDescription,
            ObjectValue schema,
            Set<Capability> requiredCapabilities) {
        return new Entry(
                alias,
                publicDescription,
                new ToolDescriptor(
                        new ToolId(id), modelDescription,
                        requiredCapabilities, schema));
    }

    private static ObjectValue objectSchema(
            Map<String, ContractValue> properties,
            List<String> required) {
        return object(Map.of(
                "type", text("object"),
                "properties", object(properties),
                "required", list(required.stream()
                        .map(V2ProductToolCatalog::text)
                        .map(ContractValue.class::cast)
                        .toList()),
                "additionalProperties", new BooleanValue(false)));
    }

    private static ObjectValue stringSchema(int minimum, int maximum) {
        return object(Map.of(
                "type", text("string"),
                "minLength", number(minimum),
                "maxLength", number(maximum)));
    }

    private static ObjectValue stringSchema(
            int minimum, int maximum, String pattern) {
        return object(Map.of(
                "type", text("string"),
                "minLength", number(minimum),
                "maxLength", number(maximum),
                "pattern", text(pattern)));
    }

    private static ObjectValue constantStringSchema(String value) {
        return object(Map.of(
                "type", text("string"),
                "const", text(value)));
    }

    private static ObjectValue integerSchema(int minimum, int maximum) {
        return object(Map.of(
                "type", text("integer"),
                "minimum", number(minimum),
                "maximum", number(maximum)));
    }

    private static ObjectValue booleanSchema() {
        return object(Map.of("type", text("boolean")));
    }

    private static ObjectValue arraySchema(
            ObjectValue items,
            int minimum,
            int maximum,
            boolean unique) {
        return object(Map.of(
                "type", text("array"),
                "items", items,
                "minItems", number(minimum),
                "maxItems", number(maximum),
                "uniqueItems", new BooleanValue(unique)));
    }

    private static ObjectValue object(Map<String, ContractValue> values) {
        return new ObjectValue(values);
    }

    private static ListValue list(List<ContractValue> values) {
        return new ListValue(values);
    }

    private static TextValue text(String value) {
        return new TextValue(value);
    }

    private static NumberValue number(int value) {
        return new NumberValue(BigDecimal.valueOf(value));
    }

    private static Map<String, Entry> indexByAlias() {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (Entry entry : ENTRIES) {
            if (result.putIfAbsent(entry.publicAlias(), entry) != null) {
                throw new IllegalStateException(
                        "duplicate V2 public tool alias");
            }
        }
        return Map.copyOf(result);
    }

    private static Map<ToolId, Entry> indexById() {
        Map<ToolId, Entry> result = new LinkedHashMap<>();
        for (Entry entry : ENTRIES) {
            if (result.putIfAbsent(entry.descriptor().id(), entry) != null) {
                throw new IllegalStateException("duplicate V2 tool id");
            }
        }
        return Map.copyOf(result);
    }

    public record Entry(
            String publicAlias,
            String publicDescription,
            ToolDescriptor descriptor) {
        public Entry {
            if (publicAlias == null
                    || !publicAlias.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException(
                        "invalid V2 public tool alias");
            }
            if (publicDescription == null || publicDescription.isBlank()
                    || descriptor == null) {
                throw new IllegalArgumentException(
                        "invalid V2 product tool entry");
            }
        }
    }
}
