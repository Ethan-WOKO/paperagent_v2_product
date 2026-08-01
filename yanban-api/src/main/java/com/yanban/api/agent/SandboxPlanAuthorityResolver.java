package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanExecutionLease;
import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanRunLeaseService;
import com.yanban.core.agent.AgentPlanStep;
import com.yanban.core.agent.AgentPlanStepRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** Reloads the durable Plan authority at the instant a sandbox dispatch is attempted. */
@Service
public final class SandboxPlanAuthorityResolver {
    public static final String TOOL_NAME = "sandbox_execute";
    private final AgentPlanRunLeaseService leases;
    private final AgentPlanRepository plans;
    private final AgentPlanStepRepository steps;
    private final AgentPlanCheckpointService checkpoints;
    private final ObjectMapper json;

    public SandboxPlanAuthorityResolver(AgentPlanRunLeaseService leases, AgentPlanRepository plans,
                                        AgentPlanStepRepository steps, AgentPlanCheckpointService checkpoints,
                                        ObjectMapper json) {
        this.leases = leases; this.plans = plans; this.steps = steps; this.checkpoints = checkpoints; this.json=json;
    }

    public Resolution resolve(AgentPlanExecutionLease lease, long stepId, ResolvedToolPolicy currentPolicy,
                              AgentPlanCheckpointService.BudgetCeiling ceiling) {
        leases.assertOwned(lease); // row lock + database time + owner/token/fence/expiry + non-terminal status
        AgentPlan plan = plans.findByIdAndUserId(lease.planId(), lease.userId())
                .orElseThrow(() -> new IllegalStateException("sandbox Plan no longer exists"));
        AgentPlanStep step = steps.findById(stepId).orElseThrow(() -> new IllegalStateException("sandbox step does not exist"));
        if (!lease.planId().equals(step.getPlanId()) || !"RUNNING".equals(plan.getStatus())
                || !List.of("PENDING", "RUNNING").contains(step.getStatus()))
            throw new IllegalStateException("sandbox step is outside the active leased Plan");
        List<String> allowed = parseAllowedTools(step.getAllowedToolsJson());
        if (currentPolicy == null || !currentPolicy.allowedTools().contains(TOOL_NAME) || !allowed.contains(TOOL_NAME))
            throw new IllegalStateException("sandbox tool authority is absent or revoked");
        AgentPlanCheckpointService.Validation validation = checkpoints.initializeOrValidate(lease, currentPolicy, ceiling);
        int remaining = checkpoints.remainingToolCalls(lease, validation.budgetCeiling());
        if (remaining < 1) throw new IllegalStateException("sandbox execution budget is exhausted");
        return new Resolution(plan.getUserId(), validation.projectContext().projectId(), plan.getSessionId(), plan.getId(),
                step.getId(), validation.manifest().version(), lease, policyDigest(currentPolicy, validation), remaining);
    }

    private List<String> parseAllowedTools(String json) {
        try {
            var node=this.json.readTree(json);
            if(node==null||!node.isArray())throw new IllegalStateException("sandbox step tool policy is invalid");
            List<String> tools=new java.util.ArrayList<>();
            node.forEach(value->{if(!value.isTextual()||value.textValue().isBlank())throw new IllegalStateException("sandbox step tool policy is invalid");tools.add(value.textValue());});
            return List.copyOf(tools);
        } catch (IllegalStateException ex){throw ex;} catch(Exception ex){throw new IllegalStateException("sandbox step tool policy is invalid",ex);}
    }

    public static String policyDigest(ResolvedToolPolicy policy, AgentPlanCheckpointService.Validation validation) {
        if (policy == null || !policy.allowedTools().contains(TOOL_NAME))
            throw new IllegalStateException("sandbox tool authority is absent or revoked");
        // The receipt binds only the sandbox capability and the immutable durable budget ceiling.
        // Unrelated Project read tools may be resolved through different call paths and are not
        // authority for this execution. Current sandbox revocation is checked before this digest.
        String value = TOOL_NAME + "|" + validation.budgetCeiling();
        return AgentPlanCheckpointService.sha256(value);
    }

    public static final class Resolution {
        private final long userId,projectId,sessionId,planId,stepId; private final String projectVersion,policyDigest;
        private final AgentPlanExecutionLease lease; private final int remainingExecutions;
        private Resolution(long userId,long projectId,long sessionId,long planId,long stepId,String projectVersion,
                           AgentPlanExecutionLease lease,String policyDigest,int remainingExecutions){this.userId=userId;this.projectId=projectId;this.sessionId=sessionId;this.planId=planId;this.stepId=stepId;this.projectVersion=projectVersion;this.lease=lease;this.policyDigest=policyDigest;this.remainingExecutions=remainingExecutions;}
        public long userId(){return userId;} public long projectId(){return projectId;} public long sessionId(){return sessionId;}
        public long planId(){return planId;} public long stepId(){return stepId;} public String projectVersion(){return projectVersion;}
        public AgentPlanExecutionLease lease(){return lease;} public String policyDigest(){return policyDigest;} public int remainingExecutions(){return remainingExecutions;}
    }
}
