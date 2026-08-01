package com.yanban.api.agent.v2.tool;

import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolId;
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
                        "project.candidate.compose",
                        "sandbox.execute"),
                V2ProductToolCatalog.descriptors().stream()
                        .map(value -> value.id().value()).toList());
        assertEquals(
                List.of(
                        "literature_search",
                        "project_read",
                        "project_search",
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
