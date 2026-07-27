package io.paperagent.v2.contracts;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectIntentValidationTest {
    private static final ToolCallId TOOL_CALL_ID =
            new ToolCallId("tool-call-opaque-sentinel");
    private static final PlanId PLAN_ID = new PlanId("plan-opaque-sentinel");
    private static final PlanStepId STEP_ID = new PlanStepId("step-opaque-sentinel");
    private static final ObjectValue ARGUMENTS = new ObjectValue(Map.of(
            "input", new TextValue("argument-opaque-sentinel")));

    @Test
    void acceptsAProviderNeutralImmutableIntent() {
        EffectIntent intent = effectIntent();

        assertEquals(TOOL_CALL_ID, intent.toolCallId());
        assertEquals(PLAN_ID, intent.planId());
        assertEquals(STEP_ID, intent.stepId());
        assertEquals("workspace.edit", intent.kind());
        assertEquals(ARGUMENTS, intent.arguments());
        assertThrows(UnsupportedOperationException.class, () ->
                intent.arguments().values().put("other", new TextValue("value")));
    }

    @Test
    void rejectsMissingTypedComponentsAndInvalidKind() {
        assertViolation(
                () -> new EffectIntent(null, PLAN_ID, STEP_ID, "workspace.edit", ARGUMENTS),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "effectIntent.toolCallId");
        assertViolation(
                () -> new EffectIntent(TOOL_CALL_ID, null, STEP_ID, "workspace.edit", ARGUMENTS),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "effectIntent.planId");
        assertViolation(
                () -> new EffectIntent(TOOL_CALL_ID, PLAN_ID, null, "workspace.edit", ARGUMENTS),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "effectIntent.stepId");
        assertViolation(
                () -> new EffectIntent(TOOL_CALL_ID, PLAN_ID, STEP_ID, " ", ARGUMENTS),
                ViolationCode.REQUIRED_TEXT_BLANK,
                "effectIntent.kind");
        assertViolation(
                () -> new EffectIntent(TOOL_CALL_ID, PLAN_ID, STEP_ID, "workspace edit", ARGUMENTS),
                ViolationCode.INVALID_ID,
                "effectIntent.kind");
        assertViolation(
                () -> new EffectIntent(TOOL_CALL_ID, PLAN_ID, STEP_ID, "workspace.edit", null),
                ViolationCode.REQUIRED_VALUE_MISSING,
                "effectIntent.arguments");
    }

    @Test
    void recordSurfaceAndTextAreStableAndOpaque() {
        EffectIntent intent = effectIntent();

        assertTrue(EffectIntent.class.isRecord());
        RecordComponent[] components = EffectIntent.class.getRecordComponents();
        assertEquals(
                List.of("toolCallId", "planId", "stepId", "kind", "arguments"),
                Arrays.stream(components).map(RecordComponent::getName).toList());
        assertEquals(
                List.of(
                        ToolCallId.class,
                        PlanId.class,
                        PlanStepId.class,
                        String.class,
                        ObjectValue.class),
                Arrays.stream(components).map(RecordComponent::getType).toList());
        assertEquals(
                "EffectIntent[toolCallId=<provided>, planId=<provided>, "
                        + "stepId=<provided>, kind=<provided>, arguments=<provided>]",
                intent.toString());
        for (String sentinel : Set.of(
                TOOL_CALL_ID.value(),
                PLAN_ID.value(),
                STEP_ID.value(),
                "workspace.edit",
                "argument-opaque-sentinel")) {
            assertFalse(intent.toString().contains(sentinel), sentinel);
        }
    }

    private static EffectIntent effectIntent() {
        return new EffectIntent(
                TOOL_CALL_ID, PLAN_ID, STEP_ID, "workspace.edit", ARGUMENTS);
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
