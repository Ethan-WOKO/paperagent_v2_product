package com.yanban.api.agent.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.sandbox.contract.*;
import com.yanban.core.agent.AgentPlanEvent;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentPlanStepRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.yanban.core.agent.*;
import com.yanban.api.project.ProjectService;
import com.yanban.api.agent.*;
import com.yanban.api.skills.*;
import java.time.Duration;

@Component
@ConditionalOnProperty(prefix="yanban.sandbox",name="enabled",havingValue="true")
class SandboxOutboxDispatcher {
    private final SandboxOutboxRepository outbox; private final SandboxBrokerClient broker; private final ObjectMapper json;
    private final AgentPlanStepRepository steps; private final AgentPlanEventRepository events;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions; private final String owner=java.util.UUID.randomUUID().toString();
    private final SandboxReceiptProjectionService receiptProjection;
    private final SandboxExecutionProperties sandboxProperties;
    SandboxOutboxDispatcher(SandboxOutboxRepository outbox,SandboxBrokerClient broker,ObjectMapper json,
                            AgentPlanStepRepository steps,AgentPlanEventRepository events,JdbcTemplate jdbc,TransactionTemplate transactions,
                            SandboxReceiptProjectionService receiptProjection,SandboxExecutionProperties sandboxProperties){this.outbox=outbox;this.broker=broker;this.json=json;this.steps=steps;this.events=events;this.jdbc=jdbc;this.transactions=transactions;this.receiptProjection=receiptProjection;this.sandboxProperties=sandboxProperties;}
    @Scheduled(fixedDelayString="${yanban.sandbox.dispatch-delay-ms:1000}")
    void reconcile(){for(SandboxOutboxExecution candidate:outbox.findReconcileable()){Claim claim=claim(candidate.executionId());if(claim==null)continue;try{reconcile(claim);}catch(RuntimeException ignored){scheduleRetryAfterFailure(claim);}}}

