package com.yanban.api.agent.v2.tool;

import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
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
            projectDocumentExtract(),
            projectSpreadsheetInspect(),
            projectLatexOutline(),
            projectLatexCrossrefAudit(),
            projectLatexFloatAudit(),
            projectLatexProtectedInventory(),
            projectPaperAcronymAudit(),
            projectPaperLanguageStats(),
            projectBibtexAudit(),
            projectCodeSymbols(),
            projectExperimentSummary(),
            projectCrossMaterialSearch(),
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

    public static Optional<Entry> entry(ToolId id) {
        return Optional.ofNullable(id == null ? null : BY_ID.get(id));
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
                        List.of("query")),
                Set.of(Capability.ACCESS_NETWORK,
                        Capability.INVOKE_EXTERNAL_TOOL),
                Set.of(RoutingRequirement.TOOL_USE,
                        RoutingRequirement.NETWORK,
                        RoutingRequirement.EXTERNAL_OBSERVATION),
                ExecutionTarget.LITERATURE,
                Set.of("product-literature-search"));
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
                        List.of("path")),
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
    }

    private static Entry projectSearch() {
        return entry(
                "project_search",
                "Search text inside the authenticated frozen Project version.",
                "project.search",
                "Search all frozen Project text files for one literal query. "
                        + "query is 1-256 characters and maxResults is 1-20. "
                        + "Choose this for ordinary literal discovery, such as "
                        + "finding every mention of an exact symbol. Do not choose it "
                        + "when the result must prove that the same observation "
                        + "appears in at least two specified materials; choose "
                        + "project.cross-material.search for that case. This "
                        + "operation is read-only.",
                objectSchema(
                        Map.of(
                                "query", stringSchema(1, 256),
                                "maxResults", integerSchema(1, 20)),
                        List.of("query", "maxResults")),
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
    }

    private static Entry projectDocumentExtract() {
        return entry(
                "project_document_extract",
                "Extract bounded text and locations from one frozen PDF or DOCX file.",
                "project.document.extract",
                "Extract bounded text locations and parser metadata from one "
                        + "exact .pdf or .docx path in the authenticated frozen "
                        + "Project Workspace. Choose this for PDF pages or DOCX "
                        + "paragraphs/table cells, for example "
                        + "{\"path\":\"paper/report.pdf\",\"maxLocations\":20}. "
                        + "Do not use project.read for binary documents, and do "
                        + "not use this tool for .tex structure, spreadsheets, "
                        + "OCR, images, or external resources. The result states "
                        + "partial, truncated, and parse-failure status.",
                objectSchema(
                        Map.of(
                                "path", stringSchema(
                                        1, 1_024,
                                        "(?i).+\\.(pdf|docx)"),
                                "maxCharacters", integerSchema(1_000, 60_000),
                                "maxLocations", integerSchema(1, 200),
                                "includeMetadata", booleanSchema()),
                        List.of("path")),
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
    }

    private static Entry projectSpreadsheetInspect() {
        return entry(
                "project_spreadsheet_inspect",
                "Inspect bounded metadata and cell samples from one frozen XLSX workbook.",
                "project.spreadsheet.inspect",
                "Inspect one exact .xlsx workbook in the authenticated frozen "
                        + "Project Workspace. Return bounded sheet dimensions, "
                        + "headers, typed cell samples, formula presence, and "
                        + "partial/truncated/parse-failure status. Example: "
                        + "{\"path\":\"results/metrics.xlsx\",\"sheetNames\":["
                        + "\"Summary\"],\"maxRowsPerSheet\":20}. Choose this "
                        + "for XLSX; keep CSV, JSON, YAML, text, Markdown, and "
                        + "logs with project.experiment.summary. Formulas are "
                        + "never evaluated, macros are never executed, and "
                        + "external links are never resolved.",
                objectSchema(
                        Map.of(
                                "path", stringSchema(
                                        1, 1_024, "(?i).+\\.xlsx"),
                                "sheetNames", arraySchema(
                                        stringSchema(1, 100),
                                        1, 20, true),
                                "maxRowsPerSheet", integerSchema(1, 100),
                                "maxColumnsPerSheet", integerSchema(1, 50)),
                        List.of("path")),
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
    }

    private static Entry projectLatexOutline() {
        return entry(
                "project_latex_outline",
                "Extract a bounded LaTeX outline from frozen Project files.",
                "project.latex.outline",
                "Inspect one to twenty exact .tex files in the authenticated "
                        + "frozen Project Workspace. Return conservative "
                        + "section, label, citation, float, and optional "
                        + "formula-reference locations. This parser does not "
                        + "expand includes or claim a complete LaTeX AST. "
                        + "Choose it to learn section/float/citation structure; "
                        + "do not use it to decide whether references resolve, "
                        + "whether float assets exist, or whether protected "
                        + "tokens changed. Those are separate audit tools. "
                        + "Example input: {\"relativePaths\":[\"paper/main.tex\"],"
                        + "\"includeFormulaReferences\":true}.",
                objectSchema(
                        Map.of(
                                "relativePaths", arraySchema(
                                        stringSchema(1, 1_024,
                                                "(?i).+\\.tex"),
                                        1, 20, true),
                                "includeFormulaReferences", booleanSchema()),
                        List.of("relativePaths")),
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
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
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
    }

    private static Entry projectLatexCrossrefAudit() {
        return entry(
                "project_latex_crossref_audit",
                "Audit LaTeX labels and references in frozen Project files.",
                "project.latex.crossref.audit",
                "Inspect one to twenty exact .tex files in the authenticated "
                        + "frozen Project Workspace. Report duplicate labels, "
                        + "unresolved ref/eqref/autoref/pageref/cref targets, "
                        + "and optionally unreferenced labels. This is a "
                        + "bounded syntax audit; it does not expand included "
                        + "files or prove successful LaTeX compilation. Choose "
                        + "this for label/ref consistency, not for document "
                        + "outline, float assets, or protected-token inventory.",
                objectSchema(
                        Map.of(
                                "relativePaths", arraySchema(
                                        stringSchema(1, 1_024,
                                                "(?i).+\\.tex"),
                                        1, 20, true),
                                "includeUnreferencedLabels", booleanSchema()),
                        List.of("relativePaths")),
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
    }

    private static Entry projectLatexFloatAudit() {
        return entry(
                "project_latex_float_audit",
                "Audit LaTeX figures, tables, captions, labels, and assets.",
                "project.latex.float.audit",
                "Inspect one to twenty exact .tex files in the authenticated "
                        + "frozen Project Workspace. Inventory figure and table "
                        + "environments, captions, labels, references, and "
                        + "normalized local includegraphics assets. The parser "
                        + "does not expand includes, graphicspath, macros, or "
                        + "inspect image contents. Choose this for figures and "
                        + "tables, not for general outline, cross-reference "
                        + "consistency, or protected-token comparison.",
                objectSchema(
                        Map.of(
                                "relativePaths", arraySchema(
                                        stringSchema(1, 1_024,
                                                "(?i).+\\.tex"),
                                        1, 20, true),
                                "checkAssetExistence", booleanSchema()),
                        List.of("relativePaths")),
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
    }

    private static Entry projectLatexProtectedInventory() {
        return entry(
                "project_latex_protected_inventory",
                "Inventory protected LaTeX elements for safe comparison.",
                "project.latex.protected.inventory",
                "Inspect one to twenty exact .tex files in the authenticated "
                        + "frozen Project Workspace. Return a stable bounded "
                        + "inventory of citation keys, references, labels, "
                        + "protected environments, and optional hashes of "
                        + "line-local math tokens. Math content itself is not "
                        + "returned. Use before and after edits to compare "
                        + "protected facts; this does not compile LaTeX. Do not "
                        + "use it as a document outline, cross-reference audit, "
                        + "or float audit.",
                objectSchema(
                        Map.of(
                                "relativePaths", arraySchema(
                                        stringSchema(1, 1_024,
                                                "(?i).+\\.tex"),
                                        1, 20, true),
                                "includeMathHashes", booleanSchema()),
                        List.of("relativePaths")),
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
    }

    private static Entry projectPaperAcronymAudit() {
        return entry(
                "project_paper_acronym_audit",
                "Audit local acronym definitions and casing in paper text.",
                "project.paper.acronym.audit",
                "Inspect one to thirty exact .tex, .md, or .txt files in the "
                        + "authenticated frozen Project Workspace. Report "
                        + "locally observed acronym definitions, undefined "
                        + "uppercase uses, use-before-definition, differing "
                        + "definitions, and casing variants. This conservative "
                        + "heuristic does not decide domain correctness.",
                objectSchema(
                        Map.of(
                                "relativePaths", arraySchema(
                                        stringSchema(1, 1_024,
                                                "(?i).+\\.(tex|md|txt)"),
                                        1, 30, true),
                                "minimumAcronymLength", integerSchema(2, 8)),
                        List.of("relativePaths")),
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
    }

    private static Entry projectPaperLanguageStats() {
        return entry(
                "project_paper_language_stats",
                "Measure bounded prose and sentence statistics in paper text.",
                "project.paper.language.stats",
                "Inspect one to thirty exact .tex, .md, or .txt files in the "
                        + "authenticated frozen Project Workspace. After "
                        + "conservative markup removal, report file and optional "
                        + "section character, word-like-unit, sentence, "
                        + "paragraph, and long-sentence locations. These are "
                        + "descriptive signals, not grammar or quality scores.",
                objectSchema(
                        Map.of(
                                "relativePaths", arraySchema(
                                        stringSchema(1, 1_024,
                                                "(?i).+\\.(tex|md|txt)"),
                                        1, 30, true),
                                "longSentenceWordLikeUnits",
                                integerSchema(10, 200),
                                "includeSections", booleanSchema()),
                        List.of("relativePaths")),
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
    }

    private static Entry projectCodeSymbols() {
        return entry(
                "project_code_symbols",
                "Extract conservative symbols from frozen Project source files.",
                "project.code.symbols",
                "Inspect one to thirty exact Java, Python, or MATLAB source "
                        + "files in the authenticated frozen Project Workspace. "
                        + "Return conservative type, function or method, "
                        + "parameter, entry-point, and optional dependency "
                        + "locations. This does not perform type inference or "
                        + "construct a complete call graph.",
                objectSchema(
                        Map.of(
                                "relativePaths", arraySchema(
                                        stringSchema(1, 1_024,
                                                "(?i).+\\.(java|py|m)"),
                                        1, 30, true),
                                "symbolQuery", stringSchema(1, 160),
                                "includeDependencies", booleanSchema()),
                        List.of("relativePaths")),
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
    }

    private static Entry projectExperimentSummary() {
        return entry(
                "project_experiment_summary",
                "Summarize bounded experiment metrics and reports in the frozen Project.",
                "project.experiment.summary",
                "Inspect one to thirty exact CSV, JSON, simple YAML, text, "
                        + "Markdown, or log files in the authenticated frozen "
                        + "Project Workspace. Return only observed values and "
                        + "parse status; malformed structured input is never "
                        + "presented as a valid summary.",
                objectSchema(
                        Map.of(
                                "relativePaths", arraySchema(
                                        stringSchema(1, 1_024,
                                                "(?i).+\\.(csv|json|yaml|yml|txt|md|log)"),
                                        1, 30, true),
                                "metricNames", arraySchema(
                                        stringSchema(1, 80),
                                        1, 30, true),
                                "maxRowsPerFile", integerSchema(1, 500)),
                        List.of("relativePaths")),
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
    }

    private static Entry projectCrossMaterialSearch() {
        return entry(
                "project_cross_material_search",
                "Find a literal concept across frozen Project materials.",
                "project.cross-material.search",
                "Search authorized frozen Project text for one literal query. "
                        + "Optionally restrict the scan to one to fifty exact "
                        + "Project-relative paths and return at most one hundred "
                        + "deterministically ordered matches. A cross-material "
                        + "link is reported only when at least two distinct "
                        + "files contain the query. This is not semantic, "
                        + "vector, retrieval, or network search. Choose this, "
                        + "for example, to prove that an accuracy claim occurs "
                        + "in both a paper and a report. Do not choose it for "
                        + "ordinary one-or-many-file discovery where no "
                        + "cross-file proof is required; use project.search.",
                objectSchema(
                        Map.of(
                                "query", stringSchema(1, 200),
                                "relativePaths", arraySchema(
                                        stringSchema(1, 1_024),
                                        1, 50, true),
                                "maxMatches", integerSchema(1, 100)),
                        List.of("query")),
                Set.of(Capability.READ_PROJECT),
                projectReadRequirements(),
                ExecutionTarget.PROJECT);
    }

    private static Entry projectCandidateCompose() {
        return entry(
                "project_candidate",
                "Replace Project files in the isolated working copy.",
                "project.candidate.compose",
                "Write complete replacement text for one to four existing "
                        + "Project files into the isolated working copy. The "
                        + "calling model must provide every target path and the "
                        + "entire resulting file text; this tool does not ask "
                        + "another model to write or repair code. It validates "
                        + "the exact paths and text and persists the resulting "
                        + "working-copy diff.",
                objectSchema(
                        Map.of(
                                "operation", constantStringSchema("compose"),
                                "paths", arraySchema(
                                        stringSchema(1, 1_024), 1, 4, true),
                                "replacements", arraySchema(
                                        objectSchema(
                                                Map.of(
                                                        "path", stringSchema(
                                                                1, 1_024),
                                                        "text", stringSchema(
                                                                0, 65_536)),
                                                List.of("path", "text")),
                                        1, 4, false)),
                        List.of("operation", "paths", "replacements")),
                Set.of(Capability.READ_PROJECT,
                        Capability.WRITE_WORKSPACE),
                Set.of(RoutingRequirement.TOOL_USE,
                        RoutingRequirement.PROJECT_FILE_ACCESS,
                        RoutingRequirement.MODIFICATION),
                ExecutionTarget.PROJECT);
    }

    private static Entry sandboxExecute() {
        return entry(
                "sandbox_execute",
                "Execute bounded code or commands in the isolated Sandbox.",
                "sandbox.execute",
                "Run selected Project paths in the existing isolated E2B "
                        + "Sandbox. Always run the latest isolated working-copy "
                        + "content, including changes saved by earlier Steps. "
                        + "This tool executes code but does not edit files. "
                        + "Supported argv profiles include "
                        + "yanban-runner java/python/c/cpp, Maven test/verify, "
                        + "direct Java source launch, direct javac, and bounded "
                        + "git checks. Exact completeArguments examples: "
                        + "Python {\"paths\":[\"src/test.py\"],\"argv\":[\"yanban-runner\",\"python\",\"src/test.py\"]}; "
                        + "Java {\"paths\":[\"src/Main.java\"],\"argv\":[\"yanban-runner\",\"java\",\"src/Main.java\"]}; "
                        + "C {\"paths\":[\"src/main.c\"],\"argv\":[\"yanban-runner\",\"c\",\"src/main.c\"]}; "
                        + "C++ {\"paths\":[\"src/main.cpp\"],\"argv\":[\"yanban-runner\",\"cpp\",\"src/main.cpp\"]}. "
                        + "Use these argv shapes exactly with the actual normalized Project-relative source path. "
                        + "Prefer yanban-runner java path.java for Java compile-and-run. Direct javac accepts only one "
                        + "or more normalized .java source paths and no flags. "
                        + "Direct java accepts only -version or one normalized "
                        + ".java source path; it does not accept a compiled class "
                        + "name. Before the first Java or Python run, inspect the "
                        + "source imports and declare every non-standard dependency "
                        + "in that first run. Java uses exact "
                        + "--dependency=group:artifact:version arguments. Python "
                        + "uses exact --dependency=package==version arguments. "
                        + "The Broker downloads all declared direct and transitive "
                        + "dependencies before it disables networking and runs the "
                        + "source; do not first run without required dependencies.",
                objectSchema(
                        Map.of(
                                "paths", arraySchema(
                                        stringSchema(1, 1_024), 1, 32, true),
                                "argv", arraySchema(
                                        stringSchema(1, 4_096), 1, 64, false)),
                        List.of("paths", "argv")),
                Set.of(Capability.EXECUTE_COMMAND,
                        Capability.INSTALL_DEPENDENCY),
                Set.of(RoutingRequirement.TOOL_USE,
                        RoutingRequirement.EXECUTION),
                ExecutionTarget.SANDBOX);
    }

    private static Set<RoutingRequirement> projectReadRequirements() {
        return Set.of(RoutingRequirement.TOOL_USE,
                RoutingRequirement.PROJECT_FILE_ACCESS);
    }

    private static Entry entry(
            String alias,
            String publicDescription,
            String id,
            String modelDescription,
            ObjectValue schema,
            Set<Capability> requiredCapabilities,
            Set<RoutingRequirement> routingRequirements,
            ExecutionTarget executionTarget) {
        return entry(
                alias, publicDescription, id, modelDescription, schema,
                requiredCapabilities, routingRequirements, executionTarget,
                Set.of());
    }

    private static Entry entry(
            String alias,
            String publicDescription,
            String id,
            String modelDescription,
            ObjectValue schema,
            Set<Capability> requiredCapabilities,
            Set<RoutingRequirement> routingRequirements,
            ExecutionTarget executionTarget,
            Set<String> requiredNetworkAllowlistEntries) {
        return new Entry(
                alias,
                publicDescription,
                new ToolDescriptor(
                        new ToolId(id), modelDescription,
                        requiredCapabilities, schema),
                permissionReference(requiredCapabilities),
                Set.of(ExecutionTier.SANDBOX_STANDARD),
                requiredNetworkAllowlistEntries,
                routingRequirements,
                executionTarget);
    }

    private static String permissionReference(
            Set<Capability> requiredCapabilities) {
        if (requiredCapabilities.equals(Set.of(Capability.READ_PROJECT))) {
            return "permission.project-read";
        }
        if (requiredCapabilities.equals(Set.of(
                Capability.READ_PROJECT, Capability.WRITE_WORKSPACE))) {
            return "permission.project-write";
        }
        if (requiredCapabilities.equals(Set.of(
                Capability.EXECUTE_COMMAND,
                Capability.INSTALL_DEPENDENCY))) {
            return "permission.sandbox-execute-install";
        }
        if (requiredCapabilities.equals(Set.of(
                Capability.ACCESS_NETWORK,
                Capability.INVOKE_EXTERNAL_TOOL))) {
            return "permission.literature-network-external";
        }
        throw new IllegalStateException(
                "V2 tool capability set has no permission reference");
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
            ToolDescriptor descriptor,
            String permissionRef,
            Set<ExecutionTier> allowedExecutionTiers,
            Set<String> requiredNetworkAllowlistEntries,
            Set<RoutingRequirement> routingRequirements,
            ExecutionTarget executionTarget) {
        public Entry {
            if (publicAlias == null
                    || !publicAlias.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException(
                        "invalid V2 public tool alias");
            }
            if (publicDescription == null || publicDescription.isBlank()
                    || descriptor == null
                    || permissionRef == null || permissionRef.isBlank()
                    || allowedExecutionTiers == null
                    || allowedExecutionTiers.isEmpty()
                    || requiredNetworkAllowlistEntries == null
                    || routingRequirements == null
                    || routingRequirements.isEmpty()
                    || executionTarget == null) {
                throw new IllegalArgumentException(
                        "invalid V2 product tool entry");
            }
            if (requiredNetworkAllowlistEntries.stream().anyMatch(
                    value -> value == null || value.isBlank())
                    || descriptor.requiredCapabilities().contains(
                    Capability.ACCESS_NETWORK)
                    != !requiredNetworkAllowlistEntries.isEmpty()) {
                throw new IllegalArgumentException(
                        "invalid V2 product tool network authority");
            }
            allowedExecutionTiers = Set.copyOf(allowedExecutionTiers);
            requiredNetworkAllowlistEntries = Set.copyOf(
                    requiredNetworkAllowlistEntries);
            routingRequirements = Set.copyOf(routingRequirements);
        }
    }

    public enum ExecutionTarget {
        LITERATURE,
        PROJECT,
        SANDBOX
    }
}
