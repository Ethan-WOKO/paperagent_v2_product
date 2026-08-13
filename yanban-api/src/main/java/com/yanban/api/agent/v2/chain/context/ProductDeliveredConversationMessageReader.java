package com.yanban.api.agent.v2.chain.context;

import com.yanban.core.agent.AgentMessage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Resolves body-free successful chain Delivery messages to their Answer authority. */
@Component
final class ProductDeliveredConversationMessageReader {
    private static final String DELIVERY_PREFIX = "chain-delivery:";

    private final NamedParameterJdbcTemplate jdbc;

    ProductDeliveredConversationMessageReader(
            NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    ProductConversationAuthoritySupport.VisibleMessage resolve(
            AgentMessage message) {
        Objects.requireNonNull(message, "message");
        if ((message.getContent() != null && !message.getContent().isBlank())
                || (message.getToolCallsJson() != null
                && !message.getToolCallsJson().isBlank())) {
            return visible(message, message.getContent(),
                    message.getContent() == null ? null
                            : "agent-message:" + message.getId() + ":body");
        }
        if (!"assistant".equalsIgnoreCase(message.getRole())
                || message.getId() == null
                || message.getToolCallId() == null
                || !message.getToolCallId().startsWith(DELIVERY_PREFIX)) {
            return visible(message, null, null);
        }
        String deliveryId = message.getToolCallId().substring(
                DELIVERY_PREFIX.length());
        if (deliveryId.isBlank()) {
            throw ProductConversationAuthoritySupport.blocked(
                    "delivered assistant message has an invalid Delivery reference");
        }
        List<DeliveredBody> matches = jdbc.query("""
                SELECT reservation.delivery_id,
                       reservation.answer_content_id,
                       reservation.answer_body_sha256,
                       task.session_id,
                       task.user_id,
                       content.content_kind,
                       content.body,
                       content.body_sha256,
                       terminal.event_kind
                  FROM agent_v2_chain_delivery_message_reservations reservation
                  JOIN agent_v2_chain_deliveries delivery
                    ON delivery.task_id = reservation.task_id
                   AND delivery.delivery_id = reservation.delivery_id
                   AND delivery.answer_content_id = reservation.answer_content_id
                   AND delivery.assistant_message_id = reservation.assistant_message_id
                  JOIN agent_v2_chain_tasks task
                    ON task.task_id = reservation.task_id
                  JOIN agent_v2_chain_contents content
                    ON content.task_id = reservation.task_id
                   AND content.content_id = reservation.answer_content_id
                  JOIN agent_v2_chain_delivery_events terminal
                    ON terminal.task_id = reservation.task_id
                   AND terminal.delivery_id = reservation.delivery_id
                   AND terminal.event_sequence = (
                       SELECT MAX(latest.event_sequence)
                         FROM agent_v2_chain_delivery_events latest
                        WHERE latest.task_id = reservation.task_id
                          AND latest.delivery_id = reservation.delivery_id)
                 WHERE reservation.assistant_message_id = :messageId
                   AND reservation.delivery_id = :deliveryId
                """, new MapSqlParameterSource()
                .addValue("messageId", message.getId())
                .addValue("deliveryId", deliveryId),
                (result, row) -> new DeliveredBody(
                        result.getString("delivery_id"),
                        result.getString("answer_content_id"),
                        result.getString("answer_body_sha256"),
                        result.getLong("session_id"),
                        result.getLong("user_id"),
                        result.getString("content_kind"),
                        result.getString("body"),
                        result.getString("body_sha256"),
                        result.getString("event_kind")));
        if (matches.size() != 1) {
            throw ProductConversationAuthoritySupport.blocked(
                    "delivered assistant message authority is missing or ambiguous");
        }
        DeliveredBody body = matches.get(0);
        String actualDigest = ProductChainContractProjectionCodec.sha256(
                body.body());
        if (!deliveryId.equals(body.deliveryId())
                || !Objects.equals(message.getSessionId(), body.sessionId())
                || !Objects.equals(message.getUserId(), body.userId())
                || !"ANSWER_BODY".equals(body.contentKind())
                || body.body() == null || body.body().isBlank()
                || !Objects.equals(body.reservationDigest(), body.bodyDigest())
                || !Objects.equals(body.bodyDigest(), actualDigest)
                || !"SUCCEEDED".equals(body.terminalStatus())) {
            throw ProductConversationAuthoritySupport.blocked(
                    "delivered assistant message authority is inconsistent");
        }
        return visible(message, body.body(), body.answerContentId());
    }

    private static ProductConversationAuthoritySupport.VisibleMessage visible(
            AgentMessage source, String content, String authorityRef) {
        return new ProductConversationAuthoritySupport.VisibleMessage(
                source, content, authorityRef);
    }

    private record DeliveredBody(
            String deliveryId, String answerContentId,
            String reservationDigest, Long sessionId, Long userId,
            String contentKind, String body, String bodyDigest,
            String terminalStatus) {
    }
}
