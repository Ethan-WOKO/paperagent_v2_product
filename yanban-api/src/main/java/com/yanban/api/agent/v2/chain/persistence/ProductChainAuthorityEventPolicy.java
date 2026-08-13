package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainPersistenceRecords.AcceptedResultRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ActionBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRequest;
import io.paperagent.v2.chain.ChainPersistenceRecords.CandidateStepResultRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextBuildFailureRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelFailureStepBlockRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ActionReceiptStepBlockRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.CandidateMaterializationFailureRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.FinalizationCheckRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.FinalizationReadinessRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PendingItemEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PendingItemRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PermissionDecisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ProposalStateEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ResultApplicabilityRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ReviewDecisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.RouteDecisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskAuthorityFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskInstructionBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskOutcomeRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionStageRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.WorkspaceCandidateRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ValidationSetRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ValidationBundleRecord;

import java.util.Objects;

/** Frozen product-side validation for atomic authority event/fact writes. */
final class ProductChainAuthorityEventPolicy {
    private ProductChainAuthorityEventPolicy() {
    }

    static void verify(AuthorityEventRequest event, TaskAuthorityFact fact) {
        String expectedType = expectedEventType(fact);
        if (!expectedType.equals(event.eventType())) {
            throw new ProductChainPersistenceException(
                    "CHAIN_AUTHORITY_EVENT_TYPE_MISMATCH");
        }
        if (hasTransitionIdentity(fact)
                && !Objects.equals(transitionId(fact),
                event.transitionId())) {
            throw new ProductChainPersistenceException(
                    "CHAIN_AUTHORITY_EVENT_TRANSITION_MISMATCH");
        }
        String identityDigest = explicitIdentityDigest(fact);
        if (identityDigest != null
                && !identityDigest.equals(event.sourceIdentitySha256())) {
            throw new ProductChainPersistenceException(
                    "CHAIN_AUTHORITY_EVENT_SOURCE_IDENTITY_MISMATCH");
        }
    }

    private static String expectedEventType(TaskAuthorityFact fact) {
        if (fact instanceof TaskInstructionBindingRecord) {
            return "INSTRUCTION_BOUND";
        }
        if (fact instanceof ContextBuildFailureRecord) {
            return "CONTEXT_BUILD_FAILURE";
        }
        if (fact instanceof CandidateMaterializationFailureRecord) {
            return "CANDIDATE_MATERIALIZATION_FAILURE";
        }
        if (fact instanceof ProposalStateEventRecord state) {
            return "PROPOSAL_" + state.stateKind().name();
        }
        if (fact instanceof TransitionRecord) {
            return "TRANSITION";
        }
        if (fact instanceof TransitionStageRecord) {
            return "TRANSITION_STAGE";
        }
        if (fact instanceof RouteDecisionRecord) {
            return "ROUTE_DECISION";
        }
        if (fact instanceof PlanBindingRecord) {
            return "PLAN_BINDING";
        }
        if (fact instanceof CandidateStepResultRecord) {
            return "CANDIDATE_STEP_RESULT";
        }
        if (fact instanceof ValidationSetRecord) {
            return "VALIDATION";
        }
        if (fact instanceof ValidationBundleRecord) {
            return "VALIDATION_BUNDLE";
        }
        if (fact instanceof ModelFailureStepBlockRecord) {
            return "MODEL_FAILURE_STEP_BLOCK";
        }
        if (fact instanceof ActionReceiptStepBlockRecord) {
            return "ACTION_RECEIPT_STEP_BLOCK";
        }
        if (fact instanceof ReviewDecisionRecord) {
            return "REVIEW_DECISION";
        }
        if (fact instanceof AcceptedResultRecord) {
            return "ACCEPTED_RESULT";
        }
        if (fact instanceof ResultApplicabilityRecord) {
            return "RESULT_APPLICABILITY";
        }
        if (fact instanceof PendingItemRecord) {
            return "PENDING_ITEM";
        }
        if (fact instanceof PendingItemEventRecord event) {
            return "PENDING_ITEM_" + event.eventKind().name();
        }
        if (fact instanceof PermissionDecisionRecord) {
            return "PERMISSION_DECISION";
        }
        if (fact instanceof ActionBindingRecord) {
            return "ACTION_BINDING";
        }
        if (fact instanceof WorkspaceCandidateRecord) {
            return "WORKSPACE_CANDIDATE";
        }
        if (fact instanceof FinalizationReadinessRecord) {
            return "FINALIZATION_READINESS";
        }
        if (fact instanceof FinalizationCheckRecord) {
            return "FINALIZATION_CHECK";
        }
        if (fact instanceof TaskOutcomeRecord) {
            return "TASK_OUTCOME";
        }
        if (fact instanceof DeliveryRecord) {
            return "DELIVERY";
        }
        if (fact instanceof DeliveryEventRecord event) {
            return "DELIVERY_" + event.eventKind().name();
        }
        throw new ProductChainPersistenceException(
                "CHAIN_AUTHORITY_FACT_TYPE_UNSUPPORTED");
    }

    private static boolean hasTransitionIdentity(TaskAuthorityFact fact) {
        return fact instanceof TransitionRecord
                || fact instanceof TransitionStageRecord
                || fact instanceof RouteDecisionRecord
                || fact instanceof PlanBindingRecord
                || fact instanceof AcceptedResultRecord
                || fact instanceof FinalizationReadinessRecord
                || fact instanceof FinalizationCheckRecord;
    }

    private static String transitionId(TaskAuthorityFact fact) {
        if (fact instanceof TransitionRecord value) {
            return value.transitionId();
        }
        if (fact instanceof TransitionStageRecord value) {
            return value.transitionId();
        }
        if (fact instanceof RouteDecisionRecord value) {
            return value.transitionId();
        }
        if (fact instanceof PlanBindingRecord value) {
            return value.transitionId();
        }
        if (fact instanceof AcceptedResultRecord value) {
            return value.transitionId();
        }
        if (fact instanceof FinalizationReadinessRecord value) {
            return value.transitionId();
        }
        return ((FinalizationCheckRecord) fact).transitionId();
    }

    private static String explicitIdentityDigest(TaskAuthorityFact fact) {
        if (fact instanceof TransitionRecord value) {
            return value.targetIdentityDigest();
        }
        if (fact instanceof AcceptedResultRecord value) {
            return value.acceptedIdentitySha256();
        }
        if (fact instanceof PendingItemRecord value) {
            return value.gapIdentitySha256();
        }
        if (fact instanceof CandidateMaterializationFailureRecord value) {
            return ProductChainRecordCodec.sha256(
                    value.actionId() + "\0" + value.errorCode());
        }
        if (fact instanceof ModelFailureStepBlockRecord value) {
            return value.versionFenceSha256();
        }
        if (fact instanceof ActionReceiptStepBlockRecord value) {
            return value.blockIdentityDigestSha256();
        }
        if (fact instanceof ValidationSetRecord value) {
            return ProductChainRecordCodec.sha256(
                    value.validationId() + "\0" + value.requestDigest()
                            + "\0" + value.receiptSetDigest() + "\0"
                            + value.conclusionDigest());
        }
        if (fact instanceof ValidationBundleRecord value) {
            return ProductChainRecordCodec.sha256(
                    value.validationBundleId() + "\0"
                            + value.requestDigest() + "\0"
                            + value.receiptSetDigest() + "\0"
                            + value.conclusionDigest());
        }
        return null;
    }
}
