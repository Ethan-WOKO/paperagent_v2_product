package com.yanban.api.agent.v2.tool;

import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2ProductToolCatalogTest {
    @Test
    void ownsStableOrderedIdsAliasesAndStrictObjectSchemas() {
        assertEquals(
                List.of(
                        "literature.search",
                        "project.read",
                        "project.search",
                        "project.latex.outline",
                        "project.latex.crossref.audit",
                        "project.latex.float.audit",
                        "project.latex.protected.inventory",
                        "project.paper.acronym.audit",
                        "project.paper.language.stats",
                        "project.bibtex.audit",
                        "project.code.symbols",
                        "project.experiment.summary",
                        "project.cross-material.search",
                        "project.candidate.compose",
                        "sandbox.execute"),
                V2ProductToolCatalog.descriptors().stream()
                        .map(value -> value.id().value()).toList());
        assertEquals(
                List.of(
                        "literature_search",
                        "project_read",
                        "project_search",
                        "project_latex_outline",
                        "project_latex_crossref_audit",
                        "project_latex_float_audit",
                        "project_latex_protected_inventory",
                        "project_paper_acronym_audit",
                        "project_paper_language_stats",
                        "project_bibtex_audit",
                        "project_code_symbols",
                        "project_experiment_summary",
                        "project_cross_material_search",
                        "project_candidate",
                        "sandbox_execute"),
                V2ProductToolCatalog.entries().stream()
                        .map(V2ProductToolCatalog.Entry::publicAlias).toList());
        assertTrue(V2ProductToolCatalog.descriptors().stream()
                .allMatch(descriptor ->
                        "object".equals(text(
                                descriptor.parameterSchema(), "type"))
                                && !bool(
                                descriptor.parameterSchema(),
                                "additionalProperties")));
        assertTrue(V2ProductToolCatalog.entries().stream()
                .allMatch(entry -> !entry.publicDescription().isBlank()
                        && !entry.descriptor().description().isBlank()));
        assertEquals(
                java.util.Set.of(Capability.READ_PROJECT),
                V2ProductToolCatalog.requireDescriptor(
                        id("project.bibtex.audit")).requiredCapabilities());
        assertTrue(V2ProductToolCatalog.requireDescriptor(
                id("project.bibtex.audit")).description().contains(
                        "Example input"));
        assertTrue(V2ProductToolCatalog.entries().stream()
                .allMatch(entry -> entry.routingRequirements().contains(
                        RoutingRequirement.TOOL_USE)));
        assertTrue(V2ProductToolCatalog.entries().stream()
                .allMatch(entry -> !entry.descriptor()
                        .requiredCapabilities().isEmpty()));
        assertTrue(V2ProductToolCatalog.entries().stream()
                .filter(entry -> entry.publicAlias().startsWith("project_"))
                .allMatch(entry -> entry.routingRequirements().contains(
                        RoutingRequirement.PROJECT_FILE_ACCESS)));
        assertTrue(V2ProductToolCatalog.entries().stream()
                .filter(entry -> entry.publicAlias().startsWith("project_"))
                .allMatch(entry -> entry.executionTarget()
                        == V2ProductToolCatalog.ExecutionTarget.PROJECT));
        assertEquals(V2ProductToolCatalog.ExecutionTarget.LITERATURE,
                V2ProductToolCatalog.entry(id("literature.search"))
                        .orElseThrow().executionTarget());
        assertEquals(V2ProductToolCatalog.ExecutionTarget.SANDBOX,
                V2ProductToolCatalog.entry(id("sandbox.execute"))
                        .orElseThrow().executionTarget());
    }

    @Test
    void acceptsAndRejectsLiteratureArgumentsWithoutExecution() {
        ToolId tool = id("literature.search");
        assertTrue(accepts(tool, object(Map.of(
                "query", text("agent planning"),
                "topK", number(10),
                "yearFrom", number(2020),
                "includeBibtex", bool(true)))));
        assertFalse(accepts(tool, object(Map.of(
                "query", text("agent planning"),
                "topK", number(21)))));
        assertFalse(accepts(tool, object(Map.of(
                "query", text("agent planning"),
                "unexpected", text("not allowed")))));
    }

    @Test
    void acceptsAndRejectsProjectReadAndSearchArgumentsWithoutExecution() {
        assertTrue(accepts(id("project.read"), object(Map.of(
                "path", text("src/main/java/Sort.java")))));
        assertFalse(accepts(id("project.read"), object(Map.of(
                "path", text("")))));

        assertTrue(accepts(id("project.search"), object(Map.of(
                "query", text("mergeSort"),
                "maxResults", number(10)))));
        assertFalse(accepts(id("project.search"), object(Map.of(
                "query", text("mergeSort"),
                "maxResults", number(0)))));
        assertFalse(accepts(id("project.search"), object(Map.of(
                "query", text("mergeSort")))));
    }

    @Test
    void acceptsAndRejectsCandidateArgumentsWithoutExecution() {
        ToolId tool = id("project.candidate.compose");
        assertTrue(accepts(tool, object(Map.of(
                "operation", text("compose"),
                "paths", list(text("paper/main.tex"))))));
        assertFalse(accepts(tool, object(Map.of(
                "operation", text("apply"),
                "paths", list(text("paper/main.tex"))))));
        assertFalse(accepts(tool, object(Map.of(
                "operation", text("compose"),
                "paths", list(
                        text("paper/main.tex"),
                        text("paper/main.tex"))))));
    }

    @Test
    void acceptsAndRejectsBibtexAuditArgumentsWithoutExecution() {
        ToolId tool = id("project.bibtex.audit");
        assertTrue(accepts(tool, object(Map.of(
                "paths", list(
                        text("paper/references.bib"),
                        text("paper/main.tex")),
                "includeUnusedEntries", bool(true)))));
        assertTrue(accepts(tool, object(Map.of(
                "paths", list(text("paper/references.bib"))))));
        assertFalse(accepts(tool, object(Map.of(
                "paths", list()))));
        assertFalse(accepts(tool, object(Map.of(
                "paths", list(
                        text("paper/references.bib"),
                        text("paper/references.bib"))))));
        assertFalse(accepts(tool, object(Map.of(
                "paths", list(text("paper/references.csv"))))));
        assertFalse(accepts(tool, object(Map.of(
                "paths", list(text("paper/references.bib")),
                "unexpected", text("not allowed")))));
    }

    @Test
    void acceptsAndRejectsReadOnlyProjectAnalysisBundleArguments() {
        assertTrue(accepts(id("project.latex.outline"), object(Map.of(
                "relativePaths", list(text("paper/main.tex")),
                "includeFormulaReferences", bool(true)))));
        assertFalse(accepts(id("project.latex.outline"), object(Map.of(
                "relativePaths", list(text("paper/main.md"))))));

        assertTrue(accepts(id("project.code.symbols"), object(Map.of(
                "relativePaths", list(text("src/Main.java")),
                "symbolQuery", text("main"),
                "includeDependencies", bool(true)))));
        assertFalse(accepts(id("project.code.symbols"), object(Map.of(
                "relativePaths", list(text("src/Main.class"))))));

        assertTrue(accepts(id("project.experiment.summary"), object(Map.of(
                "relativePaths", list(text("results/metrics.csv")),
                "metricNames", list(text("accuracy")),
                "maxRowsPerFile", number(100)))));
        assertFalse(accepts(id("project.experiment.summary"), object(Map.of(
                "relativePaths", list(text("results/metrics.csv")),
                "maxRowsPerFile", number(501)))));

        assertTrue(accepts(id("project.cross-material.search"),
                object(Map.of(
                        "query", text("accuracy"),
                        "relativePaths", list(
                                text("paper/main.tex"),
                                text("results/metrics.csv")),
                        "maxMatches", number(20)))));
        assertTrue(accepts(id("project.cross-material.search"),
                object(Map.of("query", text("accuracy")))));
        assertFalse(accepts(id("project.cross-material.search"),
                object(Map.of(
                        "query", text("accuracy"),
                        "maxMatches", number(101)))));
    }

    @Test
    void acceptsAndRejectsPaperQualityAuditBundleArguments() {
        assertTrue(accepts(id("project.latex.crossref.audit"), object(Map.of(
                "relativePaths", list(text("paper/main.tex")),
                "includeUnreferencedLabels", bool(true)))));
        assertFalse(accepts(id("project.latex.crossref.audit"), object(Map.of(
                "relativePaths", list(text("paper/main.md"))))));

        assertTrue(accepts(id("project.latex.float.audit"), object(Map.of(
                "relativePaths", list(text("paper/main.tex")),
                "checkAssetExistence", bool(true)))));
        assertFalse(accepts(id("project.latex.float.audit"), object(Map.of(
                "relativePaths", list(text("paper/main.tex")),
                "checkAssetExistence", text("yes")))));

        assertTrue(accepts(id("project.latex.protected.inventory"),
                object(Map.of(
                        "relativePaths", list(text("paper/main.tex")),
                        "includeMathHashes", bool(false)))));
        assertFalse(accepts(id("project.latex.protected.inventory"),
                object(Map.of("relativePaths", list()))));

        assertTrue(accepts(id("project.paper.acronym.audit"), object(Map.of(
                "relativePaths", list(text("paper/main.tex")),
                "minimumAcronymLength", number(2)))));
        assertFalse(accepts(id("project.paper.acronym.audit"), object(Map.of(
                "relativePaths", list(text("paper/main.pdf"))))));
        assertFalse(accepts(id("project.paper.acronym.audit"), object(Map.of(
                "relativePaths", list(text("paper/main.tex")),
                "minimumAcronymLength", number(1)))));

        assertTrue(accepts(id("project.paper.language.stats"), object(Map.of(
                "relativePaths", list(text("paper/main.md")),
                "longSentenceWordLikeUnits", number(35),
                "includeSections", bool(true)))));
        assertFalse(accepts(id("project.paper.language.stats"), object(Map.of(
                "relativePaths", list(text("paper/main.md")),
                "longSentenceWordLikeUnits", number(201)))));
    }

    @Test
    void acceptsAndRejectsSandboxArgumentsWithoutExecution() {
        ToolId tool = id("sandbox.execute");
        assertTrue(accepts(tool, object(Map.of(
                "paths", list(text("src/main/java/Sort.java")),
                "argv", list(
                        text("yanban-runner"),
                        text("java"),
                        text("src/main/java/Sort.java"))))));
        assertFalse(accepts(tool, object(Map.of(
                "paths", list(text("src/main/java/Sort.java")),
                "argv", list()))));
        assertFalse(accepts(tool, object(Map.of(
                "paths", list(
                        text("src/main/java/Sort.java"),
                        text("src/main/java/Sort.java")),
                "argv", list(text("java"), text("-version"))))));
    }

    @Test
    void unknownAliasesAndToolIdsFailClosed() {
        assertTrue(V2ProductToolCatalog
                .toolIdForPublicAlias("project_read").isPresent());
        assertTrue(V2ProductToolCatalog
                .toolIdForPublicAlias("project.read").isEmpty());
        assertTrue(V2ProductToolCatalog
                .descriptor(id("unknown.tool")).isEmpty());
        assertFalse(V2ProductToolCatalog.acceptsArguments(
                id("unknown.tool"), object(Map.of())));
    }

    private static boolean accepts(ToolId id, ObjectValue arguments) {
        return V2ProductToolCatalog.acceptsArguments(id, arguments);
    }

    private static ToolId id(String value) {
        return new ToolId(value);
    }

    private static ObjectValue object(Map<String, ContractValue> values) {
        return new ObjectValue(values);
    }

    private static TextValue text(String value) {
        return new TextValue(value);
    }

    private static NumberValue number(int value) {
        return new NumberValue(BigDecimal.valueOf(value));
    }

    private static BooleanValue bool(boolean value) {
        return new BooleanValue(value);
    }

    private static ListValue list(ContractValue... values) {
        return new ListValue(List.of(values));
    }

    private static String text(ObjectValue value, String field) {
        return ((TextValue) value.values().get(field)).value();
    }

    private static boolean bool(ObjectValue value, String field) {
        return ((BooleanValue) value.values().get(field)).value();
    }
}
