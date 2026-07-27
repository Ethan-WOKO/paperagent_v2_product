package io.paperagent.v2.persistence;

import java.util.Optional;

final class InMemoryIdempotencyRepository implements IdempotencyRepository {
    private final InMemoryState state;

    InMemoryIdempotencyRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<IdempotencyRecord> start(
            IdempotencyKey key,
            String requestFingerprint) {
        if (PersistenceChecks.invalid(key)) {
            return PersistenceChecks.invalid("idempotencyKey");
        }
        if (PersistenceChecks.blank(requestFingerprint)) {
            return PersistenceChecks.invalid("requestFingerprint");
        }
        synchronized (state.monitor) {
            IdempotencyRecord current = state.idempotency.get(key);
            if (current == null) {
                IdempotencyRecord started = new IdempotencyRecord(
                        key,
                        requestFingerprint,
                        IdempotencyState.IN_PROGRESS,
                        Optional.empty());
                state.idempotency.put(key, started);
                return PersistenceResult.applied(started);
            }
            if (!current.requestFingerprint().equals(requestFingerprint)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT,
                        "requestFingerprint");
            }
            return PersistenceResult.replayed(current);
        }
    }

    @Override
    public PersistenceResult<IdempotencyRecord> complete(
            IdempotencyKey key,
            String requestFingerprint,
            Optional<String> resultReference) {
        if (PersistenceChecks.invalid(key)) {
            return PersistenceChecks.invalid("idempotencyKey");
        }
        if (PersistenceChecks.blank(requestFingerprint)) {
            return PersistenceChecks.invalid("requestFingerprint");
        }
        if (resultReference == null
                || resultReference.filter(PersistenceChecks::blank).isPresent()) {
            return PersistenceChecks.invalid("resultReference");
        }
        synchronized (state.monitor) {
            IdempotencyRecord current = state.idempotency.get(key);
            if (current == null) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.IDEMPOTENCY_ILLEGAL_TRANSITION,
                        "idempotencyKey");
            }
            if (!current.requestFingerprint().equals(requestFingerprint)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT,
                        "requestFingerprint");
            }
            if (current.state() == IdempotencyState.COMPLETED) {
                return current.resultReference().equals(resultReference)
                        ? PersistenceResult.replayed(current)
                        : PersistenceResult.rejected(
                                PersistenceErrorCode.IDEMPOTENCY_ILLEGAL_TRANSITION,
                                "resultReference");
            }
            IdempotencyRecord completed = new IdempotencyRecord(
                    key,
                    requestFingerprint,
                    IdempotencyState.COMPLETED,
                    resultReference);
            state.idempotency.put(key, completed);
            return PersistenceResult.applied(completed);
        }
    }

    @Override
    public PersistenceResult<IdempotencyRecord> find(IdempotencyKey key) {
        if (PersistenceChecks.invalid(key)) {
            return PersistenceChecks.invalid("idempotencyKey");
        }
        synchronized (state.monitor) {
            IdempotencyRecord current = state.idempotency.get(key);
            return current == null
                    ? PersistenceChecks.notFound("idempotencyKey")
                    : PersistenceResult.found(current);
        }
    }
}
