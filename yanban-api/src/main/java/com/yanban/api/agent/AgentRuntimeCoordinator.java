package com.yanban.api.agent;

import com.yanban.api.agent.worker.ControlledWorkerDispatchPlanner;
import com.yanban.core.agent.AgentRunIdentity;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The sole CHAT-to-runtime facade.  It makes the deterministic strategy decision only
 * after the model endpoint, budget and resolved tool policy have been supplied.
 */
@Service
public class AgentRuntimeCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeCoordinator.class);

    private final AgentRuntimeService runtimeService;
    private final AgentStrategySelector strategySelector;
    private final AgentTaskWorkspaceService workspaceService;
    private final ControlledWorkerDispatchPlanner controlledWorkerDispatchPlanner;

    public AgentRuntimeCoordinator(AgentRuntimeService runtimeService,
                                   AgentStrategySelector strategySelector) {
        this(runtimeService, strategySelector, null, null);
    }

    public AgentRuntimeCoordinator(AgentRuntimeService runtimeService,
                                   AgentStrategySelector strategySelector,
                                   AgentTaskWorkspaceService workspaceService) {
        this(runtimeService, strategySelector, workspaceService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRuntimeCoordinator(AgentRuntimeService runtimeService,
                                   AgentStrategySelector strategySelector,
                                   AgentTaskWorkspaceService workspaceService,
                                   ControlledWorkerDispatchPlanner controlledWorkerDispatchPlanner) {
        this.runtimeService = runtimeService;
        this.strategySelector = strategySelector;
        this.workspaceService = workspaceService;
        this.controlledWorkerDispatchPlanner = controlledWorkerDispatchPlanner;
    }

    public AgentCoordinationResult coordinate(AgentCoordinationRequest request) {
        AgentRuntimeRequest resolved = request.runtimeRequest();
        boolean explicitPlanRequest = request.explicitPlanRequest();
        boolean projectBoundCapability = request.capability() == AgentRequestCapability.PROJECT_READ
                || request.capability() == AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ
                || (request.capability() == AgentRequestCapability.LEGACY_PLAN_REFLECT
                && resolved.projectContext() != null);
        if (projectBoundCapability
                && (resolved.projectContext() == null
                || !resolved.userId().equals(resolved.projectContext().userId()))) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.DIRECT, false, false, null, "missing_trusted_project_context");
            return coordination(resolved, decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Project execution requires authenticated project context."));
        }
        if (request.capability() != AgentRequestCapability.PROJECT_READ
                && request.capability() != AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ
                && request.capability() != AgentRequestCapability.LEGACY_PLAN_REFLECT
                && resolved.projectContext() != null) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.DIRECT, false, false, null, "project_context_capability_mismatch");
            return coordination(resolved, decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Project context is invalid for this request capability."));
        }
        if (request.capability() == AgentRequestCapability.LEGACY_PLAN_REFLECT
                && !strategySelector.isPlanReflectIntent(resolved.userMessage())) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.DIRECT, true, false, null, "invalid_legacy_plan_reflect_request");
            return coordination(resolved, decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Legacy plan reflection requires '/plan reflect <goal>'."));
        }
        if ((request.capability() == AgentRequestCapability.TRUSTED_PLAN_API
                || request.capability() == AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ)
                && request.planOperation() == PlanApiOperation.EXECUTE && request.planId() == null) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.PLAN_EXECUTE, true, false, null, "missing_trusted_plan_id");
            return coordination(resolved, decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Trusted Plan API execution requires a persisted plan id."));
        }
        if ((request.capability() == AgentRequestCapability.TRUSTED_PLAN_API
                || request.capability() == AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ)
                && request.planOperation() == PlanApiOperation.CREATE && request.planId() != null) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.PLAN_EXECUTE, true, false, null, "unexpected_plan_id_for_create");
            return coordination(resolved, decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Trusted Plan API creation must not supply a plan id."));
        }
        if ((request.capability() == AgentRequestCapability.TRUSTED_PLAN_API
                || request.capability() == AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ)
                && resolved.planId() != null && !resolved.planId().equals(request.planId())) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.PLAN_EXECUTE, true, false, null, "conflicting_trusted_plan_id");
            return coordination(resolved, decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Trusted Plan API request contains conflicting plan ids."));
        }
        if (!StringUtils.hasText(resolved.provider()) || !StringUtils.hasText(resolved.model())
                || !StringUtils.hasText(resolved.traceId())
                || resolved.toolPolicy() == null || resolved.maxSteps() <= 0) {
            AgentCoordinationDecision decision = new AgentCoordinationDecision(
                    AgentStrategy.DIRECT, explicitPlanRequest, false, null, "unresolved_runtime_prerequisite");
            return coordination(resolved, decision, failed(
                    decision, AgentStopReason.POLICY_REJECTED, "Runtime request is missing a resolved endpoint, budget, or tool policy."));
        }

        AgentStrategySelection selection = strategySelector.decide(request);
        AgentStrategy selected = selection.selectedStrategy();
        if (request.capability() == AgentRequestCapability.PROJECT_READ
                && selected != AgentStrategy.PLAN_EXECUTE
                && selection.orchestration().reasonCodes()
                .contains(AgentStrategyReasonCode.PROJECT_TOOLS_REQUIRE_PLAN)) {
            AgentCoordinationDecision unavailable = new AgentCoordinationDecision(
                    selected, false, true, AgentStrategy.PLAN_EXECUTE,
                    "project_plan_required_but_unavailable", selection);
            return coordination(resolved, unavailable, failed(unavailable, AgentStopReason.POLICY_REJECTED,
                    "PROJECT_PLAN_UNAVAILABLE: Project tools require PLAN_EXECUTE and current policy/budget cannot execute it."));
        }
        boolean explicitPlanSelection = selection.explicitOverride()
                && (selected == AgentStrategy.PLAN_EXECUTE
                || selected == AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION);
        AgentCoordinationDecision decision = new AgentCoordinationDecision(
                selected, explicitPlanRequest || explicitPlanSelection, selection.degraded(),
                selection.degradedFrom(), selection.reason(), selection);
        log.info("Agent strategy selected traceId={} capability={} origin={} requested={} selected={} candidates={} "
                        + "degraded={} degradedFrom={} signals={} reasonCodes={} materialCoverage={}",
                resolved.traceId(), request.capability(), selection.orchestration().selectionOrigin(),
                selection.requestedStrategy(), selected,
                selection.serverCandidates(), selection.degraded(), selection.degradedFrom(),
                selection.orchestration().signals(), selection.orchestration().reasonCodes(),
                selection.orchestration().materialRequirements());
        try {
            // planId is canonical on the coordination envelope and is copied only here,
            // after the trusted capability and conflict checks have completed.
            AgentRuntimeRequest executable = resolved.withStrategy(selected)
                    .withPlanId(request.planId())
                    .withOrchestrationRequirements(selection.orchestration());
            if (selected == AgentStrategy.DIRECT && request.capability() == AgentRequestCapability.PROJECT_READ) {
                executable = executable.withoutToolAuthority("direct_strategy_deny_all");
            }
            if (controlledWorkerDispatchPlanner != null) {
                executable = controlledWorkerDispatchPlanner.plan(executable, request.capability())
                        .map(executable::withControlledWorkerDispatch).orElse(executable);
            }
            AgentRuntimeResult result = runtimeService.run(executable);
            AgentStopReason stopReason = result.stopReason() == null ? stopReason(result) : result.stopReason();
            boolean degraded = decision.degraded() || result.degraded();
            AgentStrategy degradedFrom = result.degradedFrom() == null
                    ? decision.degradedFrom() : result.degradedFrom();
            return coordination(resolved, decision, result.withCoordination(
                    selected, stopReason, result.outcome() == null ? outcome(result) : result.outcome(),
                    degraded, degradedFrom));
        } catch (NoRuntimeAdapterException ex) {
            return coordination(resolved, decision, failed(decision, AgentStopReason.NO_RUNTIME_ADAPTER, ex.getMessage()));
        } catch (RuntimeException ex) {
            return coordination(resolved, decision, failed(decision, AgentStopReason.RUNTIME_EXCEPTION,
                    StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    private AgentCoordinationResult coordination(AgentRuntimeRequest request,
                                                   AgentCoordinationDecision decision,
                                                   AgentRuntimeResult result) {
        Long projectId = request.projectContext() == null ? null : request.projectContext().projectId();
        boolean persistedPlan = result.planId() != null;
        boolean hasTrace = StringUtils.hasText(request.traceId());
        AgentRunIdentity identity = new AgentRunIdentity(
                persistedPlan ? "AGENT_PLAN" : hasTrace ? "RUNTIME_TRACE" : "RUNTIME_INVOCATION",
                persistedPlan ? result.planId().toString()
                        : hasTrace ? request.traceId() : UUID.randomUUID().toString(),
                request.userId(),
                request.sessionId(),
                projectId);
        AgentRunProjection projection = AgentRunProjection.fromRuntime(result, identity);
        return new AgentCoordinationResult(decision, result, projection,
                workspaceService == null ? null : workspaceService.capture(request, result, projection));
    }

    private AgentStopReason stopReason(AgentRuntimeResult result) {
        return switch (result.runtimeStopSignal()) {
            case TOOL_CALL_BUDGET_EXHAUSTED -> AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED;
            case MAX_STEPS_BUDGET_EXHAUSTED -> AgentStopReason.MAX_STEPS_BUDGET_EXHAUSTED;
            case MODEL_OUTPUT_TRUNCATED -> AgentStopReason.MODEL_OUTPUT_TRUNCATED;
            case NONE -> result.success() ? AgentStopReason.COMPLETED : AgentStopReason.RUNTIME_FAILED;
        };
    }

    private String outcome(AgentRuntimeResult result) {
        return result.runtimeStopSignal() == AgentRuntimeStopSignal.NONE
                ? (result.success() ? "SUCCESS" : "FAILURE")
                : "BUDGET_STOP";
    }

    private AgentRuntimeResult failed(AgentCoordinationDecision decision, AgentStopReason stopReason, String message) {
        return new AgentRuntimeResult(false, null, List.of(), 0, message, List.of(), List.of(message), null, null, null)
                .withCoordination(decision.selectedStrategy(), stopReason, "FAILURE", decision.degraded(), decision.degradedFrom());
    }
}
