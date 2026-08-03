package com.yanban.api.agent.v2.context.runtime;

import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.V2ContextRevisionSnapshot;
import java.util.List;

public record V2ContextBoundaryFailure(
        String code,
        ContextSectionType failedSection,
        V2ContextRevisionSnapshot failedRevision,
        List<V2ContextRevisionSnapshot> phaseRevisions
) implements V2ContextBoundaryResult {
    public V2ContextBoundaryFailure {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("failure code is required");
        }
        phaseRevisions = phaseRevisions == null
                ? List.of() : List.copyOf(phaseRevisions);
    }
}
