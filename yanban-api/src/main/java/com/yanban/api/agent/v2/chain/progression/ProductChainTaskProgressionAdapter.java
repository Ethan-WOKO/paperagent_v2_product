package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.recovery.ProductChainNextRoleSelector;
import com.yanban.api.agent.v2.chain.recovery.ProductChainRecoveryCoordinator;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;
import java.time.Clock;
import java.util.Objects;

/**
 * Strict one-selection bridge from durable recovery to role-owned actions.
 * It routes only the selector's frozen identity and never derives a role or
 * retries another action itself.
 */
public final class ProductChainTaskProgressionAdapter
        implements ProductChainDurableProgressionDriver.TaskProgression {
    private final RecoveryPort recovery;
    private final ModelProgression models;
    private final MechanicalProgression mechanical;
    private final ProposalProgression proposals;
    private final Clock clock;

    public ProductChainTaskProgressionAdapter(
            ProductChainRecoveryCoordinator recovery,
            ModelProgression models,
            MechanicalProgression mechanical,
            ProposalProgression proposals,
            Clock clock) {
        this(recovery::recover, models, mechanical, proposals, clock);
    }

    ProductChainTaskProgressionAdapter(
            RecoveryPort recovery,
            ModelProgression models,
            MechanicalProgression mechanical,
            Clock clock) {
        this(recovery, models, mechanical, command -> {
            throw new IllegalStateException(
                    "CHAIN_ACCEPTED_PROPOSAL_RECOVERY_OWNER_MISSING");
        }, clock);
    }

    ProductChainTaskProgressionAdapter(
            RecoveryPort recovery,
            ModelProgression models,
            MechanicalProgression mechanical,
            ProposalProgression proposals,
            Clock clock) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.models = Objects.requireNonNull(models, "models");
        this.mechanical = Objects.requireNonNull(
                mechanical, "mechanical");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void advance(
            String taskId, ProductChainProgressionClaim claim) {
        required(taskId, "taskId");
        Objects.requireNonNull(claim, "claim");
        require(taskId.equals(claim.taskId()),
                "progression claim belongs to another task");
        ProductChainRecoveryCoordinator.RecoveryResult recovered =
                Objects.requireNonNull(
                        recovery.recover(taskId, clock.instant()),
                        "recovery result");
        ChainRecoveryRuntime.RecoverySnapshot snapshot = recovered.snapshot();
        require(taskId.equals(snapshot.taskId()),
                "recovery returned another task");
        require(snapshot.roleProjection().authorityCut()
                        >= claim.authorityEventCut(),
                "recovery snapshot predates the progression claim");

        ProductChainNextRoleSelector.MechanicalFinalization completed =
                completedMechanical(recovered);
        if (completed != null) {
            // ProductChainRecoveryCoordinator already executed exactly this
            // retained selection. Dispatching another action would overrun
            // the single-action boundary.
            expectedMechanical(completed);
            return;
        }

        if (recovered instanceof ProductChainRecoveryCoordinator
                .RuntimeOutcome runtime) {
            ChainRecoveryRuntime.RecoveryOutcome outcome = runtime.outcome();
            if (outcome.disposition()
                    != ChainRecoveryRuntime.RecoveryDisposition
                    .NEXT_ROLE_SELECTED
                    && outcome.disposition()
                    != ChainRecoveryRuntime.RecoveryDisposition
                    .WAITING_FORMAL_SUCCESSOR) {
                return;
            }
            ChainRecoveryRuntime.NextDirective directive = Objects
                    .requireNonNull(outcome.nextDirective(),
                            "selected model directive");
            SelectedAction expected = SelectedAction.model(directive);
            ActionReceipt receipt = Objects.requireNonNull(
                    models.advance(new ModelCommand(
                            taskId, snapshot, claim, directive)),
                    "model progression receipt");
            require(expected.equals(receipt.consumedSelection()),
                    "model progression consumed another selector identity");
            return;
        }

        ProductChainNextRoleSelector.Selection selection =
                ((ProductChainRecoveryCoordinator.Waiting) recovered)
                        .directive();
        if (selection instanceof ProductChainNextRoleSelector.ControlWait) {
            return;
        }
        if (selection instanceof ProductChainNextRoleSelector
                .MechanicalProposal proposal) {
            SelectedAction expected = expectedMechanical(proposal);
            ActionReceipt receipt = Objects.requireNonNull(
                    proposals.advance(new MechanicalCommand(
                            taskId, snapshot, claim, proposal)),
                    "proposal progression receipt");
            require(expected.equals(receipt.consumedSelection()),
                    "proposal progression consumed another selector identity");
            return;
        }
        SelectedAction expected = expectedMechanical(selection);
        ActionReceipt receipt = Objects.requireNonNull(
                mechanical.advance(new MechanicalCommand(
                        taskId, snapshot, claim, selection)),
                "mechanical progression receipt");
        require(expected.equals(receipt.consumedSelection()),
                "mechanical progression consumed another selector identity");
    }

    private static ProductChainNextRoleSelector.MechanicalFinalization
            completedMechanical(
                    ProductChainRecoveryCoordinator.RecoveryResult result) {
        if (result instanceof ProductChainRecoveryCoordinator
                .RuntimeOutcome runtime) {
            return runtime.completedMechanicalSelection().orElse(null);
        }
        return ((ProductChainRecoveryCoordinator.Waiting) result)
                .completedMechanicalSelection().orElse(null);
    }

    private static SelectedAction expectedMechanical(
            ProductChainNextRoleSelector.Selection selection) {
        if (selection instanceof ProductChainNextRoleSelector
                .MechanicalDelivery delivery) {
            return SelectedAction.mechanical(
                    "DELIVERY", delivery.deliveryId());
        }
        if (selection instanceof ProductChainNextRoleSelector
                .MechanicalDirectPlannerDelivery direct) {
            return SelectedAction.mechanical(
                    "DIRECT_PLANNER_ROUTE", direct.routeDecisionId());
        }
        if (selection instanceof ProductChainNextRoleSelector
                .MechanicalFinalization finalization) {
            return SelectedAction.mechanical(
                    "FINALIZATION_READINESS",
                    finalization.readinessId());
        }
        if (selection instanceof ProductChainNextRoleSelector
                .MechanicalProposal proposal) {
            return SelectedAction.mechanical(
                    "MODEL_PROPOSAL", proposal.proposalId());
        }
        if (selection instanceof ProductChainNextRoleSelector
                .MechanicalPermission permission) {
            return SelectedAction.mechanical(
                    "PERMISSION_DECISION",
                    permission.permissionDecisionId());
        }
        if (selection instanceof ProductChainNextRoleSelector
                .MechanicalModelFailure failure) {
            return SelectedAction.mechanical(
                    "MODEL_CALL_FAILED", failure.invocationId());
        }
        if (selection instanceof ProductChainNextRoleSelector
                .MechanicalContextFailure failure) {
            return SelectedAction.mechanical(
                    "CONTEXT_BUILD_FAILURE",
                    failure.contextBuildFailureId());
        }
        throw new IllegalArgumentException(
                "selection is not a mechanical action");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @FunctionalInterface
    interface RecoveryPort {
        ProductChainRecoveryCoordinator.RecoveryResult recover(
                String taskId, java.time.Instant observedAt);
    }

    @FunctionalInterface
    public interface ModelProgression {
        ActionReceipt advance(ModelCommand command);
    }

    @FunctionalInterface
    public interface MechanicalProgression {
        ActionReceipt advance(MechanicalCommand command);
    }

    @FunctionalInterface
    public interface ProposalProgression {
        ActionReceipt advance(MechanicalCommand command);
    }

    public record ModelCommand(
            String taskId,
            ChainRecoveryRuntime.RecoverySnapshot snapshot,
            ProductChainProgressionClaim claim,
            ChainRecoveryRuntime.NextDirective directive) {
        public ModelCommand {
            validateCommon(taskId, snapshot, claim);
            Objects.requireNonNull(directive, "directive");
        }
    }

    public record MechanicalCommand(
            String taskId,
            ChainRecoveryRuntime.RecoverySnapshot snapshot,
            ProductChainProgressionClaim claim,
            ProductChainNextRoleSelector.Selection selection) {
        public MechanicalCommand {
            validateCommon(taskId, snapshot, claim);
            Objects.requireNonNull(selection, "selection");
            if (selection instanceof ProductChainNextRoleSelector.Model
                    || selection instanceof ProductChainNextRoleSelector
                    .ControlWait) {
                throw new IllegalArgumentException(
                        "selection is not a mechanical action");
            }
        }
    }

    public record SelectedAction(
            ActionKind kind,
            ChainRole role,
            ChainWorkState workState,
            String sourceAuthorityType,
            String sourceAuthorityRef) {
        public SelectedAction {
            Objects.requireNonNull(kind, "kind");
            required(sourceAuthorityType, "sourceAuthorityType");
            required(sourceAuthorityRef, "sourceAuthorityRef");
            if (kind == ActionKind.MODEL
                    ? role == null || workState == null
                    : role != null || workState != null) {
                throw new IllegalArgumentException(
                        "only a model selection carries role and work state");
            }
        }

        public static SelectedAction model(
                ChainRecoveryRuntime.NextDirective directive) {
            Objects.requireNonNull(directive, "directive");
            return new SelectedAction(
                    ActionKind.MODEL, directive.role(),
                    directive.workState(),
                    directive.sourceAuthorityType(),
                    directive.sourceAuthorityRef());
        }

        public static SelectedAction mechanical(
                String authorityType, String authorityRef) {
            return new SelectedAction(
                    ActionKind.MECHANICAL, null, null,
                    authorityType, authorityRef);
        }
    }

    public record ActionReceipt(SelectedAction consumedSelection) {
        public ActionReceipt {
            Objects.requireNonNull(
                    consumedSelection, "consumedSelection");
        }
    }

    public enum ActionKind {
        MODEL,
        MECHANICAL
    }

    private static void validateCommon(
            String taskId,
            ChainRecoveryRuntime.RecoverySnapshot snapshot,
            ProductChainProgressionClaim claim) {
        required(taskId, "taskId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(claim, "claim");
        if (!taskId.equals(snapshot.taskId())
                || !taskId.equals(claim.taskId())) {
            throw new IllegalArgumentException(
                    "progression command identity crosses tasks");
        }
    }
}
