package io.paperagent.v2.chain;

import java.util.List;
import java.util.Optional;

public interface ChainWorkflowRepository {
    Optional<ChainPersistenceRecords.TransitionRecord> findTransition(String transitionId);

    List<ChainPersistenceRecords.TransitionStageRecord> findTransitionStages(String transitionId);

    List<ChainPersistenceRecords.TransitionRecord> findIncompleteTransitions(String taskId);

    List<ChainPersistenceRecords.RouteDecisionRecord> findRouteDecisions(String taskId);

    List<ChainPersistenceRecords.PlanBindingRecord> findPlanBindings(String taskId);

    List<ChainPersistenceRecords.CandidateStepResultRecord> findCandidateStepResults(String taskId);

    default List<ChainPersistenceRecords.ModelFailureStepBlockRecord>
            findModelFailureStepBlocks(String taskId) {
        return List.of();
    }

    default List<ChainPersistenceRecords.ActionReceiptStepBlockRecord>
            findActionReceiptStepBlocks(String taskId) {
        return List.of();
    }

    List<ChainPersistenceRecords.ReviewDecisionRecord> findReviewDecisions(String taskId);

    List<ChainPersistenceRecords.AcceptedResultRecord> findAcceptedResults(String taskId);

    List<ChainPersistenceRecords.ResultApplicabilityRecord> findApplicabilityDecisions(String taskId);

    List<ChainPersistenceRecords.PendingItemRecord> findPendingItems(String taskId);

    List<ChainPersistenceRecords.PendingItemRecord> findOpenPendingItems(String taskId);

    List<ChainPersistenceRecords.PendingItemEventRecord> findPendingItemEvents(String gapId);

    List<ChainPersistenceRecords.PermissionDecisionRecord> findPermissionDecisions(String taskId);

    List<ChainPersistenceRecords.ActionBindingRecord> findActionBindings(String taskId);

    List<ChainPersistenceRecords.ActionBindingRecord> findInFlightActions(String taskId);

    List<ChainPersistenceRecords.WorkspaceCandidateRecord> findWorkspaceCandidates(String taskId);

    default List<ChainPersistenceRecords.InstructionDispositionRecord>
            findInstructionDispositions(String taskId) {
        return List.of();
    }
}
