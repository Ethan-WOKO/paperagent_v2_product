package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.publish.ProductChainPublishAuthoritySource;
import com.yanban.api.agent.v2.chain.recovery.ProductChainFinalizationRecoverySource;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical value encoding that never copies Validation receipt bodies. */
final class ProductValidationPublishValueCodec {
    static final ProductValidationPublishValueCodec INSTANCE =
            new ProductValidationPublishValueCodec();

    private ProductValidationPublishValueCodec() {
    }

    ChainContextValue validation(ProductTypedValidationView value) {
        if (value == null) return ChainContextValue.nil();
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("scope", ChainContextValue.text(
                        value.scope().name())),
                Map.entry("validationRef", ref(value.authorityRef())),
                Map.entry("conclusion", ChainContextValue.text(
                        value.conclusion().name())),
                Map.entry("requestDigest", ChainContextValue.text(
                        value.requestDigest())),
                Map.entry("receiptSetDigest", ChainContextValue.text(
                        value.receiptSetDigest())),
                Map.entry("conclusionDigest", ChainContextValue.text(
                        value.conclusionDigest())),
                Map.entry("sets", ChainContextValue.array(value.sets().stream()
                        .map(this::validationSet).toList()))));
    }

    private ChainContextValue validationSet(
            ProductTypedValidationView.SetView value) {
        var set = value.validation();
        List<ChainContextValue> items = new java.util.ArrayList<>();
        value.candidateItems().stream()
                .sorted(java.util.Comparator.comparing(
                        ChainPersistenceRecords.CandidateValidationItemRecord
                                ::requirementId))
                .map(this::candidateItem).forEach(items::add);
        value.actionReceiptItems().stream()
                .sorted(java.util.Comparator.comparing(
                        ChainPersistenceRecords
                                .ActionReceiptValidationItemRecord
                                ::requirementId))
                .map(this::actionItem).forEach(items::add);
        items.sort(java.util.Comparator.comparing(item ->
                ((ChainContextValue.Text) ((ChainContextValue.ObjectValue) item)
                        .values().get("requirementId")).value()));
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("validationId", ref(set.validationId())),
                Map.entry("stepId", ref(set.stepId())),
                Map.entry("activationEventId", ref(set.activationEventId())),
                Map.entry("requestDigest", ChainContextValue.text(
                        set.requestDigest())),
                Map.entry("receiptSetDigest", ChainContextValue.text(
                        set.receiptSetDigest())),
                Map.entry("conclusionDigest", ChainContextValue.text(
                        set.conclusionDigest())),
                Map.entry("conclusion", ChainContextValue.text(
                        set.conclusion().name())),
                Map.entry("items", ChainContextValue.array(items)),
                Map.entry("receiptBodies", ChainContextValue.array(
                        value.receiptBodies().stream()
                                .map(this::receiptBody).toList()))));
    }

    private ChainContextValue receiptBody(
            ProductTypedValidationView.ReceiptView value) {
        return ChainContextValue.object(Map.of(
                "requirementId", ref(value.requirementId()),
                "receipt", ProductCurrentStepReceiptValueCodec.INSTANCE
                        .full(value.receipt())));
    }

    private ChainContextValue candidateItem(
            ChainPersistenceRecords.CandidateValidationItemRecord value) {
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("subject", ChainContextValue.text("CANDIDATE")),
                Map.entry("requirementId", ref(value.requirementId())),
                Map.entry("requirementDigest", ChainContextValue.text(
                        value.requirementDigest())),
                Map.entry("receiptId", ref(value.receiptId())),
                Map.entry("receiptPayloadSha256", ChainContextValue.text(
                        value.receiptPayloadSha256())),
                Map.entry("actionSignatureSha256", ChainContextValue.text(
                        value.actionSignatureSha256())),
                Map.entry("candidateActionId", ref(value.candidateActionId())),
                Map.entry("validationActionId", ref(
                        value.validationActionId())),
                Map.entry("workspaceCandidateId", ref(
                        value.workspaceCandidateId())),
                Map.entry("workspaceId", ref(value.workspaceId())),
                Map.entry("artifactId", ChainContextValue.number(
                        value.artifactId())),
                Map.entry("candidateFingerprint", ChainContextValue.text(
                        value.candidateFingerprint())),
                Map.entry("baseProjectVersion", ChainContextValue.text(
                        value.baseProjectVersion())),
                Map.entry("conclusion", ChainContextValue.text(
                        value.conclusion().name()))));
    }

    private ChainContextValue actionItem(
            ChainPersistenceRecords.ActionReceiptValidationItemRecord value) {
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("subject", ChainContextValue.text(
                        "ACTION_RECEIPT")),
                Map.entry("requirementId", ref(value.requirementId())),
                Map.entry("requirementDigest", ChainContextValue.text(
                        value.requirementDigest())),
                Map.entry("actionId", ref(value.actionId())),
                Map.entry("receiptId", ref(value.receiptId())),
                Map.entry("receiptPayloadSha256", ChainContextValue.text(
                        value.receiptPayloadSha256())),
                Map.entry("actionSignatureSha256", ChainContextValue.text(
                        value.actionSignatureSha256())),
                Map.entry("conclusion", ChainContextValue.text(
                        value.conclusion().name()))));
    }

    ChainContextValue readiness(
            ChainPersistenceRecords.FinalizationReadinessRecord value) {
        if (value == null) return ChainContextValue.nil();
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("readinessId", ref(value.readinessId())),
                Map.entry("finalStepId", ref(value.finalStepId())),
                Map.entry("workspaceId", ref(value.workspaceId())),
                Map.entry("candidateKey", ChainContextValue.text(
                        value.candidateKey())),
                Map.entry("validationId", ChainContextValue.text(
                        value.validationId())),
                Map.entry("projectVersion", ChainContextValue.text(
                        value.projectVersion())),
                Map.entry("publishRequirement", ChainContextValue.text(
                        value.publishRequirement().name())),
                Map.entry("publishRequirementDigest", ChainContextValue.text(
                        value.publishRequirementDigest())),
                Map.entry("coverageDigest", ChainContextValue.text(
                        value.coverage().sha256()))));
    }

    ChainContextValue check(
            ChainPersistenceRecords.FinalizationCheckRecord value) {
        if (value == null) return ChainContextValue.nil();
        return ChainContextValue.object(Map.ofEntries(
                Map.entry("finalizationCheckId", ref(
                        value.finalizationCheckId())),
                Map.entry("attemptNo", ChainContextValue.number(
                        value.attemptNo())),
                Map.entry("resultStatus", ChainContextValue.text(
                        value.resultStatus().name())),
                Map.entry("errorCode", value.errorCode() == null
                        ? ChainContextValue.nil()
                        : ChainContextValue.text(value.errorCode().name())),
                Map.entry("failureDisposition", ChainContextValue.text(
                        value.failureDisposition().name())),
                Map.entry("inputDigest", ChainContextValue.text(
                        value.inputDigest())),
                Map.entry("contentDigest", ChainContextValue.text(
                        value.contentDigest())),
                Map.entry("publishDigest", ChainContextValue.text(
                        value.publishDigest())),
                Map.entry("runtimePolicyVersion", ChainContextValue.text(
                        value.runtimePolicyVersion()))));
    }

    ChainContextValue publish(ProductValidationPublishFacts facts) {
        if (facts.readiness() != null && facts.readiness().publishRequirement()
                == ChainPublishRequirement.NOT_REQUIRED) {
            return ChainContextValue.object(Map.of(
                    "status", ChainContextValue.text("NOT_REQUIRED"),
                    "readinessId", ref(facts.readiness().readinessId())));
        }
        if (facts.publishOperation() != null) {
            var value = facts.publishOperation();
            return ChainContextValue.object(Map.ofEntries(
                    Map.entry("status", ChainContextValue.text(value.outcome())),
                    Map.entry("operationRef", ref(value.formalRef())),
                    Map.entry("baseProjectVersion", ChainContextValue.text(
                            value.baseVersion())),
                    Map.entry("resultProjectVersion", nullable(
                            value.resultVersion())),
                    Map.entry("resultRevisionId", value.resultRevisionId() == null
                            ? ChainContextValue.nil()
                            : ChainContextValue.number(value.resultRevisionId())),
                    Map.entry("errorCode", nullable(value.errorCode()))));
        }
        if (facts.publishFailure() != null) {
            var value = facts.publishFailure();
            return ChainContextValue.object(Map.of(
                    "status", ChainContextValue.text("FAILED"),
                    "formalFailureRef", ref(value.formalFailureRef()),
                    "errorCode", ChainContextValue.text(
                            value.errorCode().name()),
                    "retryable", ChainContextValue.bool(value.retryable())));
        }
        return ChainContextValue.object(Map.of(
                "status", ChainContextValue.text("NOT_STARTED")));
    }

    ChainContextValue publishRequirement(ProductValidationPublishFacts facts) {
        if (facts.readiness() == null) {
            return ChainContextValue.object(Map.of(
                    "status", ChainContextValue.text("UNDECIDED")));
        }
        return ChainContextValue.object(Map.of(
                "requirement", ChainContextValue.text(
                        facts.readiness().publishRequirement().name()),
                "digest", ChainContextValue.text(
                        facts.readiness().publishRequirementDigest()),
                "source", ref(facts.readiness().readinessId())));
    }

    ChainContextValue failureSummary(ProductValidationPublishFacts facts) {
        Map<String, ChainContextValue> result = new LinkedHashMap<>();
        boolean validationFailed = facts.validation() != null
                && facts.validation().conclusion()
                == io.paperagent.v2.chain.ChainValidationConclusion.FAILED;
        result.put("validation", validationFailed
                ? ChainContextValue.object(Map.of(
                "validationRef", ref(facts.validation().authorityRef()),
                "conclusion", ChainContextValue.text("FAILED")))
                : ChainContextValue.nil());
        result.put("finalization", facts.latestCheck() != null
                && facts.latestCheck().errorCode() != null
                ? ChainContextValue.object(Map.of(
                "checkId", ref(facts.latestCheck().finalizationCheckId()),
                "errorCode", ChainContextValue.text(
                        facts.latestCheck().errorCode().name())))
                : ChainContextValue.nil());
        result.put("publish", facts.publishFailure() == null
                ? ChainContextValue.nil()
                : ChainContextValue.object(Map.of(
                "failureRef", ref(facts.publishFailure().formalFailureRef()),
                "errorCode", ChainContextValue.text(
                        facts.publishFailure().errorCode().name()))));
        return ChainContextValue.object(result);
    }

    ChainContextValue finalizationFailureSeparated(
            ProductValidationPublishFacts facts) {
        boolean validationFailed = facts.validation() != null
                && facts.validation().conclusion()
                == io.paperagent.v2.chain.ChainValidationConclusion.FAILED;
        return ChainContextValue.object(Map.of(
                "validationFailure", validationFailed
                        ? ChainContextValue.object(Map.of(
                        "validationRef", ref(
                                facts.validation().authorityRef()),
                        "conclusion", ChainContextValue.text("FAILED")))
                        : ChainContextValue.nil(),
                "finalizationFailure", facts.latestCheck() != null
                        && facts.latestCheck().errorCode() != null
                        ? check(facts.latestCheck()) : ChainContextValue.nil()));
    }

    ChainContextValue coverage(ProductValidationPublishFacts facts) {
        var outcome = facts.outcome();
        if (outcome == null) throw blocked("Answer coverage requires TaskOutcome");
        return ChainContextValue.object(Map.of(
                "coverage", referenced(outcome.coverage(), outcome.outcomeId()),
                "incompleteItems", referenced(
                        outcome.incompleteItems(), outcome.outcomeId()),
                "limitations", referenced(
                        outcome.limitations(), outcome.outcomeId()),
                "risks", referenced(outcome.risks(), outcome.outcomeId())));
    }

    private static ChainContextValue referenced(
            ChainPersistenceRecords.CanonicalJson value, String authority) {
        return ChainContextValue.referencedText(value.json(), authority);
    }

    ChainContextValue nullable(String value) {
        return value == null ? ChainContextValue.nil()
                : ChainContextValue.text(value);
    }

    ChainContextValue.Text ref(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(
                ChainContextModule.VALIDATION_AND_PUBLISH, reason);
    }
}
