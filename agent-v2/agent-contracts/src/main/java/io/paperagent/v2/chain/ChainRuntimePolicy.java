package io.paperagent.v2.chain;

public record ChainRuntimePolicy(
        String policyVersion,
        int providerAttemptsTotal,
        int modelInvocationsPerContextTotal,
        int protocolRepairAttemptsTotal,
        int deliveryAttemptsTotal,
        int finalizationMechanicalAttemptsTotal,
        int sameActionSignatureOccurrencesMax,
        int noProgressThreshold,
        int contextRequestCharactersMax,
        int contextBodyPageItemsMax) {

    public static final ChainRuntimePolicy V1 = new ChainRuntimePolicy(
            "chain-runtime-policy-v1", 3, 2, 2, 3, 2, 2, 3,
            1_000_000, 1_000);

    /** Current policy for a newly created Task; existing Tasks resolve by ID. */
    public static ChainRuntimePolicy current() {
        return V1;
    }

    /** Resolves an immutable policy version saved by a chain authority fact. */
    public static ChainRuntimePolicy requireVersion(String policyVersion) {
        String required = ChainValues.required(
                policyVersion, "policyVersion");
        if (V1.policyVersion().equals(required)) return V1;
        throw new IllegalArgumentException(
                "unsupported chain runtime policy version: " + required);
    }

    public ChainRuntimePolicy {
        policyVersion = ChainValues.required(policyVersion, "policyVersion");
        requirePositive(providerAttemptsTotal, "providerAttemptsTotal");
        requirePositive(modelInvocationsPerContextTotal,
                "modelInvocationsPerContextTotal");
        requirePositive(protocolRepairAttemptsTotal, "protocolRepairAttemptsTotal");
        requirePositive(deliveryAttemptsTotal, "deliveryAttemptsTotal");
        requirePositive(finalizationMechanicalAttemptsTotal, "finalizationMechanicalAttemptsTotal");
        requirePositive(sameActionSignatureOccurrencesMax, "sameActionSignatureOccurrencesMax");
        requirePositive(noProgressThreshold, "noProgressThreshold");
        requirePositive(contextRequestCharactersMax,
                "contextRequestCharactersMax");
        requirePositive(contextBodyPageItemsMax,
                "contextBodyPageItemsMax");
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
