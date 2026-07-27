package io.paperagent.v2.persistence;

final class PersistenceChecks {
    private PersistenceChecks() {
    }

    static boolean missing(Object value) {
        return value == null;
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static boolean invalidIdentifier(String value) {
        return value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    static boolean invalid(IdempotencyKey key) {
        return key == null
                || invalidIdentifier(key.scope())
                || invalidIdentifier(key.key());
    }

    static <T> PersistenceResult<T> invalid(String path) {
        return PersistenceResult.rejected(PersistenceErrorCode.INVALID_ARGUMENT, path);
    }

    static <T> PersistenceResult<T> notFound(String path) {
        return PersistenceResult.rejected(PersistenceErrorCode.NOT_FOUND, path);
    }
}
