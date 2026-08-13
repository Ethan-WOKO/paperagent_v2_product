package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure role-field selection and four-domain version projection. */
final class ProductValidationPublishProjectionValues {
    private static final ChainContextModule MODULE =
            ChainContextModule.VALIDATION_AND_PUBLISH;

    private ProductValidationPublishProjectionValues() {
    }

    static Values create(
            List<String> requiredFields, ProductValidationPublishFacts facts) {
        Map<String, ChainContextValue> fields = new TreeMap<>();
        for (String field : requiredFields) {
            fields.put(field, field(field, facts));
        }
        long readinessEvent = event(facts,
                facts.readiness() == null ? null : facts.readiness().eventId());
        long checkEvent = event(facts, facts.latestCheck() == null
                ? null : facts.latestCheck().eventId());
        long outcomeEvent = event(facts, facts.outcome() == null
                ? null : facts.outcome().eventId());
        Map<String, ChainContextValue> source = Map.of(
                "validationIdentityStatusAndDigest", validationVersion(facts),
                "readiness", facts.readiness() == null
                        ? ChainContextValue.text("NONE")
                        : ChainContextValue.object(Map.of(
                        "readinessId", codec().ref(
                                facts.readiness().readinessId()),
                        "eventSequence", ChainContextValue.number(
                                readinessEvent))),
                "finalizationAttempt", facts.latestCheck() == null
                        ? ChainContextValue.number(0)
                        : ChainContextValue.object(Map.of(
                        "checkId", codec().ref(
                                facts.latestCheck().finalizationCheckId()),
                        "attemptNo", ChainContextValue.number(
                                facts.latestCheck().attemptNo()),
                        "eventSequence", ChainContextValue.number(checkEvent))),
                "publishOperationAndVersion", publishVersion(facts));
        Map<String, ChainContextValue> boundary = Map.of(
                "candidate", candidateBoundary(facts),
                "workspace", codec().nullable(facts.building().workspaceId()),
                "validationCut", ChainContextValue.object(Map.of(
                        "taskEventSequence", ChainContextValue.number(
                                Math.max(readinessEvent,
                                        Math.max(checkEvent, outcomeEvent))),
                        "validationRef", facts.validation() != null
                                ? codec().ref(facts.validation().authorityRef())
                                : ChainContextValue.text("NONE"))));
        Map<String, ChainContextValue> parameters = new LinkedHashMap<>();
        parameters.put("taskRef", codec().ref(facts.building().taskId()));
        parameters.put("role", ChainContextValue.text(
                facts.building().role().name()));
        parameters.put("taskAuthorityHead", ChainContextValue.number(
                facts.taskEventCut()));
        return new Values(source, boundary, Map.copyOf(parameters), fields);
    }

    private static ChainContextValue field(
            String field, ProductValidationPublishFacts facts) {
        return switch (field) {
            case "validation.latestState" -> codec().validation(
                    facts.validation());
            case "validation.finalizationState" -> finalizationState(facts);
            case "validation.publishState" -> codec().publish(facts);
            case "validation.failureSummary" -> codec().failureSummary(facts);
            case "validation.publishRequirement" ->
                    codec().publishRequirement(facts);
            case "validation.currentStepFormalValidation" ->
                    currentStepValidation(facts);
            case "validation.finalizationFailureSeparated" ->
                    codec().finalizationFailureSeparated(facts);
            case "validation.authoritativeValidation" ->
                    authoritativeValidation(facts);
            case "validation.finalizationCheckResult" ->
                    codec().check(facts.latestCheck());
            case "validation.publishFailure" -> publishFailure(facts);
            case "validation.finalValidation" -> finalValidation(facts);
            case "validation.coverageSkipAndRisk" -> codec().coverage(facts);
            case "validation.finalizationAndPublishResultOrNotRequired" ->
                    terminalResult(facts);
            default -> throw blocked("unsupported validation field: " + field);
        };
    }

    private static ChainContextValue currentStepValidation(
            ProductValidationPublishFacts facts) {
        if (facts.validation() == null
                || facts.validation().scope()
                != ProductTypedValidationView.Scope.CURRENT_STEP) {
            throw blocked("Executor requires current Step formal Validation");
        }
        return ChainContextValue.object(Map.of(
                "validation", codec().validation(facts.validation())));
    }

    private static ChainContextValue authoritativeValidation(
            ProductValidationPublishFacts facts) {
        return facts.validation() == null
                ? ChainContextValue.object(Map.of(
                "status", ChainContextValue.text(
                        facts.validationRequired()
                                ? "PENDING" : "NOT_REQUIRED")))
                : codec().validation(facts.validation());
    }

