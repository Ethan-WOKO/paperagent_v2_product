package com.yanban.api.agent.reactplan;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapCommand;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.runtime.planning.InitialPlanDraft;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Creates the P1 one-Step Plan in code. No planner-model output is consumed. */
@Component
public class DeterministicReactPlanDraftFactory {
    static final String GOAL_KEY = "execute-objective";
    static final String REASON = "product-authoritative-react-plan-shell-v1";
    static final List<String> COMPLETION_CRITERIA = List.of(
            "A terminal formal Receipt is durably recorded for the objective.",
            "A user-visible delivery is bound by code to the terminal Receipt facts.",
            "No tool effect remains pending or unknown."
    );

    public ProductPersistentPlanBootstrapCommand create(
            String runId,
            ReactPlanBootstrapCommand command) {
        ReactPlanGoal goal = singleGoal(command);
        PlanStep step = new PlanStep(
                deterministicStepId(runId),
                goal.objective(),
                goal.expectedOutcome(),
                Set.of(),
                goal.doneWhen(),
                goal.executionHints(),
                goal.constraints());
        return new ProductPersistentPlanBootstrapCommand(
                command.routingDecision(),
                command.taskFrameDraft(),
                command.executionProfile(),
                new InitialPlanDraft(REASON, List.of(step)),
                command.taskFrameCreatedAt(),
                command.planCreatedAt(),
                command.checkpointCreatedAt());
    }

    ReactPlanDefinition definition(ReactPlanBootstrapCommand command) {
        return new ReactPlanDefinition(List.of(singleGoal(command)));
    }

    private ReactPlanGoal singleGoal(ReactPlanBootstrapCommand command) {
        String deliverables = String.join("; ", command.taskFrameDraft().deliverables());
        return new ReactPlanGoal(
                GOAL_KEY,
                command.taskFrameDraft().objective(),
                "Produce the frozen deliverables with receipt-backed evidence: " + deliverables,
                List.of(),
                COMPLETION_CRITERIA,
                command.taskFrameDraft().constraints(),
                command.executionHints());
    }

    private static PlanStepId deterministicStepId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("react-plan-step-v1\0" + runId.trim()).getBytes(StandardCharsets.UTF_8));
            return new PlanStepId("react-step-" + HexFormat.of().formatHex(digest, 0, 16));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
