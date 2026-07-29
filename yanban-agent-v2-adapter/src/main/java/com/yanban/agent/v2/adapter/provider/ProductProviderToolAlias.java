package com.yanban.agent.v2.adapter.provider;

import io.paperagent.v2.contracts.ToolId;

/** One provider-facing alias for a stable internal V2 ToolId. */
final class ProductProviderToolAlias {
    private static final int MAX_LENGTH = 64;

    private ProductProviderToolAlias() {
    }

    static String from(ToolId toolId) {
        if (toolId == null) {
            throw invalid();
        }
        StringBuilder safe = new StringBuilder();
        for (char character : toolId.value().toCharArray()) {
            safe.append(isAllowed(character) ? character : '_');
        }
        String value = safe.toString();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw invalid();
        }
        return value;
    }

    private static boolean isAllowed(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '_'
                || character == '-';
    }

    private static ProductStepTurnException invalid() {
        return new ProductStepTurnException(
                ProductStepTurnError.INVALID_CONFIGURATION,
                "productProviderToolAlias");
    }
}
