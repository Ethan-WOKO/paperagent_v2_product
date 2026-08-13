package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.publish.ProductChainPublishAuthoritySource;
import com.yanban.api.agent.v2.chain.recovery.ProductChainFinalizationRecoverySource;
import io.paperagent.v2.chain.ChainPersistenceRecords;

import java.util.List;
import java.util.Map;

/** Exact formal authority cut used by validation/publish module 9. */
record ProductValidationPublishFacts(
        ChainPersistenceRecords.ContextRevisionRecord building,
        ChainPersistenceRecords.TaskRecord task,
        long taskEventCut,
        Map<String, Long> sequences,
        ChainPersistenceRecords.WorkspaceCandidateRecord workspaceCandidate,
        ChainPersistenceRecords.CandidateStepResultRecord candidate,
        boolean validationRequired,
        ProductTypedValidationView validation,
        ChainPersistenceRecords.FinalizationReadinessRecord readiness,
        List<ChainPersistenceRecords.FinalizationCheckRecord> checks,
        ChainPersistenceRecords.FinalizationCheckRecord latestCheck,
        ChainPersistenceRecords.TaskOutcomeRecord outcome,
        ProductChainPublishAuthoritySource.Operation publishOperation,
        ProductChainFinalizationRecoverySource.PublishFailure publishFailure) {
    ProductValidationPublishFacts {
        sequences = Map.copyOf(sequences);
        checks = List.copyOf(checks);
    }

    boolean empty() {
        return validation == null && readiness == null && checks.isEmpty()
                && outcome == null && publishOperation == null
                && publishFailure == null;
    }
}
