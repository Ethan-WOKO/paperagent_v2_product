package com.yanban.api.agent.v2.chain.recovery;

import com.yanban.api.agent.v2.chain.api.ProjectChainTurnCoordinator;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnResponse;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Narrow hand-off from a verified {@code RECEIVED} command to the existing
 * public intake boundary.
 *
 * <p>It intentionally owns neither a scan cursor nor a progression claim.
 * A durable driver supplies one exact {@link
 * ProductChainReceivedCommandSource.ReceivedCommand}; the coordinator first
 * publishes a known new Task boundary, or (for a continuation whose immutable
 * result Task is not known yet) owns the formal Planner classification and
 * boundary-replacement commit semantics.</p>
 */
@Component
public final class ProductChainReceivedPlannerProgression {
    private final ProjectChainTurnCoordinator turns;

    public ProductChainReceivedPlannerProgression(
            ProjectChainTurnCoordinator turns) {
        this.turns = Objects.requireNonNull(turns, "turns");
    }

    public V2NaturalLanguageTurnResponse advance(
            ProductChainReceivedCommandSource.ReceivedCommand command) {
        return turns.resumeReceivedPlanner(
                Objects.requireNonNull(command, "command"));
    }
}
