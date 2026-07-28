package com.yanban.api.agent.v2.execution;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelOutcome;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelProtocolException;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelRequest;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelValidationException;
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

/** Authenticated composition of active-Step recovery and one kernel turn. */
@Service
public class AuthenticatedAgentTurnStepTurnComposer {
    private static final String ROOT = "authenticatedStepTurn";

    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final StepRecoverer recoverer;
    private final SingleTurnStepKernel kernel;

    public AuthenticatedAgentTurnStepTurnComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepRecoverer recoverer,
            SingleTurnStepKernel kernel) {
        this.contexts = contexts;
        this.planIds = planIds;
        this.recoverer = recoverer;
        this.kernel = kernel;
    }

    public AuthenticatedAgentTurnStepTurnOutcome execute(
            Long userId,
            Long turnId,
            AuthenticatedAgentTurnStepTurnCommand command) {
        VerifiedAgentTurnProductContext context = contexts.resolve(userId, turnId);
        PlanId planId = planIds.derive(context.identity());
        requireCommand(command);

        StepRecoveryCompositionOutcome recovery =
                recover(planId, command);
        validateRecovery(planId, recovery);
        if (recovery instanceof StepRecoveryLeaseRejected
                || recovery instanceof StepRecoveryPersistenceRejected) {
            return new AuthenticatedAgentTurnStepTurnRecoveryRejected(recovery);
        }
        if (!(recovery instanceof RecoveredActiveStep active)) {
            throw failure(
                    AuthenticatedAgentTurnStepTurnCompositionCode
                            .INVALID_RECOVERY_RESULT,
                    ROOT + ".recovery");
        }
        SingleTurnStepKernelOutcome outcome = run(active);
        if (!planId.equals(outcome.planId())) {
            throw failure(
                    AuthenticatedAgentTurnStepTurnCompositionCode
                            .KERNEL_PLAN_MISMATCH,
                    ROOT + ".kernel.planId");
        }
        return new AuthenticatedAgentTurnStepTurnExecuted(outcome);
    }

    private StepRecoveryCompositionOutcome recover(
            PlanId planId,
            AuthenticatedAgentTurnStepTurnCommand command) {
        try {
            StepRecoveryCompositionOutcome outcome = recoverer.recover(
                    new StepRecoveryRequest(
                            planId, command.recoveryAttempt()));
            if (outcome == null) {
                throw invalidRecovery();
            }
            return outcome;
        } catch (StepRecoveryValidationException
                | StepRecoveryProtocolException exception) {
            throw exception;
        } catch (AuthenticatedAgentTurnStepTurnCompositionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(
                    AuthenticatedAgentTurnStepTurnCompositionCode
                            .RECOVERY_COLLABORATOR_FAILURE,
                    ROOT + ".recovery");
        }
    }

    private SingleTurnStepKernelOutcome run(RecoveredActiveStep active) {
        try {
            SingleTurnStepKernelOutcome outcome =
                    kernel.run(new SingleTurnStepKernelRequest(active));
            if (outcome == null || outcome.planId() == null
                    || outcome.stepId() == null) {
                throw failure(
                        AuthenticatedAgentTurnStepTurnCompositionCode
                                .INVALID_KERNEL_RESULT,
                        ROOT + ".kernel");
            }
            return outcome;
        } catch (SingleTurnStepKernelValidationException
                | SingleTurnStepKernelProtocolException exception) {
            throw exception;
        } catch (AuthenticatedAgentTurnStepTurnCompositionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(
                    AuthenticatedAgentTurnStepTurnCompositionCode
                            .KERNEL_COLLABORATOR_FAILURE,
                    ROOT + ".kernel");
        }
    }

    private static void validateRecovery(
            PlanId planId,
            StepRecoveryCompositionOutcome recovery) {
        try {
            if (recovery.planId() == null
                    || recovery.leaseDisposition() == null) {
                throw invalidRecovery();
            }
            if (!planId.equals(recovery.planId())) {
                throw failure(
                        AuthenticatedAgentTurnStepTurnCompositionCode
                                .RECOVERY_PLAN_MISMATCH,
                        ROOT + ".recovery.planId");
            }
            if (recovery instanceof RecoveredActiveStep active) {
                if (active.recovery() == null || active.lease() == null
                        || active.leaseDisposition()
                                != StepRecoveryLeaseDisposition
                                        .RETAINED_FOR_RECOVERY
                        || !planId.equals(active.recovery().planId())
                        || !planId.equals(active.lease().planId())) {
                    throw invalidRecovery();
                }
            } else if (recovery instanceof StepRecoveryLeaseRejected rejected) {
                if (rejected.failure() == null
                        || rejected.leaseDisposition()
                                != StepRecoveryLeaseDisposition.NOT_ACQUIRED) {
                    throw invalidRecovery();
                }
            } else if (recovery
                    instanceof StepRecoveryPersistenceRejected rejected) {
                if (rejected.failure() == null) {
                    throw invalidRecovery();
                }
            } else {
                throw invalidRecovery();
            }
        } catch (AuthenticatedAgentTurnStepTurnCompositionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(
                    AuthenticatedAgentTurnStepTurnCompositionCode
                            .INVALID_RECOVERY_RESULT,
                    ROOT + ".recovery");
        }
    }

    private static void requireCommand(
            AuthenticatedAgentTurnStepTurnCommand command) {
        if (command == null) {
            throw failure(
                    AuthenticatedAgentTurnStepTurnCompositionCode
                            .REQUIRED_VALUE_MISSING,
                    ROOT + ".command");
        }
        if (command.recoveryAttempt() == null) {
            throw failure(
                    AuthenticatedAgentTurnStepTurnCompositionCode
                            .REQUIRED_VALUE_MISSING,
                    ROOT + ".command.recoveryAttempt");
        }
    }

    private static AuthenticatedAgentTurnStepTurnCompositionException
            invalidRecovery() {
        return failure(
                AuthenticatedAgentTurnStepTurnCompositionCode
                        .INVALID_RECOVERY_RESULT,
                ROOT + ".recovery");
    }

    private static AuthenticatedAgentTurnStepTurnCompositionException failure(
            AuthenticatedAgentTurnStepTurnCompositionCode code,
            String path) {
        return new AuthenticatedAgentTurnStepTurnCompositionException(
                code, path);
    }

}
