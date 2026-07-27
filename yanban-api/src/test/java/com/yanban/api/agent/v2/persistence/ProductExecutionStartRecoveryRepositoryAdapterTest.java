package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.ExecutionStartRecoverySnapshot;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2execution_recovery_behavior;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductExecutionStartRecoveryRepositoryAdapter.class,
        ProductExecutionStartRecoveryTransactions.class,
        ProductExecutionStartCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductExecutionStartRecoveryRepositoryAdapterTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductExecutionStartRecoveryRepositoryAdapterTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @jakarta.annotation.Resource
    private ProductExecutionStartRecoveryRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapJpaRepository bootstraps;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapCodec bootstrapCodec;
    @jakarta.annotation.Resource
    private ProductExecutionStartJpaRepository starts;
    @jakarta.annotation.Resource
    private ProductExecutionStartCodec startCodec;
    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        starts.deleteAll();
        bootstraps.deleteAll();
        starts.flush();
        bootstraps.flush();
    }

    @Test
    void invalidAndMissingPlansAreSanitizedAndReadOnly() {
        assertFailure(
                adapter.inspect(null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "planId");
        assertFailure(
                adapter.inspect(new PlanId("missing")),
                PersistenceErrorCode.NOT_FOUND,
                "planId");
        assertEquals(0, bootstraps.count());
        assertEquals(0, starts.count());
    }

    @Test
    void canonicalBootstrapIsReadyAndStableWithoutWriting() {
        PersistedPlanBootstrap bootstrap = seedBootstrap("plan-a", "task-a");
        String before = bootstrapJson("plan-a");

        for (int attempt = 0; attempt < 3; attempt++) {
            PersistedExecutionStartReady ready = assertInstanceOf(
                    PersistedExecutionStartReady.class,
                    found(adapter.inspect(bootstrap.plan().id())));
            assertEquals(bootstrap, ready.bootstrap());
            assertEquals(bootstrap.plan(), ready.currentPlan());
        }

        assertEquals(before, bootstrapJson("plan-a"));
        assertEquals(1, bootstraps.count());
        assertEquals(0, starts.count());
    }

    @Test
    void canonicalStartIsCommittedWithExactPersistedAuthorityAndStable() {
        Scenario scenario = seedCommitted(
                "plan-a", "task-a", "owner-a", "token-a", 7, "event-a");
        List<?> before = rowValues("plan-a");

        for (int attempt = 0; attempt < 3; attempt++) {
            PersistedExecutionStartCommitted committed = assertInstanceOf(
                    PersistedExecutionStartCommitted.class,
                    found(adapter.inspect(scenario.bootstrap().plan().id())));
            assertEquals(scenario.bootstrap(), committed.bootstrap());
            assertEquals(scenario.bootstrap().plan(), committed.currentPlan());
            assertEquals(scenario.persisted(), committed.executionStart());
            assertEquals("owner-a", committed.executionStart().leaseOwnerId());
            assertEquals(7, committed.executionStart().fencingToken());
            assertEquals("event-a",
                    committed.executionStart().startEvent().id().value());
            assertEquals(1,
                    committed.executionStart().startEvent().sequence());
            assertEquals(2,
                    committed.executionStart().startedCheckpoint().version());
        }

        assertEquals(before, rowValues("plan-a"));
        assertEquals(1, bootstraps.count());
        assertEquals(1, starts.count());
    }

    @Test
    void orphanStartAuthorityIsPartialRatherThanNotFound() {
        PersistedPlanBootstrap bootstrap =
                ProductExecutionStartTestFixtures.bootstrap("orphan", "task-o");
        seedStartRow(
                "orphan", bootstrap,
                ProductExecutionStartTestFixtures.request(
                        bootstrap, "token-o", 1, "event-o"),
                "owner-o");

        assertPartial(adapter.inspect(new PlanId("orphan")));
        assertEquals(0, bootstraps.count());
        assertEquals(1, starts.count());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bootstrapCorruptions")
    void everyBootstrapFormatHashPayloadAndIndexCorruptionIsPartial(
            String label,
            Consumer<ProductExecutionStartRecoveryRepositoryAdapterTest>
                    corruption) {
        seedBootstrap("plan-a", "task-a");
        corruption.accept(this);

        assertPartial(adapter.inspect(new PlanId("plan-a")));
        assertEquals(1, bootstraps.count());
        assertEquals(0, starts.count());
    }

    static Stream<Object[]> bootstrapCorruptions() {
        return Stream.of(
                mutation("bootstrap format", test -> test.jdbc.update(
                        "update agent_v2_plan_bootstraps "
                                + "set payload_format_version = 99 "
                                + "where plan_id = 'plan-a'")),
                mutation("bootstrap hash", test -> test.jdbc.update(
                        "update agent_v2_plan_bootstraps "
                                + "set payload_sha256 = ? "
                                + "where plan_id = 'plan-a'",
                        "0".repeat(64))),
                mutation("bootstrap payload", test -> test.jdbc.update(
                        "update agent_v2_plan_bootstraps "
                                + "set payload_json = '{}' "
                                + "where plan_id = 'plan-a'")),
                mutation("bootstrap task index", test -> test.jdbc.update(
                        "update agent_v2_plan_bootstraps "
                                + "set task_frame_id = 'other-task' "
                                + "where plan_id = 'plan-a'")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startStorageCorruptions")
    void everyStartFormatHashPayloadAndIndexCorruptionIsPartial(
            String label,
            Consumer<ProductExecutionStartRecoveryRepositoryAdapterTest>
                    corruption) {
        seedCommitted("plan-a", "task-a", "owner-a", "token-a", 7, "event-a");
        corruption.accept(this);

        assertPartial(adapter.inspect(new PlanId("plan-a")));
        assertEquals(1, bootstraps.count());
        assertEquals(1, starts.count());
    }

    static Stream<Object[]> startStorageCorruptions() {
        return Stream.of(
                mutation("request format", test -> test.updateStart(
                        "request_format_version = 99")),
                mutation("request hash", test -> test.jdbc.update(
                        "update agent_v2_execution_starts "
                                + "set request_sha256 = ? "
                                + "where plan_id = 'plan-a'",
                        "0".repeat(64))),
                mutation("request payload", test -> test.updateStart(
                        "request_json = '{}'")),
                mutation("result format", test -> test.updateStart(
                        "result_format_version = 99")),
                mutation("result hash", test -> test.jdbc.update(
                        "update agent_v2_execution_starts "
                                + "set result_sha256 = ? "
                                + "where plan_id = 'plan-a'",
                        "0".repeat(64))),
                mutation("result payload", test -> test.updateStart(
                        "result_json = '{}'")),
                mutation("event index", test -> test.updateStart(
                        "start_event_id = 'other-event'")),
                mutation("owner index", test -> test.updateStart(
                        "lease_owner_id = 'other-owner'")),
                mutation("fence index", test -> test.updateStart(
                        "fencing_token = 99")));
    }

    @Test
    void canonicalButCrossBoundRequestAndResultArePartial() {
        PersistedPlanBootstrap target = seedBootstrap("plan-a", "task-a");
        PersistedPlanBootstrap other =
                ProductExecutionStartTestFixtures.bootstrap("plan-b", "task-b");
        ExecutionStartRequest otherRequest =
                ProductExecutionStartTestFixtures.request(
                        other, "token-b", 3, "event-b");
        seedStartRow("plan-a", target, otherRequest, "owner-b");

        assertPartial(adapter.inspect(target.plan().id()));
    }

    @Test
    void crossBoundBootstrapAndStartPrimaryIndexesArePartial() {
        seedBootstrap("plan-a", "task-a");
        jdbc.update("update agent_v2_plan_bootstraps "
                + "set plan_id = 'wrong-bootstrap-index' "
                + "where plan_id = 'plan-a'");
        assertPartial(adapter.inspect(new PlanId("wrong-bootstrap-index")));

        reset();
        seedCommitted("plan-a", "task-a", "owner-a", "token-a", 7, "event-a");
        jdbc.update("update agent_v2_execution_starts "
                + "set plan_id = 'wrong-start-index' "
                + "where plan_id = 'plan-a'");
        assertPartial(adapter.inspect(new PlanId("wrong-start-index")));
    }

    @Test
    void corruptPayloadAndExceptionDetailsNeverEscapePartialFailure() {
        seedBootstrap("plan-a", "task-a");
        String sentinel = "PRIVATE-PAYLOAD-SENTINEL-DO-NOT-EXPOSE";
        jdbc.update("update agent_v2_plan_bootstraps "
                + "set payload_json = ? where plan_id = 'plan-a'", sentinel);

        PersistenceResult<ExecutionStartRecoverySnapshot> result =
                adapter.inspect(new PlanId("plan-a"));

        assertPartial(result);
        assertFalse(result.toString().contains(sentinel));
        assertFalse(result.failure().orElseThrow().toString().contains(sentinel));
    }

    private PersistedPlanBootstrap seedBootstrap(String plan, String task) {
        PersistedPlanBootstrap bootstrap =
                ProductExecutionStartTestFixtures.bootstrap(plan, task);
        ProductPlanBootstrapCodec.EncodedPayload encoded =
                bootstrapCodec.encode(bootstrap);
        bootstraps.saveAndFlush(new ProductPlanBootstrapEntity(
                plan,
                task,
                encoded.formatVersion(),
                encoded.sha256(),
                encoded.json(),
                ProductExecutionStartTestFixtures.NOW));
        return bootstrap;
    }

    private Scenario seedCommitted(
            String plan,
            String task,
            String owner,
            String token,
            long fence,
            String event) {
        PersistedPlanBootstrap bootstrap = seedBootstrap(plan, task);
        ExecutionStartRequest request =
                ProductExecutionStartTestFixtures.request(
                        bootstrap, token, fence, event);
        PersistedExecutionStart persisted =
                seedStartRow(plan, bootstrap, request, owner);
        return new Scenario(bootstrap, persisted);
    }

    private PersistedExecutionStart seedStartRow(
            String rowPlan,
            PersistedPlanBootstrap unusedBootstrap,
            ExecutionStartRequest request,
            String owner) {
        PersistedExecutionStart persisted = new PersistedExecutionStart(
                request.planId(),
                owner,
                request.fencingToken(),
                request.startEvent(),
                new VersionedCheckpoint(2, request.startedCheckpoint()));
        starts.saveAndFlush(new ProductExecutionStartEntity(
                rowPlan,
                request.startEvent().id().value(),
                owner,
                request.fencingToken(),
                startCodec.encodeRequest(request),
                startCodec.encodeResult(persisted),
                ProductExecutionStartTestFixtures.NOW.plusSeconds(1)));
        return persisted;
    }

    private String bootstrapJson(String plan) {
        return jdbc.queryForObject(
                "select payload_json from agent_v2_plan_bootstraps "
                        + "where plan_id = ?",
                String.class,
                plan);
    }

    private List<?> rowValues(String plan) {
        return jdbc.queryForList(
                "select * from agent_v2_execution_starts where plan_id = ?",
                plan);
    }

    private void updateStart(String assignment) {
        jdbc.update(
                "update agent_v2_execution_starts set "
                        + assignment
                        + " where plan_id = 'plan-a'");
    }

    private static Object[] mutation(
            String label,
            Consumer<ProductExecutionStartRecoveryRepositoryAdapterTest>
                    corruption) {
        return new Object[] { label, corruption };
    }

    private static ExecutionStartRecoverySnapshot found(
            PersistenceResult<ExecutionStartRecoverySnapshot> result) {
        assertEquals(PersistenceOutcome.FOUND, result.outcome());
        return result.value().orElseThrow();
    }

    private static void assertPartial(
            PersistenceResult<ExecutionStartRecoverySnapshot> result) {
        assertFailure(
                result,
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }

    private record Scenario(
            PersistedPlanBootstrap bootstrap,
            PersistedExecutionStart persisted) {
    }
}
