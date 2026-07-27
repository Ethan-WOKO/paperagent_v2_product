package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.ToolCallId;

public interface EffectIntentRepository {
    PersistenceResult<PersistedEffectIntent> persist(EffectIntentRequest request);

    PersistenceResult<PersistedEffectIntent> find(ToolCallId toolCallId);
}
