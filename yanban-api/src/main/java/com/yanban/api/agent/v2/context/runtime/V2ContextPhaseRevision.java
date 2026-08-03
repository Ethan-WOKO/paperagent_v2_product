package com.yanban.api.agent.v2.context.runtime;

import com.yanban.api.agent.v2.context.V2ContextRevisionStatus;

public record V2ContextPhaseRevision(
        V2ContextRevisionStatus phase,
        int revisionNumber
) {
    public V2ContextPhaseRevision {
        if (phase == null || revisionNumber <= 0) {
            throw new IllegalArgumentException("phase revision is invalid");
        }
    }
}
