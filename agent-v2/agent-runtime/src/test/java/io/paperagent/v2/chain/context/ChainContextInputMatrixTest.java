package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainContextInputMatrixTest {
    @Test
    void freezesExactlyThirteenModulesFourRolesAndFiftyTwoNonemptyCells() {
        assertEquals(13, ChainContextInputMatrix.orderedModules().size());
        assertEquals(4, ChainRole.values().length);
        assertEquals(52, ChainContextInputMatrix.entries().size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13),
                ChainContextInputMatrix.orderedModules().stream()
                        .map(ChainContextModule::ordinalCode).toList());
        assertTrue(ChainContextInputMatrix.entries().stream()
                .allMatch(entry -> !entry.requirements().isEmpty()));
        assertEquals(52, ChainContextInputMatrix.entries().stream()
                .map(entry -> entry.role() + ":" + entry.module()).distinct().count());
    }

    @Test
    void keepsTheContractCriticalFieldsInTheirRoleCells() {
        assertTrue(ChainContextInputMatrix.requirements(
                ChainRole.EXECUTOR,
                ChainContextModule.PROJECT_AND_INPUT_MATERIALS)
                .containsAll(List.of("project.version", "project.manifest.complete")));
        assertTrue(ChainContextInputMatrix.requirements(
                ChainRole.EXECUTOR,
                ChainContextModule.PLAN_AND_STEP_CONTRACT)
                .contains("plan.currentRevisionComplete"));
        assertTrue(ChainContextInputMatrix.requirements(
                ChainRole.EXECUTOR,
                ChainContextModule.MEMORY_RETRIEVAL_AND_KNOWLEDGE_EVIDENCE)
                .contains("evidence.frozenCompleteCatalog"));
        assertTrue(ChainContextInputMatrix.requirements(
                ChainRole.ANSWER,
                ChainContextModule.CURRENT_STEP_ACTION_TOOLS_AND_ERRORS)
                .contains("action.officialFailureSummaryOnly"));
        assertTrue(ChainContextInputMatrix.requirements(
                ChainRole.ANSWER,
                ChainContextModule.TASK_AND_STEP_RUNTIME_STATE)
                .contains("runtime.answerPayloadTemplate"));
        assertEquals(7, ChainContextInputMatrix.commonFoundation().size());
        assertEquals(13, ChainContextVersionMatrix.entries().size());
        assertFalse(ChainContextVersionMatrix.requirement(
                ChainContextModule.RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS).emptyAllowed());
        assertEquals("priorInvocationOrdinal=0", ChainContextVersionMatrix.requirement(
                ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS).emptyWatermark());
    }

    @Test
    void requiresExplicitEmptyWithFrozenVersionBoundaryAndProjectionDigest() {
        ChainContextVersionMatrix.VersionRequirement version =
                ChainContextVersionMatrix.requirement(ChainContextModule.TASK_CONTRACT);
        var snapshot = new ChainContextSourceSnapshot(
                ChainContextModule.TASK_CONTRACT,
                ChainContextModuleStatus.EMPTY,
                values(version.sourceVersionFields(), "NONE"),
                values(version.readBoundaryFields(), "cut-0"),
                "projection-v1",
                "pagination-v1",
                java.util.Map.of(), java.util.Map.of(), version.emptyWatermark());

        assertEquals(ChainContextModuleStatus.EMPTY, snapshot.presenceKind());
        assertFalse(snapshot.sourceVersion().json().isBlank());
        assertFalse(snapshot.readBoundary().json().isBlank());
        assertFalse(snapshot.projection().sha256().isBlank());

        assertThrows(ChainContextException.class, () -> new ChainContextSourceSnapshot(
                ChainContextModule.TASK_CONTRACT,
                ChainContextModuleStatus.EMPTY,
                values(version.sourceVersionFields(), "NONE"),
                values(version.readBoundaryFields(), "cut-0"),
                "projection-v1",
                "pagination-v1",
                java.util.Map.of(), java.util.Map.of(), "wrong-watermark"));

        assertThrows(ChainContextException.class, () -> new ChainContextSourceSnapshot(
                ChainContextModule.TASK_CONTRACT,
                ChainContextModuleStatus.EMPTY,
                java.util.Map.of(),
                values(version.readBoundaryFields(), "cut-0"),
                "projection-v1",
                "pagination-v1",
                java.util.Map.of(), java.util.Map.of(), version.emptyWatermark()));

        assertThrows(ChainContextException.class, () -> new ChainContextSourceSnapshot(
                ChainContextModule.TASK_CONTRACT,
                ChainContextModuleStatus.EMPTY,
                values(version.sourceVersionFields(), "NONE"),
                values(version.readBoundaryFields(), "cut-0"),
                "projection-v1",
                "pagination-v1",
                java.util.Map.of(),
                java.util.Map.of("unexpected", ChainContextValue.text("value")),
                version.emptyWatermark()));

        ChainContextVersionMatrix.VersionRequirement runtimeRules =
                ChainContextVersionMatrix.requirement(
                        ChainContextModule.RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS);
        assertThrows(ChainContextException.class, () -> new ChainContextSourceSnapshot(
                ChainContextModule.RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS,
                ChainContextModuleStatus.EMPTY,
                values(runtimeRules.sourceVersionFields(), "NONE"),
                values(runtimeRules.readBoundaryFields(), "cut-0"),
                "projection-v1",
                "pagination-v1",
                java.util.Map.of(), java.util.Map.of(), runtimeRules.emptyWatermark()));
    }

    private static java.util.Map<String, ChainContextValue> values(
            List<String> names, String value) {
        java.util.Map<String, ChainContextValue> result = new java.util.LinkedHashMap<>();
        names.forEach(name -> result.put(name, ChainContextValue.text(value)));
        return result;
    }

    @Test
    void deterministicBodyPagingUsesStableUniqueIdsAndExactDigests() {
        record Value(String id) {
        }
        List<Value> page = ChainContextBodySource.deterministicPage(
                List.of(new Value("c"), new Value("a"), new Value("b")),
                Value::id,
                new ChainContextBodySource.BodyRequest(
                        "task-1", "revision-1",
                        ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                        "PROJECT_FILE", "file-root", "version-1",
                        ChainContextDigests.sha256("catalog"), "a", 2));
        assertEquals(List.of("b", "c"), page.stream().map(Value::id).toList());

        var request = new ChainContextBodySource.BodyRequest(
                "task-1", "revision-1", ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                "PROJECT_FILE", "file-root", "version-1",
                ChainContextDigests.sha256("catalog"), null, 2);
        var first = new ChainContextBodySource.BodyItem(
                "file-a", "version-1", "alpha", ChainContextDigests.sha256("alpha"));
        var second = new ChainContextBodySource.BodyItem(
                "file-b", "version-1", "beta", ChainContextDigests.sha256("beta"));
        var bodyPage = new ChainContextBodySource.BodyPage(
                List.of(first, second), "file-b", false).validateFor(request);
        assertEquals("file-b", bodyPage.nextAfterItemId());

        assertThrows(ChainContextException.class, () -> new ChainContextBodySource.BodyPage(
                List.of(second, first), null, true));
    }

    @Test
    void derivesVisibleSourceRefsOnlyFromTheCanonicalValuesThatUseThem() {
        ChainContextModule module = ChainContextModule.TASK_CONTRACT;
        ChainContextVersionMatrix.VersionRequirement version =
                ChainContextVersionMatrix.requirement(module);
        var sourceVersion = values(version.sourceVersionFields(), "source");
        sourceVersion.put(version.sourceVersionFields().get(0),
                ChainContextValue.referencedText("source", "authority.source"));
        var readBoundary = values(version.readBoundaryFields(), "boundary");
        readBoundary.put(version.readBoundaryFields().get(0),
                ChainContextValue.referencedText("boundary", "authority.boundary"));
        var snapshot = new ChainContextSourceSnapshot(
                module,
                ChainContextModuleStatus.PRESENT,
                sourceVersion,
                readBoundary,
                "projection-v1",
                "pagination-v1",
                java.util.Map.of("page", ChainContextValue.referencedText(
                        "first", "authority.parameter")),
                java.util.Map.of("taskFrame.complete",
                        ChainContextValue.referencedText("body", "authority.projection")),
                null);

        assertEquals(Set.of("authority.boundary", "authority.parameter",
                "authority.projection", "authority.source"), snapshot.visibleSourceRefs());
        assertTrue(snapshot.projection().json().contains(
                "\"visibleSourceRefs\":[\"authority.boundary\",\"authority.parameter\","
                        + "\"authority.projection\",\"authority.source\"]"));
    }
}
