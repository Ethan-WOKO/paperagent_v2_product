package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.EffectProgressRequest;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectProgress;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ProductEffectOutcomeRepositoryAdapter
        implements EffectOutcomeRepository {
    private final ProductEffectOutcomeTransactions transactions;
    private final ProductEffectIntentRepositoryAdapter intents;
    private final ProductStepRecoveryTransactions recovery;

    public ProductEffectOutcomeRepositoryAdapter(
            ProductEffectOutcomeTransactions transactions,
            ProductEffectIntentRepositoryAdapter intents,
            ProductStepRecoveryTransactions recovery) {
        this.transactions = transactions;
        this.intents = intents;
        this.recovery = recovery;
    }

    @Override
    public PersistenceResult<PersistedEffectProgress> appendProgress(
            EffectProgressRequest request) {
        if (request == null) {
            return invalid("request");
        }
        PersistenceResult<PersistedEffectProgress> replay =
                transactions.replayProgress(request);
        if (replay != null) {
            return replay;
        }
        PersistenceResult<PersistedEffectIntent> intent =
                intent(request.progress().toolCallId(),
                        "request.progress.toolCallId");
        if (!intent.successful()) {
            return copy(intent);
        }
        PersistenceResult<StepRecoverySnapshot> inspected =
                recovery.inspectWriterAuthority(
                        intent.value().orElseThrow().intent().planId());
        PersistenceResult<PersistedEffectProgress> authority =
                active(inspected);
        if (authority != null) {
            return authority;
        }
        try {
            return transactions.appendProgress(
                    request, intent.value().orElseThrow(),
                    (PersistedStepRecoveryActive)
                            inspected.value().orElseThrow());
        } catch (RuntimeException exception) {
            if (!ProductReceiptRaceFailure.recognized(exception)) {
                throw exception;
            }
            PersistenceResult<PersistedEffectProgress> classified =
                    transactions.classifyProgress(request);
            if (classified != null) {
                return classified;
            }
            throw exception;
        }
    }

    @Override
    public PersistenceResult<List<PersistedEffectProgress>> readProgress(
            ToolCallId toolCallId) {
        return toolCallId == null
                ? invalid("toolCallId")
                : transactions.readProgress(toolCallId);
    }

    @Override
    public PersistenceResult<PersistedEffectResult> recordResult(
            EffectResultRequest request) {
        if (request == null) {
            return invalid("request");
        }
        PersistenceResult<PersistedEffectResult> replay =
                transactions.replayResult(request);
        if (replay != null) {
            return replay;
        }
        PersistenceResult<PersistedEffectIntent> intent =
                intent(request.receipt().toolCallId(),
                        "request.receipt.toolCallId");
        if (!intent.successful()) {
            return copy(intent);
        }
        PersistenceResult<StepRecoverySnapshot> inspected =
                recovery.inspectWriterAuthority(
                        intent.value().orElseThrow().intent().planId());
        PersistenceResult<PersistedEffectResult> authority =
                active(inspected);
        if (authority != null) {
            return authority;
        }
        try {
            return transactions.recordResult(
                    request, intent.value().orElseThrow(),
                    (PersistedStepRecoveryActive)
                            inspected.value().orElseThrow());
        } catch (RuntimeException exception) {
            if (!ProductReceiptRaceFailure.recognized(exception)) {
                throw exception;
            }
            PersistenceResult<PersistedEffectResult> classified =
                    transactions.replayResult(request);
            if (classified != null) {
                return classified;
            }
            throw exception;
        }
    }

    @Override
    public PersistenceResult<PersistedEffectResult> findResult(
            ToolCallId toolCallId) {
        return toolCallId == null
                ? invalid("toolCallId")
                : transactions.findResult(toolCallId);
    }

    private PersistenceResult<PersistedEffectIntent> intent(
            ToolCallId toolCallId, String missingPath) {
        PersistenceResult<PersistedEffectIntent> found =
                intents.find(toolCallId);
        if (found.successful()) {
            return found;
        }
        var failure = found.failure().orElseThrow();
        if (failure.code() == PersistenceErrorCode.NOT_FOUND) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.NOT_FOUND, missingPath);
        }
        return PersistenceResult.rejected(
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE,
                "effectOutcome.source");
    }

    private static <T> PersistenceResult<T> active(
            PersistenceResult<StepRecoverySnapshot> inspected) {
        if (inspected.successful()
                && inspected.value().orElseThrow()
                instanceof PersistedStepRecoveryActive) {
            return null;
        }
        if (!inspected.successful()
                && inspected.failure().orElseThrow().code()
                == PersistenceErrorCode.STEP_RECOVERY_NOT_ELIGIBLE) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                    "effectIntent.stepId");
        }
        return PersistenceResult.rejected(
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE,
                "effectOutcome.source");
    }

    private static <T> PersistenceResult<T> copy(
            PersistenceResult<?> source) {
        var failure = source.failure().orElseThrow();
        return PersistenceResult.rejected(
                failure.code(), failure.path());
    }

    private static <T> PersistenceResult<T> invalid(String path) {
        return PersistenceResult.rejected(
                PersistenceErrorCode.INVALID_ARGUMENT, path);
    }
}
