package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(ReactPlanTurnIntakeTransactions.class)
class ReactPlanTurnIntakePersistenceTest {
    @Autowired
    private ReactPlanTurnIntakeTransactions transactions;

    @Test
    void createsAProxiedAtomicMessageTurnAndIntake() {
        assertThat(AopUtils.isAopProxy(transactions)).isTrue();

        ReactPlanTurnIntakeEntity created = transactions.create(
                7L, 11L, "request.0123456789abcdef", "a".repeat(64),
                "Compile Sort.java");

        assertThat(created.turnId()).isPositive();
        assertThat(created.taskId()).isEqualTo(
                ReactPlanRuntimeService.taskId(7L, created.turnId()));
        assertThat(transactions.find(7L, 11L, "request.0123456789abcdef"))
                .hasValueSatisfying(replayed -> {
                    assertThat(replayed.turnId()).isEqualTo(created.turnId());
                    assertThat(replayed.taskId()).isEqualTo(created.taskId());
                });
    }
}
