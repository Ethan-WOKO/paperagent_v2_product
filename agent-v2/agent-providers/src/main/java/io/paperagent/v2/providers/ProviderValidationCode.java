package io.paperagent.v2.providers;

/**
 * Stable machine-readable validation codes for provider-neutral values.
 */
public enum ProviderValidationCode {
    REQUIRED_VALUE_MISSING,
    REQUIRED_TEXT_BLANK,
    REQUIRED_COLLECTION_EMPTY,
    INVALID_ID,
    NULL_COLLECTION_ELEMENT,
    DUPLICATE_ID,
    INVALID_GENERATION_OPTIONS,
    INVALID_USAGE_COUNT,
    INVALID_RESPONSE_COMBINATION,
    INCONSISTENT_REFERENCE,
    INVALID_SCRIPT
}
