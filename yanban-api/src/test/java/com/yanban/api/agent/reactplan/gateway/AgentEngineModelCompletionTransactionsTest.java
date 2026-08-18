package com.yanban.api.agent.reactplan.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentEngineModelCompletionTransactionsTest {
    private final AgentEngineModelCompletionRepository repository =
            mock(AgentEngineModelCompletionRepository.class);
    private final AgentEngineModelCompletionTransactions transactions =
            new AgentEngineModelCompletionTransactions(repository);

    @Test
    void successfulReplayKeepsOnePersistedModelResult() {
        AgentEngineModelCompletionEntity value = new AgentEngineModelCompletionEntity(
                "task." + "a".repeat(64), "model." + "b".repeat(64), "c".repeat(64));
        when(repository.lock("task." + "a".repeat(64), "model." + "b".repeat(64)))
                .thenReturn(Optional.of(value));

        transactions.succeed("task." + "a".repeat(64), "model." + "b".repeat(64),
                "{\"content\":\"ok\"}", 3, 2);
        transactions.succeed("task." + "a".repeat(64), "model." + "b".repeat(64),
                "{\"content\":\"ok\"}", 3, 2);

        assertThat(value.promptTokens()).isEqualTo(3);
        assertThat(value.completionTokens()).isEqualTo(2);
        assertThat(transactions.claim("task." + "a".repeat(64),
                "model." + "b".repeat(64), "c".repeat(64))).contains("{\"content\":\"ok\"}");
    }

    @Test
    void reusedRequestIdentityWithAnotherDigestIsRejected() {
        AgentEngineModelCompletionEntity value = new AgentEngineModelCompletionEntity(
                "task." + "a".repeat(64), "model." + "b".repeat(64), "c".repeat(64));
        when(repository.lock("task." + "a".repeat(64), "model." + "b".repeat(64)))
                .thenReturn(Optional.of(value));
        assertThatThrownBy(() -> transactions.claim("task." + "a".repeat(64),
                "model." + "b".repeat(64), "d".repeat(64)))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo("MODEL_REQUEST_DIGEST_CONFLICT"));
    }
}
