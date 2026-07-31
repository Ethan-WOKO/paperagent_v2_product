package com.yanban.api.agent.v2.loop;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.effect.AuthenticatedLiteratureSearchEffectExecutionCommand;
import com.yanban.api.agent.v2.effect.AuthenticatedLiteratureSearchEffectExecutionComposer;
import com.yanban.api.agent.v2.effect.AuthenticatedLiteratureSearchEffectExecutionOutcome;
import com.yanban.api.agent.v2.effect.project.AuthenticatedProjectEffectExecutionCommand;
import com.yanban.api.agent.v2.effect.project.AuthenticatedProjectEffectExecutionComposer;
import com.yanban.api.agent.v2.effect.project.ProjectEffectExecutionException;
import com.yanban.api.agent.v2.progression.AuthenticatedEffectDrivenStepProgressionComposer;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionCommand;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionException;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionOutcome;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.activation.composition.ReadyStepActivationCompositionRequest;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCommitted;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCompositionOutcome;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationLeaseRejected;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationPersistenceRejected;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnIntentPersisted;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnNoEffect;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnPersistenceRejected;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelOutcome;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelProtocolException;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelRequest;
import io.paperagent.v2.runtime.execution.loop.BoundedStepAgentLoopNoEffect;
import io.paperagent.v2.runtime.execution.loop.BoundedStepAgentLoopTurnLimitReached;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryCompositionOutcome;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseRejected;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryPersistenceRejected;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryRequest;
import io.paperagent.v2.runtime.execution.replan.composition.BoundedStepReplanApplied;
import io.paperagent.v2.runtime.execution.replan.composition.BoundedStepReplanComposer;
import io.paperagent.v2.runtime.execution.replan.composition.BoundedStepReplanCompositionOutcome;
import io.paperagent.v2.runtime.execution.replan.composition.BoundedStepReplanPersistenceRejected;
import io.paperagent.v2.runtime.execution.replan.composition.BoundedStepReplanReplayed;
import io.paperagent.v2.providers.ModelProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Authenticated, bounded product composition over existing stable V2
 * recovery, kernel, effect, progression and replan boundaries.
 */
@Service
public class AuthenticatedPersistentPlanAgentLoopComposer {
    private static final String LITERATURE_SEARCH = "literature.search";
    private static final String PROJECT_CANDIDATE_COMPOSE =
            "project.candidate.compose";

    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final StepRecoveryRepository inspections;
    private final StepRecoverer recoverer;
    private final StepActivationComposer activation;
    private final SingleTurnStepKernel kernel;
    private final AuthenticatedLiteratureSearchEffectExecutionComposer effects;
    private final AuthenticatedProjectEffectExecutionComposer projectEffects;
    private final AuthenticatedEffectDrivenStepProgressionComposer progression;
    private final BoundedStepReplanComposer replans;

