package io.paperagent.v2.persistence;

public interface ExecutionStartRepository {
    PersistenceResult<PersistedExecutionStart> start(ExecutionStartRequest request);
}
