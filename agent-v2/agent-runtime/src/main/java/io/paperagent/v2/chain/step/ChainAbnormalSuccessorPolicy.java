package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;

import java.util.Objects;

/** One mechanical successor for each stage-4 abnormal formal state. */
public final class ChainAbnormalSuccessorPolicy {
    private final ChainRuntimePolicy policy;

    public ChainAbnormalSuccessorPolicy(ChainRuntimePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public Successor modelCallFailed(
            ChainRole role, int attemptsUsed) {
        Objects.requireNonNull(role, "role");
        nonNegative(attemptsUsed, "attemptsUsed");
        if (attemptsUsed < policy.providerAttemptsTotal()) {
            return role == ChainRole.ANSWER
                    ? Successor.RETRY_ANSWER_SAME_FORMAL_SOURCE
                    : Successor.RETRY_MODEL_SAME_CONTEXT;
        }
        return switch (role) {
            case PLANNER, REFLECTOR -> Successor.FAIL_TASK_THEN_ANSWER;
            case EXECUTOR -> Successor.BLOCK_STEP_THEN_REFLECTOR;
            case ANSWER -> Successor.DELIVERY_FAILED;
        };
    }

    public Successor planningBlocked(boolean recoverableGap) {
        return recoverableGap
                ? Successor.CREATE_FORMAL_GAP
                : Successor.FAIL_TASK_THEN_ANSWER;
    }

    public Successor contextInputBlocked(
            boolean deterministicPageAvailable,
            boolean existingStep) {
        if (deterministicPageAvailable) {
            return Successor.PAGE_FROZEN_INPUT;
        }
        return existingStep
                ? Successor.REVISE_TO_SMALLER_STEP
                : Successor.FAIL_TASK_THEN_ANSWER;
    }

    /** A source-reader failure has no formal proof that body paging helps. */
    public Successor contextBuildFailed(ChainRole role) {
        Objects.requireNonNull(role, "role");
        return switch (role) {
            case PLANNER, REFLECTOR -> Successor.FAIL_TASK_THEN_ANSWER;
            case EXECUTOR -> Successor.BLOCK_STEP_THEN_REFLECTOR;
            case ANSWER -> Successor.DELIVERY_FAILED;
        };
    }

    public Successor permissionRejected(
            boolean lowerPermissionPathAvailable) {
        return lowerPermissionPathAvailable
                ? Successor.PLAN_LOWER_PERMISSION_PATH
                : Successor.FAIL_TASK_THEN_ANSWER;
    }

    public Successor stepSchedulingBlocked() {
        return Successor.REFLECT_SCHEDULING_BLOCK;
    }

    public Successor effectStatusUnknown(String actionId) {
        required(actionId, "actionId");
        return Successor.RECONCILE_SAME_ACTION;
    }

    public Successor finalizationFailed(
            ChainFinalization.ErrorCode errorCode,
            int attemptsUsed) {
        Objects.requireNonNull(errorCode, "errorCode");
        nonNegative(attemptsUsed, "attemptsUsed");
        if (errorCode
                == ChainFinalization.ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE
                && attemptsUsed
                < policy.finalizationMechanicalAttemptsTotal()) {
            return Successor.RETRY_SAME_FINALIZATION_TRANSITION;
        }
        return Successor.REFLECT_FORMAL_FINALIZATION_FAILURE;
    }

    public Successor publishConflict() {
        return Successor.REFLECT_FORMAL_FINALIZATION_FAILURE;
    }

    public Successor deliveryBlocked(
            boolean formalPredecessorMissing,
            int attemptsUsed) {
        nonNegative(attemptsUsed, "attemptsUsed");
        if (formalPredecessorMissing) {
            return Successor.COMPLETE_FORMAL_DELIVERY_PREDECESSOR;
        }
        return attemptsUsed < policy.deliveryAttemptsTotal()
                ? Successor.RETRY_ANSWER_SAME_FORMAL_SOURCE
                : Successor.DELIVERY_FAILED;
    }

    public enum Successor {
        RETRY_MODEL_SAME_CONTEXT,
        RETRY_ANSWER_SAME_FORMAL_SOURCE,
        FAIL_TASK_THEN_ANSWER,
        BLOCK_STEP_THEN_REFLECTOR,
        DELIVERY_FAILED,
        CREATE_FORMAL_GAP,
        PAGE_FROZEN_INPUT,
        REVISE_TO_SMALLER_STEP,
        PLAN_LOWER_PERMISSION_PATH,
        REFLECT_SCHEDULING_BLOCK,
        RECONCILE_SAME_ACTION,
        RETRY_SAME_FINALIZATION_TRANSITION,
        REFLECT_FORMAL_FINALIZATION_FAILURE,
        COMPLETE_FORMAL_DELIVERY_PREDECESSOR
    }

    private static void nonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    name + " must not be negative");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
