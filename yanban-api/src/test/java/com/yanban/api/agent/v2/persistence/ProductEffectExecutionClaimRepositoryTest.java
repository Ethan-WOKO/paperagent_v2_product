package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.LiteratureSearchStartToolExecutor;
import com.yanban.api.agent.v2.chain.persistence.ProductPlanReplanMarkerReader;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import com.yanban.paper.literature.LiteratureSearchTaskService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2effect_claim_behavior;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductEffectExecutionClaimRepository.class,
        ProductEffectExecutionClaimTransactions.class,
        ProductEffectOutcomeCodec.class,
        ProductEffectOutcomeMarkerReader.class,
        ProductEffectIntentRepositoryAdapter.class,
        ProductEffectIntentTransactions.class,
        ProductEffectIntentCodec.class,
        ProductReceiptCodec.class,
        ProductReceiptMarkerReader.class,
        ProductStepRecoveryTransactions.class,
        ProductActiveStepReplanMarkerReader.class,
        ProductActiveStepReplanCodec.class,
        ProductStepInterruptionMarkerReader.class,
        ProductStepInterruptionCodec.class,
        ProductStepCompletionMarkerReader.class,
        ProductStepCompletionCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductExecutionStartCodec.class,
        ProductStepActivationCodec.class,
        ProductEffectExecutionClaimRepositoryTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductEffectExecutionClaimRepositoryTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ProductLeaseTimeSource leaseTimeSource() {
            return () -> ProductStepActivationTestFixtures.NOW;
        }

        @Bean
        ProductPlanReplanMarkerReader productPlanReplanMarkerReader() {
            return mock(ProductPlanReplanMarkerReader.class);
        }
    }

    @jakarta.annotation.Resource
    ProductEffectExecutionClaimRepository repository;
    @jakarta.annotation.Resource
    ProductEffectIntentRepositoryAdapter intentAdapter;
    @jakarta.annotation.Resource
    ProductEffectExecutionClaimJpaRepository claimRows;
    @jakarta.annotation.Resource
    ProductEffectOutcomeResultJpaRepository resultRows;
    @jakarta.annotation.Resource
    ProductReceiptJpaRepository receiptRows;
    @jakarta.annotation.Resource
    ProductEffectIntentJpaRepository intentRows;
    @jakarta.annotation.Resource
    ProductReceiptToolCallClaimJpaRepository ownershipRows;
    @jakarta.annotation.Resource
    ProductStepActivationJpaRepository activationRows;
    @jakarta.annotation.Resource
    ProductStepInterruptionJpaRepository interruptionRows;
    @jakarta.annotation.Resource
    ProductExecutionStartJpaRepository startRows;
    @jakarta.annotation.Resource
    ProductLeaseJpaRepository leaseRows;
    @jakarta.annotation.Resource
    ProductPlanBootstrapJpaRepository bootstrapRows;
    @jakarta.annotation.Resource
    ProductPlanBootstrapCodec bootstrapCodec;
    @jakarta.annotation.Resource
    ProductExecutionStartCodec startCodec;
    @jakarta.annotation.Resource
    ProductStepActivationCodec persistedActivationCodec;
    @jakarta.annotation.Resource
    LiteratureSearchTaskRepository literatureTasks;
    @jakarta.annotation.Resource
    ObjectMapper objectMapper;

    @BeforeEach
    void clearDatabase() {
        resultRows.deleteAll();
        receiptRows.deleteAll();
        claimRows.deleteAll();
        literatureTasks.deleteAll();
        intentRows.deleteAll();
        ownershipRows.deleteAll();
        interruptionRows.deleteAll();
        activationRows.deleteAll();
        startRows.deleteAll();
        leaseRows.deleteAll();
        bootstrapRows.deleteAll();
    }

    @Test
    void realProductTaskClaimReceiptAndOutcomeCommitAtomically() {
        Scenario scenario = scenario("atomic-success");
        AtomicInteger invocations = new AtomicInteger();

        ProductEffectExecutionClaimResult result = repository.execute(
                request(scenario, scenario.lease().expiresAt().minusSeconds(1),
                        invocations));

        assertEquals(false, result.replayed());
        assertEquals(1, invocations.get());
        assertEquals(1, literatureTasks.count());
        assertEquals(1, claimRows.count());
        assertEquals(1, receiptRows.count());
        assertEquals(1, resultRows.count());
        assertEquals(
                "v2-request-" + scenario.intent().intent().toolCallId().value(),
                literatureTasks.findAll().get(0).getClientRequestId());
    }

    @Test
    void executionThatEndsAtLeaseExpiryRollsBackAndLaterValidCallExecutes() {
        Scenario scenario = scenario("lease-expiry");
        AtomicInteger expiredInvocations = new AtomicInteger();

        ProductEffectExecutionClaimException failure = assertThrows(
                ProductEffectExecutionClaimException.class,
                () -> repository.execute(request(
                        scenario, scenario.lease().expiresAt(),
                        expiredInvocations)));
        assertEquals("authority.leaseAfterExecution.expired", failure.path());
        assertEquals(0L, failure.timingDeltaMillis());
        assertEquals(1, expiredInvocations.get());
        assertAtomicRows(0);

        AtomicInteger retryInvocations = new AtomicInteger();
        ProductEffectExecutionClaimResult recovered = repository.execute(
                request(scenario,
                        scenario.lease().expiresAt().minusSeconds(1),
                        retryInvocations));
        assertEquals(false, recovered.replayed());
        assertEquals(1, retryInvocations.get());
        assertAtomicRows(1);
    }

    @Test
    void pendingExternalExecutionRollsBackClaimAndAllowsRetry() {
        Scenario scenario = scenario("pending-execution");
        AtomicInteger pendingInvocations = new AtomicInteger();
        ProductEffectExecutionClaimRequest valid = request(
                scenario, scenario.lease().expiresAt().minusSeconds(1),
                new AtomicInteger());
        ProductEffectExecutionClaimRequest pending =
                new ProductEffectExecutionClaimRequest(
                        valid.recovery(), valid.lease(), valid.intent(),
                        valid.leaseToken(), valid.fencingToken(),
                        valid.observedAt(), () -> {
                            pendingInvocations.incrementAndGet();
                            throw new com.yanban.api.agent.sandbox
                                    .V2SandboxEffectPendingException();
                        });

        assertThrows(
                com.yanban.api.agent.sandbox
                        .V2SandboxEffectPendingException.class,
                () -> repository.execute(pending));
        assertEquals(1, pendingInvocations.get());
        assertAtomicRows(0);

        AtomicInteger retryInvocations = new AtomicInteger();
        ProductEffectExecutionClaimResult recovered = repository.execute(
                request(scenario,
                        scenario.lease().expiresAt().minusSeconds(1),
                        retryInvocations));
        assertEquals(false, recovered.replayed());
        assertEquals(1, retryInvocations.get());
        assertAtomicRows(1);
    }

    @Test
    void splitExternalExecutionCommitsClaimBeforePendingAndReusesItOnRetry() {
        Scenario scenario = scenario("split-pending-execution");
        ProductEffectExecutionClaimRequest valid = request(
                scenario, scenario.lease().expiresAt().minusSeconds(1),
                new AtomicInteger());
        ProductEffectExecutionClaimRequest pending =
                new ProductEffectExecutionClaimRequest(
                        valid.recovery(), valid.lease(), valid.intent(),
                        valid.leaseToken(), valid.fencingToken(),
                        valid.observedAt(), () -> {
                            throw new com.yanban.api.agent.sandbox
                                    .V2SandboxEffectPendingException();
                        });

        assertThrows(
                com.yanban.api.agent.sandbox
                        .V2SandboxEffectPendingException.class,
                () -> repository.executeExternal(pending));
        assertEquals(1, claimRows.count());
        assertEquals(0, receiptRows.count());
        assertEquals(0, resultRows.count());

        AtomicInteger retryInvocations = new AtomicInteger();
        ProductEffectExecutionClaimResult recovered = repository.executeExternal(
                request(scenario,
                        scenario.lease().expiresAt().minusSeconds(1),
                        retryInvocations));
        assertEquals(false, recovered.replayed());
        assertEquals(1, retryInvocations.get());
        assertAtomicRows(1);
    }

    @Test
    void staleActiveRecoveryCannotExecuteAfterInterruptionCommits() {
        Scenario scenario = scenario("stale-interruption");
        var payload = new ProductStepInterruptionCodec.EncodedPayload(
                1, "0".repeat(64), "{}");
        interruptionRows.saveAndFlush(new ProductStepInterruptionEntity(
                scenario.intent().intent().planId().value(),
                scenario.intent().intent().stepId().value(),
                "interruption-stale", "PAUSE",
                scenario.recovery().checkpoint().checkpoint()
                        .revisionId().value(),
                scenario.recovery().checkpoint().checkpoint()
                        .revisionNumber(),
                scenario.recovery().checkpoint().checkpoint()
                        .revisionId().value(),
                scenario.recovery().checkpoint().checkpoint()
                        .revisionNumber(),
                scenario.recovery().checkpoint().version(),
                scenario.recovery().checkpoint().version() + 1,
                scenario.recovery().activation().activationEvent()
                        .sequence(),
                scenario.recovery().activation().activationEvent()
                        .sequence() + 1,
                scenario.lease().ownerId(),
                scenario.lease().fencingToken(),
                payload, payload,
                ProductStepActivationTestFixtures.NOW.plusSeconds(1)));
        AtomicInteger invocations = new AtomicInteger();

        ProductEffectExecutionClaimException failure = assertThrows(
                ProductEffectExecutionClaimException.class,
                () -> repository.executeExternal(request(
                        scenario,
                        scenario.lease().expiresAt().minusSeconds(1),
                        invocations)));

        assertEquals("authority.activeStep", failure.path());
        assertEquals(0, invocations.get());
        assertAtomicRows(0);
    }

    @Test
    void precedingStepCompletionDoesNotBlockCurrentActiveStepReplay() {
        var bootstraps = mock(ProductPlanBootstrapJpaRepository.class);
        var activations = mock(ProductStepActivationJpaRepository.class);
        var activationCodec = mock(ProductStepActivationCodec.class);
        var interruptions = mock(
                ProductStepInterruptionJpaRepository.class);
        var completions = mock(ProductStepCompletionJpaRepository.class);
        var leases = mock(ProductLeaseJpaRepository.class);
        var claims = mock(ProductEffectExecutionClaimJpaRepository.class);
        var results = mock(ProductEffectOutcomeResultJpaRepository.class);
        var markers = mock(ProductEffectOutcomeMarkerReader.class);
        var receipts = mock(ProductReceiptJpaRepository.class);
        var receiptCodec = mock(ProductReceiptCodec.class);
        var outcomeCodec = mock(ProductEffectOutcomeCodec.class);
        var entityManager = mock(EntityManager.class);
        var transactions = new ProductEffectExecutionClaimTransactions(
                bootstraps, activations, activationCodec, interruptions,
                completions, leases, claims, results, markers, receipts,
                receiptCodec, outcomeCodec, entityManager);

        PlanId planId = new PlanId("plan-a");
        PlanStepId stepId = new PlanStepId("step-b");
        ToolCallId toolCallId = new ToolCallId("tool-b");
        EventId eventId = new EventId("activation-b");
        EffectIntent effect = mock(EffectIntent.class);
        when(effect.planId()).thenReturn(planId);
        when(effect.stepId()).thenReturn(stepId);
        when(effect.toolCallId()).thenReturn(toolCallId);
        PersistedEffectIntent intent = new PersistedEffectIntent(
                effect, "owner", 2, eventId);
        PersistedStepActivation activation =
                mock(PersistedStepActivation.class);
        EventEnvelope event = mock(EventEnvelope.class);
        when(activation.stepId()).thenReturn(stepId);
        when(activation.activationEvent()).thenReturn(event);
        when(event.id()).thenReturn(eventId);
        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.stepStates()).thenReturn(Map.of(
                new PlanStepId("step-a"), StepExecutionState.SUCCEEDED,
                stepId, StepExecutionState.ACTIVE));
        VersionedCheckpoint versioned = mock(VersionedCheckpoint.class);
        when(versioned.checkpoint()).thenReturn(checkpoint);
        PersistedStepRecoveryActive recovery =
                mock(PersistedStepRecoveryActive.class);
        when(recovery.planId()).thenReturn(planId);
        when(recovery.activation()).thenReturn(activation);
        when(recovery.checkpoint()).thenReturn(versioned);
        LeaseRecord lease = new LeaseRecord(
                planId, "owner", "token", 2,
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T00:10:00Z"));
        ProductEffectExecutionClaimRequest request =
                new ProductEffectExecutionClaimRequest(
                        recovery, lease, intent, "token", 2,
                        Instant.parse("2026-07-28T00:01:00Z"),
                        () -> {
                            throw new AssertionError("replay must not execute");
                        });

        when(bootstraps.lockByPlanId("plan-a")).thenReturn(
                Optional.of(mock(ProductPlanBootstrapEntity.class)));
        when(markers.intent("tool-b")).thenReturn(intent);
        when(interruptions.findAllByPlanId("plan-a"))
                .thenReturn(List.of());
        ProductStepActivationEntity activationRow =
                mock(ProductStepActivationEntity.class);
        when(activations.findById("activation-b"))
                .thenReturn(Optional.of(activationRow));
        when(activationCodec.decodeResult(any(Integer.class), any(), any()))
                .thenReturn(activation);
        when(completions.findAllByPlanId("plan-a"))
                .thenReturn(List.of(mock(ProductStepCompletionEntity.class)));
        when(completions.findByPlanIdAndStepIdAndActivationEventId(
                "plan-a", "step-b", "activation-b"))
                .thenReturn(Optional.empty());
        ProductLeaseEntity leaseRow = mock(ProductLeaseEntity.class);
        when(leaseRow.planId()).thenReturn("plan-a");
        when(leaseRow.leaseToken()).thenReturn("token");
        when(leaseRow.ownerId()).thenReturn("owner");
        when(leaseRow.fencingToken()).thenReturn(2L);
        when(leaseRow.expiresAt()).thenReturn(
                Instant.parse("2026-07-28T00:10:00Z"));
        when(leases.findFirstByPlanIdOrderByFencingTokenDesc("plan-a"))
                .thenReturn(Optional.of(leaseRow));
        ProductEffectOutcomeResultEntity resultRow =
                mock(ProductEffectOutcomeResultEntity.class);
        when(results.findById("tool-b")).thenReturn(Optional.of(resultRow));
        ProductEffectExecutionClaimEntity claim =
                new ProductEffectExecutionClaimEntity(
                        "tool-b", "plan-a", "step-b", "activation-b",
                        Instant.parse("2026-07-28T00:00:30Z"));
        when(claims.findById("tool-b")).thenReturn(Optional.of(claim));
        PersistedEffectResult persisted = mock(PersistedEffectResult.class);
        when(markers.result(resultRow)).thenReturn(
                new ProductEffectOutcomeMarkerReader.ResultMarker(
                        mock(io.paperagent.v2.persistence
                                .EffectResultRequest.class),
                        persisted));

        assertEquals(persisted, transactions.execute(request).result());
        verify(completions, never()).findAllByPlanId("plan-a");
    }

    @Test
    void migrationRejectsDuplicateAndOrphanClaims() throws Exception {
        String url = schema("claim_repository");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute(intent("tool-a"));
            statement.execute(claim("tool-a"));
            assertEquals(1, count(statement));
            assertThrows(SQLException.class,
                    () -> statement.execute(claim("tool-a")));
            assertThrows(SQLException.class,
                    () -> statement.execute(claim("missing")));
            assertThrows(SQLException.class, () -> statement.execute(
                    "DELETE FROM agent_v2_effect_intents "
                            + "WHERE tool_call_id='tool-a'"));
        }
    }

    Scenario scenario(String suffix) {
        String plan = "plan-" + suffix;
        String token = "token-" + suffix;
        ProductEffectIntentTestFixtures.Scenario seeded =
                ProductEffectIntentTestFixtures.seed(
                        plan, "task-" + suffix, "owner-" + suffix,
                        token, 1, bootstrapRows, bootstrapCodec, leaseRows,
                        startRows, startCodec, activationRows,
                        persistedActivationCodec);
        EffectIntentRequest intentRequest =
                ProductEffectIntentTestFixtures.request(
                        seeded, "tool-" + suffix, token, 1,
                        "literature.search",
                        new ObjectValue(Map.of(
                                "query", new TextValue("graph retrieval"))));
        PersistedEffectIntent intent = intentAdapter.persist(intentRequest)
                .value().orElseThrow();
        PersistedStepRecoveryActive recovery =
                new PersistedStepRecoveryActive(
                        seeded.bootstrap().taskFrame(),
                        seeded.bootstrap().plan(),
                        seeded.persistedActivation().activatedCheckpoint(),
                        seeded.persistedActivation(), Optional.empty());
        LeaseRecord lease = new LeaseRecord(
                seeded.bootstrap().plan().id(),
                "owner-" + suffix, token, 1,
                ProductStepActivationTestFixtures.NOW.minusSeconds(1),
                ProductStepActivationTestFixtures.NOW.plusSeconds(60));
        return new Scenario(recovery, lease, intent);
    }

    ProductEffectExecutionClaimRequest request(
            Scenario scenario, Instant endedAt,
            AtomicInteger invocations) {
        return new ProductEffectExecutionClaimRequest(
                scenario.recovery(), scenario.lease(), scenario.intent(),
                scenario.lease().leaseToken(),
                scenario.lease().fencingToken(),
                ProductStepActivationTestFixtures.NOW.plusSeconds(2),
                () -> executeLiteratureStart(
                        scenario, endedAt, invocations));
    }

    private ExecutionReceipt executeLiteratureStart(
            Scenario scenario, Instant endedAt,
            AtomicInteger invocations) {
        invocations.incrementAndGet();
        LiteratureSearchStartToolExecutor executor = realExecutor();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("query", "graph retrieval");
        args.put("topK", 8);
        args.put("includeBibtex", true);
        args.put("clientRequestId",
                "v2-request-" + scenario.intent().intent()
                        .toolCallId().value());
        ToolExecutionContext.clear();
        ToolExecutionContext.setCurrentUserId(7L);
        ToolExecutionContext.setResolvedAllowedTools(
                Set.of("literature_search_start"));
        final com.yanban.core.tool.ToolResult toolResult;
        try {
            toolResult = executor.execute(new ToolCall(
                    scenario.intent().intent().toolCallId().value(),
                    "literature_search_start", args));
        } finally {
            ToolExecutionContext.clear();
        }
        if (!toolResult.success()) {
            throw new AssertionError("real product task start failed");
        }
        return new ExecutionReceipt(
                new ReceiptId("receipt-" + scenario.intent().intent()
                        .toolCallId().value()),
                scenario.intent().intent().toolCallId(),
                ReceiptStatus.SUCCESS,
                ProductStepActivationTestFixtures.NOW.plusSeconds(3),
                endedAt, Optional.of(0), Optional.empty(),
                OutputCapture.inline(
                        toolResult.output().toString(), false),
                OutputCapture.empty(), List.of(), Optional.empty(),
                List.of());
    }

    @SuppressWarnings("unchecked")
    private LiteratureSearchStartToolExecutor realExecutor() {
        try {
            ObjectProvider<?> emptyProvider = mock(ObjectProvider.class);
            when(emptyProvider.getIfAvailable()).thenReturn(null);
            LiteratureSearchTaskService service =
                    new LiteratureSearchTaskService(
                            literatureTasks,
                            (ObjectProvider) emptyProvider,
                            (ObjectProvider) emptyProvider,
                            (ObjectProvider) emptyProvider);
            Class<?> supportType = Class.forName(
                    "com.yanban.api.agent.LiteratureSearchTaskToolSupport");
            var supportConstructor = supportType.getDeclaredConstructor(
                    LiteratureSearchTaskService.class, ObjectMapper.class);
            supportConstructor.setAccessible(true);
            Object support = supportConstructor.newInstance(
                    service, objectMapper);
            var executorConstructor =
                    LiteratureSearchStartToolExecutor.class
                            .getConstructors()[0];
            return (LiteratureSearchStartToolExecutor)
                    executorConstructor.newInstance(support, objectMapper);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private void assertAtomicRows(long expected) {
        assertEquals(expected, literatureTasks.count());
        assertEquals(expected, claimRows.count());
        assertEquals(expected, receiptRows.count());
        assertEquals(expected, resultRows.count());
    }

    record Scenario(
            PersistedStepRecoveryActive recovery,
            LeaseRecord lease,
            PersistedEffectIntent intent) {
    }

    static ProductEffectExecutionClaimRepositoryTest harness(
            org.springframework.context.ApplicationContext context) {
        var value = new ProductEffectExecutionClaimRepositoryTest();
        value.repository = context.getBean(
                ProductEffectExecutionClaimRepository.class);
        value.intentAdapter = context.getBean(
                ProductEffectIntentRepositoryAdapter.class);
        value.claimRows = context.getBean(
                ProductEffectExecutionClaimJpaRepository.class);
        value.resultRows = context.getBean(
                ProductEffectOutcomeResultJpaRepository.class);
        value.receiptRows = context.getBean(
                ProductReceiptJpaRepository.class);
        value.intentRows = context.getBean(
                ProductEffectIntentJpaRepository.class);
        value.ownershipRows = context.getBean(
                ProductReceiptToolCallClaimJpaRepository.class);
        value.activationRows = context.getBean(
                ProductStepActivationJpaRepository.class);
        value.interruptionRows = context.getBean(
                ProductStepInterruptionJpaRepository.class);
        value.startRows = context.getBean(
                ProductExecutionStartJpaRepository.class);
        value.leaseRows = context.getBean(
                ProductLeaseJpaRepository.class);
        value.bootstrapRows = context.getBean(
                ProductPlanBootstrapJpaRepository.class);
        value.bootstrapCodec = context.getBean(
                ProductPlanBootstrapCodec.class);
        value.startCodec = context.getBean(
                ProductExecutionStartCodec.class);
        value.persistedActivationCodec = context.getBean(
                ProductStepActivationCodec.class);
        value.literatureTasks = context.getBean(
                LiteratureSearchTaskRepository.class);
        value.objectMapper = context.getBean(ObjectMapper.class);
        return value;
    }

    static String schema(String name) throws Exception {
        String url = "jdbc:h2:mem:" + name
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE agent_v2_effect_intents (
                      tool_call_id VARCHAR(128) NOT NULL,
                      plan_id VARCHAR(128) NOT NULL,
                      step_id VARCHAR(128) NOT NULL,
                      activation_event_id VARCHAR(128) NOT NULL,
                      PRIMARY KEY (tool_call_id),
                      CONSTRAINT uk_agent_v2_effect_intent_binding UNIQUE
                        (tool_call_id, plan_id, step_id, activation_event_id))
                    """);
        }
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("52")
                .target("53").load().migrate();
        return url;
    }

    static String intent(String toolCall) {
        return "INSERT INTO agent_v2_effect_intents VALUES ('"
                + toolCall + "','plan-a','step-a','activation-a')";
    }

    static String claim(String toolCall) {
        return "INSERT INTO agent_v2_effect_execution_claims VALUES ('"
                + toolCall
                + "','plan-a','step-a','activation-a',"
                + "TIMESTAMP '2026-07-28 00:00:00')";
    }

    static long count(Statement statement) throws Exception {
        try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM agent_v2_effect_execution_claims")) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
