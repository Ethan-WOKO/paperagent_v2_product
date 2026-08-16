package com.yanban.api.agent.reactplan;

final class ReactPlanValues {
    private ReactPlanValues() {
    }

    static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
