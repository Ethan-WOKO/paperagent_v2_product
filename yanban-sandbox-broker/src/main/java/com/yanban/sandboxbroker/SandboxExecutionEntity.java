package com.yanban.sandboxbroker;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "sandbox_executions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sandbox_execution_id", columnNames = "execution_id"),
        @UniqueConstraint(name = "uk_sandbox_execution_idempotency", columnNames = "idempotency_key")})
class SandboxExecutionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="execution_id",nullable=false,length=64) private String executionId;
    @Column(name="idempotency_key",nullable=false,length=128) private String idempotencyKey;
    @Column(name="request_digest",nullable=false,length=64) private String requestDigest;
    @Column(name="api_fence",nullable=false) private long apiFence;
    @Column(name="user_id") private Long userId;
    @Column(nullable=false,length=32) private String status;
    @Column(name="worker_owner",length=128) private String workerOwner;
    @Column(name="worker_token",length=32) private String workerToken;
    @Column(name="worker_fence",nullable=false) private long workerFence;
    @Column(name="lease_expires_at") private LocalDateTime leaseExpiresAt;
    @Column(name="sandbox_name",nullable=false,length=128) private String sandboxName;
    @Column(name="request_json",columnDefinition="LONGTEXT") private String requestJson;
    @Column(name="checkpoint_json",columnDefinition="LONGTEXT") private String checkpointJson;
    @Column(name="receipt_digest",length=64) private String receiptDigest;
    @Column(name="receipt_json",columnDefinition="LONGTEXT") private String receiptJson;
    @Column(name="error_code",length=64) private String errorCode;
    @Column(name="cancel_requested",nullable=false) private boolean cancelRequested;
    @Column(name="created_at",nullable=false) private LocalDateTime createdAt;
    @Column(name="updated_at",nullable=false) private LocalDateTime updatedAt;
    @Column(name="started_at") private LocalDateTime startedAt;
    @Column(name="finished_at") private LocalDateTime finishedAt;
    protected SandboxExecutionEntity() {}
    SandboxExecutionEntity(String executionId,String key,String digest,long fence,long userId,String name,String requestJson,LocalDateTime now){this.executionId=executionId;idempotencyKey=key;requestDigest=digest;apiFence=fence;this.userId=userId;sandboxName=name;this.requestJson=requestJson;status="ACCEPTED";createdAt=updatedAt=now;}
    String executionId(){return executionId;} String idempotencyKey(){return idempotencyKey;} String requestDigest(){return requestDigest;} long apiFence(){return apiFence;} long userId(){return userId==null?0L:userId;} String status(){return status;} String requestJson(){return requestJson;} String sandboxName(){return sandboxName;} long workerFence(){return workerFence;} String workerToken(){return workerToken;} boolean cancelRequested(){return cancelRequested;} String errorCode(){return errorCode;}
    void requestCancel(long fence,LocalDateTime now){if(fence!=apiFence)throw new IllegalStateException("stale API fence");if(java.util.Set.of("SUCCEEDED","FAILED","CANCELLED","TIMED_OUT","CLEANUP_FAILED").contains(status))return;cancelRequested=true;status="CANCEL_REQUESTED";updatedAt=now;}
    void claim(String owner,String token,LocalDateTime now,LocalDateTime until){workerOwner=owner;workerToken=token;workerFence++;leaseExpiresAt=until;status="CLAIMED";if(startedAt==null)startedAt=now;updatedAt=now;}
    boolean leaseMatches(String owner,String token,long fence,LocalDateTime now){return Objects.equals(workerOwner,owner)&&Objects.equals(workerToken,token)&&workerFence==fence&&leaseExpiresAt!=null&&leaseExpiresAt.isAfter(now);}
    void heartbeat(LocalDateTime now,LocalDateTime until){leaseExpiresAt=until;updatedAt=now;}
    void transition(String next,String checkpoint,LocalDateTime now){status=next;checkpointJson=checkpoint;updatedAt=now;}
    void stageReceipt(String digest,String receipt,LocalDateTime now){if(receiptDigest!=null&&(!receiptDigest.equals(digest)||!Objects.equals(receiptJson,receipt)))throw new IllegalStateException("receipt is immutable");receiptDigest=digest;receiptJson=receipt;updatedAt=now;}
    void terminal(String terminal,String digest,String receipt,String error,LocalDateTime now){status=terminal;receiptDigest=digest;receiptJson=receipt;errorCode=error;finishedAt=now;workerOwner=null;workerToken=null;leaseExpiresAt=null;requestJson=null;checkpointJson=null;updatedAt=finishedAt;}
    String checkpointJson(){return checkpointJson;} String receiptJson(){return receiptJson;} String receiptDigest(){return receiptDigest;}
}
