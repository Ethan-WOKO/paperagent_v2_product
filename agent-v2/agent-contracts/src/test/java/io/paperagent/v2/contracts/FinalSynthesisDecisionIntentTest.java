package io.paperagent.v2.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class FinalSynthesisDecisionIntentTest {
    private static final String DECISION_ID = "decision-opaque-sentinel";
    private static final FinalSynthesisId SYNTHESIS_ID =
            new FinalSynthesisId("synthesis-opaque-sentinel");
    private static final String REASON = "reason-opaque-sentinel";
    private static final Instant REQUESTED_AT = Instant.parse("2026-07-27T01:02:03Z");

    @Test
    void acceptsExactlyTheTwoOpaqueRequestedActions() {
        FinalSynthesisDecisionIntent accept = intent(FinalSynthesisDecisionAction.ACCEPT, Optional.empty());
        FinalSynthesisDecisionIntent reject = intent(FinalSynthesisDecisionAction.REJECT, Optional.of(REASON));

        assertEquals(DECISION_ID, accept.decisionId());
        assertEquals(SYNTHESIS_ID, accept.finalSynthesisId());
        assertEquals(FinalSynthesisDecisionAction.ACCEPT, accept.action());
        assertTrue(accept.reason().isEmpty());
        assertEquals(REQUESTED_AT, accept.requestedAt());
        assertEquals(FinalSynthesisDecisionAction.REJECT, reject.action());
        assertEquals(Optional.of(REASON), reject.reason());
        assertEquals(
                Set.of(FinalSynthesisDecisionAction.ACCEPT, FinalSynthesisDecisionAction.REJECT),
                Set.copyOf(Arrays.asList(FinalSynthesisDecisionAction.values())));
    }

    @Test
    void copiesOptionalReasonAndRedactsEveryValue() {
        Optional<String> sourceReason = Optional.of(REASON);
        FinalSynthesisDecisionIntent intent = intent(FinalSynthesisDecisionAction.REJECT, sourceReason);

        assertNotSame(sourceReason, intent.reason());
        assertEquals(Optional.of(REASON), intent.reason());
        assertEquals(
                "FinalSynthesisDecisionIntent[decisionId=<provided>, finalSynthesisId=<provided>, "
                        + "action=<provided>, reason=<provided>, requestedAt=<provided>]",
                intent.toString());
        for (String sentinel : Set.of(DECISION_ID, SYNTHESIS_ID.value(),
                FinalSynthesisDecisionAction.REJECT.name(), REASON, REQUESTED_AT.toString())) {
            assertFalse(intent.toString().contains(sentinel), sentinel);
        }
    }

    @Test
    void rejectsMissingBlankAndInvalidComponentsWithExactPaths() {
        assertViolation(
                () -> new FinalSynthesisDecisionIntent(
                        null, SYNTHESIS_ID, FinalSynthesisDecisionAction.ACCEPT, Optional.empty(), REQUESTED_AT),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesisDecisionIntent.decisionId");
        assertViolation(
                () -> new FinalSynthesisDecisionIntent(
                        " ", SYNTHESIS_ID, FinalSynthesisDecisionAction.ACCEPT, Optional.empty(), REQUESTED_AT),
                ViolationCode.REQUIRED_TEXT_BLANK,
                "finalSynthesisDecisionIntent.decisionId");
        assertViolation(
                () -> new FinalSynthesisDecisionIntent(
                        "invalid decision id", SYNTHESIS_ID, FinalSynthesisDecisionAction.ACCEPT,
                        Optional.empty(), REQUESTED_AT),
                ViolationCode.INVALID_ID,
                "finalSynthesisDecisionIntent.decisionId");
        assertViolation(
                () -> new FinalSynthesisDecisionIntent(
                        DECISION_ID, null, FinalSynthesisDecisionAction.ACCEPT, Optional.empty(), REQUESTED_AT),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesisDecisionIntent.finalSynthesisId");
        assertViolation(
                () -> new FinalSynthesisDecisionIntent(
                        DECISION_ID, SYNTHESIS_ID, null, Optional.empty(), REQUESTED_AT),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesisDecisionIntent.action");
        assertViolation(
                () -> new FinalSynthesisDecisionIntent(
                        DECISION_ID, SYNTHESIS_ID, FinalSynthesisDecisionAction.ACCEPT, null, REQUESTED_AT),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesisDecisionIntent.reason");
        assertViolation(
                () -> new FinalSynthesisDecisionIntent(
                        DECISION_ID, SYNTHESIS_ID, FinalSynthesisDecisionAction.ACCEPT, Optional.of(" "),
                        REQUESTED_AT),
                ViolationCode.REQUIRED_TEXT_BLANK,
                "finalSynthesisDecisionIntent.reason");
        assertViolation(
                () -> new FinalSynthesisDecisionIntent(
                        DECISION_ID, SYNTHESIS_ID, FinalSynthesisDecisionAction.ACCEPT, Optional.empty(), null),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "finalSynthesisDecisionIntent.requestedAt");
    }

    @Test
    void keepsTheExactUntrustedInputSurface() {
        assertTrue(FinalSynthesisDecisionIntent.class.isRecord());
        RecordComponent[] components = FinalSynthesisDecisionIntent.class.getRecordComponents();
        assertEquals(
                List.of("decisionId", "finalSynthesisId", "action", "reason", "requestedAt"),
                Arrays.stream(components).map(RecordComponent::getName).toList());
        assertEquals(
                List.of(
                        String.class,
                        FinalSynthesisId.class,
                        FinalSynthesisDecisionAction.class,
                        Optional.class,
                        Instant.class),
                Arrays.stream(components).map(RecordComponent::getType).toList());
        assertEquals(
                Set.of(
                        "action",
                        "decisionId",
                        "equals",
                        "finalSynthesisId",
                        "hashCode",
                        "reason",
                        "requestedAt",
                        "toString"),
                Arrays.stream(FinalSynthesisDecisionIntent.class.getDeclaredMethods())
                        .map(method -> method.getName())
                        .collect(Collectors.toSet()));
    }

    @Test
    void productionSourcesUseOnlyJdkImportsAndNoOuterOrV1Concerns() throws IOException {
        List<Path> productionSources = List.of(
                Path.of("src/main/java/io/paperagent/v2/contracts/FinalSynthesisDecisionAction.java"),
                Path.of("src/main/java/io/paperagent/v2/contracts/FinalSynthesisDecisionIntent.java"));
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
        String intentSource = Files.readString(productionSources.get(1));
        assertTrue(intentSource.contains("Only a later authenticated and persisted authority can apply this intent."));

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

    private static FinalSynthesisDecisionIntent intent(
            FinalSynthesisDecisionAction action,
            Optional<String> reason) {
        return new FinalSynthesisDecisionIntent(DECISION_ID, SYNTHESIS_ID, action, reason, REQUESTED_AT);
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
