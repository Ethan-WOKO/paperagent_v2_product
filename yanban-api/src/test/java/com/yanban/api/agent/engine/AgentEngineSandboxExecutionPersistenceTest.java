package com.yanban.api.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "yanban.agent.engine.gateway.enabled=true"
})
@Import(AgentEngineSandboxExecutionTransactions.class)
class AgentEngineSandboxExecutionPersistenceTest {
    private static final String TASK = "task." + "1".repeat(64);
    private static final String CALL = "call.abcdefghijklmnop";

    @Autowired
    AgentEngineSandboxExecutionTransactions transactions;
    @Autowired
    EntityManager entities;

    @Test
    void executionMappingAndFormalReceiptSurvivePersistenceReload() {
        AgentEngineSandboxExecutionEntity created = transactions.create(
                new AgentEngineSandboxExecutionEntity(
                        TASK, CALL, "2".repeat(64), "3".repeat(64),
                        "execution." + "4".repeat(64), "{}", LocalDateTime.now()));
        transactions.dispatched(TASK, CALL, "broker-1", "RUNNING");
        transactions.terminal(TASK, CALL, "SUCCEEDED",
                "receipt." + "5".repeat(64), "{\"status\":\"SUCCEEDED\"}");

        entities.clear();
        AgentEngineSandboxExecutionEntity recovered = transactions.find(TASK, CALL).orElseThrow();
        assertThat(recovered.executionRef()).isEqualTo(created.executionRef());
        assertThat(recovered.brokerExecutionRef()).isEqualTo("broker-1");
        assertThat(recovered.state()).isEqualTo("SUCCEEDED");
        assertThat(recovered.receiptRef()).isEqualTo("receipt." + "5".repeat(64));
        assertThat(transactions.findReceipt(TASK, recovered.receiptRef())).isPresent();
    }
}
