package io.paperagent.v2.contracts;

import java.time.Instant;
import java.util.Optional;

/**
 * Immutable, untrusted input requesting an action for one Final Synthesis.
 *
 * <p>Only a later authenticated and persisted authority can apply this intent. This contract grants no
 * identity, authorization, status, receipt or diff access, or mutation authority.</p>
 */
public record FinalSynthesisDecisionIntent(
        String decisionId,
        FinalSynthesisId finalSynthesisId,
        FinalSynthesisDecisionAction action,
        Optional<String> reason,
        Instant requestedAt) {

    public FinalSynthesisDecisionIntent {
        decisionId = Contracts.id(decisionId, "finalSynthesisDecisionIntent.decisionId");
        finalSynthesisId = Contracts.required(finalSynthesisId, "finalSynthesisDecisionIntent.finalSynthesisId");
        action = Contracts.required(action, "finalSynthesisDecisionIntent.action");
        reason = Contracts.required(reason, "finalSynthesisDecisionIntent.reason");
        if (reason.isPresent()) {
            reason = Optional.of(Contracts.text(reason.get(), "finalSynthesisDecisionIntent.reason"));
        }
        requestedAt = Contracts.required(requestedAt, "finalSynthesisDecisionIntent.requestedAt");
    }

    @Override
    public String toString() {
        return "FinalSynthesisDecisionIntent["
                + "decisionId=<provided>, "
                + "finalSynthesisId=<provided>, "
                + "action=<provided>, "
                + "reason=<provided>, "
                + "requestedAt=<provided>]";
    }
}
