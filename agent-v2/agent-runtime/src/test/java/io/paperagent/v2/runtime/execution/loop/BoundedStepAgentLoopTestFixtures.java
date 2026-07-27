package io.paperagent.v2.runtime.execution.loop;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelOutcome;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelRequest;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

final class BoundedStepAgentLoopTestFixtures {
    static final Instant T0 = Instant.parse("2026-07-26T00:00:00Z");

    private BoundedStepAgentLoopTestFixtures() {
    }

    static RecoveredActiveStep recovered(String suffix) {
        TaskFrameId taskFrameId = new TaskFrameId("task-" + suffix);
        PlanId planId = new PlanId("plan-" + suffix);
        PlanStepId stepId = new PlanStepId("step-" + suffix);
        TaskFrame taskFrame = new TaskFrame(
                taskFrameId,
                "goal " + suffix,
                List.of("target"),
                List.of("deliverable"),
                List.of("constraint"),
                Optional.empty(),
                profile(),
                T0);
        PlanStep step = new PlanStep(
                stepId,
                "do " + suffix,
                "verify " + suffix,
                Set.of(),
                List.of("done"),
                new BoundedExecutionHints(2, Duration.ofMinutes(2)));
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-" + suffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial " + suffix,
                T0,
                List.of(step),
                Map.of());
        Plan plan = new Plan(planId, taskFrameId, List.of(revision));
        Map<PlanStepId, StepExecutionState> stepStates = new LinkedHashMap<>();
        stepStates.put(stepId, StepExecutionState.ACTIVE);
        Checkpoint checkpoint = new Checkpoint(
                taskFrameId,
                planId,
                revision.id(),
                revision.number(),
                2,
                PlanExecutionState.ACTIVE,
                stepStates,
                List.of(),
                T0.plusSeconds(2));
        EventEnvelope activationEvent = new EventEnvelope(
                new EventId("activation-" + suffix),
                taskFrameId,
                planId,
                2,
                T0.plusSeconds(2),
                new EventType("step-activation"),
                Optional.empty(),
                "correlation-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of())));
        LeaseRecord lease = new LeaseRecord(
                planId,
                "owner-" + suffix,
                "token-" + suffix,
                7,
                T0,
                T0.plus(Duration.ofMinutes(5)));
        PersistedStepActivation activation = new PersistedStepActivation(
                planId,
                stepId,
                lease.ownerId(),
                lease.fencingToken(),
                activationEvent,
                new VersionedCheckpoint(3, checkpoint));
        PersistedStepRecoveryActive recovery = new PersistedStepRecoveryActive(
                taskFrame,
                plan,
                new VersionedCheckpoint(3, checkpoint),
                activation,
                Optional.empty());
        return new RecoveredActiveStep(
                recovery, lease, StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    static EffectIntent intent(RecoveredActiveStep recoveredStep, String suffix) {
        return new EffectIntent(
                new ToolCallId("call-" + suffix),
                recoveredStep.planId(),
                recoveredStep.recovery().activation().stepId(),
                "workspace.edit",
                new ObjectValue(Map.of()));
    }

    static PersistedEffectIntent persisted(
            RecoveredActiveStep recoveredStep,
            String suffix) {
        return new PersistedEffectIntent(
                intent(recoveredStep, suffix),
                recoveredStep.lease().ownerId(),
                recoveredStep.lease().fencingToken(),
                recoveredStep.recovery().activation().activationEvent().id());
    }

    static final class RecordingKernel implements SingleTurnStepKernel {
        private final Function<SingleTurnStepKernelRequest, SingleTurnStepKernelOutcome> response;
        private final AtomicInteger calls = new AtomicInteger();
        private final ConcurrentLinkedQueue<SingleTurnStepKernelRequest> requests =
                new ConcurrentLinkedQueue<>();

        RecordingKernel(
                Function<SingleTurnStepKernelRequest, SingleTurnStepKernelOutcome> response) {
            this.response = response;
        }

        @Override
        public SingleTurnStepKernelOutcome run(SingleTurnStepKernelRequest request) {
            calls.incrementAndGet();
            requests.add(request);
            return response.apply(request);
        }

        int calls() {
            return calls.get();
        }

        List<SingleTurnStepKernelRequest> requests() {
            return List.copyOf(requests);
        }
    }

    static SingleTurnStepKernel scripted(
            List<SingleTurnStepKernelOutcome> outcomes,
            AtomicInteger calls,
            List<SingleTurnStepKernelRequest> requests) {
        List<SingleTurnStepKernelOutcome> remaining = new ArrayList<>(outcomes);
        return request -> {
            calls.incrementAndGet();
            requests.add(request);
            if (remaining.isEmpty()) {
                throw new AssertionError("loop invoked a turn after scripted stop");
            }
            return remaining.remove(0);
        };
    }

    private static ExecutionProfile profile() {
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(),
                NetworkPolicy.DENY_ALL,
                List.of(),
                new ResourceLimits(
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(2),
                        1024,
                        1024,
                        1),
                Set.of());
    }
}