    public AuthenticatedPersistentPlanAgentLoopComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepRecoveryRepository inspections,
            StepRecoverer recoverer,
            StepActivationComposer activation,
            SingleTurnStepKernel kernel,
            AuthenticatedLiteratureSearchEffectExecutionComposer effects,
            AuthenticatedProjectEffectExecutionComposer projectEffects,
            AuthenticatedEffectDrivenStepProgressionComposer progression,
            BoundedStepReplanComposer replans) {
        this.contexts = contexts;
        this.planIds = planIds;
        this.inspections = inspections;
        this.recoverer = recoverer;
        this.activation = activation;
        this.kernel = kernel;
        this.effects = effects;
        this.projectEffects = projectEffects;
        this.progression = progression;
        this.replans = replans;
    }

    public PersistentPlanAgentLoopOutcome execute(
            Long userId, Long agentTurnId,
            PersistentPlanAgentLoopCommand command) {
        return executeWithKernel(userId, agentTurnId, command, kernel);
    }

    public PersistentPlanAgentLoopOutcome executeWithKernel(
            Long userId, Long agentTurnId,
            PersistentPlanAgentLoopCommand command,
            SingleTurnStepKernel turnKernel) {
        return executeWithKernelAndReplanFactory(
                userId, agentTurnId, command, turnKernel,
                ignored -> command.replanProposal().orElse(null));
    }

    public PersistentPlanAgentLoopOutcome executeWithKernelAndReplanFactory(
            Long userId, Long agentTurnId,
            PersistentPlanAgentLoopCommand command,
            SingleTurnStepKernel turnKernel,
            java.util.function.Function<
                    RecoveredActiveStep,
                    io.paperagent.v2.persistence.ActiveStepReplanRequest>
                    replanFactory) {
        return executeWithKernelAndReplanFactory(
                userId, agentTurnId, command, turnKernel,
                replanFactory, null);
    }

    public PersistentPlanAgentLoopOutcome executeWithKernelAndReplanFactory(
            Long userId, Long agentTurnId,
            PersistentPlanAgentLoopCommand command,
            SingleTurnStepKernel turnKernel,
            java.util.function.Function<
                    RecoveredActiveStep,
                    io.paperagent.v2.persistence.ActiveStepReplanRequest>
                    replanFactory,
            ModelProvider requestProvider) {
        VerifiedAgentTurnProductContext context;
        PlanId planId;
        try {
            context = contexts.resolve(userId, agentTurnId);
            planId = planIds.derive(context.identity());
        } catch (RuntimeException exception) {
            throw protocol("context");
        }
        requireCommand(command);
        var recoveryAttempt = command.currentRecoveryAttempt();
        Optional<PersistentPlanAgentLoopReceiptFacts> latestReceipt =
                Optional.empty();

        for (int cycle = 1; cycle <= command.maxCycles(); cycle++) {
            StepRecoverySnapshot cut = inspect(planId);
            if (cut instanceof PersistedStepRecoverySucceeded) {
                return outcome(planId, cycle - 1,
                        PersistentPlanAgentLoopState.PLAN_SUCCEEDED,
                        Optional.empty(), Optional.of(cut),
                        Optional.empty(), Optional.empty());
            }
            if (cut instanceof PersistedStepRecoveryReady ready) {
                StepActivationCompositionOutcome activated;
                try {
                    activated = activation.composeReady(
                            new ReadyStepActivationCompositionRequest(
                                    ready,
                                    command.readyActivationAttempt()));
                } catch (RuntimeException exception) {
                    throw protocol("activation");
                }
                if (!(activated instanceof StepActivationCommitted)) {
                    return outcome(planId, cycle - 1,
                            PersistentPlanAgentLoopState
                                    .ACTIVATION_REJECTED,
                            Optional.of(ready.readyStepId()),
                            Optional.of(ready), Optional.empty(),
                            Optional.ofNullable(failure(activated)));
                }
            }

            StepRecoveryCompositionOutcome recovered;
            try {
                recovered = recoverer.recover(
                        new StepRecoveryRequest(
                                planId, recoveryAttempt));
            } catch (RuntimeException exception) {
                throw protocol("recovery");
            }
            if (!(recovered instanceof RecoveredActiveStep active)) {
                return outcome(planId, cycle - 1,
                        PersistentPlanAgentLoopState
                                .RECOVERY_REJECTED,
                        Optional.empty(), Optional.empty(),
                        Optional.empty(),
                        Optional.ofNullable(failure(recovered)));
            }

            SingleTurnStepKernelOutcome kernelOutcome;
            try {
                kernelOutcome = turnKernel.run(
                        new SingleTurnStepKernelRequest(active));
            } catch (SingleTurnStepKernelProtocolException exception) {
                throw new PersistentPlanAgentLoopException(
                        "kernel", exception);
            } catch (RuntimeException exception) {
                throw protocol("kernel");
            }
            verifyKernel(active, kernelOutcome);
            PlanStepId stepId =
                    active.recovery().activation().stepId();
            if (kernelOutcome instanceof SingleTurnNoEffect) {
                io.paperagent.v2.persistence.ActiveStepReplanRequest proposal =
                        replanProposal(active, replanFactory);
                if (proposal != null) {
                    return replanNoEffect(
                            planId, cycle, active, proposal);
                }
                return outcome(planId, cycle,
                        PersistentPlanAgentLoopState.REPLAN_REQUIRED,
                        Optional.of(stepId),
                        Optional.of(active.recovery()),
                        Optional.empty(), Optional.empty());
            }
            if (kernelOutcome
                    instanceof SingleTurnPersistenceRejected rejected) {
                return outcome(planId, cycle,
                        PersistentPlanAgentLoopState
                                .KERNEL_PERSISTENCE_REJECTED,
                        Optional.of(stepId),
                        Optional.of(active.recovery()),
                        Optional.empty(),
                        Optional.of(rejected.failure()));
            }
            if (!(kernelOutcome
                    instanceof SingleTurnIntentPersisted intent)) {
                throw protocol("kernelOutcome");
            }
            String effectKind = intent.persistedIntent().intent().kind();
            if (!LITERATURE_SEARCH.equals(effectKind)
                    && !"project.read".equals(effectKind)
                    && !"project.search".equals(effectKind)
                    && !PROJECT_CANDIDATE_COMPOSE.equals(effectKind)) {
                return outcome(planId, cycle,
                        PersistentPlanAgentLoopState
                                .UNSUPPORTED_INTENT,
                        Optional.of(stepId),
                        Optional.of(active.recovery()),
                        Optional.empty(), Optional.empty());
            }

            io.paperagent.v2.persistence.PersistedEffectResult effectResult;
            try {
                if (LITERATURE_SEARCH.equals(effectKind)) {
                    AuthenticatedLiteratureSearchEffectExecutionOutcome effect =
                            effects.execute(
                                    userId, agentTurnId,
                                    new AuthenticatedLiteratureSearchEffectExecutionCommand(
                                            planId,
                                            intent.persistedIntent().intent()
                                                    .toolCallId(),
                                            recoveryAttempt));
                    effectResult = effect.result();
                } else {
                    var effect = projectEffects.execute(
                            userId, agentTurnId,
                            new AuthenticatedProjectEffectExecutionCommand(
                                    planId,
                                    intent.persistedIntent().intent()
                                            .toolCallId(),
                                    recoveryAttempt, requestProvider));
                    effectResult = effect.result();
                }
            } catch (ProjectEffectExecutionException exception) {
                throw protocol("effect." + exception.stage());
            } catch (RuntimeException exception) {
                throw protocol("effect");
            }
            if (effectResult == null
                    || !effectResult.receipt()
                            .toolCallId().equals(
                                    intent.persistedIntent().intent()
                                            .toolCallId())) {
                return outcome(planId, cycle,
                        PersistentPlanAgentLoopState.EFFECT_REJECTED,
                        Optional.of(stepId),
                        Optional.of(active.recovery()),
                        Optional.empty(), Optional.empty());
            }
            try {
                latestReceipt = Optional.of(
                        PersistentPlanAgentLoopReceiptFacts.from(
                                effectResult.receipt()));
            } catch (RuntimeException exception) {
                throw protocol("effectReceipt");
            }
            if (effectResult.receipt().status()
                    != ReceiptStatus.SUCCESS) {
                io.paperagent.v2.persistence.ActiveStepReplanRequest proposal =
                        replanProposal(active, replanFactory);
                if (proposal != null) {
                    return replanCompletedEffect(
                            planId, cycle, active, proposal,
                            intent.persistedIntent(), latestReceipt);
                }
                return outcome(planId, cycle,
                        PersistentPlanAgentLoopState.EFFECT_REJECTED,
                        Optional.of(stepId),
                        Optional.of(active.recovery()),
                        Optional.empty(), Optional.empty(), latestReceipt);
            }

            EffectDrivenStepProgressionOutcome progressed;
            try {
                progressed = progression.progress(
                        userId, agentTurnId,
                        new EffectDrivenStepProgressionCommand(
                                planId,
                                intent.persistedIntent().intent()
                                        .toolCallId(),
                                recoveryAttempt,
                                command.nextStepActivationAttempt()));
            } catch (EffectDrivenStepProgressionException exception) {
                throw protocol("progression." + exception.path());
            } catch (RuntimeException exception) {
                throw protocol("progression");
            }
            if (progressed == null
                    || !progressed.planId().equals(planId)
                    || !progressed.completedStepId()
                            .equals(stepId)) {
                return outcome(planId, cycle,
                        PersistentPlanAgentLoopState
                                .PROGRESSION_REJECTED,
                        Optional.of(stepId),
                        Optional.empty(), Optional.empty(),
                        Optional.empty(), latestReceipt);
            }
            if (progressed.state()
                    == EffectDrivenStepProgressionState.PLAN_SUCCEEDED) {
                return outcome(planId, cycle,
                        PersistentPlanAgentLoopState.PLAN_SUCCEEDED,
                        Optional.of(stepId),
                        Optional.of(progressed.snapshot()),
                        Optional.empty(), Optional.empty(), latestReceipt);
            }
            recoveryAttempt =
                    new io.paperagent.v2.runtime.execution.recovery
                            .composition.StepRecoveryLeaseAttempt(
                            command.nextStepActivationAttempt()
                                    .leaseOwnerId(),
                            command.nextStepActivationAttempt()
                                    .leaseToken(),
                            command.nextStepActivationAttempt()
                                    .leaseExpiresAt());
        }
        StepRecoverySnapshot cut = inspect(planId);
        return outcome(planId, command.maxCycles(),
                cut instanceof PersistedStepRecoverySucceeded
                        ? PersistentPlanAgentLoopState.PLAN_SUCCEEDED
                        : PersistentPlanAgentLoopState.REPLAN_REQUIRED,
                activeStep(cut), Optional.of(cut),
                Optional.empty(), Optional.empty(), latestReceipt);
    }

    private static io.paperagent.v2.persistence.ActiveStepReplanRequest
            replanProposal(
                    RecoveredActiveStep active,
                    java.util.function.Function<
                            RecoveredActiveStep,
                            io.paperagent.v2.persistence.ActiveStepReplanRequest>
                            replanFactory) {
        try {
            return replanFactory == null
                    ? null : replanFactory.apply(active);
        } catch (RuntimeException exception) {
            throw protocol("replanFactory");
        }
    }

    private PersistentPlanAgentLoopOutcome replanNoEffect(
            PlanId planId, int cycle, RecoveredActiveStep active,
            io.paperagent.v2.persistence.ActiveStepReplanRequest request) {
        BoundedStepAgentLoopNoEffect noEffect =
                new BoundedStepAgentLoopNoEffect(
                        planId,
                        active.recovery().activation().stepId(),
                        1, java.util.List.of());
        BoundedStepReplanCompositionOutcome composed;
        try {
            composed = replans.composeNoEffect(
                    active, noEffect, request);
        } catch (RuntimeException exception) {
            throw protocol("replan");
        }
        return replanOutcome(
                planId, cycle, active, composed, Optional.empty());
    }

    private PersistentPlanAgentLoopOutcome replanCompletedEffect(
            PlanId planId, int cycle, RecoveredActiveStep active,
            io.paperagent.v2.persistence.ActiveStepReplanRequest request,
            io.paperagent.v2.persistence.PersistedEffectIntent intent,
            Optional<PersistentPlanAgentLoopReceiptFacts> receiptFacts) {
        BoundedStepAgentLoopTurnLimitReached completedEffect =
                new BoundedStepAgentLoopTurnLimitReached(
                        planId,
                        active.recovery().activation().stepId(),
                        1, java.util.List.of(intent));
        BoundedStepReplanCompositionOutcome composed;
        try {
            composed = replans.compose(
                    active, completedEffect, request);
        } catch (RuntimeException exception) {
            throw protocol("replan");
        }
        return replanOutcome(
                planId, cycle, active, composed, receiptFacts);
    }

    private PersistentPlanAgentLoopOutcome replanOutcome(
            PlanId planId, int cycle, RecoveredActiveStep active,
            BoundedStepReplanCompositionOutcome composed,
            Optional<PersistentPlanAgentLoopReceiptFacts> receiptFacts) {
        if (composed
                instanceof BoundedStepReplanPersistenceRejected rejected) {
            return outcome(planId, cycle,
                    PersistentPlanAgentLoopState
                            .REPLAN_REJECTED,
                    Optional.of(
                            active.recovery().activation().stepId()),
                    Optional.of(active.recovery()),
                    Optional.empty(),
                    Optional.of(rejected.failure()), receiptFacts);
        }
        PersistedActiveStepReplan persisted;
        PersistentPlanAgentLoopState state;
        if (composed instanceof BoundedStepReplanApplied applied) {
            persisted = applied.persistedReplan();
            state = PersistentPlanAgentLoopState.REPLAN_APPLIED;
        } else if (composed
                instanceof BoundedStepReplanReplayed replayed) {
            persisted = replayed.persistedReplan();
            state = PersistentPlanAgentLoopState.REPLAN_REPLAYED;
        } else {
            throw protocol("replanOutcome");
        }
        StepRecoverySnapshot replacement = inspect(planId);
        return outcome(planId, cycle, state,
                Optional.of(persisted.supersededStepId()),
                Optional.of(replacement), Optional.of(persisted),
                Optional.empty(), receiptFacts);
    }

    private StepRecoverySnapshot inspect(PlanId planId) {
        try {
            PersistenceResult<StepRecoverySnapshot> result =
                    inspections.inspect(planId);
            if (result == null
                    || result.outcome() != PersistenceOutcome.FOUND
                    || result.failure().isPresent()
                    || result.value().isEmpty()
                    || !result.value().orElseThrow().planId()
                            .equals(planId)) {
                throw protocol("inspection");
            }
            return result.value().orElseThrow();
        } catch (PersistentPlanAgentLoopException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw protocol("inspection");
        }
    }

    private static void verifyKernel(
            RecoveredActiveStep active,
            SingleTurnStepKernelOutcome outcome) {
        if (outcome == null
                || !outcome.planId().equals(active.planId())
                || !outcome.stepId().equals(
                        active.recovery().activation().stepId())) {
            throw protocol("kernelAuthority");
        }
    }

    private static void requireCommand(
            PersistentPlanAgentLoopCommand command) {
        if (command == null || command.maxCycles() < 1
                || command.currentRecoveryAttempt() == null
                || command.readyActivationAttempt() == null
                || command.nextStepActivationAttempt() == null
                || command.replanProposal() == null) {
            throw new IllegalArgumentException(
                    "persistentPlanAgentLoop.command is invalid");
        }
    }

    private static Optional<PlanStepId> activeStep(
            StepRecoverySnapshot snapshot) {
        return snapshot instanceof PersistedStepRecoveryActive active
                ? Optional.of(active.activation().stepId())
                : Optional.empty();
    }

    private static PersistenceFailure failure(Object value) {
        if (value instanceof StepRecoveryLeaseRejected rejected) {
            return rejected.failure();
        }
        if (value instanceof StepRecoveryPersistenceRejected rejected) {
            return rejected.failure();
        }
        if (value instanceof StepActivationLeaseRejected rejected) {
            return rejected.failure();
        }
        if (value instanceof StepActivationPersistenceRejected rejected) {
            return rejected.failure();
        }
        return null;
    }

    private static PersistentPlanAgentLoopOutcome outcome(
            PlanId planId, int cycles,
            PersistentPlanAgentLoopState state,
            Optional<PlanStepId> stepId,
            Optional<StepRecoverySnapshot> snapshot,
            Optional<PersistedActiveStepReplan> replan,
            Optional<PersistenceFailure> failure) {
        return new PersistentPlanAgentLoopOutcome(
                planId, cycles, state, stepId,
                cut(snapshot), replanEvidence(replan), failure,
                Optional.empty());
    }

    private static PersistentPlanAgentLoopOutcome outcome(
            PlanId planId, int cycles,
            PersistentPlanAgentLoopState state,
            Optional<PlanStepId> stepId,
            Optional<StepRecoverySnapshot> snapshot,
            Optional<PersistedActiveStepReplan> replan,
            Optional<PersistenceFailure> failure,
            Optional<PersistentPlanAgentLoopReceiptFacts> receiptFacts) {
        return new PersistentPlanAgentLoopOutcome(
                planId, cycles, state, stepId,
                cut(snapshot), replanEvidence(replan), failure,
                receiptFacts);
    }

    private static Optional<PersistentPlanAgentLoopCut> cut(
            Optional<StepRecoverySnapshot> snapshot) {
        return snapshot.map(value -> {
            PersistentPlanAgentLoopCutKind kind;
            Optional<PlanStepId> stepId;
            VersionedCheckpoint checkpoint;
            if (value instanceof PersistedStepRecoveryReady ready) {
                kind = PersistentPlanAgentLoopCutKind.READY;
                stepId = Optional.of(ready.readyStepId());
                checkpoint = ready.checkpoint();
            } else if (value
                    instanceof PersistedStepRecoveryActive active) {
                kind = PersistentPlanAgentLoopCutKind.ACTIVE;
                stepId = Optional.of(active.activation().stepId());
                checkpoint = active.checkpoint();
            } else if (value
                    instanceof PersistedStepRecoverySucceeded succeeded) {
                kind = PersistentPlanAgentLoopCutKind.SUCCEEDED;
                stepId = Optional.empty();
                checkpoint = succeeded.checkpoint();
            } else {
                throw protocol("cut");
            }
            if (checkpoint == null
                    || checkpoint.checkpoint() == null) {
                return new PersistentPlanAgentLoopCut(
                        kind, stepId, Optional.empty(),
                        Optional.empty(), Optional.empty(),
                        Optional.empty());
            }
            return new PersistentPlanAgentLoopCut(
                    kind, stepId,
                    Optional.of(checkpoint.checkpoint().revisionId()),
                    Optional.of(
                            checkpoint.checkpoint().revisionNumber()),
                    Optional.of(checkpoint.version()),
                    Optional.of(
                            checkpoint.checkpoint()
                                    .lastEventSequence()));
        });
    }

    private static Optional<PersistentPlanAgentLoopReplanEvidence>
            replanEvidence(
                    Optional<PersistedActiveStepReplan> replan) {
        return replan.map(value ->
                new PersistentPlanAgentLoopReplanEvidence(
                        value.supersededStepId(),
                        value.supersessionEvent().id(),
                        value.replanEvent().id(),
                        value.replannedRevision().id(),
                        value.supersededCheckpoint().version(),
                        value.replannedCheckpoint().version()));
    }

    private static PersistentPlanAgentLoopException protocol(
            String stage) {
        return new PersistentPlanAgentLoopException(stage);
    }
}
