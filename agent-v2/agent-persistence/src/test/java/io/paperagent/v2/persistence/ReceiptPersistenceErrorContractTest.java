package io.paperagent.v2.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReceiptPersistenceErrorContractTest {

    private static final List<String> EXPECTED_ERROR_CODES = List.of(
            "INVALID_ARGUMENT",
            "NOT_FOUND",
            "CONFLICTING_REPLAY",
            "BOOTSTRAP_PARTIAL_STATE",
            "STALE_VERSION",
            "TASK_FRAME_MISMATCH",
            "PLAN_VALIDATION_FAILED",
            "EVENT_SEQUENCE_NOT_MONOTONIC",
            "CHECKPOINT_VALIDATION_FAILED",
            "LEASE_HELD",
            "LEASE_NOT_HELD",
            "LEASE_TOKEN_INVALID",
            "LEASE_FENCING_TOKEN_INVALID",
            "LEASE_EXPIRED",
            "EXECUTION_START_PARTIAL_STATE",
            "EXECUTION_RECOVERY_PARTIAL_STATE",
            "EXECUTION_RECOVERY_ADVANCED_STATE",
            "STEP_RECOVERY_PARTIAL_STATE",
            "STEP_RECOVERY_NOT_ELIGIBLE",
            "PLAN_EXECUTION_CONTEXT_PARTIAL_STATE",
            "PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE",
            "STEP_ACTIVATION_PARTIAL_STATE",
            "STEP_ACTIVATION_NOT_ELIGIBLE",
            "STEP_COMPLETION_PARTIAL_STATE",
            "STEP_COMPLETION_NOT_ELIGIBLE",
            "STEP_INTERRUPTION_PARTIAL_STATE",
            "STEP_INTERRUPTION_NOT_ELIGIBLE",
            "PLAN_REPLAN_PARTIAL_STATE",
            "PLAN_REPLAN_NOT_ELIGIBLE",
            "ACTIVE_STEP_REPLAN_PARTIAL_STATE",
            "ACTIVE_STEP_REPLAN_NOT_ELIGIBLE",
            "EFFECT_INTENT_PARTIAL_STATE",
            "EFFECT_OUTCOME_PARTIAL_STATE",
            "EFFECT_PROGRESS_OUT_OF_SEQUENCE",
            "EFFECT_OUTCOME_FINALIZED",
            "EFFECT_RECEIPT_OWNERSHIP_REQUIRED",
            "EXECUTION_MUTATION_REQUIRES_FENCE",
            "IDEMPOTENCY_FINGERPRINT_CONFLICT",
            "IDEMPOTENCY_ILLEGAL_TRANSITION",
            "RECEIPT_PARTIAL_STATE");

    @Test
    void exposesExactlyTheFrozenErrorCodeSurfaceWithoutReorderingExistingMembers() {
        assertEquals(
                EXPECTED_ERROR_CODES,
                Arrays.stream(PersistenceErrorCode.values())
                        .map(Enum::name)
                        .toList());
        assertEquals(
                PersistenceErrorCode.RECEIPT_PARTIAL_STATE,
                PersistenceErrorCode.valueOf("RECEIPT_PARTIAL_STATE"));
    }

    @Test
    void exposesTheReceiptPartialStateCodeExactlyOnce() {
        long occurrences = Arrays.stream(PersistenceErrorCode.values())
                .filter(code -> code.name().equals("RECEIPT_PARTIAL_STATE"))
                .count();

        assertEquals(1L, occurrences);
    }

    @Test
    void separatesOrdinaryReceiptCorruptionFromEffectCorruptionAndOwnershipRejection() {
        PersistenceErrorCode receiptPartial =
                PersistenceErrorCode.RECEIPT_PARTIAL_STATE;
        PersistenceErrorCode effectOutcomePartial =
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE;
        PersistenceErrorCode ownershipRequired =
                PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED;

        assertEquals(3, Set.of(
                        receiptPartial,
                        effectOutcomePartial,
                        ownershipRequired)
                .size());
        assertNotEquals(receiptPartial, effectOutcomePartial);
        assertNotEquals(receiptPartial, ownershipRequired);
        assertTrue(receiptPartial.name().startsWith("RECEIPT_"));
        assertTrue(effectOutcomePartial.name().startsWith("EFFECT_OUTCOME_"));
        assertTrue(ownershipRequired.name().endsWith("OWNERSHIP_REQUIRED"));
    }
}
