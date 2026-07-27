package com.yanban.api.agent.v2;

import com.yanban.core.agent.AgentRunIdentity;
import java.util.Objects;
import java.util.Optional;

public record VerifiedAgentTurnProductContext(
        AgentRunIdentity identity,
        Optional<String> projectVersionId
) {
    public VerifiedAgentTurnProductContext {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(projectVersionId, "projectVersionId");
    }
}