    private static ChainContextValue finalValidation(
            ProductValidationPublishFacts facts) {
        if (facts.outcome() == null) {
            throw blocked("Answer final Validation requires TaskOutcome");
        }
        if (facts.outcome().finalizationReadinessId() == null) {
            return ChainContextValue.object(Map.of(
                    "status", ChainContextValue.text("UNAVAILABLE"),
                    "reason", ChainContextValue.text(
                            "NO_EXACT_TERMINAL_ROOT")));
        }
        if (io.paperagent.v2.chain.ChainIdentity.NONE.equals(
                facts.outcome().validationId())) {
            return ChainContextValue.object(Map.of(
                    "status", ChainContextValue.text("NOT_REQUIRED")));
        }
        if (facts.validation() == null
                || facts.validation().scope()
                != ProductTypedValidationView.Scope.PLAN) {
            throw blocked("TaskOutcome final Validation is missing");
        }
        return codec().validation(facts.validation());
    }

    private static ChainContextValue finalizationState(
            ProductValidationPublishFacts facts) {
        return ChainContextValue.object(Map.of(
                "readiness", codec().readiness(facts.readiness()),
                "latestCheck", codec().check(facts.latestCheck())));
    }

    private static ChainContextValue publishFailure(
            ProductValidationPublishFacts facts) {
        if (facts.publishFailure() == null) return ChainContextValue.nil();
        return codec().publish(facts);
    }

    private static ChainContextValue terminalResult(
            ProductValidationPublishFacts facts) {
        if (facts.outcome() == null) {
            throw blocked("Answer finalization result requires TaskOutcome");
        }
        if (facts.readiness() == null) {
            return ChainContextValue.object(Map.of(
                    "finalization", ChainContextValue.text("NOT_REQUIRED"),
                    "publish", ChainContextValue.text("NOT_REQUIRED"),
                    "taskOutcomeId", codec().ref(
                            facts.outcome().outcomeId())));
        }
        if (facts.latestCheck() == null) {
            throw blocked("Answer finalization check is missing");
        }
        return ChainContextValue.object(Map.of(
                "readiness", codec().readiness(facts.readiness()),
                "finalizationCheck", codec().check(facts.latestCheck()),
                "publish", codec().publish(facts),
                "taskOutcomeId", codec().ref(facts.outcome().outcomeId())));
    }

    private static ChainContextValue validationVersion(
            ProductValidationPublishFacts facts) {
        if (facts.validation() == null) {
            return ChainContextValue.text(facts.validationRequired()
                    ? "PENDING" : "NOT_REQUIRED");
        }
        return ChainContextValue.object(Map.of(
                "validationRef", codec().ref(
                        facts.validation().authorityRef()),
                "scope", ChainContextValue.text(
                        facts.validation().scope().name()),
                "conclusion", ChainContextValue.text(
                        facts.validation().conclusion().name()),
                "requestDigest", ChainContextValue.text(
                        facts.validation().requestDigest()),
                "receiptSetDigest", ChainContextValue.text(
                        facts.validation().receiptSetDigest())));
    }

    private static ChainContextValue publishVersion(
            ProductValidationPublishFacts facts) {
        if (facts.readiness() != null
                && facts.readiness().publishRequirement()
                == io.paperagent.v2.chain.ChainPublishRequirement.NOT_REQUIRED) {
            return ChainContextValue.text("NOT_REQUIRED");
        }
        if (facts.publishOperation() != null) {
            return ChainContextValue.object(Map.of(
                    "operationRef", codec().ref(
                            facts.publishOperation().formalRef()),
                    "baseVersion", ChainContextValue.text(
                            facts.publishOperation().baseVersion()),
                    "resultVersion", codec().nullable(
                            facts.publishOperation().resultVersion())));
        }
        if (facts.publishFailure() != null) {
            return codec().ref(facts.publishFailure().formalFailureRef());
        }
        return ChainContextValue.text("NONE");
    }

    private static ChainContextValue candidateBoundary(
            ProductValidationPublishFacts facts) {
        if (facts.candidate() == null) return ChainContextValue.text("NONE");
        return ChainContextValue.object(Map.of(
                "artifactId", ChainContextValue.number(
                        facts.candidate().artifactId()),
                "fingerprint", ChainContextValue.text(
                        facts.candidate().candidateFingerprint()),
                "candidateResultId", codec().ref(
                        facts.candidate().candidateResultId())));
    }

    private static long event(ProductValidationPublishFacts facts, String id) {
        return id == null ? 0 : facts.sequences().get(id);
    }

    private static ProductValidationPublishValueCodec codec() {
        return ProductValidationPublishValueCodec.INSTANCE;
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }

    record Values(Map<String, ChainContextValue> sourceVersion,
                  Map<String, ChainContextValue> readBoundary,
                  Map<String, ChainContextValue> parameters,
                  Map<String, ChainContextValue> fields) {
    }
}
