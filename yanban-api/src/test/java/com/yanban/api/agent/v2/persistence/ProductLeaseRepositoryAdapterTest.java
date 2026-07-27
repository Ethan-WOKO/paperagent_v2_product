package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2lease_behavior;MODE=MySQL;"
                + "DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductLeaseRepositoryAdapter.class,
        ProductLeaseTransactions.class,
        SystemProductLeaseTimeSource.class,
        ProductLeaseRepositoryAdapterTest.TimeConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductLeaseRepositoryAdapterTest {
    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");

    static class TimeConfiguration {
        @Bean
        @Primary
        MutableProductLeaseTimeSource leaseTimeSource() {
            return new MutableProductLeaseTimeSource();
        }
    }

    static final class MutableProductLeaseTimeSource implements ProductLeaseTimeSource {
        private final AtomicReference<Instant> instant = new AtomicReference<>(NOW);
        private final AtomicInteger observations = new AtomicInteger();

        @Override
        public Instant observe() {
            observations.incrementAndGet();
            return instant.get();
        }

        void set(Instant value) {
            instant.set(value);
        }

        int observations() {
            return observations.get();
        }

        void reset() {
            instant.set(NOW);
            observations.set(0);
        }
    }

    @jakarta.annotation.Resource
    private ProductLeaseRepositoryAdapter adapter;

    @jakarta.annotation.Resource
    private ProductPlanBootstrapJpaRepository bootstraps;

    @jakarta.annotation.Resource
    private ProductLeaseJpaRepository leases;

    @jakarta.annotation.Resource
    private MutableProductLeaseTimeSource time;

    @BeforeEach
    void reset() {
        leases.deleteAll();
        bootstraps.deleteAll();
        leases.flush();
        bootstraps.flush();
        time.reset();
    }

    @Test
    void validatesEveryInputBeforeTimeOrStorage() {
        PlanId plan = plan("plan-1");
        Instant expiry = NOW.plusSeconds(60);

        assertFailure(adapter.acquire(null, "owner", "token", expiry),
                PersistenceErrorCode.INVALID_ARGUMENT, "planId");
        assertFailure(adapter.acquire(plan, " ", "token", expiry),
                PersistenceErrorCode.INVALID_ARGUMENT, "ownerId");
        assertFailure(adapter.acquire(plan, "owner", "", expiry),
                PersistenceErrorCode.INVALID_ARGUMENT, "leaseToken");
        assertFailure(adapter.acquire(plan, "owner", "token", null),
                PersistenceErrorCode.INVALID_ARGUMENT, "expiresAt");
        assertFailure(adapter.renew(null, "token", expiry),
                PersistenceErrorCode.INVALID_ARGUMENT, "planId");
        assertFailure(adapter.renew(plan, null, expiry),
                PersistenceErrorCode.INVALID_ARGUMENT, "leaseToken");
        assertFailure(adapter.renew(plan, "token", null),
                PersistenceErrorCode.INVALID_ARGUMENT, "expiresAt");
        assertFailure(adapter.release(null, "token"),
                PersistenceErrorCode.INVALID_ARGUMENT, "planId");
        assertFailure(adapter.release(plan, " "),
                PersistenceErrorCode.INVALID_ARGUMENT, "leaseToken");
        assertFailure(adapter.find(null),
                PersistenceErrorCode.INVALID_ARGUMENT, "planId");
        assertEquals(0, time.observations());
        assertEquals(0, leases.count());
    }

    @Test
    void missingBootstrapIsNotFoundAndDoesNotObserveTimeOrWrite() {
        assertFailure(
                adapter.acquire(plan("missing"), "owner", "token", NOW.plusSeconds(30)),
                PersistenceErrorCode.NOT_FOUND,
                "planId");
        assertFailure(
                adapter.renew(plan("missing"), "token", NOW.plusSeconds(30)),
                PersistenceErrorCode.LEASE_NOT_HELD,
                "planId");
        assertFailure(
                adapter.release(plan("missing"), "token"),
                PersistenceErrorCode.LEASE_NOT_HELD,
                "planId");
        assertFailure(
                adapter.find(plan("missing")),
                PersistenceErrorCode.NOT_FOUND,
                "planId");
        assertEquals(0, time.observations());
        assertEquals(0, leases.count());
    }

    @Test
    void acquireAppliesWithTrustedTimeThenExactlyReplays() {
        seed("plan-1");
        Instant expiry = NOW.plusSeconds(60);

        PersistenceResult<LeaseRecord> applied =
                adapter.acquire(plan("plan-1"), "owner-a", "token-a", expiry);
        PersistenceResult<LeaseRecord> replayed =
                adapter.acquire(plan("plan-1"), "owner-a", "token-a", expiry);

        assertEquals(PersistenceOutcome.APPLIED, applied.outcome());
        assertEquals(PersistenceOutcome.REPLAYED, replayed.outcome());
        assertEquals(applied.value(), replayed.value());
        LeaseRecord record = applied.value().orElseThrow();
        assertEquals(NOW, record.acquiredAt());
        assertEquals(1, record.fencingToken());
        assertEquals(2, time.observations());
        assertEquals(1, leases.count());
    }

    @Test
    void activeLeaseRejectsAnyNonExactContenderBeforeExpiryOrTokenChecks() {
        seed("plan-1");
        acquire("plan-1", "owner-a", "token-a", NOW.plusSeconds(60));

        assertFailure(
                adapter.acquire(plan("plan-1"), "owner-b", "token-b", NOW),
                PersistenceErrorCode.LEASE_HELD,
                "planId");
        assertFailure(
                adapter.acquire(plan("plan-1"), "owner-a", "token-a", NOW.plusSeconds(61)),
                PersistenceErrorCode.LEASE_HELD,
                "planId");
        assertEquals(1, leases.count());
    }

    @Test
    void acquisitionRequiresFutureExpiryAndNeverReusesToken() {
        seed("plan-1");
        seed("plan-2");
        assertFailure(
                adapter.acquire(plan("plan-1"), "owner", "token-a", NOW),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "expiresAt");
        acquire("plan-1", "owner", "token-a", NOW.plusSeconds(10));
        time.set(NOW.plusSeconds(10));
        assertFailure(
                adapter.acquire(
                        plan("plan-2"), "other", "token-a", NOW.plusSeconds(30)),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "leaseToken");
    }

    @Test
    void renewalReplaysOrExtendsWhilePreservingGenerationAndTrustedAcquisition() {
        seed("plan-1");
        LeaseRecord original = acquire(
                "plan-1", "owner", "token-a", NOW.plusSeconds(30));
        time.set(NOW.plusSeconds(5));

        PersistenceResult<LeaseRecord> replayed =
                adapter.renew(plan("plan-1"), "token-a", NOW.plusSeconds(30));
        PersistenceResult<LeaseRecord> renewed =
                adapter.renew(plan("plan-1"), "token-a", NOW.plusSeconds(60));

        assertEquals(PersistenceOutcome.REPLAYED, replayed.outcome());
        assertEquals(PersistenceOutcome.APPLIED, renewed.outcome());
        LeaseRecord value = renewed.value().orElseThrow();
        assertEquals(original.acquiredAt(), value.acquiredAt());
        assertEquals(original.fencingToken(), value.fencingToken());
        assertEquals(NOW.plusSeconds(60), value.expiresAt());
        assertEquals(1, leases.count());
    }

    @Test
    void renewalClassifiesNotHeldWrongTokenExpiredAndNonIncreasingExpiry() {
        seed("plan-1");
        assertFailure(
                adapter.renew(plan("plan-1"), "token", NOW.plusSeconds(30)),
                PersistenceErrorCode.LEASE_NOT_HELD,
                "planId");
        acquire("plan-1", "owner", "token-a", NOW.plusSeconds(30));
        assertFailure(
                adapter.renew(plan("plan-1"), "wrong", NOW.plusSeconds(60)),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "leaseToken");
        assertFailure(
                adapter.renew(plan("plan-1"), "token-a", NOW.plusSeconds(29)),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "expiresAt");
        time.set(NOW.plusSeconds(30));
        assertFailure(
                adapter.renew(plan("plan-1"), "token-a", NOW.plusSeconds(60)),
                PersistenceErrorCode.LEASE_EXPIRED,
                "planId");
    }

    @Test
    void releaseRetainsHistoryAndFindTreatsReleasedGenerationAsMissing() {
        seed("plan-1");
        LeaseRecord held = acquire(
                "plan-1", "owner", "token-a", NOW.plusSeconds(30));
        time.set(NOW.plusSeconds(5));

        PersistenceResult<LeaseRecord> released =
                adapter.release(plan("plan-1"), "token-a");

        assertEquals(PersistenceOutcome.APPLIED, released.outcome());
        assertEquals(held, released.value().orElseThrow());
        assertFailure(adapter.find(plan("plan-1")),
                PersistenceErrorCode.NOT_FOUND, "planId");
        assertFailure(adapter.release(plan("plan-1"), "token-a"),
                PersistenceErrorCode.LEASE_NOT_HELD, "planId");
        assertEquals(1, leases.count());
        assertEquals(NOW.plusSeconds(5),
                leases.findFirstByPlanIdOrderByFencingTokenDesc("plan-1")
                        .orElseThrow().releasedAt());
    }

    @Test
    void releaseRejectsWrongTokenAndExpiredLease() {
        seed("plan-1");
        acquire("plan-1", "owner", "token-a", NOW.plusSeconds(30));
        assertFailure(adapter.release(plan("plan-1"), "wrong"),
                PersistenceErrorCode.LEASE_TOKEN_INVALID, "leaseToken");
        time.set(NOW.plusSeconds(30));
        assertFailure(adapter.release(plan("plan-1"), "token-a"),
                PersistenceErrorCode.LEASE_EXPIRED, "planId");
    }

    @Test
    void findReturnsActiveAndRejectsExpired() {
        seed("plan-1");
        LeaseRecord held = acquire(
                "plan-1", "owner", "token-a", NOW.plusSeconds(30));
        assertEquals(PersistenceOutcome.FOUND,
                adapter.find(plan("plan-1")).outcome());
        assertEquals(held, adapter.find(plan("plan-1")).value().orElseThrow());
        time.set(NOW.plusSeconds(30));
        assertFailure(adapter.find(plan("plan-1")),
                PersistenceErrorCode.LEASE_EXPIRED, "planId");
    }

    @Test
    void releaseAndExpiryReacquisitionsIncrementFenceAndRetainEveryRow() {
        seed("plan-1");
        LeaseRecord first = acquire(
                "plan-1", "owner-a", "token-a", NOW.plusSeconds(10));
        time.set(NOW.plusSeconds(2));
        adapter.release(plan("plan-1"), "token-a");
        LeaseRecord second = acquire(
                "plan-1", "owner-b", "token-b", NOW.plusSeconds(20));
        time.set(NOW.plusSeconds(20));
        LeaseRecord third = acquire(
                "plan-1", "owner-c", "token-c", NOW.plusSeconds(40));

        assertEquals(1, first.fencingToken());
        assertEquals(2, second.fencingToken());
        assertEquals(3, third.fencingToken());
        assertEquals(3, leases.count());
    }

    @Test
    void eachValidOperationObservesExactlyOneEffectiveTime() {
        seed("plan-1");
        int beforeAcquire = time.observations();
        acquire("plan-1", "owner", "token", NOW.plusSeconds(30));
        assertEquals(beforeAcquire + 1, time.observations());

        int beforeFind = time.observations();
        adapter.find(plan("plan-1"));
        assertEquals(beforeFind + 1, time.observations());

        int beforeRenew = time.observations();
        adapter.renew(plan("plan-1"), "token", NOW.plusSeconds(60));
        assertEquals(beforeRenew + 1, time.observations());

        int beforeRelease = time.observations();
        adapter.release(plan("plan-1"), "token");
        assertEquals(beforeRelease + 1, time.observations());
    }

    @Test
    void unrelatedConstraintFailurePropagatesAfterFreshTokenLookup() {
        ProductLeaseTransactions transactions = mock(ProductLeaseTransactions.class);
        ProductLeaseRepositoryAdapter isolated =
                new ProductLeaseRepositoryAdapter(transactions);
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("synthetic unrelated failure");
        when(transactions.acquire(
                plan("plan-1"), "owner", "token", NOW.plusSeconds(10)))
                .thenThrow(failure);
        when(transactions.tokenExists("token")).thenReturn(false);

        assertSame(failure, assertThrows(
                DataIntegrityViolationException.class,
                () -> isolated.acquire(
                        plan("plan-1"), "owner", "token", NOW.plusSeconds(10))));
    }

    @Test
    void rawHibernateTokenConstraintIsClassifiedOnlyAfterFreshLookup() {
        ProductLeaseTransactions transactions = mock(ProductLeaseTransactions.class);
        ProductLeaseRepositoryAdapter isolated =
                new ProductLeaseRepositoryAdapter(transactions);
        var failure = new org.hibernate.exception.ConstraintViolationException(
                "synthetic constraint",
                new SQLException("synthetic"),
                "synthetic sql");
        when(transactions.acquire(
                plan("plan-1"), "owner", "token", NOW.plusSeconds(10)))
                .thenThrow(failure);
        when(transactions.tokenExists("token")).thenReturn(true);

        assertFailure(
                isolated.acquire(
                        plan("plan-1"), "owner", "token", NOW.plusSeconds(10)),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "leaseToken");
    }

    private LeaseRecord acquire(
            String planId, String owner, String token, Instant expiry) {
        PersistenceResult<LeaseRecord> result =
                adapter.acquire(plan(planId), owner, token, expiry);
        assertEquals(PersistenceOutcome.APPLIED, result.outcome());
        return result.value().orElseThrow();
    }

    private void seed(String planId) {
        bootstraps.saveAndFlush(new ProductPlanBootstrapEntity(
                planId,
                "task-" + planId,
                1,
                "0".repeat(64),
                "{}",
                NOW.minusSeconds(1)));
    }

    private static PlanId plan(String value) {
        return new PlanId(value);
    }

    private static void assertFailure(
            PersistenceResult<?> result, PersistenceErrorCode code, String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }
}
