package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

final class ReactPlanCanonicalJson {
    private ReactPlanCanonicalJson() { }

    static String digest(ObjectMapper json, Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(sort(json.valueToTree(value)))
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("canonical request digest failed", impossible);
        }
    }

    static String sha256Utf8(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static JsonNode sort(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            Map<String, JsonNode> fields = new TreeMap<>(Comparator.naturalOrder());
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), sort(entry.getValue())));
            fields.forEach(result::set);
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> result.add(sort(item)));
            return result;
        }
        return value;
    }
}
