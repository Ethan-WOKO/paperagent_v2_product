package io.paperagent.v2.chain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValidationSourceContractTest {
    @Test
    void stepResultCarriesExactRequirementToReceiptMapping() {
        var source = new ProposalFields.ValidationSource(
                "requirement-1", "receipt-1");
        var result = stepResult(
                List.of("receipt-1"), List.of(source));

        assertEquals(List.of(source), result.validationSources());
    }

    @Test
    void rejectsDuplicateRequirementOrUnlistedReceipt() {
        var source = new ProposalFields.ValidationSource(
                "requirement-1", "receipt-1");
        assertThrows(IllegalArgumentException.class, () -> stepResult(
                List.of("receipt-1"), List.of(source, source)));
        assertThrows(IllegalArgumentException.class, () -> stepResult(
                List.of(), List.of(source)));
    }

    private static ExecutorPayload.StepResult stepResult(
            List<String> receipts,
            List<ProposalFields.ValidationSource> sources) {
        return new ExecutorPayload.StepResult(
                List.of(new ProposalFields.RequirementCoverage(
                        "condition", ProposalFields.RequirementStatus.SATISFIED,
                        List.of("receipt-1"))),
                "body", List.of(), null, receipts, sources, List.of(),
                List.of(), List.of(), null);
    }
}
