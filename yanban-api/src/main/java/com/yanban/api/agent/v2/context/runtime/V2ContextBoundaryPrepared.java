package com.yanban.api.agent.v2.context.runtime;

import com.yanban.api.agent.v2.context.V2ContextRevisionSnapshot;
import java.util.List;

public record V2ContextBoundaryPrepared(
        V2ContextRevisionSnapshot readyRevision,
        List<V2ContextRevisionSnapshot> phaseRevisions
) implements V2ContextBoundaryResult {
    public V2ContextBoundaryPrepared {
        if (readyRevision == null) {
            throw new IllegalArgumentException("ready revision is required");
        }
        phaseRevisions = phaseRevisions == null
                ? List.of() : List.copyOf(phaseRevisions);
    }
}
