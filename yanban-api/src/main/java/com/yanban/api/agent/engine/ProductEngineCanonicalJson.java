package com.yanban.api.agent.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class ProductEngineCanonicalJson {
    private static final Comparator<String> UNICODE_CODE_POINTS = (left, right) -> {
        int li = 0;
        int ri = 0;
        while (li < left.length() && ri < right.length()) {
            int lc = left.codePointAt(li);
            int rc = right.codePointAt(ri);
            if (lc != rc) return Integer.compare(lc, rc);
            li += Character.charCount(lc);
            ri += Character.charCount(rc);
        }
        return Integer.compare(left.length() - li, right.length() - ri);
    };

    private final ObjectMapper json;

    ProductEngineCanonicalJson(ObjectMapper json) {
        this.json = json;
    }

    String canonical(Object value) {
        try {
            return json.writeValueAsString(sort(json.valueToTree(value)));
        } catch (Exception failure) {
            throw new IllegalStateException("Engine authority canonicalization failed", failure);
        }
    }

    String digest(Object value) {
        return sha256(canonical(value));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private JsonNode sort(JsonNode source) {
        if (source.isObject()) {
            ObjectNode result = json.createObjectNode();
            List<String> names = new ArrayList<>();
            source.fieldNames().forEachRemaining(names::add);
            names.sort(UNICODE_CODE_POINTS);
            names.forEach(name -> result.set(name, sort(source.get(name))));
            return result;
        }
        if (source.isArray()) {
            ArrayNode result = json.createArrayNode();
            source.forEach(item -> result.add(sort(item)));
            return result;
        }
        return source;
    }
}
