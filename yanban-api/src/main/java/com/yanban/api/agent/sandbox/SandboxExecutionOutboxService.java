package com.yanban.api.agent.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.*;
import com.yanban.core.agent.AgentPlanExecutionLease;
import com.yanban.core.agent.AgentPlanEvent;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.sandbox.contract.SandboxDispatch;
import java.util.*;
import java.time.LocalDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Atomic yanban_agent authority, budget reservation and durable dispatch-intent boundary. */
@Service
@ConditionalOnProperty(prefix="yanban.sandbox",name="enabled",havingValue="true")
public class SandboxExecutionOutboxService {
    private final SandboxPlanAuthorityResolver authority;
    private final GovernedSandboxExecutionService governance;
    private final SandboxOutboxRepository outbox;
    private final ObjectMapper json;
    private final AgentPlanEventRepository events;
    private final JdbcTemplate jdbc;
    public SandboxExecutionOutboxService(SandboxPlanAuthorityResolver authority,GovernedSandboxExecutionService governance,
                                         SandboxOutboxRepository outbox,ObjectMapper json,AgentPlanEventRepository events,JdbcTemplate jdbc){this.authority=authority;this.governance=governance;this.outbox=outbox;this.json=json;this.events=events;this.jdbc=jdbc;}

    @Transactional
    public Claim claim(AgentPlanExecutionLease lease,long stepId,ResolvedToolPolicy currentPolicy,
                       AgentPlanCheckpointService.BudgetCeiling ceiling,String idempotencyKey,
                       Set<String> relativePaths,List<String> argv){
        return claim(lease, stepId, currentPolicy, ceiling, idempotencyKey, relativePaths, argv, null);
    }

    @Transactional
    public Claim claim(AgentPlanExecutionLease lease,long stepId,ResolvedToolPolicy currentPolicy,
                       AgentPlanCheckpointService.BudgetCeiling ceiling,String idempotencyKey,
                       Set<String> relativePaths,List<String> argv,CandidateArtifactResponse candidate){
        SandboxPlanAuthorityResolver.Resolution resolved=authority.resolve(lease,stepId,currentPolicy,ceiling);
        SandboxDispatch dispatch=governance.prepare(resolved,
                new GovernedSandboxExecutionService.Request(idempotencyKey,relativePaths,argv),candidate);
        String executionId=UUID.randomUUID().toString();
        try{
            jdbc.update("insert into sandbox_execution_outbox(execution_id,plan_id,step_id,user_id,session_id,project_id,lease_fence,idempotency_key,request_digest,project_version,policy_digest,request_json,status,dispatch_attempts,created_at,updated_at,claim_fence,retry_phase) values(?,?,?,?,?,?,?,?,?,?,?,?, 'PENDING',0,current_timestamp,current_timestamp,0,'DISPATCH')",
                    executionId,dispatch.planId(),dispatch.stepId(),dispatch.userId(),dispatch.sessionId(),dispatch.projectId(),dispatch.fence(),dispatch.idempotencyKey(),dispatch.requestDigest(),dispatch.projectVersion(),dispatch.policyDigest(),write(dispatch));
            SandboxOutboxExecution created=outbox.lockByPlanIdAndIdempotencyKey(lease.planId(),idempotencyKey).orElseThrow();Claim claimed=claim(created);
            String eventKey="sandbox-budget:"+executionId;
            events.saveAndFlush(new AgentPlanEvent(dispatch.planId(),dispatch.stepId(),"step_tool_observation",write(java.util.Map.of(
                    "toolName",SandboxPlanAuthorityResolver.TOOL_NAME,"budgetConsumed",true,"requestDigest",dispatch.requestDigest())),eventKey));
            return claimed;
        }
        catch(org.springframework.dao.DuplicateKeyException race){
            SandboxOutboxExecution winner=outbox.lockByPlanIdAndIdempotencyKey(lease.planId(),idempotencyKey)
                    .orElseThrow(()->race);return same(winner,dispatch);
        }
    }

    private Claim same(SandboxOutboxExecution stored,SandboxDispatch request){
        if(!stored.requestDigest().equals(request.requestDigest()))throw new SandboxExecutionException(
                SandboxFailureCode.RECEIPT_CONFLICT,"idempotency key is bound to a different sandbox request");
        return claim(stored);
    }
    private Claim claim(SandboxOutboxExecution value){return new Claim(value.executionId(),value.requestDigest(),value.status());}
    private String write(SandboxDispatch dispatch){try{return json.writeValueAsString(dispatch);}catch(Exception ex){throw new IllegalStateException("sandbox dispatch serialization failed",ex);}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("sandbox event serialization failed",ex);}}
    @Transactional(readOnly = true)
    public boolean isAwaiting(long planId, long stepId) {
        return outbox.findByPlanIdAndStepId(planId, stepId)
                .map(value -> !java.util.Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "CLEANUP_FAILED").contains(value.status()))
                .orElse(false);
    }
    @Transactional
    public void requestCancellation(long planId, long userId) {
        LocalDateTime now=dbNow();
        for (SandboxOutboxExecution value : outbox.lockByPlanId(planId)) if (value.userId() == userId) {
            if(value.brokerExecutionId()==null)value.cancelBeforeDispatch(now);else value.requestCancel(now);
            outbox.save(value);
        }
    }
    private LocalDateTime dbNow(){return jdbc.queryForObject("select current_timestamp",LocalDateTime.class);}
    public record Claim(String executionId,String requestDigest,String status){}
}
