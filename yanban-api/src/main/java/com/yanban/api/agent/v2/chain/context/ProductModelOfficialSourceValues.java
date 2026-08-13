package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure formal-source values for Answer model Context. */
final class ProductModelOfficialSourceValues {
    private ProductModelOfficialSourceValues() {
    }

    static ChainContextValue officialSources(
            List<ProductModelInvocationProjectionValues.InvocationView>
                    invocations,
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            List<ProductModelInvocationProjectionValues.DeliveryView>
                    deliveries) {
        List<ChainContextValue> values = new ArrayList<>();
        invocations.forEach(invocation -> invocation.states().stream()
                .filter(state -> state.stateKind()
                        == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT)
                .forEach(state -> values.add(ChainContextValue.object(Map.of(
                        "sourceKind", ChainContextValue.text(
                                "PROPOSAL_OFFICIAL_REPLACEMENT"),
                        "authorityType", ChainContextValue.text(
                                state.officialAuthorityType()),
                        "authorityRef", ref(state.officialAuthorityRef()))))));
        if (outcome != null) values.add(ChainContextValue.object(Map.of(
                "sourceKind", ChainContextValue.text("TASK_OUTCOME"),
                "authorityRef", ref(outcome.outcomeId()),
                "status", ChainContextValue.text(
                        outcome.outcomeType().name()))));
        deliveries.forEach(value -> values.add(ChainContextValue.object(Map.of(
                "sourceKind", ChainContextValue.text("DELIVERY"),
                "authorityRef", ref(value.delivery().deliveryId()),
                "sourceRef", ref(deliverySource(value.delivery()))))));
        return ChainContextValue.array(values);
    }

    static ChainContextValue latestDeliveryFailure(
            List<ProductModelInvocationProjectionValues.DeliveryView> values) {
        for (int index = values.size() - 1; index >= 0; index--) {
            var delivery = values.get(index);
            if (!delivery.events().isEmpty()) {
                var event = delivery.events().get(
                        delivery.events().size() - 1);
                if (event.eventKind() == ChainDeliveryStatus.RETRYING
                        || event.eventKind()
                        == ChainDeliveryStatus.DELIVERY_FAILED) {
                    return ChainContextValue.object(Map.of(
                            "deliveryRef", ref(
                                    delivery.delivery().deliveryId()),
                            "eventRef", ref(event.eventId()),
                            "status", ChainContextValue.text(
                                    event.eventKind().name()),
                            "attemptNo", ChainContextValue.number(
                                    event.attemptNo()),
                            "errorCode", ChainContextValue.text(
                                    event.errorCode())));
                }
            }
        }
        return ChainContextValue.object(Map.of(
                "status", ChainContextValue.text("NONE"),
                "reason", ChainContextValue.text("NO_DELIVERY_FAILURE")));
    }

    private static String deliverySource(
            ChainPersistenceRecords.DeliveryRecord value) {
        if (value.routeDecisionId() != null) return value.routeDecisionId();
        if (value.taskOutcomeId() != null) return value.taskOutcomeId();
        if (value.gapId() != null) return value.gapId();
        return value.decisionId();
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }
}
