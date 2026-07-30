package com.yanban.api.agent.v2.adaptive;

import java.util.List;

/** One durable runtime cycle. Implementations may never hide retries. */
@FunctionalInterface
public interface V2AdaptiveCyclePort {
    CycleResult executeOne(CycleCommand command);

    record CycleCommand(
            Long userId, Long turnId, String planId, int cycle,
            Object replanRequest) {
    }

    record CycleResult(
            State state, String stepId, String detail,
            boolean durableSucceeded, Object replanAuthority,
            List<String> authoritativeFacts,
            boolean receiptBacked,
            boolean failedReceipt) {
        public CycleResult {
            authoritativeFacts = authoritativeFacts == null
                    ? List.of() : List.copyOf(authoritativeFacts);
        }

        public CycleResult(
                State state, String stepId, String detail,
                boolean durableSucceeded, Object replanAuthority) {
            this(state, stepId, detail, durableSucceeded, replanAuthority,
                    List.of(), false, false);
        }

        public enum State {
            STEP_SUCCEEDED, REPLAN_REQUIRED, PLAN_SUCCEEDED, FAILED
        }
    }
}
