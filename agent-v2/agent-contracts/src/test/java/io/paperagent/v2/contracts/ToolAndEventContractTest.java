package io.paperagent.v2.contracts;

import static io.paperagent.v2.contracts.ContractFixtures.PLAN_ID;
import static io.paperagent.v2.contracts.ContractFixtures.STEP_1;
import static io.paperagent.v2.contracts.ContractFixtures.T0;
import static io.paperagent.v2.contracts.ContractFixtures.TASK_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ToolAndEventContractTest {
    @Test
    void recordsFrameworkNeutralToolAndEventFacts() {
        ToolDescriptor descriptor = new ToolDescriptor(
                new ToolId("workspace.read"),
                "Read one workspace file",
                Set.of(Capability.READ_PROJECT));
        ToolCall call = new ToolCall(
                new ToolCallId("call-1"),
                descriptor.id(),
                TASK_ID,
                PLAN_ID,
                STEP_1,
                new ObjectValue(Map.of("path", new TextValue("paper/main.tex"))),
                T0);
        ToolResult result = new ToolResult(
                call.id(),
                ToolResultStatus.SUCCESS,
                Optional.of(new TextValue("contents")),
                Optional.empty(),
                T0.plusSeconds(1));
        EventEnvelope event = new EventEnvelope(
                new EventId("event-1"),
                TASK_ID,
                PLAN_ID,
                1,
                T0.plusSeconds(1),
                new EventType("tool.completed"),
                Optional.empty(),
                "correlation-1",
                new InlineEventPayload(new ObjectValue(Map.of(
                        "callId", new TextValue(call.id().value()),
                        "status", new TextValue(result.status().name())))));

        assertEquals(call.id(), result.toolCallId());
        assertEquals("tool.completed", event.type().value());
        assertEquals(1, event.sequence());
    }

    @Test
    void rejectsSelfCausation() {
        EventId id = new EventId("event-1");
        ContractViolationException exception = ContractFixtures.violation(() -> new EventEnvelope(
                id,
                TASK_ID,
                PLAN_ID,
                1,
                T0,
                new EventType("event"),
                Optional.of(id),
                "correlation-1",
                new EventPayloadRef("payload-1")));
        assertEquals(ViolationCode.INCONSISTENT_REFERENCE, exception.primaryCode());
    }

    @Test
    void rejectsZeroAndNegativeEventSequencesWithStableCodeAndPath() {
        assertSequenceConstructionRejected(0);
        assertSequenceConstructionRejected(-1);
    }

    @Test
    void acceptsStrictlyIncreasingSequencesWithGapsAndRejectsRegression() {
        EventEnvelope first = event("event-1", 1);
        EventEnvelope second = event("event-2", 2);
        EventEnvelope equal = event("event-equal", 2);
        EventEnvelope third = event("event-3", 3);

        assertTrue(EventValidators.validateNext(first, second).isEmpty());
        assertTrue(EventValidators.validateNext(first, third).isEmpty());
        assertViolation(
                EventValidators.validateNext(second, equal),
                ViolationCode.EVENT_SEQUENCE_REGRESSION,
                "event.sequence");
        assertViolation(
                EventValidators.validateNext(third, second),
                ViolationCode.EVENT_SEQUENCE_REGRESSION,
                "event.sequence");
    }

    @Test
    void allowsCorrelationChangeWithinOnePlanGlobalSequence() {
        EventEnvelope previous = event("event-1", 1);
        EventEnvelope current = event(
                "event-other-correlation",
                TASK_ID,
                PLAN_ID,
                3,
                "correlation-other");

        assertTrue(EventValidators.validateNext(previous, current).isEmpty());
    }

    @Test
    void rejectsTaskOrPlanMismatch() {
        EventEnvelope previous = event("event-1", 1);
        List<EventEnvelope> mismatchedEvents = List.of(
                event(
                        "event-task-mismatch",
                        new TaskFrameId("task-other"),
                        PLAN_ID,
                        2,
                        "correlation-1"),
                event(
                        "event-plan-mismatch",
                        TASK_ID,
                        new PlanId("plan-other"),
                        2,
                        "correlation-1"));

        mismatchedEvents.forEach(current -> assertViolation(
                EventValidators.validateNext(previous, current),
                ViolationCode.INCONSISTENT_REFERENCE,
                "event"));
    }

    private static EventEnvelope event(String id, long sequence) {
        return event(id, TASK_ID, PLAN_ID, sequence, "correlation-1");
    }

    private static EventEnvelope event(
            String id,
            TaskFrameId taskFrameId,
            PlanId planId,
            long sequence,
            String correlationId) {
        return new EventEnvelope(
                new EventId(id),
                taskFrameId,
                planId,
                sequence,
                T0.plusSeconds(sequence),
                new EventType("event"),
                Optional.empty(),
                correlationId,
                new EventPayloadRef("payload-1"));
    }

    private static void assertSequenceConstructionRejected(long sequence) {
        ContractViolationException exception =
                ContractFixtures.violation(() -> event("event-" + sequence, sequence));
        assertEquals(ViolationCode.EVENT_SEQUENCE_REGRESSION, exception.primaryCode());
        assertEquals("event.sequence", exception.violations().get(0).path());
    }

    private static void assertViolation(
            List<ContractViolation> violations,
            ViolationCode expectedCode,
            String expectedPath) {
        assertEquals(1, violations.size());
        assertEquals(expectedCode, violations.get(0).code());
        assertEquals(expectedPath, violations.get(0).path());
    }
}
