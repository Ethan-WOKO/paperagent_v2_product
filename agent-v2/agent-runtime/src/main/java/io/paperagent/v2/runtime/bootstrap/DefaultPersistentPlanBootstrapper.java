package io.paperagent.v2.runtime.bootstrap;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanBootstrapRepository;
import io.paperagent.v2.runtime.checkpoint.InitialCheckpointFreezeRequest;
import io.paperagent.v2.runtime.checkpoint.InitialCheckpointFreezer;
import io.paperagent.v2.runtime.planning.InitialPlanFreezeRequest;
import io.paperagent.v2.runtime.planning.InitialPlanFreezer;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.taskframe.TaskFrameFreezer;

import java.util.Objects;

/**
 * Freezes and atomically persists one caller-authorized persistent Plan tuple.
 *
 * <p>This composition does not authorize or start execution. The persistence
 * outcome is returned unchanged for the caller to interpret explicitly.
 */
public final class DefaultPersistentPlanBootstrapper
        implements PersistentPlanBootstrapper {
    private final TaskFrameFreezer taskFrameFreezer;
    private final InitialPlanFreezer initialPlanFreezer;
    private final InitialCheckpointFreezer initialCheckpointFreezer;
    private final PlanBootstrapRepository planBootstrapRepository;

    public DefaultPersistentPlanBootstrapper(
            TaskFrameFreezer taskFrameFreezer,
            InitialPlanFreezer initialPlanFreezer,
            InitialCheckpointFreezer initialCheckpointFreezer,
            PlanBootstrapRepository planBootstrapRepository) {
        this.taskFrameFreezer =
                Objects.requireNonNull(taskFrameFreezer, "taskFrameFreezer");
        this.initialPlanFreezer =
                Objects.requireNonNull(initialPlanFreezer, "initialPlanFreezer");
        this.initialCheckpointFreezer = Objects.requireNonNull(
                initialCheckpointFreezer,
                "initialCheckpointFreezer");
        this.planBootstrapRepository = Objects.requireNonNull(
                planBootstrapRepository,
                "planBootstrapRepository");
    }

    @Override
    public PersistenceResult<PersistedPlanBootstrap> bootstrap(
            PersistentPlanBootstrapRequest request) {
        PersistentPlanBootstrapValues.required(
                request,
                "persistentPlanBootstrapRequest");
        RoutingDecision routingDecision =
                request.taskFrameFreezeRequest().routingDecision();
        if (routingDecision.route() != Route.PERSISTENT_PLAN_EXECUTE) {
            PersistentPlanBootstrapValues.fail(
                    PersistentPlanBootstrapValidationCode.ROUTE_NOT_PERSISTENT,
                    "persistentPlanBootstrapRequest"
                            + ".taskFrameFreezeRequest.routingDecision.route",
                    "Plan bootstrap requires a persistent route");
        }

        TaskFrame taskFrame =
                taskFrameFreezer.freeze(request.taskFrameFreezeRequest());
        Plan plan = initialPlanFreezer.freeze(new InitialPlanFreezeRequest(
                routingDecision,
                taskFrame,
                request.planId(),
                request.initialRevisionId(),
                request.initialPlanDraft(),
                request.planCreatedAt()));
        Checkpoint checkpoint = initialCheckpointFreezer.freeze(
                new InitialCheckpointFreezeRequest(
                        routingDecision,
                        taskFrame,
                        plan,
                        request.checkpointCreatedAt()));
        return planBootstrapRepository.bootstrap(taskFrame, plan, checkpoint);
    }
}
