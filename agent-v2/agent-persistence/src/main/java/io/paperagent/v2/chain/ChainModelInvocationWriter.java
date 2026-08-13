package io.paperagent.v2.chain;

public interface ChainModelInvocationWriter {
    ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.ModelInvocationRecord> appendInvocation(
            ChainPersistenceRecords.ModelInvocationRecord invocation);

    ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.ProviderAttemptRecord> appendProviderAttempt(
            ChainPersistenceRecords.ProviderAttemptRecord attempt);
}
