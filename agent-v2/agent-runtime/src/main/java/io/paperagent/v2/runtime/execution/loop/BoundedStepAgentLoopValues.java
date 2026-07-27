package io.paperagent.v2.runtime.execution.loop;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedEffectIntent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class BoundedStepAgentLoopValues {
    static final int MIN_TURNS = 1;
    static final int MAX_TURNS = 16;

    private static final Set<String> VALIDATION_PATHS = Set.of(
            "boundedStepAgentLoop.singleTurnStepKernel",
            "boundedStepAgentLoop.request",
            "boundedStepAgentLoopRequest.recoveredStep",
            "boundedStepAgentLoopRequest.maxTurns",
            "boundedStepAgentLoopNoEffect.planId",
            "boundedStepAgentLoopNoEffect.stepId",
            "boundedStepAgentLoopNoEffect.turnsExecuted",
            "boundedStepAgentLoopNoEffect.persistedIntents",
            "boundedStepAgentLoopPersistenceRejected.planId",
            "boundedStepAgentLoopPersistenceRejected.stepId",
            "boundedStepAgentLoopPersistenceRejected.turnsExecuted",
            "boundedStepAgentLoopPersistenceRejected.persistedIntents",
            "boundedStepAgentLoopPersistenceRejected.failure",
            "boundedStepAgentLoopTurnLimitReached.planId",
            "boundedStepAgentLoopTurnLimitReached.stepId",
            "boundedStepAgentLoopTurnLimitReached.turnsExecuted",
            "boundedStepAgentLoopTurnLimitReached.persistedIntents");
    private static final Set<String> PROTOCOL_PATHS = Set.of(
            "boundedStepAgentLoop.kernelRun",
            "boundedStepAgentLoop.kernelOutcome");

    private BoundedStepAgentLoopValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw failure(BoundedStepAgentLoopValidationCode.REQUIRED_VALUE_MISSING, path);
        }
        return value;
    }

    static int maxTurns(int value, String path) {
        if (value < MIN_TURNS || value > MAX_TURNS) {
            throw failure(BoundedStepAgentLoopValidationCode.INVALID_MAX_TURNS, path);
        }
        return value;
    }

    static int positiveTurns(int value, String path) {
        if (value < MIN_TURNS) {
            throw failure(BoundedStepAgentLoopValidationCode.INVALID_TURNS_EXECUTED, path);
        }
        return value;
    }

    static List<PersistedEffectIntent> intents(
            List<PersistedEffectIntent> values,
            String path) {
        required(values, path);
        List<PersistedEffectIntent> copy = new ArrayList<>(values.size());
        for (PersistedEffectIntent value : values) {
            if (value == null) {
                throw failure(BoundedStepAgentLoopValidationCode.NULL_DURABLE_INTENT, path);
            }
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    static void requirePriorIntentCount(
            int turnsExecuted,
            List<PersistedEffectIntent> persistedIntents,
            String path) {
        if (persistedIntents.size() != turnsExecuted - 1) {
            throw failure(BoundedStepAgentLoopValidationCode.INVALID_DURABLE_INTENT_COUNT, path);
        }
    }

    static void requireAllIntentCount(
            int turnsExecuted,
            List<PersistedEffectIntent> persistedIntents,
            String path) {
        if (persistedIntents.size() != turnsExecuted) {
            throw failure(BoundedStepAgentLoopValidationCode.INVALID_DURABLE_INTENT_COUNT, path);
        }
    }

    static BoundedStepAgentLoopValidationException failure(
            BoundedStepAgentLoopValidationCode code,
            String path) {
        return new BoundedStepAgentLoopValidationException(
                requiredInternal(code, "code"), requiredInternal(path, "path"));
    }

    static BoundedStepAgentLoopProtocolException protocolFailure(
            PlanId planId,
            PlanStepId stepId,
            BoundedStepAgentLoopStage stage,
            BoundedStepAgentLoopProtocolCode code,
            String path,
            Throwable cause) {
        return new BoundedStepAgentLoopProtocolException(
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
