package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.ToolCallId;

final class InMemoryEffectIntentRepository implements EffectIntentRepository {
    private static final String PARTIAL_PATH = "effectIntent.source";

    private final InMemoryState state;

    InMemoryEffectIntentRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<PersistedEffectIntent> persist(
            EffectIntentRequest request) {
        if (PersistenceChecks.missing(request)) {
            return PersistenceChecks.invalid("request");
        }
        ToolCallId toolCallId = request.intent().toolCallId();
        synchronized (state.monitor) {
            InMemoryState.EffectIntentMarker existing =
                    state.effectIntents.get(toolCallId);
            if (existing != null) {
                return replayOrConflict(toolCallId, existing, request);
            }

            InMemoryExecutionMutationAuthority.AuthoritativeSource source =
                    InMemoryExecutionMutationAuthority.validateAuthoritativeSource(
                            state, request.intent().planId());
            if (source == null) {
                return InMemoryExecutionMutationAuthority
                                .hasPlanScopedOccupancy(
                                        state, request.intent().planId())
                        ? partialState()
                        : PersistenceChecks.notFound("request.intent.planId");
            }

            PersistenceResult<PersistedEffectIntent> activationFailure =
                    validateActivation(source, request);
            if (activationFailure != null) {
                return activationFailure;
            }

            PersistenceResult<PersistedEffectIntent> leaseFailure =
                    validateLiveLease(request);
            if (leaseFailure != null) {
                return leaseFailure;
            }

            LeaseRecord lease = state.leases.get(request.intent().planId());
            PersistedEffectIntent persisted = new PersistedEffectIntent(
                    request.intent(),
                    lease.ownerId(),
                    lease.fencingToken(),
                    request.expectedActivationEventId());
            state.effectIntents.put(
                    toolCallId,
                    new InMemoryState.EffectIntentMarker(
                            request, persisted, lease.ownerId()));
            return PersistenceResult.applied(persisted);
        }
    }

    @Override
    public PersistenceResult<PersistedEffectIntent> find(ToolCallId toolCallId) {
        if (PersistenceChecks.missing(toolCallId)) {
            return PersistenceChecks.invalid("toolCallId");
        }
        synchronized (state.monitor) {
            InMemoryState.EffectIntentMarker marker =
                    state.effectIntents.get(toolCallId);
            if (marker == null) {
                return PersistenceChecks.notFound("toolCallId");
            }
            if (!isIntactMarker(toolCallId, marker)) {
                return partialState();
            }
            return PersistenceResult.found(marker.result());
        }
    }

    private PersistenceResult<PersistedEffectIntent> validateLiveLease(
            EffectIntentRequest request) {
        LeaseRecord lease = state.leases.get(request.intent().planId());
        if (lease == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_NOT_HELD,
                    "request.intent.planId");
        }
        if (!lease.leaseToken().equals(request.leaseToken())) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_TOKEN_INVALID,
                    "request.leaseToken");
        }
        if (lease.fencingToken() != request.fencingToken()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                    "request.fencingToken");
        }
        if (lease.isExpiredAt(state.observeLeaseTime())) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_EXPIRED,
                    "request.intent.planId");
        }
        return null;
    }

    private static PersistenceResult<PersistedEffectIntent> validateActivation(
            InMemoryExecutionMutationAuthority.AuthoritativeSource source,
            EffectIntentRequest request) {
        EventId expectedActivationId = request.expectedActivationEventId();
        InMemoryState.StepActivationMarker marker = source.activationMarkers()
                .get(expectedActivationId);
        if (marker == null) {
            return PersistenceChecks.notFound("request.expectedActivationEventId");
        }
        if (!expectedActivationId.equals(source.head().mutationEventId())
                || !InMemoryExecutionMutationAuthority.isSelfConsistentMarker(
                        request.intent().planId(), expectedActivationId, marker)) {
            return partialState();
        }
        if (!marker.result().stepId().equals(request.intent().stepId())
                || source.checkpoint().checkpoint().stepStates().get(
                                request.intent().stepId())
                        != StepExecutionState.ACTIVE) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                    "request.intent.stepId");
        }
        return null;
    }

    private static PersistenceResult<PersistedEffectIntent> replayOrConflict(
            ToolCallId toolCallId,
            InMemoryState.EffectIntentMarker marker,
            EffectIntentRequest request) {
        if (!isIntactMarker(toolCallId, marker)) {
            return partialState();
        }
        EffectIntent storedIntent = marker.request().intent();
        EffectIntent requestedIntent = request.intent();
        if (!storedIntent.planId().equals(requestedIntent.planId())) {
            return conflicting("request.intent.planId");
        }
        if (!storedIntent.stepId().equals(requestedIntent.stepId())) {
            return conflicting("request.intent.stepId");
        }
        if (!storedIntent.kind().equals(requestedIntent.kind())) {
            return conflicting("request.intent.kind");
        }
        if (!storedIntent.arguments().equals(requestedIntent.arguments())) {
            return conflicting("request.intent.arguments");
        }
        if (!marker.request().expectedActivationEventId().equals(
                request.expectedActivationEventId())) {
            return conflicting("request.expectedActivationEventId");
        }
        if (!marker.request().leaseToken().equals(request.leaseToken())) {
            return conflicting("request.leaseToken");
        }
        if (marker.request().fencingToken() != request.fencingToken()) {
            return conflicting("request.fencingToken");
        }
        return PersistenceResult.replayed(marker.result());
    }

    static boolean isIntactMarker(
            ToolCallId toolCallId,
            InMemoryState.EffectIntentMarker marker) {
        if (marker == null
                || marker.request() == null
                || marker.result() == null
                || PersistenceChecks.blank(marker.leaseOwnerId())) {
            return false;
        }
        EffectIntentRequest request = marker.request();
        PersistedEffectIntent result = marker.result();
        return toolCallId.equals(request.intent().toolCallId())
                && request.intent().equals(result.intent())
                && marker.leaseOwnerId().equals(result.leaseOwnerId())
                && request.fencingToken() == result.fencingToken()
                && request.expectedActivationEventId().equals(
                        result.activationEventId());
    }

    private static PersistenceResult<PersistedEffectIntent> conflicting(
            String path) {
        return PersistenceResult.rejected(
                PersistenceErrorCode.CONFLICTING_REPLAY, path);
    }

    private static PersistenceResult<PersistedEffectIntent> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                PARTIAL_PATH);
    }
}
