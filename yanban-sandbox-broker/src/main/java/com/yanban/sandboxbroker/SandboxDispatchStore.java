package com.yanban.sandboxbroker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix="yanban.broker",name="enabled",havingValue="true")
class SandboxDispatchStore {
    private final JdbcTemplate jdbc; private final SandboxExecutionRepository executions;
    SandboxDispatchStore(JdbcTemplate jdbc,SandboxExecutionRepository executions){this.jdbc=jdbc;this.executions=executions;}
    void insert(String executionId,String key,String digest,long fence,long userId,String sandboxName,String requestJson){
        try{jdbc.update("insert into sandbox_executions(execution_id,idempotency_key,request_digest,api_fence,user_id,status,worker_fence,sandbox_name,request_json,cancel_requested,created_at,updated_at) values(?,?,?,?,?, 'ACCEPTED',0,?,?,false,current_timestamp,current_timestamp)",executionId,key,digest,fence,userId,sandboxName,requestJson);}
        catch(DuplicateKeyException duplicate){/* only a declared unique-key collision is recoverable */}
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    SandboxExecutionEntity current(String key){return executions.lockByIdempotencyKey(key).orElseThrow();}
}
