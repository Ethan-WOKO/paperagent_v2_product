package io.paperagent.v2.sandbox;

/**
 * Explicit protocol bounds for provider-neutral Sandbox values.
 */
public final class SandboxLimits {
    public static final int MAX_ARGUMENT_COUNT = 256;
    public static final int MAX_ARGUMENT_TEXT_LENGTH = 8_192;
    public static final int MAX_ENVIRONMENT_ENTRIES = 128;
    public static final int MAX_ENVIRONMENT_NAME_LENGTH = 128;
    public static final int MAX_ENVIRONMENT_VALUE_LENGTH = 8_192;
    public static final int MAX_SECRET_ENVIRONMENT_BINDINGS = 64;
    public static final int MAX_METADATA_ENTRIES = 64;
    public static final int MAX_METADATA_KEY_LENGTH = 128;
    public static final int MAX_METADATA_VALUE_LENGTH = 4_096;

    private SandboxLimits() {
    }
}
