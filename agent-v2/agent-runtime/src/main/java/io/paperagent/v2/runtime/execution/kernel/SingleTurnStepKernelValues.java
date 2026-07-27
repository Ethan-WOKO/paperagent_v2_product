package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;

import java.util.Set;

final class SingleTurnStepKernelValues {
    private static final Set<String> VALIDATION_PATHS = Set.of(
            "singleTurnStepKernel.stepTurnPort",
            "singleTurnStepKernel.effectIntentRepository",
            "singleTurnStepKernel.request",
            "singleTurnStepKernelRequest.recoveredStep",
            "stepTurnInput.taskFrame",
            "stepTurnInput.plan",
            "stepTurnInput.checkpoint",
            "stepTurnInput.activeStep",
            "effectIntentDecision.intent",
            "singleTurnNoEffect.planId",
            "singleTurnNoEffect.stepId",
            "singleTurnIntentPersisted.persistedIntent",
            "singleTurnPersistenceRejected.planId",
            "singleTurnPersistenceRejected.stepId",
            "singleTurnPersistenceRejected.failure");
    private static final Set<String> PROTOCOL_PATHS = Set.of(
            "singleTurnStepKernel.recoveredAuthority",
            "singleTurnStepKernel.turnDecision",
            "singleTurnStepKernel.intentPersistResult",
            "singleTurnStepKernel.intentPersistResult.outcome",
            "singleTurnStepKernel.intentPersistResult.value",
            "singleTurnStepKernel.intentPersistResult.failure");

    private SingleTurnStepKernelValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw failure(SingleTurnStepKernelValidationCode.REQUIRED_VALUE_MISSING, path);
        }
        return value;
    }

    static SingleTurnStepKernelValidationException failure(
            SingleTurnStepKernelValidationCode code,
            String path) {
        return new SingleTurnStepKernelValidationException(
                requiredInternal(code, "code"), requiredInternal(path, "path"));
    }

    static SingleTurnStepKernelProtocolException protocolFailure(
            PlanId planId,
            PlanStepId stepId,
            SingleTurnStepKernelStage stage,
            SingleTurnStepKernelProtocolCode code,
            String path,
            Throwable cause) {
        return new SingleTurnStepKernelProtocolException(
                requiredInternal(planId, "planId"),
                requiredInternal(stepId, "stepId"),
                requiredInternal(stage, "stage"),
                requiredInternal(code, "code"),
                requiredInternal(path, "path"),
                cause);
    }

    static String validationPath(String path) {
        requiredInternal(path, "path");
        if (!VALIDATION_PATHS.contains(path)) {
            throw new IllegalArgumentException("path is not in the validation lexicon");
        }
        return path;
    }

    static String protocolPath(String path) {
        requiredInternal(path, "path");
        if (!PROTOCOL_PATHS.contains(path)) {
            throw new IllegalArgumentException("path is not in the protocol lexicon");
        }
        return path;
    }

    static <T> T requiredInternal(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
