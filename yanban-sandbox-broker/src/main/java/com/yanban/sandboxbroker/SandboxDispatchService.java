package com.yanban.sandboxbroker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.sandbox.contract.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="yanban.broker",name="enabled",havingValue="true")
class SandboxDispatchService {
    private final SandboxExecutionRepository executions;
    private final ObjectMapper json;
    private final SandboxProcessRegistry processes;
    private final SandboxDispatchStore store;
    SandboxDispatchService(SandboxExecutionRepository executions,ObjectMapper json,SandboxProcessRegistry processes,SandboxDispatchStore store){this.executions=executions;this.json=json;this.processes=processes;this.store=store;}

    SandboxDispatchResponse dispatch(SandboxDispatch request){
        validate(request);
        String executionId=UUID.randomUUID().toString();
        String sandboxName="yb-"+executionId.replace("-","");
        store.insert(executionId,request.idempotencyKey(),request.requestDigest(),request.fence(),request.userId(),sandboxName,write(request));
        return response(store.current(request.idempotencyKey()),request.requestDigest());
    }

    @Transactional(readOnly=true)
    SandboxExecutionView status(String executionId){
        SandboxExecutionEntity entity=executions.findByExecutionId(executionId)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        try{SandboxExecutionStatus status=SandboxExecutionStatus.valueOf(entity.status());boolean terminal=java.util.Set.of(SandboxExecutionStatus.SUCCEEDED,SandboxExecutionStatus.FAILED,SandboxExecutionStatus.CANCELLED,SandboxExecutionStatus.TIMED_OUT).contains(status);
            return new SandboxExecutionView(entity.executionId(),entity.idempotencyKey(),entity.requestDigest(),entity.apiFence(),
                status,!terminal||entity.receiptJson()==null?null:json.readValue(entity.receiptJson(),SandboxReceipt.class),
                entity.errorCode()==null?null:SandboxErrorCode.valueOf(entity.errorCode()));
        }catch(Exception ex){throw new IllegalStateException("stored sandbox receipt invalid",ex);}
    }

    @Transactional
    void cancel(String executionId,long fence){
        SandboxExecutionEntity entity=executions.lockByExecutionId(executionId)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        try{entity.requestCancel(fence,executions.databaseNow(executionId));}catch(IllegalStateException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"stale fence");}
        executions.saveAndFlush(entity);
        processes.cancel(executionId);
    }

    private SandboxDispatchResponse response(SandboxExecutionEntity entity,String digest){
        if(!entity.requestDigest().equals(digest))throw new ResponseStatusException(HttpStatus.CONFLICT,"idempotency digest conflict");
        return new SandboxDispatchResponse(entity.executionId(),entity.idempotencyKey(),entity.requestDigest(),
                entity.apiFence(),SandboxExecutionStatus.valueOf(entity.status()));
    }
    private void validate(SandboxDispatch r){
        try{if(r!=null)SandboxCommandProfiles.requireAllowed(r.argv());}catch(IllegalArgumentException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid command profile");}
        if(r==null||r.idempotencyKey()==null||!r.idempotencyKey().matches("[A-Za-z0-9_.:-]{1,128}")
                ||r.requestDigest()==null||!r.requestDigest().matches("[0-9a-f]{64}")
                ||r.projectVersion()==null||!r.projectVersion().matches("[0-9a-f]{64}")
                ||r.policyDigest()==null||!r.policyDigest().matches("[0-9a-f]{64}")
                ||r.userId()<1||r.projectId()<1||r.sessionId()<1||r.planId()<1||r.stepId()<1||r.fence()<1
                ||!SandboxCanonicalDigest.compute(r).equals(r.requestDigest())||r.networkEnabled()
                ||r.cpus()<1||r.cpus()>2||r.memoryBytes()<1||r.memoryBytes()>4294967296L
                ||r.timeoutMillis()<1||r.timeoutMillis()>900000||r.maxOutputBytes()<1||r.maxOutputBytes()>20971520L
                ||r.files().size()>256||r.argv().isEmpty()||r.argv().size()>64)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid sandbox dispatch");
        long total=0;
        for(var file:r.files().entrySet()){
            if(file.getKey()==null||file.getKey().isBlank()||file.getValue()==null||file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>5*1024*1024)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid sandbox file");
            try{var path=java.nio.file.Path.of(file.getKey());if(path.isAbsolute()||!path.normalize().equals(path)||file.getKey().contains("\\")||java.util.stream.StreamSupport.stream(path.spliterator(),false).anyMatch(part->"..".equals(part.toString())||".".equals(part.toString())))throw new IllegalArgumentException();}
            catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid sandbox path");}
            total=Math.addExact(total,file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            if(total>20L*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"sandbox files too large");
        }
    }
    private String write(SandboxDispatch request){try{return json.writeValueAsString(request);}catch(Exception ex){throw new IllegalStateException("dispatch serialization failed",ex);}}
}
