package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.finalization.ChainFinalizationAuthorityPort;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductChainRetainedAuthoritySourceTest {
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void completionCheckpointDescendantKeepsTheFormalPlanBinding() {
        Scenario scenario = completionScenario();

        var snapshot = source(scenario.recovery()).freeze(request(
                binding("revision.2", 2)));

        assertEquals("revision.2",
                snapshot.stepState().orElseThrow().planRevisionId());
        assertTrue(snapshot.facts().stream().anyMatch(value ->
                value.authorityType().equals("PLAN_REVISION")
                        && value.authorityRef().equals("revision.2")));
    }

    @Test
    void aReplannedDescendantWithoutItsOwnBindingIsRejected() {
        Scenario scenario = completionScenario();

        assertThrows(IllegalStateException.class, () ->
                source(scenario.recovery()).freeze(request(
                        binding("revision.1", 1))));
    }

    @Test
    void checkpointMustStillMatchTheLatestRecoveredRevision() {
        Scenario scenario = completionScenario();
        Checkpoint stale = checkpoint(
                scenario.frame(), scenario.plan(),
                scenario.plan().revisions().get(1), 5,
                Map.of(new PlanStepId("step.1"), StepExecutionState.ACTIVE,
                        new PlanStepId("step.2"),
                        StepExecutionState.NOT_STARTED));
        var recovery = new PersistedStepRecoveryReady(
                scenario.frame(), scenario.plan(),
                new VersionedCheckpoint(5, stale),
                new PlanStepId("step.2"), Optional.empty());

        assertThrows(IllegalStateException.class, () ->
                source(recovery).freeze(request(
                        binding("revision.2", 2))));
    }

    private static Scenario completionScenario() {
        TaskFrame frame = frame();
        PlanStep old = step("old.step", Set.of());
        PlanStep first = step("step.1", Set.of());
        PlanStep second = step("step.2", Set.of(first.id()));
        PlanRevision initial = revision(
                "revision.1", 1, Optional.empty(), List.of(old), Map.of());
        PlanRevision replanned = revision(
                "revision.2", 2,
                Optional.of(initial.id()), List.of(first, second), Map.of());
        CompletionFact completion = new CompletionFact(
                first.id(), HASH, NOW.plusSeconds(1), List.of());
        Map<PlanStepId, CompletionFact> completed = new LinkedHashMap<>();
        completed.put(first.id(), completion);
        PlanRevision checkpointRevision = revision(
                "revision.3", 3, Optional.of(replanned.id()),
                replanned.steps(), completed);
        Plan plan = new Plan(new PlanId("plan.1"), frame.id(),
                List.of(initial, replanned, checkpointRevision));
        Checkpoint checkpoint = checkpoint(
                frame, plan, checkpointRevision, 6,
                Map.of(first.id(), StepExecutionState.SUCCEEDED,
                        second.id(), StepExecutionState.NOT_STARTED));
        var recovery = new PersistedStepRecoveryReady(
                frame, plan, new VersionedCheckpoint(6, checkpoint),
                second.id(), Optional.empty());
        return new Scenario(frame, plan, recovery);
    }

    private static ProductChainRetainedAuthoritySource source(
            PersistedStepRecoveryReady recovery) {
        StepRecoveryRepository steps = mock(StepRecoveryRepository.class);
        when(steps.inspect(new PlanId("plan.1")))
                .thenReturn(PersistenceResult.found(recovery));
        return new ProductChainRetainedAuthoritySource(
                steps, mock(EffectIntentRepository.class),
                mock(EffectOutcomeRepository.class),
                mock(ChainWorkflowRepository.class),
                mock(ChainFinalizationAuthorityPort.class),
                ignored -> Optional.empty());
    }

    private static ProductChainRecoverySource.StableAuthorityRequest request(
            ChainPersistenceRecords.PlanBindingRecord binding) {
        return new ProductChainRecoverySource.StableAuthorityRequest(
                task(), 20, List.of(binding), List.of(), List.of(),
                List.of(), Map.of());
    }

    private static ChainPersistenceRecords.PlanBindingRecord binding(
            String revision, long number) {
        return new ChainPersistenceRecords.PlanBindingRecord(
                "binding." + number, "task.1", "event." + number,
                "instruction.1", "route.1", "frame.1", "plan.1",
                revision, number, "STABLE_V2_PLAN", revision,
                HASH, "transition." + number, NOW);
    }

    private static ChainPersistenceRecords.TaskRecord task() {
        return new ChainPersistenceRecords.TaskRecord(
                "task.1", "command.1", "instruction.1", null,
                1, 1, 1, 1L, "request.1", HASH,
                9L, "version.1", 0, NOW);
    }

    private static TaskFrame frame() {
        return new TaskFrame(new TaskFrameId("frame.1"), "Execute safely",
                List.of("project"), List.of("verified result"), List.of(),
                Optional.of(new ProjectVersionRef("9", "version.1")),
                new ExecutionProfile(ExecutionTier.SANDBOX_STANDARD,
                        Set.of(Capability.READ_PROJECT),
                        NetworkPolicy.DENY_ALL, List.of(),
                        new ResourceLimits(Duration.ofMinutes(1),
                                Duration.ofSeconds(30), 1024, 1024, 1),
                        Set.of()), NOW);
    }

    private static PlanStep step(String id, Set<PlanStepId> dependencies) {
        return new PlanStep(new PlanStepId(id), id, id + " complete",
                dependencies, List.of("verified"),
                new BoundedExecutionHints(2, Duration.ofMinutes(1)));
    }

    private static PlanRevision revision(
            String id, long number, Optional<PlanRevisionId> parent,
            List<PlanStep> steps, Map<PlanStepId, CompletionFact> completed) {
        return new PlanRevision(new PlanRevisionId(id),
                new TaskFrameId("frame.1"), number, parent, id,
                NOW.plusSeconds(number), steps, completed);
    }

    private static Checkpoint checkpoint(
            TaskFrame frame, Plan plan, PlanRevision revision, long sequence,
            Map<PlanStepId, StepExecutionState> states) {
        return new Checkpoint(frame.id(), plan.id(), revision.id(),
                revision.number(), sequence, PlanExecutionState.ACTIVE,
                states, List.of(), NOW.plusSeconds(sequence));
    }

    private record Scenario(
            TaskFrame frame,
            Plan plan,
            PersistedStepRecoveryReady recovery) {
    }
}
