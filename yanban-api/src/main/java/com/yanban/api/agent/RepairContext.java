package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** Small model-visible description of one failed governed tool attempt. */
public record RepairContext(
        String failedTool,
        JsonNode arguments,
        String errorCode,
        String errorMessage,
        boolean retryable,
        int remainingAttempts
) {
    private static final int MAX_DEPTH = 4;
    private static final int MAX_FIELDS = 24;
    private static final int MAX_STRING = 400;
    private static final int MAX_ERROR = 600;
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(authorization|api.?key|token|secret|password|credential|cookie).*");
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)(authorization|api[_-]?key|token|secret|password)\\s*[:=]\\s*\\S+");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer)\\s+[^\\s\\\"']+");
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile(
            "(?i)\\b[A-Z]:\\\\(?:[^\\s\\\\]+\\\\)*[^\\s]*");
    private static final Pattern UNIX_ABSOLUTE = Pattern.compile(
            "(?<![A-Za-z0-9_.-])/(?:[^\\s/:]+/)+[^\\s:]*");

    public RepairContext {
        if (!StringUtils.hasText(failedTool)) {
            throw new IllegalArgumentException("repair context requires failedTool");
        }
        arguments = arguments == null ? new ObjectMapper().createObjectNode() : arguments.deepCopy();
        errorCode = safeCode(errorCode);
        errorMessage = sanitizeText(errorMessage, MAX_ERROR, "tool_failed");
        remainingAttempts = Math.max(0, Math.min(1, remainingAttempts));
        retryable = retryable && remainingAttempts > 0;
    }

    public static RepairContext create(ObjectMapper mapper,
                                       String failedTool,
                                       String rawArguments,
                                       String errorCode,
                                       String errorMessage,
                                       boolean retryable,
                                       int remainingAttempts) {
        JsonNode parsed;
        try {
            parsed = mapper.readTree(StringUtils.hasText(rawArguments) ? rawArguments : "{}");
        } catch (Exception ignored) {
            parsed = mapper.createObjectNode();
        }
        return new RepairContext(failedTool, sanitizeNode(mapper, parsed, 0), errorCode, errorMessage,
                retryable, remainingAttempts);
    }

    public RepairContext withRemainingAttempts(int attempts) {
        return new RepairContext(failedTool, arguments, errorCode, errorMessage, retryable, attempts);
    }

    public String signature(ObjectMapper mapper) {
        try {
            return failedTool + "|" + mapper.writeValueAsString(canonicalize(mapper, arguments));
        } catch (Exception ignored) {
            return failedTool + "|{}";
        }
    }

    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (Exception ignored) {
            return "{\"failedTool\":\"tool\",\"arguments\":{},\"errorCode\":\"INTERNAL_ERROR\","
                    + "\"errorMessage\":\"tool_failed\",\"retryable\":false,\"remainingAttempts\":0}";
        }
    }

    public String toModelToolResult(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("success", false);
        root.set("repairContext", mapper.valueToTree(this));
        return root.toString();
    }

    public String toFallback(ObjectMapper mapper) {
        return "tool_repair_context:" + toJson(mapper);
    }

    public static RepairContext fromFallbacks(ObjectMapper mapper, List<String> fallbacks) {
        if (fallbacks == null) return null;
        for (int index = fallbacks.size() - 1; index >= 0; index--) {
            String value = fallbacks.get(index);
            if (!StringUtils.hasText(value) || !value.startsWith("tool_repair_context:")) continue;
            try {
                return mapper.readValue(value.substring("tool_repair_context:".length()), RepairContext.class);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    static JsonNode sanitizeArguments(ObjectMapper mapper, String rawArguments) {
        try {
            return sanitizeNode(mapper, mapper.readTree(StringUtils.hasText(rawArguments) ? rawArguments : "{}"), 0);
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    private static JsonNode sanitizeNode(ObjectMapper mapper, JsonNode node, int depth) {
        if (node == null || node.isNull()) return mapper.nullNode();
        if (depth >= MAX_DEPTH) return mapper.getNodeFactory().textNode("<truncated>");
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            int count = 0;
            while (fields.hasNext() && count++ < MAX_FIELDS) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), SENSITIVE_KEY.matcher(field.getKey()).matches()
                        ? mapper.getNodeFactory().textNode("<redacted>")
                        : sanitizeNode(mapper, field.getValue(), depth + 1));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            for (int index = 0; index < Math.min(node.size(), MAX_FIELDS); index++) {
                result.add(sanitizeNode(mapper, node.get(index), depth + 1));
            }
            return result;
        }
        if (node.isTextual()) {
            return mapper.getNodeFactory().textNode(sanitizeText(node.asText(), MAX_STRING, ""));
        }
        return node.deepCopy();
    }

    private static JsonNode canonicalize(ObjectMapper mapper, JsonNode node) {
        if (node == null || node.isValueNode()) return node;
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(value -> result.add(canonicalize(mapper, value)));
            return result;
        }
        ObjectNode result = mapper.createObjectNode();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.stream().sorted().forEach(name -> result.set(name, canonicalize(mapper, node.get(name))));
        return result;
    }

    private static String safeCode(String value) {
        if (!StringUtils.hasText(value)) return "INTERNAL_ERROR";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9_]{1,64}") ? normalized : "INTERNAL_ERROR";
    }

    private static String sanitizeText(String value, int max, String fallback) {
        String sanitized = StringUtils.hasText(value) ? value : fallback;
        sanitized = BEARER.matcher(sanitized).replaceAll("$1 <redacted>");
        sanitized = SECRET_VALUE.matcher(sanitized).replaceAll("$1=<redacted>");
        sanitized = WINDOWS_ABSOLUTE.matcher(sanitized).replaceAll("<host-path>");
        sanitized = UNIX_ABSOLUTE.matcher(sanitized).replaceAll("<host-path>");
        sanitized = sanitized.replaceAll("[\\r\\n\\t]+", " ").trim();
        return sanitized.length() <= max ? sanitized : sanitized.substring(0, max) + "...";
    }
}
