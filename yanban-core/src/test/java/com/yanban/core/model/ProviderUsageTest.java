package com.yanban.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProviderUsageTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void preservesMissingCountersInsteadOfClaimingCacheMisses() throws Exception {
        var usage = ProviderUsage.parse(json.readTree("{\"prompt_tokens\":100,\"completion_tokens\":2}"));
        assertThat(usage.cacheHitTokens()).isNull();
        assertThat(usage.cacheMissTokens()).isNull();
        assertThat(ProviderUsage.parse(null)).isNull();
    }

    @Test void readsDeepSeekCountersAndStandardCachedTokenRemainder() throws Exception {
        var nativeUsage = ProviderUsage.parse(json.readTree("{\"prompt_tokens\":100,\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":20}"));
        assertThat(nativeUsage.cacheHitTokens()).isEqualTo(80);
        assertThat(nativeUsage.cacheMissTokens()).isEqualTo(20);
        var standard = ProviderUsage.parse(json.readTree("{\"prompt_tokens\":100,\"prompt_tokens_details\":{\"cached_tokens\":0}}"));
        assertThat(standard.cacheHitTokens()).isZero();
        assertThat(standard.cacheMissTokens()).isEqualTo(100);
    }

    @Test void rejectsInvalidOrInconsistentCounters() throws Exception {
        for (String extra : new String[]{"\"prompt_cache_hit_tokens\":101", "\"prompt_cache_hit_tokens\":-1", "\"prompt_cache_hit_tokens\":1.5", "\"prompt_cache_hit_tokens\":2147483648", "\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":30"}) {
            var usage = ProviderUsage.parse(json.readTree("{\"prompt_tokens\":100," + extra + "}"));
            assertThat(usage.cacheHitTokens()).isNull();
            assertThat(usage.cacheMissTokens()).isNull();
        }
    }
}
