package io.paperagent.v2.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class FinalSynthesisValidationTest {
    private static final FinalSynthesisId SYNTHESIS_ID =
            new FinalSynthesisId("synthesis-opaque-sentinel");
    private static final TaskFrameId TASK_FRAME_ID = new TaskFrameId("task-opaque-sentinel");
    private static final PlanId PLAN_ID = new PlanId("plan-opaque-sentinel");
    private static final PlanRevisionId PLAN_REVISION_ID =
            new PlanRevisionId("revision-opaque-sentinel");
    private static final ProjectVersionRef SOURCE_PROJECT_VERSION =
            new ProjectVersionRef("project-opaque-sentinel", "version-opaque-sentinel");
    private static final ReceiptId RECEIPT_ONE = new ReceiptId("receipt-one-opaque-sentinel");
    private static final ReceiptId RECEIPT_TWO = new ReceiptId("receipt-two-opaque-sentinel");
    private static final Instant OBSERVED_AT = Instant.parse("2026-07-27T01:02:03Z");
    private static final String NARRATIVE = "narrative-opaque-sentinel";
    private static final WorkspaceDiff WORKSPACE_DIFF = new WorkspaceDiff(
            new DiffId("diff-opaque-sentinel"),
            new WorkspaceRef(
                    new WorkspaceId("workspace-opaque-sentinel"),
                    SOURCE_PROJECT_VERSION),
            List.of(new WorkspaceDiffEntry(
                    DiffKind.ADD,
                    new ProjectPath("delivery-opaque-sentinel.txt"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new ContentHash("sha256", "a".repeat(64))),
                    java.util.Map.of("evidence", "metadata-opaque-sentinel"))),
            OBSERVED_AT);

    @Test
    void acceptsReferencedDeliveryEvidenceAndRedactsItsValues() {
        FinalSynthesis synthesis = synthesis(Optional.of(SOURCE_PROJECT_VERSION), List.of(RECEIPT_ONE, RECEIPT_TWO));
        FinalSynthesis withoutSourceVersion = synthesis(Optional.empty(), List.of());

        assertEquals(SYNTHESIS_ID, synthesis.id());
        assertEquals(TASK_FRAME_ID, synthesis.taskFrameId());
        assertEquals(PLAN_ID, synthesis.planId());
        assertEquals(PLAN_REVISION_ID, synthesis.planRevisionId());
        assertEquals(Optional.of(SOURCE_PROJECT_VERSION), synthesis.sourceProjectVersion());
        assertEquals(WORKSPACE_DIFF, synthesis.workspaceDiff());
        assertEquals(List.of(RECEIPT_ONE, RECEIPT_TWO), synthesis.receiptIds());
        assertEquals(NARRATIVE, synthesis.narrative());
        assertEquals(OBSERVED_AT, synthesis.observedAt());
        assertTrue(withoutSourceVersion.sourceProjectVersion().isEmpty());
        assertEquals(
                "FinalSynthesis[id=<provided>, taskFrameId=<provided>, planId=<provided>, "
                        + "planRevisionId=<provided>, sourceProjectVersion=<provided>, "
                        + "workspaceDiff=<provided>, receiptIds=<provided>, narrative=<provided>, "
                        + "observedAt=<provided>]",
                synthesis.toString());
        for (String sentinel : Set.of(
                SYNTHESIS_ID.value(),
                TASK_FRAME_ID.value(),
                PLAN_ID.value(),
                PLAN_REVISION_ID.value(),
                SOURCE_PROJECT_VERSION.projectId(),
                SOURCE_PROJECT_VERSION.versionId(),
                WORKSPACE_DIFF.id().value(),
                WORKSPACE_DIFF.workspace().id().value(),
                WORKSPACE_DIFF.entries().get(0).path().value(),
                WORKSPACE_DIFF.entries().get(0).afterHash().orElseThrow().value(),
                WORKSPACE_DIFF.entries().get(0).metadata().get("evidence"),
                RECEIPT_ONE.value(),
                RECEIPT_TWO.value(),
                NARRATIVE,
                OBSERVED_AT.toString())) {
            assertFalse(synthesis.toString().contains(sentinel), sentinel);
        }
    }

    @Test
    void copiesOptionalAndReceiptReferencesWhileKeepingReceiptOrderImmutable() {
        Optional<ProjectVersionRef> source = Optional.of(SOURCE_PROJECT_VERSION);
        List<ReceiptId> receipts = new ArrayList<>(List.of(RECEIPT_ONE, RECEIPT_TWO));

        FinalSynthesis synthesis = synthesis(source, receipts);
        receipts.clear();

        assertNotSame(source, synthesis.sourceProjectVersion());
        assertNotSame(SOURCE_PROJECT_VERSION, synthesis.sourceProjectVersion().orElseThrow());
        assertEquals(Optional.of(SOURCE_PROJECT_VERSION), synthesis.sourceProjectVersion());
        assertEquals(List.of(RECEIPT_ONE, RECEIPT_TWO), synthesis.receiptIds());
        assertThrows(UnsupportedOperationException.class, () -> synthesis.receiptIds().add(RECEIPT_ONE));
    }

    @Test
    void rejectsDuplicateReceiptReferences() {
        assertViolation(
                () -> synthesis(Optional.empty(), List.of(RECEIPT_ONE, RECEIPT_ONE)),
                ViolationCode.DUPLICATE_ID,
                "finalSynthesis.receiptIds");
    }

    @Test
    void rejectsASourceProjectVersionThatDoesNotMatchWorkspaceDiffProvenance() {
        assertViolation(
                () -> synthesis(
                        Optional.of(new ProjectVersionRef(
                                "other-project-opaque-sentinel", "other-version-opaque-sentinel")),
                        List.of(RECEIPT_ONE)),
                ViolationCode.INCONSISTENT_REFERENCE,
                "finalSynthesis.sourceProjectVersion");
    }

    @Test
    void rejectsMissingComponentsAndBlankNarrativeWithExactPaths() {
        assertViolation(
                () -> new FinalSynthesis(
                        null, TASK_FRAME_ID, PLAN_ID, PLAN_REVISION_ID, Optional.empty(), WORKSPACE_DIFF,
                        List.of(), NARRATIVE, OBSERVED_AT),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesis.id");
        assertViolation(
                () -> new FinalSynthesis(
                        SYNTHESIS_ID, null, PLAN_ID, PLAN_REVISION_ID, Optional.empty(), WORKSPACE_DIFF,
                        List.of(), NARRATIVE, OBSERVED_AT),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesis.taskFrameId");
        assertViolation(
                () -> new FinalSynthesis(
                        SYNTHESIS_ID, TASK_FRAME_ID, null, PLAN_REVISION_ID, Optional.empty(), WORKSPACE_DIFF,
                        List.of(), NARRATIVE, OBSERVED_AT),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesis.planId");
        assertViolation(
                () -> new FinalSynthesis(
                        SYNTHESIS_ID, TASK_FRAME_ID, PLAN_ID, null, Optional.empty(), WORKSPACE_DIFF,
                        List.of(), NARRATIVE, OBSERVED_AT),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesis.planRevisionId");
        assertViolation(
                () -> new FinalSynthesis(
                        SYNTHESIS_ID, TASK_FRAME_ID, PLAN_ID, PLAN_REVISION_ID, null, WORKSPACE_DIFF,
                        List.of(), NARRATIVE, OBSERVED_AT),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesis.sourceProjectVersion");
        assertViolation(
                () -> new FinalSynthesis(
                        SYNTHESIS_ID, TASK_FRAME_ID, PLAN_ID, PLAN_REVISION_ID, Optional.empty(), null,
                        List.of(), NARRATIVE, OBSERVED_AT),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesis.workspaceDiff");
        assertViolation(
                () -> new FinalSynthesis(
                        SYNTHESIS_ID, TASK_FRAME_ID, PLAN_ID, PLAN_REVISION_ID, Optional.empty(), WORKSPACE_DIFF,
                        null, NARRATIVE, OBSERVED_AT),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesis.receiptIds");
        assertViolation(
                () -> new FinalSynthesis(
                        SYNTHESIS_ID, TASK_FRAME_ID, PLAN_ID, PLAN_REVISION_ID, Optional.empty(), WORKSPACE_DIFF,
                        Arrays.asList(RECEIPT_ONE, null), NARRATIVE, OBSERVED_AT),
                ViolationCode.NULL_COLLECTION_ELEMENT,
                "finalSynthesis.receiptIds");
        assertViolation(
                () -> synthesis(Optional.empty(), List.of(RECEIPT_ONE), " ", OBSERVED_AT),
                ViolationCode.REQUIRED_TEXT_BLANK,
                "finalSynthesis.narrative");
        assertViolation(
                () -> synthesis(Optional.empty(), List.of(RECEIPT_ONE), NARRATIVE, null),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesis.observedAt");
    }

    @Test
    void rejectsInvalidSynthesisIdThroughTheStableContractPath() {
        assertViolation(
                () -> new FinalSynthesisId(null),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesisId");
        assertViolation(
                () -> new FinalSynthesisId(" "),
                ViolationCode.REQUIRED_TEXT_BLANK,
                "finalSynthesisId");
        assertViolation(
                () -> new FinalSynthesisId("invalid synthesis id"),
                ViolationCode.INVALID_ID,
                "finalSynthesisId");
    }

    @Test
    void keepsThePublicRecordSurfacePresentationOnly() {
        assertTrue(FinalSynthesis.class.isRecord());
        RecordComponent[] components = FinalSynthesis.class.getRecordComponents();
        assertEquals(
                List.of(
                        "id",
                        "taskFrameId",
                        "planId",
                        "planRevisionId",
                        "sourceProjectVersion",
                        "workspaceDiff",
                        "receiptIds",
                        "narrative",
                        "observedAt"),
                Arrays.stream(components).map(RecordComponent::getName).toList());
        assertEquals(
                List.of(
                        FinalSynthesisId.class,
                        TaskFrameId.class,
                        PlanId.class,
                        PlanRevisionId.class,
                        Optional.class,
                        WorkspaceDiff.class,
                        List.class,
                        String.class,
                        Instant.class),
                Arrays.stream(components).map(RecordComponent::getType).toList());
    }

    @Test
    void productionSourcesUseOnlyJdkImportsAndNoOuterModuleOrV1Markers() throws IOException {
        List<Path> productionSources = List.of(
                Path.of("src/main/java/io/paperagent/v2/contracts/FinalSynthesisId.java"),
                Path.of("src/main/java/io/paperagent/v2/contracts/FinalSynthesis.java"));
        for (Path sourceFile : productionSources) {
            String source = Files.readString(sourceFile);
            List<String> imports = source.lines().filter(line -> line.startsWith("import ")).toList();
            assertTrue(imports.stream().allMatch(line -> line.startsWith("import java.")), sourceFile.toString());
            for (String forbiddenMarker : List.of(
                    "io.paperagent.v1",
                    "io.paperagent.v2.api",
                    "io.paperagent.v2.persistence",
                    "io.paperagent.v2.providers",
                    "io.paperagent.v2.runtime",
                    "io.paperagent.v2.sandbox",
                    "io.paperagent.v2.workspace",
                    "org.springframework",
                    "jakarta.persistence",
                    "javax.persistence")) {
                assertFalse(source.contains(forbiddenMarker), sourceFile + ": " + forbiddenMarker);
            }
        }

        String pom = Files.readString(Path.of("pom.xml"));
        Matcher dependencies = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(pom);
        int dependencyCount = 0;
        while (dependencies.find()) {
            dependencyCount++;
            String dependency = dependencies.group(1);
            assertTrue(dependency.contains("<scope>test</scope>"), dependency);
            assertTrue(dependency.contains("<groupId>org.junit.jupiter</groupId>"), dependency);
        }
        assertEquals(1, dependencyCount);
    }

    private static FinalSynthesis synthesis(
            Optional<ProjectVersionRef> sourceProjectVersion,
            List<ReceiptId> receiptIds) {
        return synthesis(sourceProjectVersion, receiptIds, NARRATIVE, OBSERVED_AT);
    }

    private static FinalSynthesis synthesis(
            Optional<ProjectVersionRef> sourceProjectVersion,
            List<ReceiptId> receiptIds,
            String narrative,
            Instant observedAt) {
        return new FinalSynthesis(
                SYNTHESIS_ID,
                TASK_FRAME_ID,
                PLAN_ID,
                PLAN_REVISION_ID,
                sourceProjectVersion,
                WORKSPACE_DIFF,
                receiptIds,
                narrative,
                observedAt);
    }

    private static void assertViolation(
            Runnable action,
            ViolationCode code,
            String path) {
        ContractViolationException exception = ContractFixtures.violation(action);
        assertEquals(code, exception.primaryCode());
        assertEquals(path, exception.violations().get(0).path());
    }
}
