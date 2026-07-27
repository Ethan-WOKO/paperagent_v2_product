package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;

public sealed interface StepRecoverySnapshot
        permits PersistedStepRecoveryActive {
    PlanId planId();
}
