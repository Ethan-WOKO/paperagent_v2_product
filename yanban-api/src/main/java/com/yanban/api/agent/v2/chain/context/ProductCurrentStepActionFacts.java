package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectProgress;
import io.paperagent.v2.persistence.PersistedEffectResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable exact authority cut consumed by the module 7 value projector. */
record ProductCurrentStepActionFacts(
        ChainPersistenceRecords.ContextRevisionRecord building,
        long taskEventCut,
        Map<String, Long> eventSequences,
        List<ActionView> actions,
        ChainPersistenceRecords.TaskOutcomeRecord taskOutcome) {
    ProductCurrentStepActionFacts {
        Objects.requireNonNull(building, "building");
        eventSequences = Map.copyOf(Objects.requireNonNull(
                eventSequences, "eventSequences"));
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
    }

    boolean initialPlannerWithoutActionAuthority() {
        return building.role() == io.paperagent.v2.chain.ChainRole.PLANNER
                && building.planId() == null && building.stepId() == null
                && actions.isEmpty() && taskOutcome == null;
    }

    record ActionView(
            ChainPersistenceRecords.ActionBindingRecord binding,
            long authorityEventSequence,
            PersistedEffectIntent intent,
            List<PersistedEffectProgress> progress,
            PersistedEffectResult result,
            ChainPersistenceRecords.WorkspaceCandidateRecord candidate,
            ChainPersistenceRecords.CandidateMaterializationFailureRecord
                    candidateFailure) {
        ActionView {
            Objects.requireNonNull(binding, "binding");
            if (authorityEventSequence < 1) {
                throw new IllegalArgumentException(
                        "authorityEventSequence must be positive");
            }
            progress = List.copyOf(Objects.requireNonNull(
                    progress, "progress"));
        }
    }
}
