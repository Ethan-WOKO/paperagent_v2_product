package com.yanban.api.agent;

import com.yanban.api.security.JwtUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent")
public class PlanAgentController {

    private final PlanAgentService planAgentService;

    public PlanAgentController(PlanAgentService planAgentService) {
        this.planAgentService = planAgentService;
    }

    @PostMapping("/sessions/{sessionId}/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentPlanResponse createPlan(@AuthenticationPrincipal JwtUser currentUser,
                                        @PathVariable Long sessionId,
                                        @Valid @RequestBody CreateAgentPlanRequest request) {
        return planAgentService.createPlan(currentUser.id(), sessionId, request);
    }

    @GetMapping("/sessions/{sessionId}/plans")
    public List<AgentPlanResponse> listSessionPlans(@AuthenticationPrincipal JwtUser currentUser,
                                                    @PathVariable Long sessionId) {
        return planAgentService.listSessionPlans(currentUser.id(), sessionId);
    }

    @GetMapping("/plans/{planId}")
    public AgentPlanResponse getPlan(@AuthenticationPrincipal JwtUser currentUser,
                                     @PathVariable Long planId) {
        return planAgentService.getPlan(currentUser.id(), planId);
    }

    @PostMapping("/plans/{planId}/execute")
    public AgentPlanResponse executePlan(@AuthenticationPrincipal JwtUser currentUser,
                                         @PathVariable Long planId) {
        return planAgentService.executePlan(currentUser.id(), planId);
    }

    @PostMapping("/plans/{planId}/execute-async")
    public AgentPlanResponse executePlanAsync(@AuthenticationPrincipal JwtUser currentUser,
                                              @PathVariable Long planId) {
        return planAgentService.executePlanAsync(currentUser.id(), planId);
    }

    @PostMapping("/plans/{planId}/sandbox-confirm-and-queue")
    public AgentPlanResponse confirmAndQueueSandboxPlan(@AuthenticationPrincipal JwtUser currentUser,
                                                        @PathVariable Long planId,
                                                        @Valid @RequestBody ConfirmSandboxExecutionRequest request) {
        return planAgentService.confirmAndExecuteSandboxPlanAsync(currentUser.id(), planId, request);
    }

    @PostMapping("/plans/{planId}/retry")
    public AgentPlanResponse retryPlan(@AuthenticationPrincipal JwtUser currentUser,
                                       @PathVariable Long planId) {
        return planAgentService.retryPlan(currentUser.id(), planId);
    }

    @PostMapping("/plans/{planId}/cancel")
    public AgentPlanResponse cancelPlan(@AuthenticationPrincipal JwtUser currentUser,
                                        @PathVariable Long planId) {
        return planAgentService.cancelPlan(currentUser.id(), planId);
    }

    @GetMapping("/plans/{planId}/events")
    public List<AgentPlanEventResponse> listEvents(@AuthenticationPrincipal JwtUser currentUser,
                                                   @PathVariable Long planId) {
        return planAgentService.listEvents(currentUser.id(), planId);
    }
}
