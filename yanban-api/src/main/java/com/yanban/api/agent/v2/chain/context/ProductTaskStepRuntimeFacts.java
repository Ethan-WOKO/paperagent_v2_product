package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable formal-authority cut used by the module 6 projector. */
record ProductTaskStepRuntimeFacts(
        ChainPersistenceRecords.ContextRevisionRecord building,
        long taskEventCut,
        Map<String, Long> sequences,
        ChainPersistenceRecords.RouteDecisionRecord route,
        ChainPersistenceRecords.PlanBindingRecord binding,
        ChainStepAuthorityPort.PlanSnapshot plan,
        List<ChainStepAuthorityPort.StepEvent> stepEvents,
        List<ChainPersistenceRecords.CandidateStepResultRecord> candidates,
        List<ChainPersistenceRecords.ReviewDecisionRecord> reviews,
        List<ChainPersistenceRecords.AcceptedResultRecord> accepted,
        List<ChainPersistenceRecords.ResultApplicabilityRecord> applicability,
        ChainPersistenceRecords.TaskOutcomeRecord outcome,
        ChainPersistenceRecords.DeliveryRecord delivery) {
    ProductTaskStepRuntimeFacts {
        Objects.requireNonNull(building, "building");
        sequences = Map.copyOf(sequences);
        stepEvents = List.copyOf(stepEvents);
        candidates = List.copyOf(candidates);
        reviews = List.copyOf(reviews);
        accepted = List.copyOf(accepted);
        applicability = List.copyOf(applicability);
    }

    boolean hasNoRuntimeFacts() {
        return route == null && binding == null && plan == null
                && stepEvents.isEmpty() && candidates.isEmpty()
                && reviews.isEmpty() && accepted.isEmpty()
                && applicability.isEmpty() && outcome == null
                && delivery == null;
    }
}
