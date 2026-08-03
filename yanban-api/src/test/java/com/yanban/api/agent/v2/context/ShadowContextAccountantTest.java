package com.yanban.api.agent.v2.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShadowContextAccountantTest {

    private final ModelContextProfile profile =
            new KnownModelContextProfileRegistry()
                    .find("deepseek", "deepseek-v4-flash")
                    .orElseThrow();
    private final ShadowContextAccountant accountant =
            new ShadowContextAccountant(new Utf8ByteTokenCounter());

    @Test
    void utf8ByteV1IsDeterministicAndConservative() {
        Utf8ByteTokenCounter counter = new Utf8ByteTokenCounter();

        assertThat(counter.version()).isEqualTo("utf8-byte-v1");
        assertThat(counter.count("abc")).isEqualTo(3L);
        assertThat(counter.count("上下文")).isEqualTo(9L);
        assertThat(counter.count(null)).isZero();
    }

    @Test
    void overLimitSectionCannotBorrowUnusedCapacity() {
        ShadowContextMeasurement measurement = accountant.measure(
                profile,
                Map.of(
                        ContextSectionType.CORE_AUTHORITY,
                        List.of("small"),
                        ContextSectionType.RAG_EVIDENCE,
                        List.of("x".repeat(50_001))));

        assertThat(measurement.section(ContextSectionType.CORE_AUTHORITY))
                .satisfies(usage -> {
                    assertThat(usage.tokenLimit()).isEqualTo(100_000L);
                    assertThat(usage.estimatedTokens()).isEqualTo(5L);
                    assertThat(usage.overLimit()).isFalse();
                });
        assertThat(measurement.section(ContextSectionType.RAG_EVIDENCE))
                .satisfies(usage -> {
                    assertThat(usage.tokenLimit()).isEqualTo(50_000L);
                    assertThat(usage.estimatedTokens()).isEqualTo(50_001L);
                    assertThat(usage.overLimit()).isTrue();
                });
        assertThat(measurement.section(ContextSectionType.OUTPUT_RESERVE)
                .tokenLimit()).isEqualTo(50_000L);
        assertThat(measurement.section(ContextSectionType.SAFETY_MARGIN)
                .tokenLimit()).isEqualTo(50_000L);
        assertThat(measurement.overLimitSectionCount()).isEqualTo(1L);
    }

    @Test
    void mismatchedCounterVersionFailsClosed() {
        VersionedTokenCounter incompatible = new VersionedTokenCounter() {
            @Override
            public String version() {
                return "other-v1";
            }

            @Override
            public long count(String value) {
                return 0L;
            }
        };

        assertThatThrownBy(() -> new ShadowContextAccountant(incompatible)
                .measure(profile, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }
}
