package com.yanban.api.agent.v2.adaptive;

import com.yanban.api.agent.v2.result.V2StepResultSnapshot;
import io.paperagent.v2.contracts.ReceiptId;
import java.util.List;
import java.util.Optional;

/** One durable runtime cycle. Implementations may never hide retries. */
@FunctionalInterface
public interface V2AdaptiveCyclePort {
    CycleResult executeOne(CycleCommand command);

    record CycleCommand(
            Long userId, Long turnId, String planId, int cycle,
            Object replanRequest, Action action) {
        public CycleCommand(
                Long userId, Long turnId, String planId, int cycle,
                Object replanRequest) {
            this(userId, turnId, planId, cycle, replanRequest,
                    Action.EXECUTE);
        }

        public CycleCommand {
            action = action == null ? Action.EXECUTE : action;
        }
    }

    enum Action {
        EXECUTE,
        COMPLETE_STEP
    }

    record CycleResult(
            State state, String stepId, String detail,
            boolean durableSucceeded, Object replanAuthority,
            List<String> authoritativeFacts,
            boolean receiptBacked,
            boolean failedReceipt,
            List<ReceiptId> receiptIds,
            Optional<V2StepResultSnapshot> stepResult) {
        public CycleResult {
            authoritativeFacts = authoritativeFacts == null
                    ? List.of() : List.copyOf(authoritativeFacts);
            stepResult = stepResult == null
                    ? Optional.empty() : stepResult;
            receiptIds = receiptIds == null
                    ? List.of() : List.copyOf(receiptIds);
        }

        public CycleResult(
                State state, String stepId, String detail,
                boolean durableSucceeded, Object replanAuthority,
                List<String> authoritativeFacts,
                boolean receiptBacked,
                boolean failedReceipt,
                Optional<V2StepResultSnapshot> stepResult) {
            this(state, stepId, detail, durableSucceeded, replanAuthority,
                    authoritativeFacts, receiptBacked, failedReceipt,
                    List.of(), stepResult);
        }

        public CycleResult(
                State state, String stepId, String detail,
                boolean durableSucceeded, Object replanAuthority,
                List<String> authoritativeFacts,
                boolean receiptBacked,
                boolean failedReceipt) {
            this(state, stepId, detail, durableSucceeded, replanAuthority,
                    authoritativeFacts, receiptBacked, failedReceipt,
                    List.of(), Optional.empty());
        }

        public CycleResult(
                State state, String stepId, String detail,
                boolean durableSucceeded, Object replanAuthority) {
            this(state, stepId, detail, durableSucceeded, replanAuthority,
                    List.of(), false, false, List.of(), Optional.empty());
        }

        public enum State {
            STEP_SUCCEEDED, RECOVERY_PENDING, REPLAN_REQUIRED,
            PLAN_SUCCEEDED, FAILED
        }
    }
}
