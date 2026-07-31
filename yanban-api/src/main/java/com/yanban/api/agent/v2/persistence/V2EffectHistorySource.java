package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only product projection of the durable effect history for one V2 Step.
 *
 * <p>The projection decodes the existing immutable intent/outcome markers. It
 * creates no second execution authority and fails closed on an occupied,
 * malformed marker.
 */
@Component
public class V2EffectHistorySource {
    private final ProductEffectIntentJpaRepository intents;
    private final ProductEffectOutcomeResultJpaRepository results;
    private final ProductEffectOutcomeMarkerReader markers;

    public V2EffectHistorySource(
            ProductEffectIntentJpaRepository intents,
            ProductEffectOutcomeResultJpaRepository results,
            ProductEffectOutcomeMarkerReader markers) {
        this.intents = intents;
        this.results = results;
        this.markers = markers;
    }

    @Transactional(readOnly = true)
    public List<Entry> inspect(PlanId planId, PlanStepId stepId) {
        if (planId == null || stepId == null) {
            throw new IllegalArgumentException(
                    "V2 effect history authority is required");
        }
        return inspectMatching(planId, stepId.value());
    }

    /**
     * Reads the complete, canonically decoded effect history for one Plan.
     */
    @Transactional(readOnly = true)
    public List<Entry> inspect(PlanId planId) {
        if (planId == null) {
            throw new IllegalArgumentException(
                    "V2 effect history authority is required");
        }
        return inspectMatching(planId, null);
    }

    private List<Entry> inspectMatching(PlanId planId, String stepId) {
        List<ProductEffectIntentEntity> rows = intents.findAllByPlanId(
                planId.value()).stream()
                .filter(row -> stepId == null || stepId.equals(row.stepId()))
                .sorted(Comparator
                        .comparing(ProductEffectIntentEntity::committedAt)
                        .thenComparing(ProductEffectIntentEntity::toolCallId))
                .toList();
        List<Entry> history = new ArrayList<>();
        for (ProductEffectIntentEntity row : rows) {
            PersistedEffectIntent intent = markers.intent(row.toolCallId());
            if (intent == null
                    || !intent.intent().planId().equals(planId)
                    || !row.stepId().equals(
                            intent.intent().stepId().value())
                    || stepId != null && !stepId.equals(
                            intent.intent().stepId().value())) {
                throw new IllegalStateException(
                        "V2 effect history is inconsistent");
            }
            ProductEffectOutcomeResultEntity resultRow =
                    results.findById(row.toolCallId()).orElse(null);
            PersistedEffectResult result = null;
            if (resultRow != null) {
                var marker = markers.result(resultRow);
                if (marker == null) {
                    throw new IllegalStateException(
                            "V2 effect outcome history is inconsistent");
                }
                result = marker.result();
            } else if (markers.hasAnyOutcomeState(row.toolCallId())) {
                throw new IllegalStateException(
                        "V2 effect outcome is incomplete");
            }
            history.add(new Entry(intent, result));
        }
        return List.copyOf(history);
    }

    public record Entry(
            PersistedEffectIntent intent,
            PersistedEffectResult result) {
        public boolean completed() {
            return result != null;
        }

        public boolean successful() {
            return completed()
                    && result.receipt().status()
                    == io.paperagent.v2.contracts.ReceiptStatus.SUCCESS;
        }
    }
}
