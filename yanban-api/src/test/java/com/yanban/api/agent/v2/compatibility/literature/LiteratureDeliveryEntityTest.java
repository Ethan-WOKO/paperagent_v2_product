package com.yanban.api.agent.v2.compatibility.literature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LiteratureDeliveryEntityTest {
    @Test
    void planAndDeliveryAuthorityAreWriteOnceOrExactReplay() {
        var value = new LiteratureDeliveryEntity(
                new LiteratureDeliveryKey(7L, 9L, "request"),
                "hash", "query", 10, null, false,
                11L, 12L, "owner", "token",
                Instant.parse("2026-07-28T00:10:00Z"),
                Instant.parse("2026-07-28T00:00:00Z"));

        value.bindPlan("plan-a");
        assertDoesNotThrow(() -> value.bindPlan("plan-a"));
        assertThrows(IllegalStateException.class,
                () -> value.bindPlan("plan-b"));

        value.complete("plan-a", "synthesis-a", 13L);
        assertDoesNotThrow(
                () -> value.complete("plan-a", "synthesis-a", 13L));
        assertThrows(IllegalStateException.class,
                () -> value.complete("plan-a", "synthesis-b", 13L));
        assertThrows(IllegalStateException.class,
                () -> value.complete("plan-a", "synthesis-a", 14L));
    }
}
