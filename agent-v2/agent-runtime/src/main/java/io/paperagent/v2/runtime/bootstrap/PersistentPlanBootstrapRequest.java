package io.paperagent.v2.runtime.bootstrap;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.runtime.planning.InitialPlanDraft;
import io.paperagent.v2.runtime.taskframe.TaskFrameFreezeRequest;

import java.time.Instant;

public record PersistentPlanBootstrapRequest(
        TaskFrameFreezeRequest taskFrameFreezeRequest,
        PlanId planId,
        PlanRevisionId initialRevisionId,
        InitialPlanDraft initialPlanDraft,
        Instant planCreatedAt,
        Instant checkpointCreatedAt) {

    public PersistentPlanBootstrapRequest {
        PersistentPlanBootstrapValues.required(
                taskFrameFreezeRequest,
                "persistentPlanBootstrapRequest.taskFrameFreezeRequest");
        PersistentPlanBootstrapValues.required(
                planId,
                "persistentPlanBootstrapRequest.planId");
        PersistentPlanBootstrapValues.required(
                initialRevisionId,
                "persistentPlanBootstrapRequest.initialRevisionId");
        PersistentPlanBootstrapValues.required(
                initialPlanDraft,
                "persistentPlanBootstrapRequest.initialPlanDraft");
        PersistentPlanBootstrapValues.required(
                planCreatedAt,
                "persistentPlanBootstrapRequest.planCreatedAt");
        PersistentPlanBootstrapValues.required(
                checkpointCreatedAt,
                "persistentPlanBootstrapRequest.checkpointCreatedAt");
    }
}
