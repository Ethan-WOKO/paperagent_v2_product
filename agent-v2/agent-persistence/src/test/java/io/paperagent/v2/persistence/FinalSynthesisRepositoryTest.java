package io.paperagent.v2.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.FinalSynthesisId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.TaskFrameId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FinalSynthesisRepositoryTest {
    @Test
    void appliesReplaysAndRejectsConflictingPlanDelivery() {
        var repository = new InMemoryFinalSynthesisRepository();
        FinalSynthesis first = synthesis("one", "text");

        assertEquals(PersistenceOutcome.APPLIED,
                repository.append(first).outcome());
        assertEquals(PersistenceOutcome.REPLAYED,
                repository.append(first).outcome());
        assertEquals(PersistenceOutcome.FOUND,
                repository.find(first.planId()).outcome());
        assertEquals(PersistenceOutcome.REJECTED,
                repository.append(synthesis("two", "changed")).outcome());
    }

    private static FinalSynthesis synthesis(String id, String narrative) {
        return new FinalSynthesis(
                new FinalSynthesisId("synthesis-" + id),
                new TaskFrameId("task-frame"),
                new PlanId("plan"),
                new PlanRevisionId("revision"),
                Optional.empty(),
                Optional.empty(),
                List.of(new ReceiptId("receipt")),
                narrative,
                Instant.parse("2026-07-28T00:00:00Z"));
    }
}
