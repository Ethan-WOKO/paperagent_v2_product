package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EffectProgress;
import io.paperagent.v2.contracts.EffectProgressId;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.ToolCallId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

final class InMemoryEffectOutcomeRepository implements EffectOutcomeRepository {
    private static final String PARTIAL_PATH = "effectOutcome.source";

    private final InMemoryState state;

    InMemoryEffectOutcomeRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<PersistedEffectProgress> appendProgress(
            EffectProgressRequest request) {
        if (PersistenceChecks.missing(request)) {
            return PersistenceChecks.invalid("request");
        }
        EffectProgress progress = request.progress();
        synchronized (state.monitor) {
            ProgressLookup existing = findProgress(progress.id());
            if (existing != null) {
                if (!isProgressStreamIntact(existing.toolCallId())
                        || !isIntactProgressMarker(
                                existing.toolCallId(),
                                existing.sequence(),
                                existing.marker())) {
                    return partialState();
                }
                return replayOrConflict(existing.marker(), request);
            }
            if (!isProgressStreamIntact(progress.toolCallId())) {
                return partialState();
            }

            InMemoryState.EffectResultMarker finalized =
                    state.effectResults.get(progress.toolCallId());
            if (finalized != null) {
                if (!isIntactResultMarker(state, progress.toolCallId(), finalized)) {
                    return partialState();
                }
                return PersistenceResult.rejected(
                        PersistenceErrorCode.EFFECT_OUTCOME_FINALIZED,
                        "request.progress.toolCallId");
            }

            PersistenceResult<EffectIntent> authority =
                    validateNewOutcomeAuthority(
                            progress.toolCallId(), "request.progress.toolCallId");
            if (!authority.successful()) {
                return rejected(authority);
            }
            PersistenceResult<PersistedEffectProgress> leaseFailure =
                    validateLiveLease(
                            authority.value().orElseThrow(),
                            request.leaseToken(),
                            request.fencingToken());
            if (leaseFailure != null) {
                return leaseFailure;
            }

            NavigableMap<Long, InMemoryState.EffectProgressMarker> stream =
                    state.effectProgresses.get(progress.toolCallId());
            long expected = stream == null || stream.isEmpty()
                    ? 1
                    : stream.lastKey() + 1;
            if (progress.sequence() != expected) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.EFFECT_PROGRESS_OUT_OF_SEQUENCE,
                        "request.progress.sequence");
            }
            LeaseRecord lease = state.leases.get(authority.value().orElseThrow().planId());
            PersistedEffectProgress persisted = new PersistedEffectProgress(
                    progress, lease.ownerId(), lease.fencingToken());
            if (stream == null) {
                stream = new TreeMap<>();
                state.effectProgresses.put(progress.toolCallId(), stream);
            }
            stream.put(progress.sequence(), new InMemoryState.EffectProgressMarker(
                    request, persisted, lease.ownerId()));
            return PersistenceResult.applied(persisted);
        }
    }

    @Override
    public PersistenceResult<List<PersistedEffectProgress>> readProgress(
            ToolCallId toolCallId) {
        if (PersistenceChecks.missing(toolCallId)) {
            return PersistenceChecks.invalid("toolCallId");
        }
        synchronized (state.monitor) {
            NavigableMap<Long, InMemoryState.EffectProgressMarker> stream =
                    state.effectProgresses.get(toolCallId);
            if (stream == null) {
                return PersistenceChecks.notFound("toolCallId");
            }
            if (!isProgressStreamIntact(toolCallId)) {
                return partialState();
            }
            List<PersistedEffectProgress> progress = new ArrayList<>();
            stream.values().forEach(marker -> progress.add(marker.result()));
            return PersistenceResult.found(List.copyOf(progress));
        }
    }

    @Override
    public PersistenceResult<PersistedEffectResult> recordResult(
            EffectResultRequest request) {
        if (PersistenceChecks.missing(request)) {
            return PersistenceChecks.invalid("request");
        }
        ExecutionReceipt receipt = request.receipt();
        ToolCallId toolCallId = receipt.toolCallId();
        synchronized (state.monitor) {
            InMemoryState.EffectResultMarker existing =
                    state.effectResults.get(toolCallId);
            if (existing != null) {
                if (!isIntactResultMarker(state, toolCallId, existing)) {
                    return partialState();
                }
                return replayOrConflict(existing, request);
            }

            PersistenceResult<EffectIntent> authority =
                    validateNewOutcomeAuthority(toolCallId, "request.receipt.toolCallId");
            if (!authority.successful()) {
                return rejected(authority);
            }
            PersistenceResult<PersistedEffectResult> leaseFailure =
                    validateLiveLease(
                            authority.value().orElseThrow(),
                            request.leaseToken(),
                            request.fencingToken());
            if (leaseFailure != null) {
                return leaseFailure;
            }
            PersistenceResult<PersistedEffectResult> receiptFailure =
                    validateReceiptOwnership(receipt);
            if (receiptFailure != null) {
                return receiptFailure;
            }

            LeaseRecord lease = state.leases.get(authority.value().orElseThrow().planId());
            PersistedEffectResult persisted = new PersistedEffectResult(
                    receipt, lease.ownerId(), lease.fencingToken());
            InMemoryState.EffectResultMarker marker =
                    new InMemoryState.EffectResultMarker(
                            request, persisted, lease.ownerId());
            // Both inserts are protected by the same adapter monitor and become visible together.
            state.receipts.put(receipt.id(), receipt);
            state.effectResults.put(toolCallId, marker);
            return PersistenceResult.applied(persisted);
        }
    }

    @Override
    public PersistenceResult<PersistedEffectResult> findResult(
            ToolCallId toolCallId) {
        if (PersistenceChecks.missing(toolCallId)) {
            return PersistenceChecks.invalid("toolCallId");
        }
        synchronized (state.monitor) {
            InMemoryState.EffectResultMarker marker = state.effectResults.get(toolCallId);
            if (marker == null) {
                return PersistenceChecks.notFound("toolCallId");
            }
            return isIntactResultMarker(state, toolCallId, marker)
                    ? PersistenceResult.found(marker.result())
                    : partialState();
        }
    }

    private PersistenceResult<EffectIntent> validateNewOutcomeAuthority(
            ToolCallId toolCallId,
            String missingIntentPath) {
        InMemoryState.EffectIntentMarker intentMarker =
                state.effectIntents.get(toolCallId);
        if (intentMarker == null) {
            return PersistenceChecks.notFound(missingIntentPath);
        }
        if (!InMemoryEffectIntentRepository.isIntactMarker(toolCallId, intentMarker)) {
            return partialAuthority();
        }
        EffectIntent intent = intentMarker.result().intent();
        InMemoryExecutionMutationAuthority.AuthoritativeSource source =
                InMemoryExecutionMutationAuthority.validateAuthoritativeSource(
                        state, intent.planId());
        if (source == null) {
            return partialAuthority();
        }
        EventId activationEventId = intentMarker.result().activationEventId();
        InMemoryState.StepActivationMarker activation = source.activationMarkers()
                .get(activationEventId);
        if (activation == null
                || !activationEventId.equals(source.head().mutationEventId())
                || !InMemoryExecutionMutationAuthority.isSelfConsistentMarker(
                        intent.planId(), activationEventId, activation)) {
            return partialAuthority();
        }
        if (!activation.result().stepId().equals(intent.stepId())
                || source.checkpoint().checkpoint().stepStates().get(intent.stepId())
                        != StepExecutionState.ACTIVE) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                    "effectIntent.stepId");
        }
        return PersistenceResult.found(intent);
    }

    private <T> PersistenceResult<T> validateLiveLease(
            EffectIntent intent,
            String leaseToken,
            long fencingToken) {
        LeaseRecord lease = state.leases.get(intent.planId());
        if (lease == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_NOT_HELD, "effectIntent.planId");
        }
        if (!lease.leaseToken().equals(leaseToken)) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_TOKEN_INVALID, "request.leaseToken");
        }
        if (lease.fencingToken() != fencingToken) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                    "request.fencingToken");
        }
        if (lease.isExpiredAt(state.observeLeaseTime())) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.LEASE_EXPIRED, "effectIntent.planId");
        }
        return null;
    }

    private PersistenceResult<PersistedEffectResult> validateReceiptOwnership(
            ExecutionReceipt receipt) {
        if (state.receipts.containsKey(receipt.id())) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                    "request.receipt.id");
        }
        boolean existingForEffect = state.receipts.values().stream()
                .anyMatch(existing -> existing.toolCallId().equals(receipt.toolCallId()));
        return existingForEffect
                ? PersistenceResult.rejected(
                        PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                        "request.receipt.toolCallId")
                : null;
    }

    private ProgressLookup findProgress(EffectProgressId id) {
        ProgressLookup found = null;
        for (Map.Entry<ToolCallId,
                NavigableMap<Long, InMemoryState.EffectProgressMarker>> stream :
                state.effectProgresses.entrySet()) {
            if (stream.getKey() == null || stream.getValue() == null) {
                continue;
            }
            for (Map.Entry<Long, InMemoryState.EffectProgressMarker> entry :
                    stream.getValue().entrySet()) {
                InMemoryState.EffectProgressMarker marker = entry.getValue();
                if (marker != null
                        && marker.request() != null
                        && marker.request().progress() != null
                        && id.equals(marker.request().progress().id())) {
                    if (found != null) {
                        return new ProgressLookup(null, -1, null);
                    }
                    found = new ProgressLookup(
                            stream.getKey(), entry.getKey(), marker);
                }
            }
        }
        return found;
    }

    private boolean isProgressStreamIntact(ToolCallId toolCallId) {
        NavigableMap<Long, InMemoryState.EffectProgressMarker> stream =
                state.effectProgresses.get(toolCallId);
        if (stream == null) {
            return true;
        }
        if (stream.isEmpty()) {
            return false;
        }
        long expected = 1;
        for (Map.Entry<Long, InMemoryState.EffectProgressMarker> entry :
                stream.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey() != expected
                    || !isIntactProgressMarker(
                            toolCallId, entry.getKey(), entry.getValue())) {
                return false;
            }
            expected++;
        }
        return true;
    }

    private boolean isIntactProgressMarker(
            ToolCallId toolCallId,
            long sequence,
            InMemoryState.EffectProgressMarker marker) {
        if (marker == null
                || marker.request() == null
                || marker.result() == null
                || PersistenceChecks.blank(marker.leaseOwnerId())) {
            return false;
        }
        EffectProgressRequest request = marker.request();
        PersistedEffectProgress result = marker.result();
        EffectProgress progress = request.progress();
        InMemoryState.EffectIntentMarker intent = state.effectIntents.get(toolCallId);
        return progress.toolCallId().equals(toolCallId)
                && progress.sequence() == sequence
                && progress.equals(result.progress())
                && marker.leaseOwnerId().equals(result.leaseOwnerId())
                && request.fencingToken() == result.fencingToken()
                && intent != null
                && InMemoryEffectIntentRepository.isIntactMarker(toolCallId, intent);
    }

    static boolean isIntactResultMarker(
            InMemoryState state,
            ToolCallId toolCallId,
            InMemoryState.EffectResultMarker marker) {
        if (marker == null
                || marker.request() == null
                || marker.result() == null
                || PersistenceChecks.blank(marker.leaseOwnerId())) {
            return false;
        }
        EffectResultRequest request = marker.request();
        PersistedEffectResult result = marker.result();
        ExecutionReceipt receipt = request.receipt();
        InMemoryState.EffectIntentMarker intent = state.effectIntents.get(toolCallId);
        return receipt.toolCallId().equals(toolCallId)
                && receipt.equals(result.receipt())
                && marker.leaseOwnerId().equals(result.leaseOwnerId())
                && request.fencingToken() == result.fencingToken()
                && receipt.equals(state.receipts.get(receipt.id()))
                && intent != null
                && InMemoryEffectIntentRepository.isIntactMarker(toolCallId, intent);
    }

    private static PersistenceResult<PersistedEffectProgress> replayOrConflict(
            InMemoryState.EffectProgressMarker marker,
            EffectProgressRequest request) {
        EffectProgress stored = marker.request().progress();
        EffectProgress requested = request.progress();
        if (!stored.toolCallId().equals(requested.toolCallId())) {
            return conflict("request.progress.toolCallId");
        }
        if (stored.sequence() != requested.sequence()) {
            return conflict("request.progress.sequence");
        }
        if (!stored.occurredAt().equals(requested.occurredAt())) {
            return conflict("request.progress.occurredAt");
        }
        if (!stored.details().equals(requested.details())) {
            return conflict("request.progress.details");
        }
        if (!marker.request().leaseToken().equals(request.leaseToken())) {
            return conflict("request.leaseToken");
        }
        if (marker.request().fencingToken() != request.fencingToken()) {
            return conflict("request.fencingToken");
        }
        return PersistenceResult.replayed(marker.result());
    }

    private static PersistenceResult<PersistedEffectResult> replayOrConflict(
            InMemoryState.EffectResultMarker marker,
            EffectResultRequest request) {
        ExecutionReceipt stored = marker.request().receipt();
        ExecutionReceipt requested = request.receipt();
        String receiptDifference = receiptDifference(stored, requested);
        if (receiptDifference != null) {
            return conflict(receiptDifference);
        }
        if (!marker.request().leaseToken().equals(request.leaseToken())) {
            return conflict("request.leaseToken");
        }
        if (marker.request().fencingToken() != request.fencingToken()) {
            return conflict("request.fencingToken");
        }
        return PersistenceResult.replayed(marker.result());
    }

    private static String receiptDifference(
            ExecutionReceipt stored,
            ExecutionReceipt requested) {
        if (!stored.id().equals(requested.id())) {
            return "request.receipt.id";
        }
        if (!stored.toolCallId().equals(requested.toolCallId())) {
            return "request.receipt.toolCallId";
        }
        if (stored.status() != requested.status()) {
            return "request.receipt.status";
        }
        if (!stored.startedAt().equals(requested.startedAt())) {
            return "request.receipt.startedAt";
        }
        if (!stored.endedAt().equals(requested.endedAt())) {
            return "request.receipt.endedAt";
        }
        if (!stored.exitCode().equals(requested.exitCode())) {
            return "request.receipt.exitCode";
        }
        if (!stored.resultCode().equals(requested.resultCode())) {
            return "request.receipt.resultCode";
        }
        if (!stored.standardOutput().equals(requested.standardOutput())) {
            return "request.receipt.standardOutput";
        }
        if (!stored.standardError().equals(requested.standardError())) {
            return "request.receipt.standardError";
        }
        if (!stored.artifactReferences().equals(requested.artifactReferences())) {
            return "request.receipt.artifactReferences";
        }
        if (!stored.resultingDiff().equals(requested.resultingDiff())) {
            return "request.receipt.resultingDiff";
        }
        return stored.eventReferences().equals(requested.eventReferences())
                ? null
                : "request.receipt.eventReferences";
    }

    private static <T> PersistenceResult<T> conflict(String path) {
        return PersistenceResult.rejected(PersistenceErrorCode.CONFLICTING_REPLAY, path);
    }

    private static <T> PersistenceResult<T> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE,
                PARTIAL_PATH);
    }

    private static PersistenceResult<EffectIntent> partialAuthority() {
        return partialState();
    }

    private static <T> PersistenceResult<T> rejected(
            PersistenceResult<?> source) {
        PersistenceFailure failure = source.failure().orElseThrow();
        return PersistenceResult.rejected(failure.code(), failure.path());
    }

    private record ProgressLookup(
            ToolCallId toolCallId,
            long sequence,
            InMemoryState.EffectProgressMarker marker) {
    }
}
