package com.yanban.api.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentPlanStepRepository;
import com.yanban.sandbox.contract.SandboxErrorCode;
import com.yanban.sandbox.contract.SandboxExecutionStatus;
import com.yanban.sandbox.contract.SandboxReceipt;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SandboxReceiptValidationTest {
    @Test void rejectsIdentityReplayOversizeArtifactsAndImpossibleStatus(){
        SandboxOutboxExecution value=new SandboxOutboxExecution("api-e",1,2,3,4,5,7,"key","a".repeat(64),"b".repeat(64),"c".repeat(64),"{}");
        value.dispatched("broker-e","RUNNING",java.time.LocalDateTime.now());
        SandboxOutboxDispatcher dispatcher=new SandboxOutboxDispatcher(mock(SandboxOutboxRepository.class),mock(SandboxBrokerClient.class),new ObjectMapper(),mock(AgentPlanStepRepository.class),mock(AgentPlanEventRepository.class),mock(org.springframework.jdbc.core.JdbcTemplate.class),mock(org.springframework.transaction.support.TransactionTemplate.class),mock(SandboxReceiptProjectionService.class),new SandboxExecutionProperties());
        SandboxReceipt valid=receipt("broker-e",Map.of(),"",0,null);
        invoke(dispatcher,value,valid,SandboxExecutionStatus.SUCCEEDED);
        assertThatThrownBy(()->invoke(dispatcher,value,receipt("other",Map.of(),"",0,null),SandboxExecutionStatus.SUCCEEDED)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->invoke(dispatcher,value,receipt("broker-e",Map.of("../x",new SandboxReceipt.Artifact("d".repeat(64),1)),"",0,null),SandboxExecutionStatus.SUCCEEDED)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->invoke(dispatcher,value,receipt("broker-e",Map.of(),"x".repeat(20*1024*1024+1),0,null),SandboxExecutionStatus.SUCCEEDED)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->invoke(dispatcher,value,receipt("broker-e",Map.of(),"",1,SandboxErrorCode.PROVIDER_REJECTED),SandboxExecutionStatus.SUCCEEDED)).isInstanceOf(Exception.class);
    }
    private void invoke(SandboxOutboxDispatcher dispatcher,SandboxOutboxExecution value,SandboxReceipt receipt,SandboxExecutionStatus status){ReflectionTestUtils.invokeMethod(dispatcher,"validateReceipt",value,receipt,status);}
    private SandboxReceipt receipt(String id,Map<String,SandboxReceipt.Artifact> artifacts,String stdout,int exit,SandboxErrorCode error){Instant now=Instant.now();return new SandboxReceipt(id,"key","a".repeat(64),1,2,3,4,5,7,"b".repeat(64),"c".repeat(64),"docker-sbx",SandboxExecutionStatus.SUCCEEDED,exit,stdout,"",false,artifacts,now,now,error);}
}
