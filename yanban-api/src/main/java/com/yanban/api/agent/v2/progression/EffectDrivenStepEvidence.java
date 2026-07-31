package com.yanban.api.agent.v2.progression;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.persistence.PersistedEffectIntent;

record EffectDrivenStepEvidence(
        PersistedEffectIntent intent,
        ExecutionReceipt receipt) {
    EffectDrivenStepEvidence {
        java.util.Objects.requireNonNull(intent, "intent");
        java.util.Objects.requireNonNull(receipt, "receipt");
    }
}
