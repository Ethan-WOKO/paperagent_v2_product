package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.List;
import java.util.Map;

/** Pure canonical values for formal review, gap and transition facts. */
final class ProductReviewPendingValueCodec {
    static final ProductReviewPendingValueCodec INSTANCE =
            new ProductReviewPendingValueCodec();

    private ProductReviewPendingValueCodec() {
    }

    ChainContextValue review(ProductReviewPendingFacts.ReviewView value) {
        var decision = value.decision();
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("reviewDecisionRef", ref(
                        decision.reviewDecisionId())),
                Map.entry("eventSequence", ChainContextValue.number(
                        value.eventSequence())),
                Map.entry("proposalRef", ref(decision.proposalId())),
                Map.entry("reviewObjectType", ChainContextValue.text(
                        decision.reviewObjectType())),
                Map.entry("reviewObjectRef", ref(
                        decision.reviewObjectId())),
                Map.entry("decisionKind", ChainContextValue.text(
                        decision.decisionKind().wireName())),
                Map.entry("reason", ChainContextValue.text(decision.reason())),
                Map.entry("factRefs", canonical(decision.factRefs())),
                Map.entry("versionFenceDigest", ChainContextValue.text(
                        decision.versionFenceSha256())),
                Map.entry("candidateBinding", value.candidate() == null
                        ? ChainContextValue.nil() : candidate(value.candidate()))));
    }

    ChainContextValue pending(ProductReviewPendingFacts.PendingView value) {
        var item = value.item();
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("gapRef", ref(item.gapId())),
                Map.entry("eventSequence", ChainContextValue.number(
                        value.eventSequence())),
                Map.entry("sourceProposalRef", ref(item.sourceProposalId())),
                Map.entry("pendingType", ChainContextValue.text(
                        item.pendingType().name())),
                Map.entry("status", ChainContextValue.text(
                        value.currentStatus().name())),
                Map.entry("gapIdentityDigest", ChainContextValue.text(
                        item.gapIdentitySha256())),
                Map.entry("missingFields", canonical(item.missingFields())),
                Map.entry("permissionScope", nullable(item.permissionScope())),
                Map.entry("question", ChainContextValue.text(item.question())),
                Map.entry("expectedFormat", ChainContextValue.text(
                        item.expectedFormat())),
                Map.entry("validationRole", ChainContextValue.text(
                        item.validationRole().name())),
                Map.entry("resumeRole", ChainContextValue.text(
                        item.resumeRole().name())),
                Map.entry("resumePosition", canonical(item.resumePosition())),
                Map.entry("versionFenceDigest", ChainContextValue.text(
                        item.versionFenceSha256())),
                Map.entry("events", ChainContextValue.array(value.events()
                        .stream().map(this::pendingEvent).toList()))));
    }

    ChainContextValue permission(
            ProductReviewPendingFacts.PermissionView value) {
        var decision = value.decision();
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("permissionDecisionRef", ref(
                        decision.permissionDecisionId())),
                Map.entry("eventSequence", ChainContextValue.number(
                        value.eventSequence())),
                Map.entry("gapRef", ref(decision.gapId())),
                Map.entry("permissionScope", ChainContextValue.text(
                        decision.permissionScope())),
                Map.entry("productAuthorityType", ChainContextValue.text(
                        decision.productAuthorityType())),
                Map.entry("productAuthorityRef", ref(
                        decision.productAuthorityRef())),
                Map.entry("decision", ChainContextValue.text(
                        decision.decision().name())),
                Map.entry("reason", ChainContextValue.text(
                        decision.reason()))));
    }

    ChainContextValue disposition(
            ProductReviewPendingFacts.DispositionView value) {
        var disposition = value.disposition();
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("dispositionRef", ref(
                        disposition.dispositionId())),
                Map.entry("eventSequence", ChainContextValue.number(
                        value.eventSequence())),
                Map.entry("proposalRef", ref(disposition.proposalId())),
                Map.entry("instructionRef", ref(
                        disposition.instructionId())),
                Map.entry("classification", ChainContextValue.text(
                        disposition.classification())),
                Map.entry("oldTaskDisposition", ChainContextValue.text(
                        disposition.oldTaskDisposition())),
                Map.entry("replyRequired", ChainContextValue.bool(
                        disposition.replyRequired())),
                Map.entry("continuationOrReintakePosition",
                        ChainContextValue.text(
                                disposition.continuationOrReintakePosition())),
                Map.entry("boundaryChanged", ChainContextValue.bool(
                        disposition.boundaryChanged())),
                Map.entry("applicability", canonical(
                        disposition.applicability())),
                Map.entry("reuseSuggestions", canonical(
                        disposition.nonAuthoritativeReuseSuggestions()))));
    }

    ChainContextValue transition(
            ProductReviewPendingFacts.TransitionView value) {
        var transition = value.transition();
        return ChainContextValue.object(Map.of(
                "transitionRef", ref(transition.transitionId()),
                "eventSequence", ChainContextValue.number(
                        value.eventSequence()),
                "transitionType", ChainContextValue.text(
                        transition.transitionType().name()),
                "sourceDecisionRef", ref(transition.sourceDecisionId()),
                "targetIdentityDigest", ChainContextValue.text(
                        transition.targetIdentityDigest()),
                "complete", ChainContextValue.bool(value.complete()),
                "stages", ChainContextValue.array(value.stages().stream()
                        .map(this::transitionStage).toList())));
    }

    private ChainContextValue candidate(
            ChainPersistenceRecords.CandidateStepResultRecord value) {
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("candidateResultRef", ref(
                        value.candidateResultId())),
                Map.entry("taskFrameRef", ref(value.taskFrameId())),
                Map.entry("planRef", ref(value.planId())),
                Map.entry("planRevisionRef", ref(value.planRevisionId())),
                Map.entry("planRevisionNumber", ChainContextValue.number(
                        value.planRevisionNumber())),
                Map.entry("stepRef", ref(value.stepId())),
                Map.entry("activationRef", ref(value.activationEventId())),
                Map.entry("candidateFingerprint", nullable(
                        value.candidateFingerprint()))));
    }

    private ChainContextValue pendingEvent(
            ProductReviewPendingFacts.PendingEventView value) {
        var event = value.event();
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("eventRef", ref(event.eventId())),
                Map.entry("eventSequence", ChainContextValue.number(
                        value.eventSequence())),
                Map.entry("responseRound", ChainContextValue.number(
                        event.responseRound())),
                Map.entry("status", ChainContextValue.text(
                        event.eventKind().name())),
                Map.entry("answerInstructionRef", nullable(
                        event.answerInstructionId())),
                Map.entry("validationInvocationRef", nullable(
                        event.validationInvocationId())),
                Map.entry("gapValidationOutcome",
                        event.gapValidationOutcome() == null
                                ? ChainContextValue.nil()
                                : ChainContextValue.text(
                                event.gapValidationOutcome().name())),
                Map.entry("detail", canonical(event.detail()))));
    }

    private ChainContextValue transitionStage(
            ProductReviewPendingFacts.TransitionStageView value) {
        var stage = value.stage();
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("stage", ChainContextValue.text(
                        stage.stageCode().name())),
                Map.entry("ordinal", ChainContextValue.number(
                        stage.stageOrdinal())),
                Map.entry("eventRef", ref(stage.eventId())),
                Map.entry("eventSequence", ChainContextValue.number(
                        value.eventSequence())),
                Map.entry("predecessorAuthorityType", nullable(
                        stage.predecessorAuthorityType())),
                Map.entry("predecessorAuthorityRef", nullable(
                        stage.predecessorAuthorityRef())),
                Map.entry("successorAuthorityType", nullable(
                        stage.successorAuthorityType())),
                Map.entry("successorAuthorityRef", nullable(
                        stage.successorAuthorityRef()))));
    }

    ChainContextValue canonical(
            ChainPersistenceRecords.CanonicalJson value) {
        return ChainContextValue.object(Map.of(
                "schemaVersion", ChainContextValue.number(
                        value.formatVersion()),
                "sha256", ChainContextValue.text(value.sha256()),
                "json", ChainContextValue.text(value.json())));
    }

    private static ChainContextValue nullable(String value) {
        return value == null ? ChainContextValue.nil()
                : ChainContextValue.text(value);
    }

    private static ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }
}
