package com.yanban.api.agent.v2.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.persistence.V2EffectHistorySource;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import({
        V2StepResultService.class,
        V2StepResultServiceH2Test.Config.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class V2StepResultServiceH2Test {
    @Autowired
    V2StepResultService results;
    @Autowired
    StepRecoveryRepository recoveries;
    @Autowired
    V2StepResultJpaRepository repository;
    @Autowired
    V2EffectHistorySource effectHistory;

    private final PlanId planId = new PlanId("plan-result-test");
    private final PlanStepId stepId = new PlanStepId("step-result-test");

    @BeforeEach
    void activeAuthority() {
        repository.deleteAll();
        PersistedStepRecoveryActive active = active(
                "revision-result-test", "activation-result-test",
                Map.of());
        when(recoveries.inspect(planId))
                .thenReturn(PersistenceResult.found(active));
        V2EffectHistorySource.Entry entry =
                mock(V2EffectHistorySource.Entry.class);
        PersistedEffectResult effectResult =
                mock(PersistedEffectResult.class);
        io.paperagent.v2.contracts.ExecutionReceipt receipt =
                mock(io.paperagent.v2.contracts.ExecutionReceipt.class);
        when(entry.completed()).thenReturn(true);
        when(entry.result()).thenReturn(effectResult);
        when(effectResult.receipt()).thenReturn(receipt);
        when(receipt.id()).thenReturn(new ReceiptId("receipt-read"));
        when(effectHistory.inspect(planId, stepId))
                .thenReturn(List.of(entry));
    }

    @Test
    void proposalReplayAndAcceptanceAreDurableAndImmutable() {
        var proposal = results.proposeCurrent(
                planId, stepId, V2StepResultSource.MODEL,
                "The code generates all permutations.",
                List.of(new ReceiptId("receipt-read")));
        var replay = results.proposeCurrent(
                planId, stepId, V2StepResultSource.MODEL,
                "The code generates all permutations.",
                List.of(new ReceiptId("receipt-read")));

        assertEquals(proposal.resultId(), replay.resultId());
        assertEquals(1, repository.count());
        assertEquals(proposal.resultId(),
                results.recoverableForActive(
                        recoveredActive()).orElseThrow().resultId());

        var accepted = results.accept(
                proposal.resultId(),
                "The code generates all permutations.");
        assertEquals(V2StepResultStatus.ACCEPTED, accepted.status());
        assertEquals(
                "The code generates all permutations.",
                accepted.acceptedText().orElseThrow());
        assertEquals(
                accepted.resultId(),
                results.acceptedForActive(
                        (PersistedStepRecoveryActive) recoveries
                                .inspect(planId).value().orElseThrow())
                        .orElseThrow().resultId());
        assertEquals(V2StepResultStatus.ACCEPTED,
                results.recoverableForActive(
                        recoveredActive()).orElseThrow().status());

        results.accept(
                proposal.resultId(),
                "The code generates all permutations.");
        assertThrows(IllegalStateException.class,
                () -> results.accept(
                        proposal.resultId(), "rewritten result"));
        assertEquals(V2StepResultStatus.ACCEPTED,
                results.reject(proposal.resultId(), "late rejection")
                        .status());
    }

    @Test
    void proposalCannotBeAcceptedAfterAuthorityChanges() {
        var proposal = results.proposeCurrent(
                planId, stepId, V2StepResultSource.MODEL,
                "reasoning result", List.of());
        PersistedStepRecoveryActive changed = active(
                "revision-new", "activation-new", Map.of());
        when(recoveries.inspect(planId)).thenReturn(
                PersistenceResult.found(changed));

        assertThrows(IllegalStateException.class,
                () -> results.accept(
                        proposal.resultId(), "reasoning result"));
    }

    @Test
    void proposalRejectsReceiptOutsideTheCurrentStepHistory() {
        assertThrows(IllegalStateException.class,
                () -> results.proposeCurrent(
                        planId, stepId, V2StepResultSource.MODEL,
                        "untrusted result",
                        List.of(new ReceiptId("receipt-other-step"))));
        assertEquals(0, repository.count());
    }

    private PersistedStepRecoveryActive active(
            String revisionId, String activationId,
            Map<PlanStepId, io.paperagent.v2.contracts.CompletionFact>
                    completed) {
        PersistedStepRecoveryActive active =
                mock(PersistedStepRecoveryActive.class);
        Plan plan = mock(Plan.class);
        PlanRevision revision = mock(PlanRevision.class);
        PersistedStepActivation activation =
                mock(PersistedStepActivation.class);
        EventEnvelope event = mock(EventEnvelope.class);
        when(active.planId()).thenReturn(planId);
        when(active.plan()).thenReturn(plan);
        when(active.activation()).thenReturn(activation);
        when(plan.latestRevision()).thenReturn(revision);
        when(revision.id()).thenReturn(new PlanRevisionId(revisionId));
        when(revision.completedFacts()).thenReturn(completed);
        when(activation.stepId()).thenReturn(stepId);
        when(activation.activationEvent()).thenReturn(event);
        when(event.id()).thenReturn(new EventId(activationId));
        return active;
    }

    private io.paperagent.v2.runtime.execution.recovery.composition
            .RecoveredActiveStep recoveredActive() {
        var recovered = mock(io.paperagent.v2.runtime.execution.recovery
                .composition.RecoveredActiveStep.class);
        var inspected = recoveries.inspect(planId).value().orElseThrow();
        when(recovered.planId()).thenReturn(planId);
        when(recovered.recovery()).thenReturn(
                (PersistedStepRecoveryActive) inspected);
        return recovered;
    }

    @TestConfiguration
    static class Config {
        @Bean
        StepRecoveryRepository stepRecoveryRepository() {
            return mock(StepRecoveryRepository.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        V2EffectHistorySource effectHistorySource() {
            return mock(V2EffectHistorySource.class);
        }
    }
}
