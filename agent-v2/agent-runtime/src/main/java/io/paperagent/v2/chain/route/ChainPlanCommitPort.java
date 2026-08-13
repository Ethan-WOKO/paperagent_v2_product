package io.paperagent.v2.chain.route;

import io.paperagent.v2.chain.PlannerPayload;

import java.util.Objects;
import java.time.Instant;

/** Narrow adapter to the stable TaskFrame/Plan core. It does not write chain bindings. */
public interface ChainPlanCommitPort {
    CommittedPlan commitPersistent(PersistentPlanCommand command);

    CommittedPlan commitRevision(PlanRevisionCommand command);

    record PersistentPlanCommand(
            String taskId,
            String instructionId,
            String proposalId,
            String routeDecisionId,
            String transitionId,
            Instant committedAt,
            PlannerPayload.PersistentPlan payload) {
        public PersistentPlanCommand {
            taskId = required(taskId, "taskId");
            instructionId = required(instructionId, "instructionId");
            proposalId = required(proposalId, "proposalId");
            routeDecisionId = required(routeDecisionId, "routeDecisionId");
            transitionId = required(transitionId, "transitionId");
            Objects.requireNonNull(committedAt, "committedAt");
            Objects.requireNonNull(payload, "payload");
        }
    }

    record PlanRevisionCommand(
            String taskId,
            String instructionId,
            String proposalId,
            String sourceAuthorityType,
            String sourceAuthorityRef,
            String transitionId,
            String taskFrameId,
            String planId,
            String oldPlanRevisionId,
            long oldPlanRevisionNumber,
            Instant committedAt,
            PlannerPayload.PlanRevision payload) {
        public PlanRevisionCommand {
            taskId = required(taskId, "taskId");
            instructionId = required(instructionId, "instructionId");
            proposalId = required(proposalId, "proposalId");
            sourceAuthorityType = required(
                    sourceAuthorityType, "sourceAuthorityType");
            sourceAuthorityRef = required(sourceAuthorityRef, "sourceAuthorityRef");
            transitionId = required(transitionId, "transitionId");
            taskFrameId = required(taskFrameId, "taskFrameId");
            planId = required(planId, "planId");
            oldPlanRevisionId = required(
                    oldPlanRevisionId, "oldPlanRevisionId");
            if (oldPlanRevisionNumber < 1) {
                throw new IllegalArgumentException(
                        "oldPlanRevisionNumber must be positive");
            }
            Objects.requireNonNull(committedAt, "committedAt");
            Objects.requireNonNull(payload, "payload");
        }
    }

    record CommittedPlan(
            String taskId,
            String taskFrameId,
            String planId,
            String planRevisionId,
            long planRevisionNumber,
            String authorityType,
            String authorityId,
            String authoritySha256) {
        public CommittedPlan {
            taskId = required(taskId, "taskId");
            taskFrameId = required(taskFrameId, "taskFrameId");
            planId = required(planId, "planId");
            planRevisionId = required(planRevisionId, "planRevisionId");
            if (planRevisionNumber < 1) {
                throw new IllegalArgumentException(
                        "planRevisionNumber must be positive");
            }
            authorityType = required(authorityType, "authorityType");
            authorityId = required(authorityId, "authorityId");
            authoritySha256 = required(authoritySha256, "authoritySha256");
            if (!authoritySha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "authoritySha256 must be lowercase SHA-256");
            }
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
