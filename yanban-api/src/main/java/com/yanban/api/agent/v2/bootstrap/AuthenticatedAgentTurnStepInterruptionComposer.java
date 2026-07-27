package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionComposer;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionCommitted;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionCompositionOutcome;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionLeaseDisposition;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionPersistenceRejected;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionCompositionProtocolException;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionCompositionValidationException;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationProtocolException;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationRequest;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationValidationException;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryCompositionOutcome;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseRejected;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryPersistenceRejected;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryProtocolException;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryRequest;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryValidationException;
import org.springframework.stereotype.Service;

/** Internal authenticated composition of recovery and active-Step interruption. */
@Service
public class AuthenticatedAgentTurnStepInterruptionComposer {
    private static final String ROOT = "authenticatedStepInterruption";

    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final StepRecoverer recoverer;
    private final ActiveStepInterruptionComposer interruptions;

    public AuthenticatedAgentTurnStepInterruptionComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepRecoverer recoverer,
            ActiveStepInterruptionComposer interruptions) {
        this.contexts = contexts;
        this.planIds = planIds;
        this.recoverer = recoverer;
        this.interruptions = interruptions;
    }

    public AuthenticatedAgentTurnStepInterruptionOutcome interrupt(
            Long userId,
            Long turnId,
            AuthenticatedAgentTurnStepInterruptionCommand command) {
        VerifiedAgentTurnProductContext context = contexts.resolve(userId, turnId);
        PlanId planId = planIds.derive(context.identity());
        requireCommand(command);

        StepRecoveryCompositionOutcome recovery =
                recover(planId, command.recoveryAttempt());
        validateRecovery(planId, recovery);
        if (recovery instanceof StepRecoveryLeaseRejected
                || recovery instanceof StepRecoveryPersistenceRejected) {
            return new AuthenticatedAgentTurnStepInterruptionRecoveryRejected(
                    recovery);
        }
        if (!(recovery instanceof RecoveredActiveStep recovered)) {
            throw failure(
                    AuthenticatedAgentTurnStepInterruptionCompositionCode
                            .INVALID_RECOVERY_RESULT,
                    ROOT + ".recovery");
        }

        ActiveStepInterruptionCompositionOutcome interruption =
                interrupt(recovered, command);
        validateInterruption(planId, interruption);
        return new AuthenticatedAgentTurnStepInterrupted(interruption);
    }

    private StepRecoveryCompositionOutcome recover(
            PlanId planId,
            io.paperagent.v2.runtime.execution.recovery.composition
                    .StepRecoveryLeaseAttempt attempt) {
        try {
            StepRecoveryCompositionOutcome outcome = recoverer.recover(
                    new StepRecoveryRequest(planId, attempt));
            if (outcome == null) {
                throw failure(
                        AuthenticatedAgentTurnStepInterruptionCompositionCode
                                .INVALID_RECOVERY_RESULT,
                        ROOT + ".recovery");
            }
            return outcome;
        } catch (StepRecoveryValidationException
                | StepRecoveryProtocolException exception) {
            throw exception;
        } catch (AuthenticatedAgentTurnStepInterruptionCompositionException
                exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(
                    AuthenticatedAgentTurnStepInterruptionCompositionCode
                            .RECOVERY_COLLABORATOR_FAILURE,
                    ROOT + ".recovery",
                    exception);
        }
    }

    private static void validateRecovery(
            PlanId planId,
            StepRecoveryCompositionOutcome recovery) {
        try {
            if (recovery.planId() == null
                    || recovery.leaseDisposition() == null) {
                throw failure(
                        AuthenticatedAgentTurnStepInterruptionCompositionCode
                                .INVALID_RECOVERY_RESULT,
                        ROOT + ".recovery");
            }
            if (!planId.equals(recovery.planId())) {
                throw failure(
                        AuthenticatedAgentTurnStepInterruptionCompositionCode
                                .RECOVERY_PLAN_MISMATCH,
                        ROOT + ".recovery.planId");
            }
            if (recovery instanceof RecoveredActiveStep recovered) {
                if (recovered.recovery() == null
                        || recovered.lease() == null
                        || recovered.leaseDisposition()
                                != StepRecoveryLeaseDisposition
                                        .RETAINED_FOR_RECOVERY
                        || !planId.equals(recovered.recovery().planId())
                        || !planId.equals(recovered.lease().planId())) {
                    throw failure(
                            AuthenticatedAgentTurnStepInterruptionCompositionCode
                                    .INVALID_RECOVERY_RESULT,
                            ROOT + ".recovery");
                }
            } else if (recovery instanceof StepRecoveryLeaseRejected rejected) {
                if (rejected.failure() == null
                        || rejected.leaseDisposition()
                                != StepRecoveryLeaseDisposition.NOT_ACQUIRED) {
                    throw invalidRecovery();
                }
            } else if (recovery
                    instanceof StepRecoveryPersistenceRejected rejected) {
                boolean validDisposition = switch (rejected.stage()) {
                    case INITIAL_INSPECT ->
                            rejected.leaseDisposition()
                                    == StepRecoveryLeaseDisposition
                                            .NO_LEASE_ACTION;
                    case POST_LEASE_INSPECT ->
                            rejected.leaseDisposition()
                                    == StepRecoveryLeaseDisposition
                                            .RETAINED_FOR_RECOVERY;
                    case LEASE_ACQUIRE -> false;
                };
                if (rejected.failure() == null || !validDisposition) {
                    throw invalidRecovery();
                }
            } else {
                throw failure(
                        AuthenticatedAgentTurnStepInterruptionCompositionCode
                                .INVALID_RECOVERY_RESULT,
                        ROOT + ".recovery");
            }
        } catch (AuthenticatedAgentTurnStepInterruptionCompositionException
                exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(
                    AuthenticatedAgentTurnStepInterruptionCompositionCode
                            .INVALID_RECOVERY_RESULT,
                    ROOT + ".recovery",
                    exception);
        }
    }

    private ActiveStepInterruptionCompositionOutcome interrupt(
            RecoveredActiveStep recovered,
            AuthenticatedAgentTurnStepInterruptionCommand command) {
        var request = new ActiveStepInterruptionMaterializationRequest(
                recovered,
                command.kind(),
                command.eventDraft(),
                command.checkpointCreatedAt());
        try {
            ActiveStepInterruptionCompositionOutcome outcome =
                    interruptions.compose(request);
            if (outcome == null) {
                throw failure(
                        AuthenticatedAgentTurnStepInterruptionCompositionCode
                                .INVALID_INTERRUPTION_RESULT,
                        ROOT + ".interruption");
            }
            return outcome;
        } catch (ActiveStepInterruptionMaterializationValidationException
                | ActiveStepInterruptionMaterializationProtocolException
                | ActiveStepInterruptionCompositionValidationException
                | ActiveStepInterruptionCompositionProtocolException
                        exception) {
            throw exception;
        } catch (AuthenticatedAgentTurnStepInterruptionCompositionException
                exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(
                    AuthenticatedAgentTurnStepInterruptionCompositionCode
                            .INTERRUPTION_COLLABORATOR_FAILURE,
                    ROOT + ".interruption",
                    exception);
        }
    }

    private static void validateInterruption(
            PlanId planId,
            ActiveStepInterruptionCompositionOutcome interruption) {
        try {
            if (interruption.planId() == null
                    || interruption.leaseDisposition() == null) {
                throw failure(
                        AuthenticatedAgentTurnStepInterruptionCompositionCode
                                .INVALID_INTERRUPTION_RESULT,
                        ROOT + ".interruption");
            }
            if (!planId.equals(interruption.planId())) {
                throw failure(
                        AuthenticatedAgentTurnStepInterruptionCompositionCode
                                .INTERRUPTION_PLAN_MISMATCH,
                        ROOT + ".interruption.planId");
            }
            if (interruption.leaseDisposition()
                    != ActiveStepInterruptionLeaseDisposition
                            .RETAINED_FOR_RECOVERY) {
                throw invalidInterruption();
            }
            if (interruption
                    instanceof ActiveStepInterruptionCommitted committed) {
                if (committed.persistedInterruption() == null
                        || committed.persistenceOutcome() == null) {
                    throw invalidInterruption();
                }
            } else if (interruption
                    instanceof ActiveStepInterruptionPersistenceRejected
                            rejected) {
                if (rejected.failure() == null) {
                    throw invalidInterruption();
                }
            } else {
                throw invalidInterruption();
            }
        } catch (AuthenticatedAgentTurnStepInterruptionCompositionException
                exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(
                    AuthenticatedAgentTurnStepInterruptionCompositionCode
                            .INVALID_INTERRUPTION_RESULT,
                    ROOT + ".interruption",
                    exception);
        }
    }

    private static AuthenticatedAgentTurnStepInterruptionCompositionException
            invalidRecovery() {
        return failure(
                AuthenticatedAgentTurnStepInterruptionCompositionCode
                        .INVALID_RECOVERY_RESULT,
                ROOT + ".recovery");
    }

    private static AuthenticatedAgentTurnStepInterruptionCompositionException
            invalidInterruption() {
        return failure(
                AuthenticatedAgentTurnStepInterruptionCompositionCode
                        .INVALID_INTERRUPTION_RESULT,
                ROOT + ".interruption");
    }

    private static void requireCommand(
            AuthenticatedAgentTurnStepInterruptionCommand command) {
        if (command == null) {
            throw required(ROOT + ".command");
        }
        if (command.recoveryAttempt() == null) {
            throw required(ROOT + ".command.recoveryAttempt");
        }
        if (command.kind() == null) {
            throw required(ROOT + ".command.kind");
        }
        if (command.eventDraft() == null) {
            throw required(ROOT + ".command.eventDraft");
        }
        if (command.checkpointCreatedAt() == null) {
            throw required(ROOT + ".command.checkpointCreatedAt");
        }
    }

    private static AuthenticatedAgentTurnStepInterruptionCompositionException
            required(String path) {
        return failure(
                AuthenticatedAgentTurnStepInterruptionCompositionCode
                        .REQUIRED_VALUE_MISSING,
                path);
    }

    private static AuthenticatedAgentTurnStepInterruptionCompositionException
            failure(
                    AuthenticatedAgentTurnStepInterruptionCompositionCode code,
                    String path) {
        return new AuthenticatedAgentTurnStepInterruptionCompositionException(
                code, path);
    }

    private static AuthenticatedAgentTurnStepInterruptionCompositionException
            failure(
                    AuthenticatedAgentTurnStepInterruptionCompositionCode code,
                    String path,
                    Throwable cause) {
        return new AuthenticatedAgentTurnStepInterruptionCompositionException(
                code, path, cause);
    }
}
