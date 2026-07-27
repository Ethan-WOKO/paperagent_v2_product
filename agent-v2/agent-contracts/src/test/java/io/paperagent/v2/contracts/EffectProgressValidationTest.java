package io.paperagent.v2.contracts;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectProgressValidationTest {
    private static final EffectProgressId PROGRESS_ID =
            new EffectProgressId("progress-opaque-sentinel");
    private static final ToolCallId TOOL_CALL_ID =
            new ToolCallId("tool-call-opaque-sentinel");
    private static final Instant OCCURRED_AT = ContractFixtures.T0.plusSeconds(1);
    private static final ObjectValue DETAILS = new ObjectValue(Map.of(
            "detail", new TextValue("progress-detail-opaque-sentinel")));

    @Test
    void acceptsAnImmutableProviderNeutralProgressFact() {
        EffectProgress progress = progress();

        assertEquals(PROGRESS_ID, progress.id());
        assertEquals(TOOL_CALL_ID, progress.toolCallId());
        assertEquals(1, progress.sequence());
        assertEquals(OCCURRED_AT, progress.occurredAt());
        assertEquals(DETAILS, progress.details());
        assertThrows(UnsupportedOperationException.class, () ->
                progress.details().values().put("other", new TextValue("value")));
    }

    @Test
    void rejectsMissingComponentsAndNonPositiveSequence() {
        assertViolation(
                () -> new EffectProgressId(" "),
                ViolationCode.REQUIRED_TEXT_BLANK,
                "effectProgressId");
        assertViolation(
                () -> new EffectProgress(null, TOOL_CALL_ID, 1, OCCURRED_AT, DETAILS),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "effectProgress.id");
        assertViolation(
                () -> new EffectProgress(PROGRESS_ID, null, 1, OCCURRED_AT, DETAILS),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "effectProgress.toolCallId");
        assertViolation(
                () -> new EffectProgress(PROGRESS_ID, TOOL_CALL_ID, 0, OCCURRED_AT, DETAILS),
                ViolationCode.INVALID_ID,
                "effectProgress.sequence");
        assertViolation(
                () -> new EffectProgress(PROGRESS_ID, TOOL_CALL_ID, 1, null, DETAILS),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "effectProgress.occurredAt");
        assertViolation(
                () -> new EffectProgress(PROGRESS_ID, TOOL_CALL_ID, 1, OCCURRED_AT, null),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "effectProgress.details");
    }

    @Test
    void keepsThePublicRecordSurfaceStableAndRedactsAllValues() {
        EffectProgress progress = progress();

        assertTrue(EffectProgress.class.isRecord());
        RecordComponent[] components = EffectProgress.class.getRecordComponents();
        assertEquals(
                List.of("id", "toolCallId", "sequence", "occurredAt", "details"),
                Arrays.stream(components).map(RecordComponent::getName).toList());
        assertEquals(
                List.of(
                        EffectProgressId.class,
                        ToolCallId.class,
                        long.class,
                        Instant.class,
                        ObjectValue.class),
                Arrays.stream(components).map(RecordComponent::getType).toList());
        for (String sentinel : Set.of(
                PROGRESS_ID.value(),
                TOOL_CALL_ID.value(),
                "progress-detail-opaque-sentinel")) {
            assertFalse(progress.toString().contains(sentinel), sentinel);
            assertFalse(PROGRESS_ID.toString().contains(sentinel), sentinel);
        }
    }

    private static EffectProgress progress() {
        return new EffectProgress(PROGRESS_ID, TOOL_CALL_ID, 1, OCCURRED_AT, DETAILS);
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
