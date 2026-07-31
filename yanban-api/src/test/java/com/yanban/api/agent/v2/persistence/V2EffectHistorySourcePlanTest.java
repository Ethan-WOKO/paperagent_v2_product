package com.yanban.api.agent.v2.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class V2EffectHistorySourcePlanTest {
    private final ProductEffectIntentJpaRepository intents =
            mock(ProductEffectIntentJpaRepository.class);
    private final ProductEffectOutcomeResultJpaRepository results =
            mock(ProductEffectOutcomeResultJpaRepository.class);
    private final ProductEffectOutcomeMarkerReader markers =
            mock(ProductEffectOutcomeMarkerReader.class);
    private final V2EffectHistorySource source =
            new V2EffectHistorySource(intents, results, markers);

    @Test
    void planReadReturnsOnlyFullyDecodedIntentAndReceiptMarkers() {
        ProductEffectIntentEntity row = row();
        PersistedEffectIntent intent = persistedIntent();
        ProductEffectOutcomeResultEntity resultRow =
                mock(ProductEffectOutcomeResultEntity.class);
        PersistedEffectResult result = mock(PersistedEffectResult.class);
        when(intents.findAllByPlanId("plan-1"))
                .thenReturn(List.of(row));
        when(markers.intent("call-1")).thenReturn(intent);
        when(results.findById("call-1"))
                .thenReturn(Optional.of(resultRow));
        when(markers.result(resultRow)).thenReturn(
                new ProductEffectOutcomeMarkerReader.ResultMarker(
                        null, result));

        assertThat(source.inspect(new PlanId("plan-1")))
                .containsExactly(new V2EffectHistorySource.Entry(
                        intent, result));
    }

    @Test
    void malformedReceiptMarkerFailsClosed() {
        ProductEffectIntentEntity row = row();
        ProductEffectOutcomeResultEntity resultRow =
                mock(ProductEffectOutcomeResultEntity.class);
        when(intents.findAllByPlanId("plan-1"))
                .thenReturn(List.of(row));
        PersistedEffectIntent intent = persistedIntent();
        when(markers.intent("call-1")).thenReturn(intent);
        when(results.findById("call-1"))
                .thenReturn(Optional.of(resultRow));
        when(markers.result(resultRow)).thenReturn(null);

        assertThatThrownBy(() -> source.inspect(new PlanId("plan-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("V2 effect outcome history is inconsistent");
    }

    private static ProductEffectIntentEntity row() {
        ProductEffectIntentEntity row =
                mock(ProductEffectIntentEntity.class);
        when(row.toolCallId()).thenReturn("call-1");
        when(row.stepId()).thenReturn("step-1");
        when(row.committedAt()).thenReturn(Instant.EPOCH);
        return row;
    }

    private static PersistedEffectIntent persistedIntent() {
        PersistedEffectIntent persisted =
                mock(PersistedEffectIntent.class);
        when(persisted.intent()).thenReturn(new EffectIntent(
                new ToolCallId("call-1"), new PlanId("plan-1"),
                new PlanStepId("step-1"), "sandbox.execute",
                new ObjectValue(Map.of())));
        return persisted;
    }
}
