package io.paperagent.v2.runtime.bootstrap;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.runtime.planning.InitialPlanDraft;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.routing.RoutingDecisionReason;
import io.paperagent.v2.runtime.routing.RoutingRequestId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;
import io.paperagent.v2.runtime.taskframe.TaskFrameFreezeRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistentPlanBootstrapRequestTest {
    private static final PlanId PLAN_ID = new PlanId("plan-bootstrap-request");
    private static final PlanRevisionId REVISION_ID =
            new PlanRevisionId("revision-bootstrap-request");
    private static final Instant PLAN_CREATED_AT =
            Instant.parse("2026-07-24T09:00:01Z");
    private static final Instant CHECKPOINT_CREATED_AT =
            Instant.parse("2026-07-24T09:00:02Z");

    @Test
    void nullFieldsFailInDeclaredOrderWithStableCodeAndPath() {
        TaskFrameFreezeRequest taskRequest = taskFrameRequest();
        InitialPlanDraft planDraft = initialPlanDraft();

        assertMissing(
                () -> new PersistentPlanBootstrapRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                "persistentPlanBootstrapRequest.taskFrameFreezeRequest");
        assertMissing(
                () -> new PersistentPlanBootstrapRequest(
                        taskRequest,
                        null,
                        null,
                        null,
                        null,
                        null),
                "persistentPlanBootstrapRequest.planId");
        assertMissing(
                () -> new PersistentPlanBootstrapRequest(
                        taskRequest,
                        PLAN_ID,
                        null,
                        null,
                        null,
                        null),
                "persistentPlanBootstrapRequest.initialRevisionId");
        assertMissing(
                () -> new PersistentPlanBootstrapRequest(
                        taskRequest,
                        PLAN_ID,
                        REVISION_ID,
                        null,
                        null,
                        null),
                "persistentPlanBootstrapRequest.initialPlanDraft");
        assertMissing(
                () -> new PersistentPlanBootstrapRequest(
                        taskRequest,
                        PLAN_ID,
                        REVISION_ID,
                        planDraft,
                        null,
                        null),
                "persistentPlanBootstrapRequest.planCreatedAt");
        assertMissing(
                () -> new PersistentPlanBootstrapRequest(
                        taskRequest,
                        PLAN_ID,
                        REVISION_ID,
                        planDraft,
                        PLAN_CREATED_AT,
                        null),
                "persistentPlanBootstrapRequest.checkpointCreatedAt");
    }

    @Test
    void validationCodeSurfaceIsExact() {
        assertArrayEquals(
                new PersistentPlanBootstrapValidationCode[] {
                    PersistentPlanBootstrapValidationCode.REQUIRED_VALUE_MISSING,
                    PersistentPlanBootstrapValidationCode.ROUTE_NOT_PERSISTENT
                },
                PersistentPlanBootstrapValidationCode.values());
    }

    private static TaskFrameFreezeRequest taskFrameRequest() {
        RoutingDecision routingDecision = new RoutingDecision(
                new RoutingRequestId("routing-bootstrap-request"),
                Route.PERSISTENT_PLAN_EXECUTE,
                RoutingDecisionReason.DECLARED_REQUIREMENT,
                Set.of(RoutingRequirement.PROJECT_FILE_ACCESS));
        return new TaskFrameFreezeRequest(
                routingDecision,
                new TaskFrameId("task-bootstrap-request"),
                new TaskFrameDraft(
                        "Prepare a verified update",
                        List.of("paper"),
                        List.of("workspace diff"),
                        List.of("preserve citations")),
                Optional.empty(),
                executionProfile(),
                Instant.parse("2026-07-24T09:00:00Z"));
    }

    private static InitialPlanDraft initialPlanDraft() {
        PlanStepId stepId = new PlanStepId("step-bootstrap-request");
        return new InitialPlanDraft(
                "Initial bootstrap plan",
                List.of(new PlanStep(
                        stepId,
                        "Prepare update",
                        "Verify update",
                        Set.of(),
                        List.of("update verified"),
                        new BoundedExecutionHints(2, Duration.ofMinutes(2)))));
    }

    private static ExecutionProfile executionProfile() {
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(Capability.READ_PROJECT, Capability.WRITE_WORKSPACE),
                NetworkPolicy.DENY_ALL,
                List.of(),
                new ResourceLimits(
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(5),
                        512 * 1024 * 1024L,
                        1024 * 1024L,
                        8),
                Set.of());
    }

    private static void assertMissing(
            Runnable invocation,
            String expectedPath) {
        PersistentPlanBootstrapValidationException failure = assertThrows(
                PersistentPlanBootstrapValidationException.class,
                invocation::run);
        assertEquals(
                PersistentPlanBootstrapValidationCode.REQUIRED_VALUE_MISSING,
                failure.code());
        assertEquals(expectedPath, failure.path());
    }
}
