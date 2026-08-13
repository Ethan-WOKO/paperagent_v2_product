package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainWorkflowRepository;

import java.util.List;
import java.util.Objects;

/** Validates the one formal DIRECT route allowed to omit TaskFrame and Plan. */
final class ProductDirectAnswerContextAuthority {
    private ProductDirectAnswerContextAuthority() {
    }

    static boolean isDirectAnswer(
            ChainPersistenceRecords.ContextRevisionRecord revision) {
        return revision.role() == ChainRole.ANSWER
                && revision.workState() == ChainWorkState.DIRECT_ANSWERING
                && "DIRECT_ROUTE".equals(revision.callReason());
    }

    static ChainPersistenceRecords.RouteDecisionRecord require(
            ChainPersistenceRecords.ContextRevisionRecord revision,
            ChainWorkflowRepository workflow) {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(workflow, "workflow");
        if (!isDirectAnswer(revision)
                || revision.taskFrameId() != null
                || revision.planId() != null
                || revision.planRevisionId() != null
                || revision.planRevisionNumber() != null
                || revision.stepId() != null
                || revision.activationEventId() != null
                || revision.workspaceId() != null
                || revision.candidateArtifactId() != null
                || revision.candidateFingerprint() != null
                || revision.validationId() != null
                || revision.validationRequestDigest() != null
                || revision.validationReceiptDigest() != null) {
            throw new IllegalStateException(
                    "DIRECT Answer Context identity is incomplete");
        }
        List<ChainPersistenceRecords.RouteDecisionRecord> routes = workflow
                .findRouteDecisions(revision.taskId()).stream()
                .filter(value -> value.instructionId().equals(
                        revision.instructionId()))
                .toList();
        if (routes.size() != 1) {
            throw new IllegalStateException(
                    "DIRECT Answer requires one exact RouteDecision");
        }
        var route = routes.get(0);
        if (route.decisionKind()
                != ChainPersistenceRecords.RouteDecisionType.INITIAL
                || route.decisionOrdinal() != 0
                || route.route() != ChainExecutionMode.DIRECT
                || route.needsTool() || route.needsNetwork()
                || route.needsProject() || route.needsPersistentProgress()
                || route.directTaskSpecification() == null
                || route.answerRequiredRefs() == null) {
            throw new IllegalStateException(
                    "DIRECT Answer RouteDecision authority is invalid");
        }
        return route;
    }
}
