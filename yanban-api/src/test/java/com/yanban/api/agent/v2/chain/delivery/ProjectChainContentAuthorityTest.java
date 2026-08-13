package com.yanban.api.agent.v2.chain.delivery;

import com.yanban.api.agent.v2.chain.finalization.ProductChainTerminalOutcomeAuthority;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainCommandStatus;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainDeliveryWriter;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.delivery.ChainDeliveryMessagePort;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectChainContentAuthorityTest {
    private static final Instant NOW =
            Instant.parse("2026-08-07T12:00:00Z");
    private static final String BODY = "authoritative answer";
    private static final String BODY_HASH = sha256(BODY);

    @Test
    void productMessagePortReceivesOnlyContentAuthorityNotBodyCopy() {
        assertFalse(Arrays.stream(
                        ChainDeliveryMessagePort.Reservation.class
                                .getRecordComponents())
                .anyMatch(component -> component.getName()
                        .toLowerCase().contains("body")
                        && !component.getName().equals("answerBodySha256")));
        assertFalse(Arrays.stream(
                        ChainDeliveryMessagePort.AttemptCommand.class
                                .getRecordComponents())
                .anyMatch(component -> component.getName()
                        .equals("answerBody")));
    }

    @Test
    void fallbackBodyProjectsOnlyFormalOutcomeAndDiagnostic() {
        var outcome = new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome-1", "task-1", "event-1", "command-1",
                ChainTaskOutcomeStatus.COMPLETED, "instruction-1",
                "frame-1", "plan-1", "revision-1",
                canonical("[]"), canonical("[]"), 41L,
                "candidate-1", "readiness-1", "check-1",
                "validation-1", "b".repeat(64), "c".repeat(64),
                io.paperagent.v2.chain.ChainPublishRequirement.REQUIRED,
                "d".repeat(64), "publish-operation-1", "project-v2",
                2L, "publish-receipt-1", canonical("[]"),
                canonical("[]"), canonical("[]"), null, null,
                "review-1", NOW);

        String body = ProductChainDeliveryMessageAdapter.outcomeFallbackBody(
                outcome, "CHAIN_ANSWER_MODEL_CALL_FAILED");

        assertTrue(body.contains("COMPLETED"));
        assertTrue(body.contains("candidate-artifact:41"));
        assertTrue(body.contains("validation-1"));
        assertTrue(body.contains("project-v2"));
        assertTrue(body.contains("CHAIN_ANSWER_MODEL_CALL_FAILED"));
        assertFalse(body.contains(BODY));
    }

    @Test
    void failedOutcomeFallbackIncludesFormalFailureCode() {
        var outcome = new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome-1", "task-1", "event-1", "command-1",
                ChainTaskOutcomeStatus.FAILED, "instruction-1",
                "frame-1", "plan-1", "revision-1",
                canonical("[]"), canonical("[]"), null,
                ChainIdentity.NONE, ChainIdentity.NONE,
                null, null, null, null, canonical("[]"),
                canonical("[]"), canonical("[]"), "EXECUTION",
                "STEP_FAILED", "review-1", NOW);

        String body = ProductChainDeliveryMessageAdapter.outcomeFallbackBody(
                outcome, "CHAIN_ANSWER_PLAN_REVISION_MISSING");

        assertTrue(body.contains("FAILED"));
        assertTrue(body.contains("EXECUTION/STEP_FAILED"));
        assertTrue(body.contains("CHAIN_ANSWER_PLAN_REVISION_MISSING"));
    }

    @Test
    void directPlannerAnswerIsDeliveredOnceWithoutAnAnswerModelTurn()
            throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:direct-planner-delivery;MODE=MySQL;"
                        + "DB_CLOSE_DELAY=-1", "sa", "");
        NamedParameterJdbcTemplate jdbc =
                new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_v2_chain_tasks(
                    task_id VARCHAR(128) PRIMARY KEY)
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_messages(
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    session_id BIGINT, user_id BIGINT,
                    role VARCHAR(32), content CLOB, tool_calls_json CLOB,
                    tool_call_id VARCHAR(128), paper_task_id BIGINT,
                    created_at TIMESTAMP)
                """);
        jdbc.getJdbcTemplate().execute(
                "INSERT INTO agent_v2_chain_tasks VALUES('task-1')");
        var task = new ChainPersistenceRecords.TaskRecord(
                "task-1", "command-1", "instruction-1", null,
                7, 8, 9, null, "request-1", "1".repeat(64),
                null, null, 0, NOW);
        var command = new ChainPersistenceRecords.CommandRecord(
                "command-1", 7L, 8L, "request-1",
                ChainInstructionRelation.INITIAL,
                null, null, null, "2".repeat(64),
                10L, 1L, "task-1", "event-command-1",
                "instruction-1", ChainCommandStatus.COMMITTED,
                null, NOW, NOW);
        String answer = "LaTeX 交叉引用通过 \\label 定义标签，并使用 \\ref 引用。";
        var content = new ChainPersistenceRecords.ContentRecord(
                "content-direct-1", "task-1", "invocation-direct-1",
                ChainContentKind.ANSWER_BODY, answer, sha256(answer),
                "text/plain", NOW);
        String proposalJson = "{\"answerBodyRef\":\"content-direct-1\","
                + "\"answerRequiredRefs\":[],"
                + "\"directTaskSpecification\":\"explain cross references\","
                + "\"gapValidation\":null,\"needsNetwork\":false,"
                + "\"needsPersistentProgress\":false,"
                + "\"needsProject\":false,\"needsTool\":false,"
                + "\"routeReason\":\"plain knowledge question\","
                + "\"userConstraints\":[]}";
        var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                "proposal-direct-1", "task-1", "invocation-direct-1", 1,
                io.paperagent.v2.chain.ChainRole.PLANNER,
                io.paperagent.v2.chain.ChainProposalKind.PLANNER_DIRECT_ROUTE,
                canonical(proposalJson), canonical("[]"), "ANSWER_BODY",
                content.contentId(), NOW);
        var route = new ChainPersistenceRecords.RouteDecisionRecord(
                "route-direct-1", "task-1", "route-event-1",
                "instruction-1", proposal.proposalId(),
                ChainPersistenceRecords.RouteDecisionType.INITIAL, 0,
                io.paperagent.v2.chain.ChainExecutionMode.DIRECT,
                "plain knowledge question",
                canonical("{\"specification\":\"explain cross references\"}"),
                canonical("[]"), canonical("[]"),
                false, false, false, false, null, null, null, NOW);
        var deliveryFacts = new CopyOnWriteArrayList<
                ChainPersistenceRecords.DeliveryRecord>();
        var eventFacts = new CopyOnWriteArrayList<
                ChainPersistenceRecords.DeliveryEventRecord>();
        var foundations = mock(ChainFoundationRepository.class);
        var finalization = mock(ChainFinalizationRepository.class);
        var models = mock(ChainModelRepository.class);
        var workflow = mock(
                io.paperagent.v2.chain.ChainWorkflowRepository.class);
        var writer = mock(ChainDeliveryWriter.class);
        var turns = mock(AgentTurnRepository.class);
        var turn = new AgentTurn(8L, 7L, 1L);
        field(AgentTurn.class, "id").set(turn, 10L);
        when(foundations.findTask("task-1")).thenReturn(Optional.of(task));
        when(foundations.findCommand("command-1"))
                .thenReturn(Optional.of(command));
        when(models.findProposal(proposal.proposalId()))
                .thenReturn(Optional.of(proposal));
        when(models.findContent(content.contentId()))
                .thenReturn(Optional.of(content));
        when(workflow.findRouteDecisions("task-1"))
                .thenReturn(List.of(route));
        when(finalization.findDeliveries("task-1"))
                .thenAnswer(ignored -> List.copyOf(deliveryFacts));
        when(finalization.findDeliveryEvents(any()))
                .thenAnswer(call -> eventFacts.stream().filter(value ->
                        value.deliveryId().equals(call.getArgument(0)))
                        .toList());
        when(turns.findById(10L)).thenReturn(Optional.of(turn));
        when(turns.saveAndFlush(any(AgentTurn.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(writer.appendDelivery(any())).thenAnswer(call -> {
            @SuppressWarnings("unchecked")
            var fact = (ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.DeliveryRecord>)
                    call.getArgument(0);
            deliveryFacts.add(fact.fact());
            return appendResult(fact);
        });
        when(writer.appendDeliveryEvent(any())).thenAnswer(call -> {
            @SuppressWarnings("unchecked")
            var fact = (ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.DeliveryEventRecord>)
                    call.getArgument(0);
            eventFacts.add(fact.fact());
            return appendResult(fact);
        });
        var adapter = new ProductChainDeliveryMessageAdapter(
                foundations, finalization, models, workflow, writer, turns,
                jdbc, new DataSourceTransactionManager(dataSource),
                mock(ProductChainTerminalOutcomeAuthority.class));
        var request = new ProductChainDeliveryMessageAdapter
                .DirectPlannerCommand(
                "task-1", "instruction-1", "command-1", route,
                ChainRuntimePolicy.V1.policyVersion(), NOW);

        var first = adapter.deliverDirectPlanner(request);
        var replay = adapter.deliverDirectPlanner(request);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.delivery(), replay.delivery());
        assertEquals(1, deliveryFacts.size());
        assertEquals(1, eventFacts.size());
        assertEquals(1L, count(jdbc, "agent_messages"));
        assertEquals(answer, jdbc.queryForObject(
                "SELECT content FROM agent_messages",
                new MapSqlParameterSource(), String.class));
        assertEquals(AgentTurn.STATUS_COMPLETED, turn.getStatus());
    }

    @Test
    void outcomeFallbackIsAtomicAndReplayCreatesNoSecondMessageOrEvent()
            throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:outcome-fallback-delivery;MODE=MySQL;"
                        + "DB_CLOSE_DELAY=-1", "sa", "");
        NamedParameterJdbcTemplate jdbc =
                new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_v2_chain_tasks(
                    task_id VARCHAR(128) PRIMARY KEY)
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_messages(
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    session_id BIGINT, user_id BIGINT,
                    role VARCHAR(32), content CLOB, tool_calls_json CLOB,
                    tool_call_id VARCHAR(128), paper_task_id BIGINT,
                    created_at TIMESTAMP)
                """);
        jdbc.getJdbcTemplate().execute(
                "INSERT INTO agent_v2_chain_tasks VALUES('task-1')");
        var task = new ChainPersistenceRecords.TaskRecord(
                "task-1", "command-1", "instruction-1", null,
                7, 8, 9, null, "request-1", "1".repeat(64),
                null, null, 0, NOW);
        var command = new ChainPersistenceRecords.CommandRecord(
                "command-1", 7L, 8L, "request-1",
                ChainInstructionRelation.INITIAL,
                null, null, null, "2".repeat(64),
                10L, 1L, "task-1", "event-command-1",
                "instruction-1", ChainCommandStatus.COMMITTED,
                null, NOW, NOW);
        var outcome = new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome-1", "task-1", "event-outcome-1", "command-1",
                ChainTaskOutcomeStatus.COMPLETED, "instruction-1",
                "frame-1", "plan-1", "revision-1",
                canonical("[]"), canonical("[]"), 41L,
                "candidate-1", "readiness-1", "check-1",
                "validation-1", "b".repeat(64), "c".repeat(64),
                io.paperagent.v2.chain.ChainPublishRequirement.NOT_REQUIRED,
                "d".repeat(64), null, null, null, null,
                canonical("[]"), canonical("[]"), canonical("[]"),
                null, null, "review-1", NOW);
        var deliveryFacts = new CopyOnWriteArrayList<
                ChainPersistenceRecords.DeliveryRecord>();
        var eventFacts = new CopyOnWriteArrayList<
                ChainPersistenceRecords.DeliveryEventRecord>();
        var foundations = mock(ChainFoundationRepository.class);
        var finalization = mock(ChainFinalizationRepository.class);
        var writer = mock(ChainDeliveryWriter.class);
        var turns = mock(AgentTurnRepository.class);
        var turn = new AgentTurn(8L, 7L, 1L);
        field(AgentTurn.class, "id").set(turn, 10L);
        when(foundations.findTask("task-1")).thenReturn(Optional.of(task));
        when(foundations.findCommand("command-1"))
                .thenReturn(Optional.of(command));
        when(finalization.findTaskOutcome("task-1"))
                .thenReturn(Optional.of(outcome));
        when(finalization.findDeliveries("task-1"))
                .thenAnswer(ignored -> List.copyOf(deliveryFacts));
        when(finalization.findDeliveryEvents(any()))
                .thenAnswer(call -> eventFacts.stream().filter(value ->
                        value.deliveryId().equals(call.getArgument(0)))
                        .toList());
        when(turns.findById(10L)).thenReturn(Optional.of(turn));
        when(turns.saveAndFlush(any(AgentTurn.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(writer.appendDelivery(any())).thenAnswer(call -> {
            @SuppressWarnings("unchecked")
            var fact = (ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.DeliveryRecord>)
                    call.getArgument(0);
            deliveryFacts.add(fact.fact());
            return appendResult(fact);
        });
        when(writer.appendDeliveryEvent(any())).thenAnswer(call -> {
            @SuppressWarnings("unchecked")
            var fact = (ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.DeliveryEventRecord>)
                    call.getArgument(0);
            eventFacts.add(fact.fact());
            return appendResult(fact);
        });
        var adapter = new ProductChainDeliveryMessageAdapter(
                foundations, finalization, mock(ChainModelRepository.class),
                mock(io.paperagent.v2.chain.ChainWorkflowRepository.class),
                writer, turns, jdbc,
                new DataSourceTransactionManager(dataSource),
                mock(ProductChainTerminalOutcomeAuthority.class));
        var request = new ProductChainDeliveryMessageAdapter
                .OutcomeFallbackCommand(
                "task-1", "outcome-1", "command-1",
                "CHAIN_ANSWER_MODEL_CALL_FAILED",
                ChainRuntimePolicy.V1.policyVersion(), NOW);

        var first = adapter.deliverOutcomeFallback(request);
        var replay = adapter.deliverOutcomeFallback(request);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.delivery(), replay.delivery());
        assertEquals(first.assistantMessageId(), replay.assistantMessageId());
        assertEquals(1, deliveryFacts.size());
        assertEquals(1, eventFacts.size());
        assertEquals(1L, count(jdbc, "agent_messages"));
        assertEquals(AgentTurn.STATUS_COMPLETED, turn.getStatus());
    }

    @Test
    void successTransactionOwnsNullBodyMessageEventAndTurnWhileRetryReusesId()
            throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:delivery-product-boundary;MODE=MySQL;"
                        + "DB_CLOSE_DELAY=-1", "sa", "");
        NamedParameterJdbcTemplate jdbc =
                new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_v2_chain_tasks(
                    task_id VARCHAR(128) PRIMARY KEY)
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_messages(
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    session_id BIGINT, user_id BIGINT,
                    role VARCHAR(32), content CLOB, tool_calls_json CLOB,
                    tool_call_id VARCHAR(128), paper_task_id BIGINT,
                    created_at TIMESTAMP)
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_turns(
                    id BIGINT PRIMARY KEY, session_id BIGINT, user_id BIGINT,
                    assistant_message_id BIGINT, status VARCHAR(32),
                    error_message VARCHAR(255))
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE agent_v2_chain_delivery_message_reservations(
                    delivery_id VARCHAR(128) PRIMARY KEY,
                    task_id VARCHAR(128), answer_content_id VARCHAR(128),
                    answer_body_sha256 CHAR(64),
                    assistant_message_id BIGINT UNIQUE,
                    created_at TIMESTAMP)
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE delivery_events(
                    delivery_id VARCHAR(128), event_sequence BIGINT,
                    task_id VARCHAR(128), event_id VARCHAR(128),
                    event_kind VARCHAR(32), attempt_no INT,
                    error_code VARCHAR(64), runtime_policy_version VARCHAR(64),
                    committed_at TIMESTAMP,
                    PRIMARY KEY(delivery_id,event_sequence))
                """);
        jdbc.getJdbcTemplate().execute(
                "INSERT INTO agent_v2_chain_tasks VALUES('task-1')");
        jdbc.getJdbcTemplate().execute("""
                INSERT INTO agent_turns VALUES
                    (9,8,7,NULL,'RUNNING',NULL),
                    (10,8,7,NULL,'RUNNING',NULL)
                """);

        ChainPersistenceRecords.TaskRecord task =
                new ChainPersistenceRecords.TaskRecord(
                        "task-1", "command-1", "instruction-1", null,
                        7, 8, 9, null, "request-1", "1".repeat(64),
                        null, null, 0, NOW);
        ChainPersistenceRecords.CommandRecord sourceCommand =
                new ChainPersistenceRecords.CommandRecord(
                        "command-current", 7L, 8L, "request-current",
                        ChainInstructionRelation.SUPPLEMENT,
                        "task-1", "request-1", null, "2".repeat(64),
                        10L, 12L, "task-1", "event-current",
                        "instruction-current", ChainCommandStatus.COMMITTED,
                        null, NOW, NOW.plusMillis(1));
        ChainPersistenceRecords.ContentRecord content =
                new ChainPersistenceRecords.ContentRecord(
                        "content-1", "task-1", "invocation-1",
                        ChainContentKind.ANSWER_BODY, BODY, BODY_HASH,
                        "text/plain", NOW);
        ChainFoundationRepository foundations =
                mock(ChainFoundationRepository.class);
        ChainFinalizationRepository finalization =
                mock(ChainFinalizationRepository.class);
        ChainModelRepository models = mock(ChainModelRepository.class);
        ChainDeliveryWriter writer = mock(ChainDeliveryWriter.class);
        AgentTurnRepository turns = mock(AgentTurnRepository.class);
        when(foundations.findTask("task-1")).thenReturn(Optional.of(task));
        when(foundations.findCommand("command-current"))
                .thenReturn(Optional.of(sourceCommand));
        when(models.findContent("content-1")).thenReturn(Optional.of(content));
        when(finalization.findDeliveryEvents("delivery-1")).thenAnswer(
                ignored -> events(jdbc));
        when(turns.findById(10L)).thenAnswer(ignored ->
                Optional.of(turn(jdbc, 10L)));
        AtomicBoolean failFirstCompletion = new AtomicBoolean(true);
        when(turns.saveAndFlush(any(AgentTurn.class))).thenAnswer(call -> {
            AgentTurn turn = call.getArgument(0);
            if (AgentTurn.STATUS_COMPLETED.equals(turn.getStatus())
                    && failFirstCompletion.getAndSet(false)) {
                throw new DataAccessResourceFailureException(
                        "forced Turn write rollback");
            }
            jdbc.update("""
                    UPDATE agent_turns
                       SET assistant_message_id=:messageId,status=:status,
                           error_message=:error
                     WHERE id=:id
                    """, new MapSqlParameterSource()
                    .addValue("messageId", turn.getAssistantMessageId())
                    .addValue("status", turn.getStatus())
                    .addValue("error", turn.getErrorMessage())
                    .addValue("id", turn.getId()));
            return turn;
        });
        when(writer.appendDeliveryEvent(any())).thenAnswer(call -> {
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.DeliveryEventRecord> request =
                    call.getArgument(0);
            ChainPersistenceRecords.DeliveryEventRecord value = request.fact();
            Instant storedAt = value.committedAt().plusMillis(1);
            jdbc.update("""
                    INSERT INTO delivery_events VALUES(
                        :deliveryId,:sequence,:taskId,:eventId,:kind,:attempt,
                        :error,:policy,:committedAt)
                    """, new MapSqlParameterSource()
                    .addValue("deliveryId", value.deliveryId())
                    .addValue("sequence", value.eventSequence())
                    .addValue("taskId", value.taskId())
                    .addValue("eventId", value.eventId())
                    .addValue("kind", value.eventKind().name())
                    .addValue("attempt", value.attemptNo())
                    .addValue("error", value.errorCode())
                    .addValue("policy", value.runtimePolicyVersion())
                    .addValue("committedAt", storedAt));
            ChainPersistenceRecords.DeliveryEventRecord stored =
                    new ChainPersistenceRecords.DeliveryEventRecord(
                            value.deliveryId(), value.eventSequence(),
                            value.taskId(), value.eventId(), value.eventKind(),
                            value.attemptNo(), value.errorCode(),
                            value.runtimePolicyVersion(), storedAt);
            ChainPersistenceRecords.AuthorityEventRecord event =
                    new ChainPersistenceRecords.AuthorityEventRecord(
                            request.event().eventId(), request.event().taskId(),
                            value.eventSequence(), request.event().eventType(),
                            null, request.event().sourceIdentitySha256(),
                            storedAt);
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    event, stored, false);
        });
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        ProductChainTerminalOutcomeAuthority terminalOutcomes =
                mock(ProductChainTerminalOutcomeAuthority.class);
        ProductChainDeliveryMessageAdapter adapter =
                new ProductChainDeliveryMessageAdapter(
                        foundations, finalization, models,
                        mock(io.paperagent.v2.chain.ChainWorkflowRepository.class),
                        writer, turns, jdbc, transactions, terminalOutcomes);
        ChainDeliveryMessagePort.Reservation reservation =
                new ChainDeliveryMessagePort.Reservation(
                        "delivery-1", "task-1", "content-1", BODY_HASH);

        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var one = pool.submit(() -> {
                start.await();
                return adapter.reserveAssistantMessage(reservation);
            });
            var two = pool.submit(() -> {
                start.await();
                return adapter.reserveAssistantMessage(reservation);
            });
            start.countDown();
            assertEquals(one.get(), two.get());
        } finally {
            pool.shutdownNow();
        }
        long messageId = adapter.reserveAssistantMessage(reservation);
        assertEquals(0L, count(jdbc, "agent_messages"));
        assertEquals(1L, count(jdbc,
                "agent_v2_chain_delivery_message_reservations"));
        assertTrue(messageId < 100,
                "reservation must use the product message sequence");
        jdbc.update("""
                INSERT INTO agent_messages(
                    session_id,user_id,role,content,tool_call_id,created_at)
                VALUES(8,7,'user','ordinary','ordinary',CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource());
        long ordinaryMessageId = jdbc.queryForObject("""
                SELECT id FROM agent_messages WHERE tool_call_id='ordinary'
                """, new MapSqlParameterSource(), Long.class);
        assertEquals(messageId + 1, ordinaryMessageId,
                "reservation must not jump the main message sequence");
        jdbc.update("DELETE FROM agent_messages WHERE id=:id",
                new MapSqlParameterSource("id", ordinaryMessageId));
        assertEquals(0L, count(jdbc, "agent_messages"));

        ChainPersistenceRecords.DeliveryRecord delivery =
                new ChainPersistenceRecords.DeliveryRecord(
                        "delivery-1", "task-1", "delivery-event-1",
                        "command-current", "route-1", null, null, null,
                        "content-1", messageId, NOW);
        when(finalization.findDeliveries("task-1"))
                .thenReturn(List.of(delivery));
        var wrongContent = new ChainDeliveryMessagePort.AttemptCommand(
                "delivery-1", "task-1", "content-1", "f".repeat(64),
                messageId, 1, 2, "forged-success", "forged-failure",
                true, ChainRuntimePolicy.V1.policyVersion(),
                NOW.plusMillis(500));
        assertThrows(IllegalStateException.class,
                () -> adapter.attempt(wrongContent));
        assertEquals(0L, count(jdbc, "delivery_events"));
        assertEquals("RUNNING", turnStatus(jdbc, 10L));

        var wrongDelivery = new ChainDeliveryMessagePort.AttemptCommand(
                "delivery-forged", "task-1", "content-1", BODY_HASH,
                messageId, 1, 2, "forged-success-2", "forged-failure-2",
                true, ChainRuntimePolicy.V1.policyVersion(),
                NOW.plusMillis(600));
        assertThrows(IllegalStateException.class,
                () -> adapter.attempt(wrongDelivery));
        assertEquals(0L, count(jdbc, "delivery_events"));

        var wrongTask = new ChainDeliveryMessagePort.AttemptCommand(
                "delivery-1", "task-forged", "content-1", BODY_HASH,
                messageId, 1, 2, "forged-success-3", "forged-failure-3",
                true, ChainRuntimePolicy.V1.policyVersion(),
                NOW.plusMillis(700));
        assertThrows(IllegalStateException.class,
                () -> adapter.attempt(wrongTask));
        assertEquals(0L, count(jdbc, "delivery_events"));

        ChainDeliveryMessagePort.AttemptCommand first = attempt(
                messageId, 1, 2, false, NOW.plusSeconds(1));
        var retry = adapter.attempt(first);
        assertEquals(ChainDeliveryStatus.RETRYING, retry.event().eventKind());
        assertEquals(0L, count(jdbc, "agent_messages"));
        assertEquals(0L, countWhere(jdbc, "event_kind='SUCCEEDED'"));
        assertEquals("RUNNING", turnStatus(jdbc, 10L));

        ChainDeliveryMessagePort.AttemptCommand second = attempt(
                messageId, 2, 3, false, NOW.plusSeconds(2));
        var success = adapter.attempt(second);
        assertEquals(ChainDeliveryStatus.SUCCEEDED,
                success.event().eventKind());
        assertEquals(1L, count(jdbc, "agent_messages"));
        assertNull(jdbc.queryForObject(
                "SELECT content FROM agent_messages WHERE id=:id",
                new MapSqlParameterSource("id", messageId), String.class));
        assertEquals(1L, countWhere(jdbc, "event_kind='SUCCEEDED'"));
        assertEquals("COMPLETED", turnStatus(jdbc, 10L));
        assertEquals("RUNNING", turnStatus(jdbc, 9L),
                "Delivery must not complete the task's root Turn");
        assertEquals(messageId, jdbc.queryForObject(
                "SELECT assistant_message_id FROM agent_turns WHERE id=10",
                new MapSqlParameterSource(), Long.class));

        var replay = adapter.attempt(second);
        assertTrue(replay.replayed());
        assertEquals(1L, count(jdbc, "agent_messages"));
        assertEquals(1L, countWhere(jdbc, "event_kind='SUCCEEDED'"));

        var forgedReplay = new ChainDeliveryMessagePort.AttemptCommand(
                "delivery-1", "task-1", "content-1", BODY_HASH,
                messageId, 2, 3, "different-success", "failure-2",
                false, ChainRuntimePolicy.V1.policyVersion(),
                NOW.plusSeconds(2));
        assertThrows(IllegalStateException.class,
                () -> adapter.attempt(forgedReplay));
        assertEquals(1L, count(jdbc, "agent_messages"));
        assertEquals(1L, countWhere(jdbc, "event_kind='SUCCEEDED'"));
        assertEquals(1L, countWhere(jdbc, "event_kind='RETRYING'"));

        var outcome = mock(ChainPersistenceRecords.TaskOutcomeRecord.class);
        when(outcome.outcomeId()).thenReturn("outcome-1");
        var outcomeDelivery = new ChainPersistenceRecords.DeliveryRecord(
                delivery.deliveryId(), delivery.taskId(), delivery.eventId(),
                delivery.sourceCommandId(), null, "outcome-1", null, null,
                delivery.answerContentId(), delivery.assistantMessageId(),
                delivery.createdAt());
        when(finalization.findDeliveries("task-1"))
                .thenReturn(List.of(outcomeDelivery));
        when(finalization.findTaskOutcome("task-1"))
                .thenReturn(Optional.of(outcome));
        when(terminalOutcomes.requireExact(any(), any())).thenThrow(
                new IllegalStateException("terminal authority changed"));

        assertThrows(IllegalStateException.class,
                () -> adapter.attempt(second));
        verify(terminalOutcomes).requireExact(any(), any());
        assertEquals(1L, count(jdbc, "agent_messages"));
        assertEquals(1L, countWhere(jdbc, "event_kind='SUCCEEDED'"));
        assertEquals(1L, countWhere(jdbc, "event_kind='RETRYING'"));
    }

    private static ChainDeliveryMessagePort.AttemptCommand attempt(
            long messageId, int attempt, long sequence,
            boolean terminal, Instant committedAt) {
        return new ChainDeliveryMessagePort.AttemptCommand(
                "delivery-1", "task-1", "content-1", BODY_HASH,
                messageId, attempt, sequence, "success-" + attempt,
                "failure-" + attempt, terminal,
                ChainRuntimePolicy.V1.policyVersion(), committedAt);
    }

    private static List<ChainPersistenceRecords.DeliveryEventRecord> events(
            NamedParameterJdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT * FROM delivery_events ORDER BY event_sequence
                """, new MapSqlParameterSource(), (row, index) ->
                new ChainPersistenceRecords.DeliveryEventRecord(
                        row.getString("delivery_id"),
                        row.getLong("event_sequence"),
                        row.getString("task_id"), row.getString("event_id"),
                        ChainDeliveryStatus.valueOf(row.getString("event_kind")),
                        row.getInt("attempt_no"), row.getString("error_code"),
                        row.getString("runtime_policy_version"),
                        row.getTimestamp("committed_at").toInstant()));
    }

    private static AgentTurn turn(
            NamedParameterJdbcTemplate jdbc, long turnId)
            throws Exception {
        var row = jdbc.queryForMap(
                "SELECT * FROM agent_turns WHERE id=:id",
                new MapSqlParameterSource("id", turnId));
        AgentTurn turn = new AgentTurn(8L, 7L, 1L);
        field(AgentTurn.class, "id").set(turn, turnId);
        String status = row.get("status").toString();
        Long messageId = row.get("assistant_message_id") == null ? null
                : ((Number) row.get("assistant_message_id")).longValue();
        if (AgentTurn.STATUS_COMPLETED.equals(status)) turn.complete(messageId);
        if (AgentTurn.STATUS_FAILED.equals(status)) {
            turn.fail(messageId, row.get("error_message").toString());
        }
        return turn;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String turnStatus(
            NamedParameterJdbcTemplate jdbc, long turnId) {
        return jdbc.queryForObject(
                "SELECT status FROM agent_turns WHERE id=:id",
                new MapSqlParameterSource("id", turnId), String.class);
    }

    private static long count(
            NamedParameterJdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table,
                new MapSqlParameterSource(), Long.class);
    }

    private static long countWhere(
            NamedParameterJdbcTemplate jdbc, String where) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_events WHERE " + where,
                new MapSqlParameterSource(), Long.class);
    }

    private static <T extends ChainPersistenceRecords.TaskAuthorityFact>
            ChainPersistenceRecords.AuthoritativeAppendResult<T>
            appendResult(ChainPersistenceRecords.AuthoritativeFact<T> fact) {
        var request = fact.event();
        return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                new ChainPersistenceRecords.AuthorityEventRecord(
                        request.eventId(), request.taskId(), 1L,
                        request.eventType(), request.transitionId(),
                        request.sourceIdentitySha256(), NOW),
                fact.fact(), false);
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(
            String json) {
        return new ChainPersistenceRecords.CanonicalJson(
                1, sha256(json), json);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
