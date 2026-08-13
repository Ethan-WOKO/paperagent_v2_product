package io.paperagent.v2.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChainAuthorityTimeTest {
    @Test
    void comparesAllInputsAtMicrosecondPrecisionWithoutInventingTime() {
        Instant requested = Instant.parse("2026-08-09T01:02:03.123456789Z");
        Instant predecessor = Instant.parse("2026-08-09T01:02:03.123456999Z");
        assertEquals(Instant.parse("2026-08-09T01:02:03.123456Z"),
                ChainAuthorityTime.atOrAfter(requested, predecessor));
    }

    @Test
    void choosesTheLatestNormalizedFormalTime() {
        Instant requested = Instant.parse("2026-08-09T01:02:03.123456Z");
        Instant predecessor = Instant.parse("2026-08-09T01:02:04.000001Z");
        assertEquals(predecessor,
                ChainAuthorityTime.atOrAfter(requested, predecessor));
    }
}
