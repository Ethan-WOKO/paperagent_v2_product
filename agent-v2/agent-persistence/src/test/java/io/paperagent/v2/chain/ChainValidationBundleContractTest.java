package io.paperagent.v2.chain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainValidationBundleContractTest {
    private static final String HASH = "1".repeat(64);

    @Test
    void bundlePortAndRecordsAreTypedAndBodyFree() throws Exception {
        assertTrue(ChainValidationBundleRepository.class.isInterface());
        assertEquals(java.util.Optional.class,
                ChainValidationBundleRepository.class.getMethod(
                        "findBundle", String.class).getReturnType());
        assertEquals(java.util.List.class,
                ChainValidationBundleRepository.class.getMethod(
                        "findBundleSets", String.class).getReturnType());

        Set<String> bundle = names(
                ChainPersistenceRecords.ValidationBundleRecord.class);
        Set<String> member = names(
                ChainPersistenceRecords.ValidationBundleSetRecord.class);
        assertTrue(bundle.containsAll(Set.of(
                "validationBundleId", "instructionId", "finalStepId",
                "requestDigest", "receiptSetDigest", "conclusionDigest")));
        assertTrue(member.containsAll(Set.of(
                "stepId", "activationEventId", "validationId",
                "validationRequestDigest", "validationReceiptSetDigest",
                "validationConclusionDigest")));
        for (String forbidden : Set.of(
                "receiptJson", "stdout", "stderr", "requirements")) {
            assertFalse(bundle.contains(forbidden));
            assertFalse(member.contains(forbidden));
        }
    }

    @Test
    void publicSetReferenceRejectsUnfrozenDigests() {
        new ChainValidationBundleSetRef(
                "step", "activation", "validation", HASH, HASH, HASH);
        assertThrows(IllegalArgumentException.class, () ->
                new ChainValidationBundleSetRef(
                        "step", "activation", "validation",
                        "not-a-hash", HASH, HASH));
    }

    private static Set<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
    }
}
