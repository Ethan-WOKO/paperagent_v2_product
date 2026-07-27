package io.paperagent.v2.persistence;

import java.util.Optional;

public interface IdempotencyRepository {
    PersistenceResult<IdempotencyRecord> start(IdempotencyKey key, String requestFingerprint);

    PersistenceResult<IdempotencyRecord> complete(
            IdempotencyKey key,
            String requestFingerprint,
            Optional<String> resultReference);

    PersistenceResult<IdempotencyRecord> find(IdempotencyKey key);
}
