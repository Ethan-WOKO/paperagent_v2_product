package io.paperagent.v2.persistence;

import java.time.Clock;
import java.util.Objects;

public final class InMemoryPersistence {
    private final TaskFrameRepository taskFrames;
    private final PlanRepository plans;
    private final EventRepository events;
    private final ReceiptRepository receipts;
    private final CheckpointRepository checkpoints;
    private final PlanBootstrapRepository planBootstraps;
    private final LeaseRepository leases;
    private final ExecutionStartRepository executionStarts;
    private final PlanExecutionContextRepository planExecutionContexts;
    private final StepActivationRepository stepActivations;
    private final StepCompletionRepository stepCompletions;
    private final StepInterruptionRepository stepInterruptions;
    private final PlanReplanRepository planReplans;
    private final ActiveStepReplanRepository activeStepReplans;
    private final EffectIntentRepository effectIntents;
    private final EffectOutcomeRepository effectOutcomes;
    private final ExecutionStartRecoveryRepository executionStartRecovery;
    private final StepRecoveryRepository stepRecovery;
    private final IdempotencyRepository idempotency;

    public InMemoryPersistence() {
        this(Clock.systemUTC());
    }

    public InMemoryPersistence(Clock leaseClock) {
        InMemoryState state =
                new InMemoryState(Objects.requireNonNull(leaseClock, "leaseClock"));
        taskFrames = new InMemoryTaskFrameRepository(state);
        plans = new InMemoryPlanRepository(state);
        events = new InMemoryEventRepository(state);
        receipts = new InMemoryReceiptRepository(state);
        checkpoints = new InMemoryCheckpointRepository(state);
        planBootstraps = new InMemoryPlanBootstrapRepository(state);
        leases = new InMemoryLeaseRepository(state);
        executionStarts = new InMemoryExecutionStartRepository(state);
        planExecutionContexts =
                new InMemoryPlanExecutionContextRepository(state);
        stepActivations = new InMemoryStepActivationRepository(state);
        stepCompletions = new InMemoryStepCompletionRepository(state);
        stepInterruptions = new InMemoryStepInterruptionRepository(state);
        planReplans = new InMemoryPlanReplanRepository(state);
        activeStepReplans = new InMemoryActiveStepReplanRepository(state);
        effectIntents = new InMemoryEffectIntentRepository(state);
        effectOutcomes = new InMemoryEffectOutcomeRepository(state);
        executionStartRecovery = new InMemoryExecutionStartRecoveryRepository(state);
        stepRecovery = new InMemoryStepRecoveryRepository(state);
        idempotency = new InMemoryIdempotencyRepository(state);
    }

    public TaskFrameRepository taskFrames() {
        return taskFrames;
    }

    public PlanRepository plans() {
        return plans;
    }

    public EventRepository events() {
        return events;
    }

    public ReceiptRepository receipts() {
        return receipts;
    }

    public CheckpointRepository checkpoints() {
        return checkpoints;
    }

    public PlanBootstrapRepository planBootstraps() {
        return planBootstraps;
    }

    public LeaseRepository leases() {
        return leases;
    }

    public ExecutionStartRepository executionStarts() {
        return executionStarts;
    }

    public ExecutionStartRecoveryRepository executionStartRecovery() {
        return executionStartRecovery;
    }

    public StepRecoveryRepository stepRecovery() {
        return stepRecovery;
    }

    public PlanExecutionContextRepository planExecutionContexts() {
        return planExecutionContexts;
    }

    public StepActivationRepository stepActivations() {
        return stepActivations;
    }

    public StepCompletionRepository stepCompletions() {
        return stepCompletions;
    }

    public StepInterruptionRepository stepInterruptions() {
        return stepInterruptions;
    }

    public PlanReplanRepository planReplans() {
        return planReplans;
    }

    public ActiveStepReplanRepository activeStepReplans() {
        return activeStepReplans;
    }

    public EffectIntentRepository effectIntents() {
        return effectIntents;
    }

    public EffectOutcomeRepository effectOutcomes() {
        return effectOutcomes;
    }

    public IdempotencyRepository idempotency() {
        return idempotency;
    }
}
