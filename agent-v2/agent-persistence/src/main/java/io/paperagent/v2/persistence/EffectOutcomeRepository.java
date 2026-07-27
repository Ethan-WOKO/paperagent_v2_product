package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.ToolCallId;

import java.util.List;

/**
 * Persistence authority for durable progress and a final receipt of one effect.
 */
public interface EffectOutcomeRepository {
    PersistenceResult<PersistedEffectProgress> appendProgress(
            EffectProgressRequest request);

    PersistenceResult<List<PersistedEffectProgress>> readProgress(
            ToolCallId toolCallId);

    PersistenceResult<PersistedEffectResult> recordResult(
            EffectResultRequest request);

    PersistenceResult<PersistedEffectResult> findResult(
            ToolCallId toolCallId);
}
