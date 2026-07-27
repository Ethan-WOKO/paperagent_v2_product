package io.paperagent.v2.runtime.execution;

public sealed interface FreshExecutionDecision
        permits FreshLeaseAdmissionEligible, RecoveryRequired, BootstrapRejected {
}
