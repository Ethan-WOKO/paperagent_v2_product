package io.paperagent.v2.runtime.checkpoint;

final class InitialCheckpointFreezeValues {
    private InitialCheckpointFreezeValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            fail(
                    InitialCheckpointFreezeValidationCode.REQUIRED_VALUE_MISSING,
                    path,
                    "value is required");
        }
        return value;
    }

    static void fail(
            InitialCheckpointFreezeValidationCode code,
            String path,
            String message) {
        throw new InitialCheckpointFreezeValidationException(code, path, message);
    }
}
