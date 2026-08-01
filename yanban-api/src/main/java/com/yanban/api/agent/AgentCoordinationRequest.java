package com.yanban.api.agent;

import java.util.Objects;

/**
 * A fully resolved request entering the Runtime Coordinator.  Strategy is deliberately
 * selected by the coordinator, rather than being supplied by a CHAT caller.
 */
public record AgentCoordinationRequest(AgentRuntimeRequest runtimeRequest,
                                       AgentRequestCapability capability,
                                       Long planId,
                                       PlanApiOperation planOperation) {
    public AgentCoordinationRequest {
        Objects.requireNonNull(runtimeRequest, "runtimeRequest must not be null");
        capability = capability == null ? AgentRequestCapability.CHAT : capability;
        planOperation = planOperation == null ? PlanApiOperation.EXECUTE : planOperation;
    }

    /** Source-compatible bridge for the MVP-2A request shape. */
    public AgentCoordinationRequest(AgentRuntimeRequest runtimeRequest, boolean explicitPlanRequest) {
        this(runtimeRequest, explicitPlanRequest
                ? AgentRequestCapability.LEGACY_PLAN_REFLECT : AgentRequestCapability.CHAT, null, null);
    }

    /** Source-compatible bridge for callers that do not need to name the operation. */
    public AgentCoordinationRequest(AgentRuntimeRequest runtimeRequest,
                                    AgentRequestCapability capability,
                                    Long planId) {
        this(runtimeRequest, capability, planId,
                capability == AgentRequestCapability.TRUSTED_PLAN_API ? PlanApiOperation.EXECUTE : null);
    }

    public static AgentCoordinationRequest chat(AgentRuntimeRequest runtimeRequest) {
        return new AgentCoordinationRequest(runtimeRequest, AgentRequestCapability.CHAT, null, null);
    }

    public static AgentCoordinationRequest projectRead(AgentRuntimeRequest runtimeRequest) {
        return new AgentCoordinationRequest(runtimeRequest, AgentRequestCapability.PROJECT_READ, null, null);
    }

    public static AgentCoordinationRequest trustedPlanApi(AgentRuntimeRequest runtimeRequest, Long planId) {
        return new AgentCoordinationRequest(runtimeRequest, AgentRequestCapability.TRUSTED_PLAN_API,
                planId, PlanApiOperation.EXECUTE);
    }

    public static AgentCoordinationRequest trustedPlanCreate(AgentRuntimeRequest runtimeRequest) {
        return new AgentCoordinationRequest(runtimeRequest, AgentRequestCapability.TRUSTED_PLAN_API,
                null, PlanApiOperation.CREATE);
    }

    public static AgentCoordinationRequest trustedProjectPlanCreate(AgentRuntimeRequest runtimeRequest) {
        return new AgentCoordinationRequest(runtimeRequest, AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ, null, PlanApiOperation.CREATE);
    }

    public static AgentCoordinationRequest trustedProjectPlan(AgentRuntimeRequest runtimeRequest, Long planId) {
        return new AgentCoordinationRequest(runtimeRequest, AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ, planId, PlanApiOperation.EXECUTE);
    }

    public static AgentCoordinationRequest legacyPlanReflect(AgentRuntimeRequest runtimeRequest) {
        return new AgentCoordinationRequest(runtimeRequest, AgentRequestCapability.LEGACY_PLAN_REFLECT, null, null);
    }

    public boolean explicitPlanRequest() {
        return capability == AgentRequestCapability.TRUSTED_PLAN_API
                || capability == AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ
                || capability == AgentRequestCapability.LEGACY_PLAN_REFLECT;
    }
}
