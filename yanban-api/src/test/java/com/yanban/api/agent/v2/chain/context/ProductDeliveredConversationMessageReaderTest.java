package com.yanban.api.agent.v2.chain.context;

import com.yanban.core.agent.AgentMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductDeliveredConversationMessageReaderTest {
    private JdbcTemplate jdbc;
    private ProductDeliveredConversationMessageReader subject;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        subject = new ProductDeliveredConversationMessageReader(
                new NamedParameterJdbcTemplate(dataSource));
        jdbc.execute("""
                CREATE TABLE agent_v2_chain_tasks (
                  task_id VARCHAR(128) PRIMARY KEY,
                  session_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE agent_v2_chain_contents (
                  task_id VARCHAR(128) NOT NULL,
                  content_id VARCHAR(128) NOT NULL,
                  content_kind VARCHAR(32) NOT NULL,
                  body CLOB NOT NULL,
                  body_sha256 VARCHAR(64) NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE agent_v2_chain_deliveries (
                  task_id VARCHAR(128) NOT NULL,
                  delivery_id VARCHAR(128) NOT NULL,
                  answer_content_id VARCHAR(128) NOT NULL,
                  assistant_message_id BIGINT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE agent_v2_chain_delivery_message_reservations (
                  task_id VARCHAR(128) NOT NULL,
                  delivery_id VARCHAR(128) NOT NULL,
                  answer_content_id VARCHAR(128) NOT NULL,
                  assistant_message_id BIGINT NOT NULL,
                  answer_body_sha256 VARCHAR(64) NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE agent_v2_chain_delivery_events (
                  task_id VARCHAR(128) NOT NULL,
                  delivery_id VARCHAR(128) NOT NULL,
                  event_sequence BIGINT NOT NULL,
                  event_kind VARCHAR(32) NOT NULL)
                """);
    }

    @Test
    void resolvesBodyOnlyFromMatchingSuccessfulDeliveryAuthority() {
        insertDelivery("SUCCEEDED");

        var visible = subject.resolve(deliveredMessage());

        assertThat(visible.content()).isEqualTo("formal answer");
        assertThat(visible.bodyAuthorityRef()).isEqualTo("content.answer.1");
    }

    @Test
    void rejectsBodyWhenLatestDeliveryEventIsNotSuccessful() {
        insertDelivery("DELIVERY_FAILED");

        assertThatThrownBy(() -> subject.resolve(deliveredMessage()))
                .hasMessageContaining("authority is inconsistent");
    }

    private void insertDelivery(String eventKind) {
        String digest = ProductChainContractProjectionCodec.sha256("formal answer");
        jdbc.update("INSERT INTO agent_v2_chain_tasks VALUES (?, ?, ?)",
                "task.1", 9L, 7L);
        jdbc.update("INSERT INTO agent_v2_chain_contents VALUES (?, ?, ?, ?, ?)",
                "task.1", "content.answer.1", "ANSWER_BODY", "formal answer", digest);
        jdbc.update("INSERT INTO agent_v2_chain_deliveries VALUES (?, ?, ?, ?)",
                "task.1", "delivery.1", "content.answer.1", 10L);
        jdbc.update("""
                INSERT INTO agent_v2_chain_delivery_message_reservations
                VALUES (?, ?, ?, ?, ?)
                """, "task.1", "delivery.1", "content.answer.1", 10L, digest);
        jdbc.update("INSERT INTO agent_v2_chain_delivery_events VALUES (?, ?, ?, ?)",
                "task.1", "delivery.1", 1L, eventKind);
    }

    private static AgentMessage deliveredMessage() {
        AgentMessage message = mock(AgentMessage.class);
        when(message.getId()).thenReturn(10L);
        when(message.getSessionId()).thenReturn(9L);
        when(message.getUserId()).thenReturn(7L);
        when(message.getRole()).thenReturn("assistant");
        when(message.getToolCallId()).thenReturn("chain-delivery:delivery.1");
        return message;
    }
}
