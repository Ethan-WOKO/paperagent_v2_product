package com.yanban.api.agent.v2.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentTurnRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class V2TurnIntakeTransactionsTest {
    private final V2TurnIntakeJpaRepository intakes =
            mock(V2TurnIntakeJpaRepository.class);
    private final V2TurnIntakeTransactions transactions =
            new V2TurnIntakeTransactions(
                    intakes,
                    mock(AgentMessageRepository.class),
                    mock(AgentTurnRepository.class),
                    mock(EntityManager.class),
                    mock(PlatformTransactionManager.class));

    @Test
    void exactExistingRequestReplaysAndChangedPayloadConflicts() {
        String digest = "a".repeat(64);
        V2TurnIntakeEntity existing = new V2TurnIntakeEntity(
                7L, 9L, "request-1", digest, "question",
                false, null, null, 11L, 12L, Instant.EPOCH);
        when(intakes.findByUserIdAndSessionIdAndClientRequestId(
                7L, 9L, "request-1"))
                .thenReturn(Optional.of(existing));

        assertThat(transactions.open(
                7L, 9L, "request-1", digest, "question",
                false, null, null)).isSameAs(existing);
        assertThatThrownBy(() -> transactions.open(
                7L, 9L, "request-1", "b".repeat(64), "changed",
                false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "clientRequestId was already used for another payload");
    }
}
