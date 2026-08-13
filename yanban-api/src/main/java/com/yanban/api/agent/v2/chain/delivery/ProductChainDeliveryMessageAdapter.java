package com.yanban.api.agent.v2.chain.delivery;

import com.yanban.api.agent.v2.chain.finalization.ProductChainTerminalOutcomeAuthority;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainDeliveryWriter;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.delivery.ChainDeliveryMessagePort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Atomic product binding of Delivery event, canonical message, and Turn. */
@Component
public final class ProductChainDeliveryMessageAdapter
        implements ChainDeliveryMessagePort {
    private static final String MESSAGE_KEY_PREFIX = "chain-delivery:";
    private static final String FIXED_ERROR =
            "CHAIN_DELIVERY_MESSAGE_WRITE_FAILED";

    private final ChainFoundationRepository foundations;
    private final ChainFinalizationRepository finalization;
    private final ChainModelRepository models;
    private final ChainDeliveryWriter deliveries;
    private final AgentTurnRepository turns;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate write;
    private final TransactionTemplate failureWrite;
    private final ProductChainTerminalOutcomeAuthority terminalOutcomes;

    public ProductChainDeliveryMessageAdapter(
            ChainFoundationRepository foundations,
            ChainFinalizationRepository finalization,
            ChainModelRepository models,
            ChainDeliveryWriter deliveries,
            AgentTurnRepository turns,
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            ProductChainTerminalOutcomeAuthority terminalOutcomes) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.finalization = Objects.requireNonNull(
                finalization, "finalization");
        this.models = Objects.requireNonNull(models, "models");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.write = new TransactionTemplate(
                Objects.requireNonNull(transactions, "transactions"));
        this.failureWrite = new TransactionTemplate(transactions);
        this.failureWrite.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.terminalOutcomes = Objects.requireNonNull(
                terminalOutcomes, "terminalOutcomes");
    }

    /**
     * Atomically delivers a code-owned presentation of one immutable Outcome.
     * It is used only when optional Answer-model presentation cannot be
     * produced; no model Proposal or second result authority is fabricated.
     */
    public OutcomeFallbackSubmission deliverOutcomeFallback(
            OutcomeFallbackCommand command) {
        Objects.requireNonNull(command, "command");
        return required(write.execute(status ->
                deliverOutcomeFallbackLocked(command)));
    }

    private OutcomeFallbackSubmission deliverOutcomeFallbackLocked(
            OutcomeFallbackCommand command) {
        ChainPersistenceRecords.TaskRecord task = lockTask(command.taskId());
        ChainPersistenceRecords.TaskOutcomeRecord outcome = finalization
                .findTaskOutcome(task.taskId())
                .filter(value -> value.outcomeId().equals(
                        command.outcomeId()))
                .orElseThrow(() -> new IllegalStateException(
                        "fallback Delivery Outcome is missing"));
        require(outcome.sourceCommandId().equals(
                        command.sourceCommandId()),
                "fallback Delivery changed Outcome source command");
        terminalOutcomes.requireExact(task, outcome);
        String body = outcomeFallbackBody(outcome, command.diagnosticCode());
        String identity = sha256(task.taskId() + "\0"
                + outcome.outcomeId() + "\0OUTCOME_FALLBACK");
        String deliveryId = "delivery.fallback." + identity;
        ChainPersistenceRecords.DeliveryRecord delivery = finalization
                .findDeliveries(task.taskId()).stream()
                .filter(value -> value.deliveryId().equals(deliveryId))
                .findFirst().orElse(null);
        if (delivery == null) {
            ChainPersistenceRecords.DeliveryRecord requested =
                    new ChainPersistenceRecords.DeliveryRecord(
                            deliveryId, task.taskId(),
                            "delivery.fallback.event." + identity,
                            outcome.sourceCommandId(), null,
                            outcome.outcomeId(), null, null,
                            null, null, command.committedAt());
            String sourceDigest = sha256("TASK_OUTCOME\0"
                    + outcome.outcomeId() + "\0OUTCOME_FALLBACK");
            var appended = deliveries.appendDelivery(
                    new ChainPersistenceRecords.AuthoritativeFact<>(
                            new ChainPersistenceRecords.AuthorityEventRequest(
                                    requested.eventId(), requested.taskId(),
                                    "DELIVERY", null, sourceDigest,
                                    requested.createdAt()), requested));
            require(sameIgnoringTime(requested, appended.fact()),
                    "fallback Delivery append changed immutable identity");
            delivery = appended.fact();
        }
        verifyFallbackDelivery(delivery, outcome);
        List<ChainPersistenceRecords.DeliveryEventRecord> events = finalization
                .findDeliveryEvents(delivery.deliveryId()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.DeliveryEventRecord
                                ::eventSequence)).toList();
        if (!events.isEmpty()) {
            require(events.size() == 1,
                    "fallback Delivery event prefix is ambiguous");
            ChainPersistenceRecords.DeliveryEventRecord event = events.get(0);
            verifyFallbackEvent(event, delivery, command);
            AgentTurn turn = sourceTurn(task, delivery);
            require(AgentTurn.STATUS_COMPLETED.equals(turn.getStatus())
                            && turn.getAssistantMessageId() != null,
                    "fallback Delivery replay lacks completed Turn");
            MessageRow message = message(turn.getAssistantMessageId());
            verifyFallbackMessage(message, task, delivery.deliveryId(), body);
            return new OutcomeFallbackSubmission(
                    delivery, event, message.id(), body, true);
        }
        AgentTurn turn = runningTurn(task, delivery);
        long messageId = insertFallbackMessage(task, delivery.deliveryId(), body);
        ChainPersistenceRecords.DeliveryEventRecord event =
                appendFallbackSuccess(delivery, command);
        turn.complete(messageId);
        turns.saveAndFlush(turn);
        return new OutcomeFallbackSubmission(
                delivery, event, messageId, body, false);
    }

    @Override
    public long reserveAssistantMessage(Reservation command) {
        Objects.requireNonNull(command, "command");
        return required(write.execute(status -> {
            ChainPersistenceRecords.TaskRecord task = lockTask(
                    command.taskId());
            verifyContent(command, task);
            ReservationRow existing = reservation(command.deliveryId());
            if (existing != null) {
                verifyReservation(existing, command, task);
                return existing.assistantMessageId();
            }
            long messageId = allocateMessageId(command, task);
            jdbc.update("""
                    INSERT INTO agent_v2_chain_delivery_message_reservations(
                        delivery_id,task_id,answer_content_id,
                        answer_body_sha256,assistant_message_id,created_at)
                    VALUES(:deliveryId,:taskId,:contentId,:bodySha256,
                        :messageId,CURRENT_TIMESTAMP)
                    """, new MapSqlParameterSource()
                    .addValue("deliveryId", command.deliveryId())
                    .addValue("taskId", task.taskId())
                    .addValue("contentId", command.answerContentId())
                    .addValue("bodySha256", command.answerBodySha256())
                    .addValue("messageId", messageId));
            ReservationRow stored = reservation(command.deliveryId());
            verifyReservation(stored, command, task);
            require(message(stored.assistantMessageId()) == null,
                    "Delivery reservation leaked its temporary message");
            return stored.assistantMessageId();
        }));
    }

    @Override
    public AttemptSubmission attempt(AttemptCommand command) {
        Objects.requireNonNull(command, "command");
        try {
            return required(write.execute(status -> attemptSuccess(command)));
        } catch (DeliveryPersistenceFailure failed) {
            return required(failureWrite.execute(
                    status -> attemptFailure(command)));
        }
    }

    private AttemptSubmission attemptSuccess(AttemptCommand command) {
        ChainPersistenceRecords.TaskRecord task = lockTask(command.taskId());
        ChainPersistenceRecords.DeliveryRecord delivery = formalDelivery(
                command, task);
        verifyTerminal(task, delivery);
        AttemptSubmission replay = replay(command, task, delivery);
        if (replay != null) return replay;
        ChainPersistenceRecords.ContentRecord content = verifyContent(
                command, task);
        MessageRow message = ensureMessage(command, task);
        AgentTurn turn = runningTurn(task, delivery);
        require(message.content() == null,
                "chain assistant message must not copy Answer content");
        ChainPersistenceRecords.DeliveryEventRecord event;
        try {
            event = appendEvent(command, ChainDeliveryStatus.SUCCEEDED, null,
                    command.successEventId());
        } catch (DataAccessException persistenceFailure) {
            throw new DeliveryPersistenceFailure(persistenceFailure);
        }
        turn.complete(message.id());
        try {
            turns.saveAndFlush(turn);
        } catch (DataAccessException persistenceFailure) {
            throw new DeliveryPersistenceFailure(persistenceFailure);
        }
        return new AttemptSubmission(event, false);
    }

    private AttemptSubmission attemptFailure(AttemptCommand command) {
        ChainPersistenceRecords.TaskRecord task = lockTask(command.taskId());
        ChainPersistenceRecords.DeliveryRecord delivery = formalDelivery(
                command, task);
        AttemptSubmission replay = replay(command, task, delivery);
        if (replay != null) return replay;
        require(message(command.assistantMessageId()) == null,
                "failed Delivery must not persist an assistant message");
        ChainDeliveryStatus status = command.terminalOnFailure()
                ? ChainDeliveryStatus.DELIVERY_FAILED
                : ChainDeliveryStatus.RETRYING;
        ChainPersistenceRecords.DeliveryEventRecord event = appendEvent(
                command, status, FIXED_ERROR, command.failureEventId());
        if (command.terminalOnFailure()) {
            AgentTurn turn = runningTurn(task, delivery);
            turn.fail(null, FIXED_ERROR);
            turns.saveAndFlush(turn);
        }
        return new AttemptSubmission(event, false);
    }

    private AttemptSubmission replay(
            AttemptCommand command,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.DeliveryRecord delivery) {
        List<ChainPersistenceRecords.DeliveryEventRecord> events = finalization
                .findDeliveryEvents(delivery.deliveryId()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.DeliveryEventRecord
                                ::eventSequence)).toList();
        ChainPersistenceRecords.DeliveryEventRecord existing = events.stream()
                .filter(item -> item.eventSequence()
                        == command.eventSequence())
                .findFirst().orElse(null);
        if (existing == null) return null;
        boolean success = existing.eventKind()
                == ChainDeliveryStatus.SUCCEEDED;
        ChainDeliveryStatus failure = command.terminalOnFailure()
                ? ChainDeliveryStatus.DELIVERY_FAILED
                : ChainDeliveryStatus.RETRYING;
        require(existing.taskId().equals(command.taskId())
                        && existing.deliveryId().equals(command.deliveryId())
                        && existing.attemptNo() == command.attemptNo()
                        && existing.runtimePolicyVersion().equals(
                        command.runtimePolicyVersion())
                        && ((success
                        && existing.eventId().equals(command.successEventId())
                        && existing.errorCode() == null)
                        || (!success && existing.eventKind() == failure
                        && existing.eventId().equals(command.failureEventId())
                        && FIXED_ERROR.equals(existing.errorCode()))),
                "Delivery attempt replay changed immutable identity");
        MessageRow message = message(command.assistantMessageId());
        AgentTurn turn = sourceTurn(task, delivery);
        if (success) {
            verifyContent(command, task);
            require(message != null && message.content() == null
                            && AgentTurn.STATUS_COMPLETED.equals(
                            turn.getStatus())
                            && Objects.equals(turn.getAssistantMessageId(),
                            message.id()),
                    "successful Delivery replay lacks message/Turn authority");
        } else if (existing.eventKind()
                == ChainDeliveryStatus.DELIVERY_FAILED) {
            require(message == null
                            && AgentTurn.STATUS_FAILED.equals(turn.getStatus())
                            && turn.getAssistantMessageId() == null
                            && FIXED_ERROR.equals(turn.getErrorMessage()),
                    "failed Delivery replay lacks terminal Turn authority");
        } else {
            require(message == null
                            && AgentTurn.STATUS_RUNNING.equals(turn.getStatus()),
                    "retrying Delivery must keep its Turn open");
        }
        return new AttemptSubmission(existing, true);
    }

    private ChainPersistenceRecords.DeliveryEventRecord appendFallbackSuccess(
            ChainPersistenceRecords.DeliveryRecord delivery,
            OutcomeFallbackCommand command) {
        String eventId = "delivery.fallback.success." + sha256(
                delivery.deliveryId() + "\0" + command.diagnosticCode());
        ChainPersistenceRecords.DeliveryEventRecord requested =
                new ChainPersistenceRecords.DeliveryEventRecord(
                        delivery.deliveryId(), 1L, delivery.taskId(), eventId,
                        ChainDeliveryStatus.SUCCEEDED, 1, null,
                        command.runtimePolicyVersion(), command.committedAt());
        var appended = deliveries.appendDeliveryEvent(
                new ChainPersistenceRecords.AuthoritativeFact<>(
                        new ChainPersistenceRecords.AuthorityEventRequest(
                                eventId, delivery.taskId(),
                                "DELIVERY_SUCCEEDED", null,
                                sha256(delivery.deliveryId() + "\0"
                                        + command.diagnosticCode()),
                                command.committedAt()), requested));
        require(sameIgnoringTime(requested, appended.fact()),
                "fallback Delivery event append changed immutable identity");
        return appended.fact();
    }

    private ChainPersistenceRecords.DeliveryEventRecord appendEvent(
            AttemptCommand command, ChainDeliveryStatus status,
            String errorCode, String eventId) {
        ChainPersistenceRecords.DeliveryEventRecord requested =
                new ChainPersistenceRecords.DeliveryEventRecord(
                        command.deliveryId(), command.eventSequence(),
                        command.taskId(), eventId, status,
                        command.attemptNo(), errorCode,
                        command.runtimePolicyVersion(), command.committedAt());
        String source = command.deliveryId() + "\0" + command.attemptNo()
                + "\0" + status
                + (errorCode == null ? "" : "\0" + errorCode);
        ChainPersistenceRecords.AuthorityEventRequest authority =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        eventId, command.taskId(), "DELIVERY_" + status,
                        null, sha256(source),
                        command.committedAt());
        var appended = deliveries.appendDeliveryEvent(
                new ChainPersistenceRecords.AuthoritativeFact<>(
                        authority, requested));
        require(sameIgnoringTime(requested, appended.fact())
                        && authority.eventId().equals(
                        appended.event().eventId())
                        && authority.taskId().equals(
                        appended.event().taskId())
                        && authority.eventType().equals(
                        appended.event().eventType())
                        && appended.event().transitionId() == null
                        && authority.sourceIdentitySha256().equals(
                        appended.event().sourceIdentitySha256())
                        && appended.fact().committedAt().equals(
                        appended.event().committedAt()),
                "Delivery event append changed immutable identity");
        return appended.fact();
    }

    private ChainPersistenceRecords.DeliveryRecord formalDelivery(
            AttemptCommand command,
            ChainPersistenceRecords.TaskRecord task) {
        ChainPersistenceRecords.DeliveryRecord delivery = finalization
                .findDeliveries(command.taskId()).stream()
                .filter(item -> item.deliveryId().equals(
                        command.deliveryId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "formal Delivery is missing"));
        require(delivery.taskId().equals(task.taskId())
                        && delivery.answerContentId().equals(
                        command.answerContentId())
                        && Objects.equals(delivery.assistantMessageId(),
                        command.assistantMessageId()),
                "Delivery command changed formal binding");
        ReservationRow reservation = reservation(command.deliveryId());
        verifyReservation(reservation, command, task);
        require(reservation.assistantMessageId()
                        == command.assistantMessageId(),
                "formal Delivery changed reserved message identity");
        return delivery;
    }

    private void verifyTerminal(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.DeliveryRecord delivery) {
        if (delivery.taskOutcomeId() == null) return;
        ChainPersistenceRecords.TaskOutcomeRecord outcome = finalization
                .findTaskOutcome(task.taskId())
                .filter(value -> value.outcomeId().equals(
                        delivery.taskOutcomeId()))
                .orElseThrow(() -> new IllegalStateException(
                        "Delivery TaskOutcome identity changed"));
        terminalOutcomes.requireExact(task, outcome);
    }

    private ChainPersistenceRecords.ContentRecord verifyContent(
            Reservation command, ChainPersistenceRecords.TaskRecord task) {
        return verifyContent(command.answerContentId(),
                command.answerBodySha256(), task);
    }

    private ChainPersistenceRecords.ContentRecord verifyContent(
            AttemptCommand command, ChainPersistenceRecords.TaskRecord task) {
        return verifyContent(command.answerContentId(),
                command.answerBodySha256(), task);
    }

    private ChainPersistenceRecords.ContentRecord verifyContent(
            String contentId, String bodySha256,
            ChainPersistenceRecords.TaskRecord task) {
        ChainPersistenceRecords.ContentRecord content = models
                .findContent(contentId).orElseThrow(() ->
                        new IllegalStateException(
                                "Delivery Answer content is missing"));
        require(content.taskId().equals(task.taskId())
                        && content.bodySha256().equals(bodySha256)
                        && sha256(content.body())
                        .equals(bodySha256),
                "Delivery Answer content authority changed");
        return content;
    }

    private MessageRow ensureMessage(
            AttemptCommand command,
            ChainPersistenceRecords.TaskRecord task) {
        ReservationRow reservation = reservation(command.deliveryId());
        verifyReservation(reservation, command, task);
        require(reservation.assistantMessageId()
                        == command.assistantMessageId(),
                "Delivery command changed reserved message identity");
        MessageRow existing = message(command.assistantMessageId());
        if (existing == null) {
            try {
                jdbc.update("""
                        INSERT INTO agent_messages(
                            id,session_id,user_id,role,content,tool_calls_json,
                            tool_call_id,paper_task_id,created_at)
                        VALUES(:messageId,:sessionId,:userId,'assistant',NULL,NULL,
                            :messageKey,NULL,CURRENT_TIMESTAMP)
                        """, new MapSqlParameterSource()
                        .addValue("messageId", command.assistantMessageId())
                        .addValue("sessionId", task.sessionId())
                        .addValue("userId", task.userId())
                        .addValue("messageKey", messageKey(command.deliveryId())));
                existing = message(command.assistantMessageId());
            } catch (DataAccessException persistenceFailure) {
                throw new DeliveryPersistenceFailure(persistenceFailure);
            }
        }
        verifyMessage(existing, task, messageKey(command.deliveryId()));
        return existing;
    }

    private long insertFallbackMessage(
            ChainPersistenceRecords.TaskRecord task,
            String deliveryId,
            String body) {
        GeneratedKeyHolder generated = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO agent_messages(
                    session_id,user_id,role,content,tool_calls_json,
                    tool_call_id,paper_task_id,created_at)
                VALUES(:sessionId,:userId,'assistant',:body,NULL,
                    :messageKey,NULL,CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource()
                .addValue("sessionId", task.sessionId())
                .addValue("userId", task.userId())
                .addValue("body", body)
                .addValue("messageKey", messageKey(deliveryId)),
                generated, new String[]{"id"});
        Number key = generated.getKey();
        require(key != null && key.longValue() > 0,
                "fallback Delivery did not allocate a message identity");
        MessageRow stored = message(key.longValue());
        verifyFallbackMessage(stored, task, deliveryId, body);
        return key.longValue();
    }

    private long allocateMessageId(
            Reservation command,
            ChainPersistenceRecords.TaskRecord task) {
        GeneratedKeyHolder generated = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO agent_messages(
                    session_id,user_id,role,content,tool_calls_json,
                    tool_call_id,paper_task_id,created_at)
                VALUES(:sessionId,:userId,'assistant',NULL,NULL,
                    :messageKey,NULL,CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource()
                .addValue("sessionId", task.sessionId())
                .addValue("userId", task.userId())
                .addValue("messageKey", messageKey(command.deliveryId())),
                generated, new String[]{"id"});
        Number key = generated.getKey();
        require(key != null && key.longValue() > 0,
                "Delivery message reservation did not allocate an identity");
        long messageId = key.longValue();
        int deleted = jdbc.update("""
                DELETE FROM agent_messages
                 WHERE id=:messageId
                   AND session_id=:sessionId
                   AND user_id=:userId
                   AND role='assistant'
                   AND content IS NULL
                   AND tool_call_id=:messageKey
                """, new MapSqlParameterSource()
                .addValue("messageId", messageId)
                .addValue("sessionId", task.sessionId())
                .addValue("userId", task.userId())
                .addValue("messageKey", messageKey(command.deliveryId())));
        require(deleted == 1,
                "Delivery message reservation placeholder changed identity");
        return messageId;
    }

    private ReservationRow reservation(String deliveryId) {
        List<ReservationRow> rows = jdbc.query("""
                SELECT delivery_id,task_id,answer_content_id,
                       answer_body_sha256,assistant_message_id
                  FROM agent_v2_chain_delivery_message_reservations
                 WHERE delivery_id=:deliveryId
                """, new MapSqlParameterSource("deliveryId", deliveryId),
                (result, row) -> new ReservationRow(
                        result.getString("delivery_id"),
                        result.getString("task_id"),
                        result.getString("answer_content_id"),
                        result.getString("answer_body_sha256"),
                        result.getLong("assistant_message_id")));
        require(rows.size() <= 1,
                "Delivery message reservation is ambiguous");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void verifyReservation(
            ReservationRow reservation,
            Reservation command,
            ChainPersistenceRecords.TaskRecord task) {
        require(reservation != null
                        && reservation.deliveryId().equals(
                        command.deliveryId())
                        && reservation.taskId().equals(task.taskId())
                        && reservation.answerContentId().equals(
                        command.answerContentId())
                        && reservation.answerBodySha256().equals(
                        command.answerBodySha256())
                        && reservation.assistantMessageId() > 0,
                "Delivery message reservation changed authority");
    }

    private static void verifyReservation(
            ReservationRow reservation,
            AttemptCommand command,
            ChainPersistenceRecords.TaskRecord task) {
        require(reservation != null
                        && reservation.deliveryId().equals(
                        command.deliveryId())
                        && reservation.taskId().equals(task.taskId())
                        && reservation.answerContentId().equals(
                        command.answerContentId())
                        && reservation.answerBodySha256().equals(
                        command.answerBodySha256())
                        && reservation.assistantMessageId() > 0,
                "Delivery message reservation changed authority");
    }

    private MessageRow message(long messageId) {
        List<MessageRow> rows = jdbc.query("""
                SELECT id,session_id,user_id,role,content,tool_call_id
                  FROM agent_messages
                 WHERE id = :messageId
                """, new MapSqlParameterSource("messageId", messageId),
                (result, row) -> new MessageRow(
                        result.getLong("id"), result.getLong("session_id"),
                        result.getLong("user_id"), result.getString("role"),
                        result.getString("content"),
                        result.getString("tool_call_id")));
        require(rows.size() <= 1, "assistant message identity is ambiguous");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void verifyFallbackDelivery(
            ChainPersistenceRecords.DeliveryRecord delivery,
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        require(delivery.taskId().equals(outcome.taskId())
                        && delivery.sourceCommandId().equals(
                        outcome.sourceCommandId())
                        && Objects.equals(delivery.taskOutcomeId(),
                        outcome.outcomeId())
                        && delivery.routeDecisionId() == null
                        && delivery.gapId() == null
                        && delivery.decisionId() == null
                        && delivery.answerContentId() == null
                        && delivery.assistantMessageId() == null,
                "fallback Delivery changed Outcome binding");
    }

    private static void verifyFallbackEvent(
            ChainPersistenceRecords.DeliveryEventRecord event,
            ChainPersistenceRecords.DeliveryRecord delivery,
            OutcomeFallbackCommand command) {
        String expectedEventId = "delivery.fallback.success." + sha256(
                delivery.deliveryId() + "\0" + command.diagnosticCode());
        require(event.deliveryId().equals(delivery.deliveryId())
                        && event.taskId().equals(delivery.taskId())
                        && event.eventSequence() == 1L
                        && event.attemptNo() == 1
                        && event.eventKind() == ChainDeliveryStatus.SUCCEEDED
                        && event.errorCode() == null
                        && event.eventId().equals(expectedEventId)
                        && event.runtimePolicyVersion().equals(
                        command.runtimePolicyVersion()),
                "fallback Delivery replay changed immutable identity");
    }

    private static void verifyFallbackMessage(
            MessageRow message,
            ChainPersistenceRecords.TaskRecord task,
            String deliveryId,
            String body) {
        require(message != null && message.id() > 0
                        && message.sessionId() == task.sessionId()
                        && message.userId() == task.userId()
                        && "assistant".equalsIgnoreCase(message.role())
                        && body.equals(message.content())
                        && messageKey(deliveryId).equals(
                        message.toolCallId()),
                "fallback assistant message changed Outcome presentation");
    }

    private static void verifyMessage(
            MessageRow message,
            ChainPersistenceRecords.TaskRecord task,
            String key) {
        require(message != null && message.id() > 0
                        && message.sessionId() == task.sessionId()
                        && message.userId() == task.userId()
                        && "assistant".equalsIgnoreCase(message.role())
                        && message.content() == null
                        && key.equals(message.toolCallId()),
                "assistant message crosses Delivery ownership");
    }

    private AgentTurn runningTurn(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.DeliveryRecord delivery) {
        AgentTurn turn = sourceTurn(task, delivery);
        require(turn.getSessionId() == task.sessionId()
                        && turn.getUserId() == task.userId()
                        && AgentTurn.STATUS_RUNNING.equals(turn.getStatus()),
                "Delivery Turn is not the task's open Turn");
        return turn;
    }

    private AgentTurn sourceTurn(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.DeliveryRecord delivery) {
        ChainPersistenceRecords.CommandRecord command = foundations
                .findCommand(delivery.sourceCommandId()).orElseThrow(() ->
                        new IllegalStateException(
                                "Delivery source command is missing"));
        require(command.userId() == task.userId()
                        && command.sessionId() == task.sessionId()
                        && command.turnId() != null
                        && command.status()
                        != io.paperagent.v2.chain.ChainCommandStatus.FAILED
                        && (command.resultTaskId() == null
                        || task.taskId().equals(command.resultTaskId())),
                "Delivery source command does not belong to the task");
        return turns.findById(command.turnId()).orElseThrow(() ->
                new IllegalStateException("Delivery source Turn is missing"));
    }

    private ChainPersistenceRecords.TaskRecord lockTask(String taskId) {
        List<String> rows = jdbc.queryForList("""
                SELECT task_id
                  FROM agent_v2_chain_tasks
                 WHERE task_id = :taskId
                 FOR UPDATE
                """, new MapSqlParameterSource("taskId", taskId),
                String.class);
        if (rows.size() != 1) {
            throw new IllegalStateException("Delivery task is missing");
        }
        return foundations.findTask(taskId).orElseThrow(() ->
                new IllegalStateException("Delivery task is missing"));
    }

    private static boolean sameIgnoringTime(Record left, Record right) {
        if (!left.getClass().equals(right.getClass())) return false;
        try {
            for (var component : left.getClass().getRecordComponents()) {
                if (component.getName().equals("createdAt")
                        || component.getName().equals("committedAt")) {
                    continue;
                }
                if (!Objects.equals(component.getAccessor().invoke(left),
                        component.getAccessor().invoke(right))) {
                    return false;
                }
            }
            return true;
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String outcomeFallbackBody(
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            String diagnosticCode) {
        Objects.requireNonNull(outcome, "outcome");
        String status = outcome.outcomeType()
                == ChainTaskOutcomeStatus.COMPLETED ? "任务已完成" : "任务未完成";
        String artifact = outcome.finalArtifactId() == null
                ? "无" : "candidate-artifact:" + outcome.finalArtifactId();
        String validation = Objects.equals(
                outcome.validationId(), io.paperagent.v2.chain.ChainIdentity.NONE)
                ? "无" : outcome.validationId();
        String published = outcome.publishedProjectVersion() == null
                ? "未发布新版本" : outcome.publishedProjectVersion();
        String failure = outcome.outcomeType()
                == ChainTaskOutcomeStatus.FAILED
                ? Objects.toString(outcome.failureCategory(), "UNKNOWN")
                + "/" + Objects.toString(outcome.failureCode(), "UNKNOWN")
                : "无";
        return status + "，但自动生成结果说明失败，以下为系统从正式结果生成的基础交付。\n"
                + "- 结果状态：" + outcome.outcomeType().name() + "\n"
                + "- 产物：" + artifact + "\n"
                + "- 验证：" + validation + "\n"
                + "- 项目版本：" + published + "\n"
                + "- 任务失败：" + failure + "\n"
                + "- 展示降级诊断：" + requiredDiagnostic(diagnosticCode);
    }

    private static String requiredDiagnostic(String value) {
        if (value == null || value.isBlank()
                || value.length() > 128
                || !value.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "diagnosticCode must be a bounded machine code");
        }
        return value;
    }

    private static String messageKey(String deliveryId) {
        return MESSAGE_KEY_PREFIX + deliveryId;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static <T> T required(T value) {
        return Objects.requireNonNull(value, "product Delivery transaction");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record MessageRow(
            long id, long sessionId, long userId, String role,
            String content, String toolCallId) {
    }

    private record ReservationRow(
            String deliveryId, String taskId, String answerContentId,
            String answerBodySha256, long assistantMessageId) {
    }

    public record OutcomeFallbackCommand(
            String taskId,
            String outcomeId,
            String sourceCommandId,
            String diagnosticCode,
            String runtimePolicyVersion,
            Instant committedAt) {
        public OutcomeFallbackCommand {
            if (taskId == null || taskId.isBlank()
                    || outcomeId == null || outcomeId.isBlank()
                    || sourceCommandId == null || sourceCommandId.isBlank()
                    || runtimePolicyVersion == null
                    || runtimePolicyVersion.isBlank()) {
                throw new IllegalArgumentException(
                        "fallback command identity must not be blank");
            }
            diagnosticCode = requiredDiagnostic(diagnosticCode);
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    public record OutcomeFallbackSubmission(
            ChainPersistenceRecords.DeliveryRecord delivery,
            ChainPersistenceRecords.DeliveryEventRecord event,
            long assistantMessageId,
            String body,
            boolean replayed) {
        public OutcomeFallbackSubmission {
            Objects.requireNonNull(delivery, "delivery");
            Objects.requireNonNull(event, "event");
            if (assistantMessageId < 1 || body == null || body.isBlank()) {
                throw new IllegalArgumentException(
                        "fallback submission is incomplete");
            }
        }
    }

    private static final class DeliveryPersistenceFailure
            extends RuntimeException {
        private DeliveryPersistenceFailure(Throwable cause) {
            super("chain Delivery product write failed", cause);
        }
    }
}
