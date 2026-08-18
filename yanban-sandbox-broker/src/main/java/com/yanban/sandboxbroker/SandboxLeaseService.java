package com.yanban.sandboxbroker;

import java.time.*;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
class SandboxLeaseService {
    private final SandboxExecutionRepository executions;
    private final JdbcTemplate jdbc;
    private final BrokerProperties properties;
    SandboxLeaseService(SandboxExecutionRepository executions,JdbcTemplate jdbc,BrokerProperties properties){this.executions=executions;this.jdbc=jdbc;this.properties=properties;}

    @Transactional
    Optional<Lease> claim(String owner,Duration duration){
        jdbc.queryForObject("select slot_id from sandbox_concurrency_slot where slot_id=1 for update",Integer.class);
        Integer active=jdbc.queryForObject("select count(*) from sandbox_executions where status not in ('ACCEPTED','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','CLEANUP_FAILED') and lease_expires_at>current_timestamp",Integer.class);
        if(active!=null&&active>=properties.getMaxConcurrentRuns())return Optional.empty();
        var found=executions.lockClaimable(PageRequest.of(0,512));
        SandboxExecutionEntity entity=null;
        Map<Long,Integer> activeByUser=new HashMap<>();
        for(SandboxExecutionEntity candidate:found){
            Integer userActive=activeByUser.computeIfAbsent(candidate.userId(),user -> {
                Integer count=jdbc.queryForObject("select count(*) from sandbox_executions where user_id=? and status not in ('ACCEPTED','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','CLEANUP_FAILED') and lease_expires_at>current_timestamp",Integer.class,user);
                return count==null?0:count;
            });
            if(userActive==null||userActive<properties.getMaxConcurrentRunsPerUser()){entity=candidate;break;}
        }
        if(entity==null)return Optional.empty();
        LocalDateTime now=databaseNow(entity.executionId());
        String previous=entity.status();
        String token=UUID.randomUUID().toString().replace("-","");
        entity.claim(owner,token,now,now.plus(duration));
        executions.saveAndFlush(entity);
        boolean recovery=!"ACCEPTED".equals(previous);
        return Optional.of(new Lease(entity.executionId(),owner,token,entity.workerFence(),previous,recovery));
    }

    @Transactional
    SandboxExecutionEntity owned(Lease lease){
        SandboxExecutionEntity entity=executions.lockByExecutionId(lease.executionId()).orElseThrow();
        if(!entity.leaseMatches(lease.owner(),lease.token(),lease.fence(),databaseNow(lease.executionId())))
            throw new IllegalStateException("sandbox worker lease lost");
        return entity;
    }

    @Transactional
    void transition(Lease lease,String status,String checkpoint){SandboxExecutionEntity e=owned(lease);e.transition(status,checkpoint,databaseNow(lease.executionId()));executions.saveAndFlush(e);}
    @Transactional
    void stageReceipt(Lease lease,String digest,String receipt){SandboxExecutionEntity e=owned(lease);e.stageReceipt(digest,receipt,databaseNow(lease.executionId()));executions.saveAndFlush(e);}
    @Transactional
    boolean cancellationRequested(Lease lease){return owned(lease).cancelRequested();}
    @Transactional
    void heartbeat(Lease lease,Duration duration){SandboxExecutionEntity e=owned(lease);LocalDateTime now=databaseNow(lease.executionId());e.heartbeat(now,now.plus(duration));executions.saveAndFlush(e);}
    @Transactional
    void terminal(Lease lease,String status,String digest,String receipt,String error){SandboxExecutionEntity e=owned(lease);e.terminal(status,digest,receipt,error,databaseNow(lease.executionId()));executions.saveAndFlush(e);}
    @Transactional
    boolean terminalSuccessIfNotCancelled(Lease lease,String digest,String receipt){SandboxExecutionEntity e=owned(lease);if(e.cancelRequested())return false;e.terminal("SUCCEEDED",digest,receipt,null,databaseNow(lease.executionId()));executions.saveAndFlush(e);return true;}
    @Transactional
    Instant now(Lease lease){
        owned(lease);
        String product = jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<String>)
                        connection -> connection.getMetaData()
                                .getDatabaseProductName());
        boolean mysql = product != null && product.toLowerCase(
                java.util.Locale.ROOT).contains("mysql");
        java.sql.Timestamp current = jdbc.queryForObject(
                mysql ? "select utc_timestamp(6)" : "select current_timestamp",
                java.sql.Timestamp.class);
        if(current==null)throw new IllegalStateException(
                "broker database time unavailable");
        return current.toInstant();
    }
    private LocalDateTime databaseNow(String id){LocalDateTime now=executions.databaseNow(id);if(now==null)throw new IllegalStateException("broker database time unavailable");return now;}
    record Lease(String executionId,String owner,String token,long fence,String previousStatus,boolean recovery){}
}
