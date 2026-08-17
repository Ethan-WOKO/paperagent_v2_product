package com.yanban.api.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.core.agent.AgentRunIdentity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentEngineTaskGrantServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");
    private static final String TASK = "task." + "1".repeat(64);
    private static final String DIGEST = "2".repeat(64);
    private static final String VERSION = "3".repeat(64);

    @Test
    void signedGrantBindsAllAuthorityAndExpiresWithoutPersistence() {
        EngineGatewayProperties properties = properties();
        AgentEngineTaskGrantService service = service(properties, NOW);

        EngineTaskGrant grant = service.issue(TASK, DIGEST, 11, 12);
        EngineTaskAuthority verified = service.verify("Bearer " + grant.value(), TASK, true);

        assertThat(verified).isEqualTo(new EngineTaskAuthority(
                TASK, DIGEST, 11, 12, 13, 14, VERSION,
                true, true, NOW.plus(Duration.ofMinutes(10))));
        assertThat(grant.expiresAt()).isEqualTo(verified.expiresAt());

        AgentEngineTaskGrantService expired = service(properties, NOW.plus(Duration.ofMinutes(11)));
        assertThatThrownBy(() -> expired.verify("Bearer " + grant.value(), TASK, false))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TASK_GRANT_EXPIRED"));
    }

    @Test
    void rejectsTamperedAndWrongTaskGrantsBeforeAuthorityUse() {
        AgentEngineTaskGrantService service = service(properties(), NOW);
        EngineTaskGrant grant = service.issue(TASK, DIGEST, 11, 12);
        String tampered = grant.value().substring(0, grant.value().length() - 1) + "A";

        assertThatThrownBy(() -> service.verify("Bearer " + tampered, TASK, false))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TASK_GRANT_INVALID"));
        assertThatThrownBy(() -> service.verify(
                "Bearer " + grant.value(), "task." + "9".repeat(64), false))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TASK_GRANT_WRONG_TASK"));
    }

    private static AgentEngineTaskGrantService service(
            EngineGatewayProperties properties, Instant instant) {
        AgentTurnProductContextResolver contexts = mock(AgentTurnProductContextResolver.class);
        when(contexts.resolve(11L, 12L)).thenReturn(new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "12", 11L, 13L, 14L),
                Optional.of(VERSION)));
        return new AgentEngineTaskGrantService(
                new ObjectMapper().findAndRegisterModules(), properties, contexts,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static EngineGatewayProperties properties() {
        EngineGatewayProperties value = new EngineGatewayProperties();
        value.setTaskGrantSecret("test-secret-with-at-least-thirty-two-characters");
        value.setTaskGrantTtl(Duration.ofMinutes(10));
        return value;
    }
}
