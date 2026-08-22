package com.yanban.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class KbProcessingOutboxEventTest {
    @Test
    void retriesBeforeBecomingDead() {
        KbProcessingOutboxEvent event = new KbProcessingOutboxEvent(
                "event-1", 1L, 2L, "1", "{}");
        Instant now = Instant.parse("2026-08-21T00:00:00Z");

        event.failed(now, 2, "temporary outage");
        assertThat(event.getStatus()).isEqualTo("RETRY");
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfter(now);

        event.failed(now, 2, "still unavailable");
        assertThat(event.getStatus()).isEqualTo("DEAD");
        assertThat(event.getAttemptCount()).isEqualTo(2);
    }
}
