package com.yanban.api.agent.v2.context.runtime;

import java.util.regex.Pattern;

final class V2RuntimeProjectionSafety {
    private static final Pattern PATH = Pattern.compile(
            "(?i)(?:[a-z]:[\\\\/]|file://|/(?:home|users?|var|etc|opt|tmp|workspace|mnt|root|data)(?:/|$))");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(?:bearer\\s+\\S+|sk-[a-z0-9_-]{8,}|(?:api[_ -]?key|password|secret|token)\\s*[:=]\\s*\\S+)");

    private V2RuntimeProjectionSafety() { }

    static String required(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || PATH.matcher(value).find() || SECRET.matcher(value).find()) {
            throw new IllegalArgumentException(field + " is unsafe or invalid");
        }
        return value.trim();
    }

    static String optional(String value, String field, int maximum) {
        return value == null ? null : required(value, field, maximum);
    }
}
