package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextReserved;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2context_behavior;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductPlanExecutionContextRepositoryAdapter.class,
        ProductPlanExecutionContextTransactions.class,
        ProductPlanExecutionContextCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductExecutionStartCodec.class,
        ProductPlanExecutionContextRepositoryAdapterTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductPlanExecutionContextRepositoryAdapterTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        MutableTime time() {
            return new MutableTime();
        }
    }

    static final class MutableTime implements ProductLeaseTimeSource {
        private final AtomicReference<Instant> now =
                new AtomicReference<>(
                        ProductPlanExecutionContextTestFixtures.NOW);
        private final AtomicInteger observations = new AtomicInteger();

        @Override
        public Instant observe() {
            observations.incrementAndGet();
            return now.get();
        }

        void set(Instant value) { now.set(value); }
        int observations() { return observations.get(); }
        void reset() {
            now.set(ProductPlanExecutionContextTestFixtures.NOW);
            observations.set(0);
        }
    }

    @jakarta.annotation.Resource
    private ProductPlanExecutionContextRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private ProductPlanExecutionContextJpaRepository contexts;
    @jakarta.annotation.Resource
    private ProductExecutionStartJpaRepository starts;
    @jakarta.annotation.Resource
    private ProductLeaseJpaRepository leases;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapJpaRepository bootstraps;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapCodec bootstrapCodec;
    @jakarta.annotation.Resource
    private ProductExecutionStartCodec startCodec;
    @jakarta.annotation.Resource
    private MutableTime time;

    @BeforeEach
    void reset() {
        contexts.deleteAll();
        starts.deleteAll();
        leases.deleteAll();
        bootstraps.deleteAll();
        contexts.flush();
        starts.flush();
        leases.flush();
        bootstraps.flush();
        time.reset();
    }

    @Test
    void sourceBackedReserveInspectConfirmAndTakeoverPreserveAuthority() {
        Scenario scenario = seed("a", true);
        var reserved = adapter.reserve(scenario.reservation());
        assertEquals(PersistenceOutcome.APPLIED, reserved.outcome());
        assertEquals(PersistenceOutcome.FOUND,
                adapter.inspect(scenario.bootstrap().plan().id()).outcome());

        ProductLeaseEntity first = leases
                .findFirstByPlanIdOrderByFencingTokenDesc("plan-a")
                .orElseThrow();
        first.releaseAt(ProductPlanExecutionContextTestFixtures.NOW);
        leases.saveAndFlush(first);
        leases.saveAndFlush(new ProductLeaseEntity(
                "plan-a", 2, "takeover-owner", "takeover-token",
                ProductPlanExecutionContextTestFixtures.NOW.minusSeconds(1),
                ProductPlanExecutionContextTestFixtures.NOW.plusSeconds(60)));
        var confirmation =
                ProductPlanExecutionContextTestFixtures.confirmation(
                        scenario.bootstrap(), "takeover-token", 2,
                        scenario.spec());
        PersistedPlanExecutionContextConfirmed confirmed =
                adapter.confirm(confirmation).value().orElseThrow();

        assertEquals("owner-a", confirmed.reservation().leaseOwnerId());
        assertEquals(1, confirmed.reservation().fencingToken());
        assertEquals("takeover-owner", confirmed.leaseOwnerId());
        assertEquals(2, confirmed.fencingToken());
        assertInstanceOf(PersistedPlanExecutionContextConfirmed.class,
                adapter.inspect(scenario.bootstrap().plan().id())
                        .value().orElseThrow());
        assertEquals(1, contexts.count());
    }

    @Test
    void exactReservationAndConfirmationReplayArePermanent() {
        Scenario scenario = seed("a", true);
        PersistedPlanExecutionContextReserved reserved =
                adapter.reserve(scenario.reservation()).value().orElseThrow();
        var confirmation =
                ProductPlanExecutionContextTestFixtures.confirmation(
                        scenario.bootstrap(), "token-a", 1, scenario.spec());
        PersistedPlanExecutionContextConfirmed confirmed =
                adapter.confirm(confirmation).value().orElseThrow();
        leases.deleteAll();
        int before = time.observations();

        var reserveReplay = adapter.reserve(scenario.reservation());
        var confirmReplay = adapter.confirm(confirmation);
        assertEquals(PersistenceOutcome.REPLAYED, reserveReplay.outcome());
        assertEquals(reserved, reserveReplay.value().orElseThrow());
        assertEquals(PersistenceOutcome.REPLAYED, confirmReplay.outcome());
        assertEquals(confirmed, confirmReplay.value().orElseThrow());
        assertEquals(before, time.observations());
    }

    @Test
    void changedPermanentRequestsConflictWithoutWrites() {
        Scenario scenario = seed("a", true);
        adapter.reserve(scenario.reservation());
        var confirmation =
                ProductPlanExecutionContextTestFixtures.confirmation(
                        scenario.bootstrap(), "token-a", 1, scenario.spec());
        adapter.confirm(confirmation);

        var changedReserve =
                new io.paperagent.v2.persistence
                        .PlanExecutionContextReservationRequest(
                        scenario.reservation().planId(), "changed-token", 1,
                        scenario.reservation().expectedRevisionId(), 1, 2, 1,
                        scenario.spec());
        var changedConfirmation =
                new io.paperagent.v2.persistence
                        .PlanExecutionContextConfirmationRequest(
                        scenario.bootstrap().plan().id(), "token-a", 1,
                        scenario.spec(),
                        new ContentHash("sha256", "b".repeat(64)));
        assertFailure(adapter.reserve(changedReserve),
                PersistenceErrorCode.CONFLICTING_REPLAY, "request.planId");
        assertFailure(adapter.confirm(changedConfirmation),
                PersistenceErrorCode.CONFLICTING_REPLAY, "request.planId");
        assertEquals(1, contexts.count());
    }

    @Test
    void sourceLessStaleBindingAndUnstartedPlansWriteNothing() {
        Scenario sourceLess = seed("source-less", false);
        assertFailure(adapter.reserve(sourceLess.reservation()),
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                "planExecutionContext.source");

        Scenario stale = seed("stale", true);
        var staleRequest =
                new io.paperagent.v2.persistence
                        .PlanExecutionContextReservationRequest(
                        stale.bootstrap().plan().id(), "token-stale", 1,
                        new io.paperagent.v2.contracts.PlanRevisionId("other"),
                        1, 2, 1, stale.spec());
        assertFailure(adapter.reserve(staleRequest),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedRevisionId");

        Scenario binding = seed("binding", true);
        WorkspaceMaterializationSpec wrong = new WorkspaceMaterializationSpec(
                binding.spec().workspaceId(),
                new ProjectVersionRef("project-42", "wrong-version"),
                binding.spec().limits());
        var wrongRequest =
                ProductPlanExecutionContextTestFixtures.reservation(
                        binding.bootstrap(), "token-binding", 1, wrong);
        assertFailure(adapter.reserve(wrongRequest),
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                "request.materializationSpec.sourceProjectVersion");
        assertEquals(0, contexts.count());
    }

    @Test
    void everyLeaseFailureIsStableAndWritesNothing() {
        Scenario missing = seed("missing", true);
        leases.deleteAll();
        assertFailure(adapter.reserve(missing.reservation()),
                PersistenceErrorCode.LEASE_NOT_HELD, "request.planId");

        Scenario wrongToken = seed("wrong-token", true);
        var tokenRequest = ProductPlanExecutionContextTestFixtures.reservation(
                wrongToken.bootstrap(), "wrong", 1, wrongToken.spec());
        assertFailure(adapter.reserve(tokenRequest),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "request.leaseToken");

        Scenario wrongFence = seed("wrong-fence", true);
        var fenceRequest = ProductPlanExecutionContextTestFixtures.reservation(
                wrongFence.bootstrap(), "token-wrong-fence", 2,
                wrongFence.spec());
        assertFailure(adapter.reserve(fenceRequest),
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken");

        Scenario expired = seed("expired", true);
        time.set(ProductPlanExecutionContextTestFixtures.NOW.plusSeconds(60));
        assertFailure(adapter.reserve(expired.reservation()),
                PersistenceErrorCode.LEASE_EXPIRED, "request.planId");
        assertEquals(0, contexts.count());
        assertEquals(4, time.observations());
    }

    @Test
    void workspaceHasOneGlobalOwnerAndInspectionIsReadOnly() {
        Scenario first = seed("first", true);
        Scenario second = seed("second", true);
        WorkspaceMaterializationSpec shared = first.spec();
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.reserve(first.reservation()).outcome());
        var competing = ProductPlanExecutionContextTestFixtures.reservation(
                second.bootstrap(), "token-second", 1, shared);
        assertFailure(adapter.reserve(competing),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.materializationSpec.workspaceId");
        long rows = contexts.count();
        assertEquals(PersistenceOutcome.FOUND,
                adapter.inspect(first.bootstrap().plan().id()).outcome());
        assertEquals(rows, contexts.count());
    }

    @Test
    void missingContextAndUnknownPlanUseDistinctNotFoundPaths() {
        Scenario scenario = seed("a", true);
        assertFailure(adapter.inspect(scenario.bootstrap().plan().id()),
                PersistenceErrorCode.NOT_FOUND, "planExecutionContext");
        assertFailure(adapter.inspect(new PlanId("unknown")),
                PersistenceErrorCode.NOT_FOUND, "planId");
        assertFailure(adapter.confirm(
                        ProductPlanExecutionContextTestFixtures.confirmation(
                                scenario.bootstrap(), "token-a", 1,
                                scenario.spec())),
                PersistenceErrorCode.NOT_FOUND, "planExecutionContext");
    }

    @Test
    void digestAndCrossBindingCorruptionFailClosedWithoutLeakage() {
        Scenario scenario = seed("a", true);
        adapter.reserve(scenario.reservation());
        ProductPlanExecutionContextEntity row =
                contexts.findById("plan-a").orElseThrow();
        set(row, "reservationRequestSha256", "0".repeat(64));
        contexts.saveAndFlush(row);
        assertFailure(adapter.inspect(scenario.bootstrap().plan().id()),
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
    }

    private Scenario seed(String suffix, boolean sourceBacked) {
        PersistedPlanBootstrap bootstrap = sourceBacked
                ? ProductPlanExecutionContextTestFixtures.bootstrap(
                        "plan-" + suffix, "task-" + suffix)
                : ProductPlanBootstrapTestFixtures.workspace(
                        "plan-" + suffix, "task-" + suffix);
        ProductPlanExecutionContextTestFixtures.seedStarted(
                bootstrap, "owner-" + suffix, "token-" + suffix, 1,
                bootstraps, bootstrapCodec, leases, starts, startCodec);
        WorkspaceMaterializationSpec spec =
                ProductPlanExecutionContextTestFixtures.spec(suffix);
        return new Scenario(bootstrap, spec,
                ProductPlanExecutionContextTestFixtures.reservation(
                        bootstrap, "token-" + suffix, 1, spec));
    }

    private static void set(Object target, String field, Object value) {
        try {
            var declared = target.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code, String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome(),
                result.toString());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }

    private record Scenario(
            PersistedPlanBootstrap bootstrap,
            WorkspaceMaterializationSpec spec,
            io.paperagent.v2.persistence
                    .PlanExecutionContextReservationRequest reservation) {
    }
}
