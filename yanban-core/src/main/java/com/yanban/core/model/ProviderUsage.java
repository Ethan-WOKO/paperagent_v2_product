package com.yanban.core.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Usage observations only; absent cache counters must never become zero hits. */
final class ProviderUsage {
    private ProviderUsage() { }

    static ChatResponse.Usage parse(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        Integer prompt = count(node.path("prompt_tokens"));
        Integer hit = count(node.path("prompt_cache_hit_tokens"));
        if (hit == null) hit = count(node.path("prompt_tokens_details").path("cached_tokens"));
        Integer miss = count(node.path("prompt_cache_miss_tokens"));
        // A remainder is meaningful only when the provider actually reported cache usage.
        if (prompt != null && hit != null && hit > prompt) hit = null;
        if (prompt != null && miss != null && miss > prompt) miss = null;
        if (prompt != null && hit != null && miss != null && (long) hit + miss != prompt) {
            hit = null;
            miss = null;
        } else if (miss == null && prompt != null && hit != null) {
            miss = prompt - hit;
        }
        return new ChatResponse.Usage(prompt, count(node.path("completion_tokens")),
                count(node.path("total_tokens")), hit, miss);
    }

    private static Integer count(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0
                ? value.intValue() : null;
    }
}