    private Claim claim(String id){return transactions.execute(s->{SandboxOutboxExecution value=outbox.lockByExecutionId(id).orElse(null);if(value==null)return null;LocalDateTime now=dbNow();if(!value.reconcileable(now)||value.claimExpiresAt()!=null&&value.claimExpiresAt().isAfter(now))return null;String token=java.util.UUID.randomUUID().toString();value.claim(owner,token,now);outbox.saveAndFlush(value);return new Claim(id,token,value.claimFence());});}
    void reconcile(String executionId){Claim claim=claim(executionId);if(claim!=null)reconcile(claim);}
    private void reconcile(Claim claim){
        SandboxOutboxExecution value=outbox.findByExecutionId(claim.id()).orElseThrow();
        if("RECEIPT_PENDING_PROJECTION".equals(value.status())){receiptProjection.project(value.executionId());return;}
        if("API_CANCEL_REQUESTED".equals(value.status())){
            if(value.brokerExecutionId()==null){commit(claim,v->v.projectReceipt(null,null,SandboxExecutionStatus.CANCELLED.name(),dbNow()));return;}
            broker.cancel(value.brokerExecutionId(),value.leaseFence());commit(claim,v->v.dispatched(v.brokerExecutionId(),SandboxExecutionStatus.CANCEL_REQUESTED.name(),dbNow()));return;
        }
        if("PENDING".equals(value.status())||("RETRY".equals(value.status())&&"DISPATCH".equals(value.retryPhase()))){
            SandboxDispatch request=read(value.requestJson());SandboxDispatchResponse response=broker.dispatch(request);
            if(!request.requestDigest().equals(response.requestDigest())||request.fence()!=response.fence())throw new SandboxExecutionException(SandboxFailureCode.RECEIPT_CONFLICT,"broker dispatch identity mismatch");
            commit(claim,v->v.dispatched(response.executionId(),response.status().name(),dbNow()));return;
        }
        if("RETRY".equals(value.status())&&"CANCEL".equals(value.retryPhase())){broker.cancel(value.brokerExecutionId(),value.leaseFence());commit(claim,v->v.dispatched(v.brokerExecutionId(),SandboxExecutionStatus.CANCEL_REQUESTED.name(),dbNow()));return;}
        if(value.brokerExecutionId()==null)throw new IllegalStateException("broker execution identity missing");
        SandboxExecutionView view=broker.status(value.brokerExecutionId());
        if(!value.requestDigest().equals(view.requestDigest())||value.leaseFence()!=view.fence())throw new SandboxExecutionException(SandboxFailureCode.RECEIPT_CONFLICT,"broker status identity mismatch");
        if(isTerminal(view.status())){
            if(view.receipt()==null&&view.status()!=SandboxExecutionStatus.CLEANUP_FAILED)throw new SandboxExecutionException(SandboxFailureCode.PROVIDER_REJECTED,"terminal broker receipt missing");
            SandboxReceipt verified=view.receipt();if(verified!=null)validateReceipt(value,verified,view.status());
            String receipt=verified==null?null:write(verified);
            if(verified==null){commit(claim,v->v.projectReceipt(null,null,view.status().name(),dbNow()));return;}
            commit(claim,v->v.stageReceipt(sha256(receipt),receipt,dbNow()));
            receiptProjection.project(value.executionId());
        }else commit(claim,v->v.dispatched(view.executionId(),view.status().name(),dbNow()));
    }
    private void commit(Claim claim,java.util.function.Consumer<SandboxOutboxExecution> change){transactions.executeWithoutResult(s->{SandboxOutboxExecution value=outbox.lockByExecutionId(claim.id()).orElseThrow();if(!value.claimMatches(claim.token(),claim.fence(),dbNow()))throw new IllegalStateException("stale sandbox outbox claim");change.accept(value);outbox.saveAndFlush(value);});}
    private void scheduleRetry(Claim claim){commit(claim,value->{LocalDateTime now=dbNow();String phase="API_CANCEL_REQUESTED".equals(value.status())||"CANCEL".equals(value.retryPhase())?"CANCEL":value.retryPhase();value.retry("SANDBOX_UNAVAILABLE",now.plusSeconds(5),now);if("CANCEL".equals(phase))value.requestCancel(now);});}
    private void scheduleRetryAfterFailure(Claim claim){
        transactions.executeWithoutResult(s->{
            SandboxOutboxExecution value=outbox.lockByExecutionId(claim.id()).orElse(null);
            if(value==null)return;
            LocalDateTime now=dbNow();
            if("RECEIPT_PENDING_PROJECTION".equals(value.status())||"PROJECTION".equals(value.retryPhase())){
                value.deferProjection("SANDBOX_PROJECTION_TRANSIENT",now,5);outbox.saveAndFlush(value);return;
            }
            if(value.claimMatches(claim.token(),claim.fence(),now)){
                String phase="API_CANCEL_REQUESTED".equals(value.status())||"CANCEL".equals(value.retryPhase())?"CANCEL":value.retryPhase();
                value.retry("SANDBOX_UNAVAILABLE",now.plusSeconds(5),now);if("CANCEL".equals(phase))value.requestCancel(now);
                outbox.saveAndFlush(value);
            }
        });
    }
    private record Claim(String id,String token,long fence){}
    private LocalDateTime dbNow(){return jdbc.queryForObject("select current_timestamp",LocalDateTime.class);}
    private boolean isTerminal(SandboxExecutionStatus s){return s==SandboxExecutionStatus.SUCCEEDED||s==SandboxExecutionStatus.FAILED||s==SandboxExecutionStatus.CANCELLED||s==SandboxExecutionStatus.TIMED_OUT||s==SandboxExecutionStatus.CLEANUP_FAILED;}
    private void validateReceipt(SandboxOutboxExecution value,SandboxReceipt receipt,SandboxExecutionStatus status){
        if(!value.brokerExecutionId().equals(receipt.executionId())||!value.idempotencyKey().equals(receipt.idempotencyKey())
                ||!value.requestDigest().equals(receipt.requestDigest())||value.leaseFence()!=receipt.fence()
                ||value.userId()!=receipt.userId()||value.projectId()!=receipt.projectId()||value.sessionId()!=receipt.sessionId()
                ||value.planId()!=receipt.planId()||value.stepId()!=receipt.stepId()
                ||!value.projectVersion().equals(receipt.projectVersion())||!value.policyDigest().equals(receipt.policyDigest())
                ||!sandboxProperties.getProvider().equals(receipt.provider())
                ||receipt.status()!=status||receipt.startedAt()==null||receipt.finishedAt()==null||receipt.finishedAt().isBefore(receipt.startedAt())
                ||receipt.stdout()==null||receipt.stderr()==null||receipt.outputTruncated()
                ||(long)receipt.stdout().getBytes(StandardCharsets.UTF_8).length+receipt.stderr().getBytes(StandardCharsets.UTF_8).length>20L*1024*1024
                ||(status==SandboxExecutionStatus.SUCCEEDED&&(receipt.exitCode()==null||receipt.exitCode()!=0||receipt.errorCode()!=null))
                ||(status==SandboxExecutionStatus.FAILED&&receipt.errorCode()==null
                        &&(receipt.exitCode()==null||receipt.exitCode()==0))
                ||(status!=SandboxExecutionStatus.SUCCEEDED&&status!=SandboxExecutionStatus.FAILED
                        &&receipt.errorCode()==null))
            throw new SandboxExecutionException(SandboxFailureCode.RECEIPT_CONFLICT,"broker receipt failed server validation");
        long artifacts=0;if(receipt.artifacts().size()>256)throw new SandboxExecutionException(SandboxFailureCode.RECEIPT_CONFLICT,"too many artifacts");
        for(var artifact:receipt.artifacts().entrySet()){
            try{var path=java.nio.file.Path.of(artifact.getKey());if(path.isAbsolute()||!path.normalize().equals(path)||artifact.getKey().contains("\\")
                    ||java.util.stream.StreamSupport.stream(path.spliterator(),false).anyMatch(part->"..".equals(part.toString())||".".equals(part.toString()))
                    ||artifact.getValue()==null||artifact.getValue().sizeBytes()<0||artifact.getValue().sizeBytes()>20L*1024*1024
                    ||artifact.getValue().sha256()==null||!artifact.getValue().sha256().matches("[0-9a-f]{64}"))throw new IllegalArgumentException();}
            catch(RuntimeException ex){throw new SandboxExecutionException(SandboxFailureCode.RECEIPT_CONFLICT,"invalid artifact receipt");}
            artifacts=Math.addExact(artifacts,artifact.getValue().sizeBytes());if(artifacts>20L*1024*1024)throw new SandboxExecutionException(SandboxFailureCode.RECEIPT_CONFLICT,"artifact budget exceeded");
        }
    }
    private SandboxDispatch read(String value){try{return json.readValue(value,SandboxDispatch.class);}catch(Exception ex){throw new IllegalStateException("stored sandbox dispatch invalid",ex);}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
}
