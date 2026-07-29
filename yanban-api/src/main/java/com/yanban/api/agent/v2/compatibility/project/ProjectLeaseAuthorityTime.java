package com.yanban.api.agent.v2.compatibility.project;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

final class ProjectLeaseAuthorityTime {
    private ProjectLeaseAuthorityTime() {
    }

    static Instant canonical(Instant value) {
        return Objects.requireNonNull(value, "value")
                .truncatedTo(ChronoUnit.MICROS);
    }
}
