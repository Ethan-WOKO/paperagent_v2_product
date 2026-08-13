package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import org.junit.jupiter.api.Test;

import static io.paperagent.v2.chain.step.ChainAbnormalSuccessorPolicy.Successor.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChainAbnormalSuccessorTest {
    @Test
    void everyAbnormalFormalStateHasOneBoundedSuccessor() {
        ChainAbnormalSuccessorPolicy successors =
                new ChainAbnormalSuccessorPolicy(ChainRuntimePolicy.V1);

        assertEquals(RETRY_MODEL_SAME_CONTEXT,
                successors.modelCallFailed(ChainRole.PLANNER, 1));
        assertEquals(FAIL_TASK_THEN_ANSWER,
                successors.modelCallFailed(ChainRole.PLANNER, 3));
        assertEquals(BLOCK_STEP_THEN_REFLECTOR,
                successors.modelCallFailed(ChainRole.EXECUTOR, 3));
        assertEquals(FAIL_TASK_THEN_ANSWER,
                successors.modelCallFailed(ChainRole.REFLECTOR, 3));
        assertEquals(RETRY_ANSWER_SAME_FORMAL_SOURCE,
                successors.modelCallFailed(ChainRole.ANSWER, 1));
        assertEquals(DELIVERY_FAILED,
                successors.modelCallFailed(ChainRole.ANSWER, 3));
        assertEquals(CREATE_FORMAL_GAP,
                successors.planningBlocked(true));
        assertEquals(FAIL_TASK_THEN_ANSWER,
                successors.planningBlocked(false));
        assertEquals(PAGE_FROZEN_INPUT,
                successors.contextInputBlocked(true, false));
        assertEquals(REVISE_TO_SMALLER_STEP,
                successors.contextInputBlocked(false, true));
        assertEquals(FAIL_TASK_THEN_ANSWER,
                successors.contextBuildFailed(ChainRole.PLANNER));
        assertEquals(BLOCK_STEP_THEN_REFLECTOR,
                successors.contextBuildFailed(ChainRole.EXECUTOR));
        assertEquals(FAIL_TASK_THEN_ANSWER,
                successors.contextBuildFailed(ChainRole.REFLECTOR));
        assertEquals(DELIVERY_FAILED,
                successors.contextBuildFailed(ChainRole.ANSWER));
        assertEquals(PLAN_LOWER_PERMISSION_PATH,
                successors.permissionRejected(true));
        assertEquals(REFLECT_SCHEDULING_BLOCK,
                successors.stepSchedulingBlocked());
        assertEquals(RECONCILE_SAME_ACTION,
                successors.effectStatusUnknown("action-1"));
        assertEquals(RETRY_SAME_FINALIZATION_TRANSITION,
                successors.finalizationFailed(
                        ChainFinalization.ErrorCode
                                .AUTHORITY_TEMPORARILY_UNAVAILABLE, 1));
        assertEquals(REFLECT_FORMAL_FINALIZATION_FAILURE,
                successors.finalizationFailed(
                        ChainFinalization.ErrorCode
                                .AUTHORITY_TEMPORARILY_UNAVAILABLE, 2));
        assertEquals(COMPLETE_FORMAL_DELIVERY_PREDECESSOR,
                successors.deliveryBlocked(true, 3));
        assertEquals(DELIVERY_FAILED,
                successors.deliveryBlocked(false, 3));
    }
}
