package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PlanExecutionContextConfirmationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextReservationRequest;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.StepActivationRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepActivationCompositionIntegrationTest {
    @Test
    void realPersistenceAppliesThenReplaysExactActivation() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("integration-replay", false);
        DefaultStepActivationComposer composer =
                StepActivationCompositionTestFixtures.composer(seeded.persistence());

        StepActivationCommitted applied = assertInstanceOf(
                StepActivationCommitted.class, composer.compose(seeded.request()));
        StepActivationCommitted replayed = assertInstanceOf(
                StepActivationCommitted.class, composer.compose(seeded.request()));
        assertEquals(PersistenceOutcome.APPLIED, applied.activationOutcome());
        assertEquals(PersistenceOutcome.REPLAYED, replayed.activationOutcome());
        assertEquals(applied.persistedActivation(), replayed.persistedActivation());
    }

    @Test
    void staleH0AndSourceBackedMissingContextRemainPersistenceAuthority() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("integration-stale", false);
        DefaultStepActivationComposer composer =
                StepActivationCompositionTestFixtures.composer(seeded.persistence());
        assertInstanceOf(StepActivationCommitted.class, composer.compose(seeded.request()));
        StepActivationCompositionOutcome stale = composer.compose(
                new StepActivationCompositionRequest(
                        seeded.committed(),
                        seeded.request().stepId(),
                        new StepActivationAttempt(
                                seeded.request().attempt().leaseOwnerId(),
                                seeded.request().attempt().leaseToken(),
                                seeded.request().attempt().leaseExpiresAt(),
                                StepActivationCompositionTestFixtures.draft("integration-stale-other"),
                                seeded.request().attempt().checkpointCreatedAt())));
        StepActivationPersistenceRejected rejected = assertInstanceOf(
                StepActivationPersistenceRejected.class, stale);
        assertEquals(PersistenceErrorCode.STALE_VERSION, rejected.failure().code());
        assertEquals("request.expectedCheckpointVersion", rejected.failure().path());

        StepActivationCompositionTestFixtures.Seeded source =
                StepActivationCompositionTestFixtures.seeded("integration-source", true);
        StepActivationPersistenceRejected sourceRejected = assertInstanceOf(
                StepActivationPersistenceRejected.class,
                StepActivationCompositionTestFixtures.composer(source.persistence())
                        .compose(source.request()));
        assertEquals(PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                sourceRejected.failure().code());
        assertEquals("stepActivation.source", sourceRejected.failure().path());
    }

    @Test
    void concurrentDifferentPlansAreBoundedAndProduceOneResultEach() throws Exception {
        StepActivationCompositionTestFixtures.Seeded first =
                StepActivationCompositionTestFixtures.seeded("integration-concurrent-a", false);
        StepActivationCompositionTestFixtures.Seeded second =
                StepActivationCompositionTestFixtures.seeded("integration-concurrent-b", false);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var firstFuture = executor.submit(() -> {
                start.await();
                return StepActivationCompositionTestFixtures.composer(first.persistence())
                        .compose(first.request());
            });
            var secondFuture = executor.submit(() -> {
                start.await();
                return StepActivationCompositionTestFixtures.composer(second.persistence())
                        .compose(second.request());
            });
            start.countDown();
            assertTrue(firstFuture.get(5, TimeUnit.SECONDS)
                    instanceof StepActivationCommitted);
            assertTrue(secondFuture.get(5, TimeUnit.SECONDS)
                    instanceof StepActivationCommitted);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void confirmedSourceBackedContextAllowsActivation() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded(
                        "integration-source-confirmed", true);
        confirmSourceContext(seeded);

        StepActivationCommitted committed = assertInstanceOf(
                StepActivationCommitted.class,
                StepActivationCompositionTestFixtures.composer(seeded.persistence())
                        .compose(seeded.request()));
        assertEquals(PersistenceOutcome.APPLIED, committed.activationOutcome());
        assertEquals(StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY,
                committed.leaseDisposition());
    }

    @Test
    void concurrentExactSamePlanConvergesAppliedAndReplayed() throws Exception {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded(
                        "integration-concurrent-same", false);
        DefaultStepActivationComposer composer =
                StepActivationCompositionTestFixtures.composer(seeded.persistence());
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                start.await();
                return composer.compose(seeded.request());
            });
            var second = executor.submit(() -> {
                start.await();
                return composer.compose(seeded.request());
            });
            start.countDown();
            StepActivationCommitted firstResult = assertInstanceOf(
                    StepActivationCommitted.class,
                    first.get(5, TimeUnit.SECONDS));
            StepActivationCommitted secondResult = assertInstanceOf(
                    StepActivationCommitted.class,
                    second.get(5, TimeUnit.SECONDS));
            assertEquals(
                    Set.of(PersistenceOutcome.APPLIED, PersistenceOutcome.REPLAYED),
                    Set.of(firstResult.activationOutcome(),
                            secondResult.activationOutcome()));
            assertEquals(firstResult.persistedActivation(),
                    secondResult.persistedActivation());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void confirmSourceContext(
            StepActivationCompositionTestFixtures.Seeded seeded) {
        var committed = seeded.committed();
        var attempt = seeded.request().attempt();
        LeaseRecord lease = seeded.persistence().leases().acquire(
                committed.planId(),
                attempt.leaseOwnerId(),
                attempt.leaseToken(),
                attempt.leaseExpiresAt()).value().orElseThrow();
        var source = committed.bootstrap().taskFrame()
                .sourceProjectVersion().orElseThrow();
        WorkspaceMaterializationSpec spec = new WorkspaceMaterializationSpec(
                new WorkspaceId("workspace-integration-source-confirmed"),
                source,
                new WorkspaceMaterializationLimits(1024, 8192, 8));
        var revision = committed.currentPlan().latestRevision();
        var reserved = seeded.persistence().planExecutionContexts().reserve(
                new PlanExecutionContextReservationRequest(
                        committed.planId(),
                        attempt.leaseToken(),
                        lease.fencingToken(),
                        revision.id(),
                        revision.number(),
                        committed.executionStart().startedCheckpoint().version(),
                        committed.executionStart().startEvent().sequence(),
                        spec));
        assertEquals(PersistenceOutcome.APPLIED, reserved.outcome());
        var confirmed = seeded.persistence().planExecutionContexts().confirm(
                new PlanExecutionContextConfirmationRequest(
                        committed.planId(),
                        attempt.leaseToken(),
                        lease.fencingToken(),
                        spec,
                        new ContentHash("sha256", "a".repeat(64))));
        assertEquals(PersistenceOutcome.APPLIED, confirmed.outcome());
    }
}
