package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import org.springframework.stereotype.Component;

@Component
class ProductReceiptEffectIntentMarkerReader {
    private final ProductEffectIntentJpaRepository intents;
    private final ProductReceiptToolCallClaimJpaRepository claims;
    private final ProductEffectIntentCodec codec;

    ProductReceiptEffectIntentMarkerReader(
            ProductEffectIntentJpaRepository intents,
            ProductReceiptToolCallClaimJpaRepository claims,
            ProductEffectIntentCodec codec) {
        this.intents = intents;
        this.claims = claims;
        this.codec = codec;
    }

    boolean valid(String toolCallId) {
        ProductEffectIntentEntity row =
                intents.findById(toolCallId).orElse(null);
        if (row == null) {
            return false;
        }
        try {
            EffectIntentRequest request = codec.decodeRequest(
                    row.requestFormatVersion(), row.requestSha256(),
                    row.requestJson());
            PersistedEffectIntent result = codec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            ProductReceiptToolCallClaimEntity claim =
                    claims.findById(toolCallId).orElse(null);
            return claim != null
                    && ProductReceiptOwnership.EFFECT_INTENT.name().equals(
                    claim.ownerKind())
                    && ProductReceiptOwnership.EFFECT_INTENT.name().equals(
                    row.toolCallOwnerKind())
                    && row.toolCallId().equals(
                    request.intent().toolCallId().value())
                    && row.planId().equals(request.intent().planId().value())
                    && row.stepId().equals(request.intent().stepId().value())
                    && row.intentKind().equals(request.intent().kind())
                    && row.activationEventId().equals(
                    request.expectedActivationEventId().value())
                    && row.leaseOwnerId().equals(result.leaseOwnerId())
                    && row.fencingToken() == result.fencingToken()
                    && request.intent().equals(result.intent())
                    && request.fencingToken() == result.fencingToken()
                    && request.expectedActivationEventId().equals(
                    result.activationEventId());
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
