package com.yanban.agent.v2.adapter.taskframe;

/**
 * Stable failures for inconsistent product-side facts.
 */
public enum ProductTaskFrameValidationCode {
    REQUIRED_VALUE_MISSING,
    NON_POSITIVE_ID,
    PROJECT_VERSION_MISSING,
    PROJECT_VERSION_UNEXPECTED,
    PROJECT_VERSION_BLANK
}
