package io.paperagent.v2.chain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Canonical microsecond precision for persisted chain authority facts. */
public final class ChainAuthorityTime {
    private ChainAuthorityTime() {
    }

    public static Instant atOrAfter(
            Instant requested, Instant... predecessors) {
        Instant selected = normalize(requested);
        Objects.requireNonNull(predecessors, "predecessors");
        for (Instant predecessor : predecessors) {
            Instant normalized = normalize(predecessor);
            if (normalized.isAfter(selected)) selected = normalized;
        }
        return selected;
    }

    public static Instant normalize(Instant value) {
        return Objects.requireNonNull(value, "value")
                .truncatedTo(ChronoUnit.MICROS);
    }
}
