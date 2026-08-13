package io.paperagent.v2.chain;

import java.util.List;
import java.util.Objects;

/** Minimum immutable identities required at chain authority boundaries. */
public final class ChainIdentity {
    public static final String NONE = "NONE";
    private static final String CANDIDATE_ARTIFACT_PREFIX =
            "candidate-artifact:";

    private ChainIdentity() {
    }

    /** Stable formal reference for a persisted Candidate artifact authority. */
    public static String candidateArtifactRef(long artifactId) {
        requirePositive(artifactId, "artifactId");
        return CANDIDATE_ARTIFACT_PREFIX + artifactId;
    }

    public record Command(
            long userId,
            long sessionId,
            String clientRequestId,
            ChainInstructionRelation kind,
            String targetTaskId,
            String targetRootClientRequestId,
            String gapId,
            String bodyDigest) {
        public Command {
            requirePositive(userId, "userId");
            requirePositive(sessionId, "sessionId");
            clientRequestId = required(clientRequestId, "clientRequestId");
            kind = Objects.requireNonNull(kind, "kind");
            bodyDigest = ChainValues.requiredSha256(bodyDigest, "bodyDigest");
            if (kind == ChainInstructionRelation.INITIAL) {
                if (targetTaskId != null || targetRootClientRequestId != null || gapId != null) {
                    throw new IllegalArgumentException("INITIAL command cannot target an existing task or gap");
                }
            } else {
                targetTaskId = required(targetTaskId, "targetTaskId");
                targetRootClientRequestId = required(targetRootClientRequestId, "targetRootClientRequestId");
                if (kind == ChainInstructionRelation.ANSWER_TO_PENDING_ITEM) {
                    gapId = required(gapId, "gapId");
                } else if (gapId != null) {
                    throw new IllegalArgumentException("only ANSWER_TO_PENDING_ITEM may target a gap");
                }
            }
        }
    }

    public record Task(
            String taskId,
            String rootCommandId,
            String rootClientRequestId,
            long turnId,
            long userId,
            long sessionId,
            Long requestMessageId,
            String rootRequestSha256,
            Long projectId,
            String projectVersion,
            String predecessorTaskId,
            String sourceInstructionId) {
        public Task {
            taskId = required(taskId, "taskId");
            rootCommandId = required(rootCommandId, "rootCommandId");
            rootClientRequestId = required(rootClientRequestId, "rootClientRequestId");
            requirePositive(turnId, "turnId");
            requirePositive(userId, "userId");
            requirePositive(sessionId, "sessionId");
            rootRequestSha256 = ChainValues.requiredSha256(rootRequestSha256, "rootRequestSha256");
            if ((projectId == null) != (projectVersion == null)) {
                throw new IllegalArgumentException("projectId and projectVersion must both be present or absent");
            }
            sourceInstructionId = required(sourceInstructionId, "sourceInstructionId");
        }
    }

    public record Instruction(
            String instructionId,
            long sessionId,
            String originTaskOpaqueRef,
            String commandId,
            Long messageId,
            String messageBodyHash,
            String messageIdentityKey,
            String effectiveBoundaryDigest) {
        public Instruction {
            instructionId = required(instructionId, "instructionId");
            requirePositive(sessionId, "sessionId");
            originTaskOpaqueRef = required(originTaskOpaqueRef, "originTaskOpaqueRef");
            commandId = required(commandId, "commandId");
            if ((messageId == null) != (messageBodyHash == null)) {
                throw new IllegalArgumentException("messageId and messageBodyHash must both be present or absent");
            }
            if (messageBodyHash != null) {
                messageBodyHash = ChainValues.requiredSha256(messageBodyHash, "messageBodyHash");
            }
            messageIdentityKey = required(messageIdentityKey, "messageIdentityKey");
            effectiveBoundaryDigest = ChainValues.requiredSha256(effectiveBoundaryDigest, "effectiveBoundaryDigest");
        }
    }

    public record TaskInstructionBinding(
            String taskId,
            long taskInstructionSequence,
            String instructionId,
            ChainInstructionRelation relation,
            String relationRole,
            String authorityEventId) {
        public TaskInstructionBinding {
            taskId = required(taskId, "taskId");
            if (taskInstructionSequence < 1) {
                throw new IllegalArgumentException("taskInstructionSequence must be positive");
            }
            instructionId = required(instructionId, "instructionId");
            relation = Objects.requireNonNull(relation, "relation");
            relationRole = required(relationRole, "relationRole");
            authorityEventId = required(authorityEventId, "authorityEventId");
        }
    }

    public record TaskFrame(String taskId, String taskFrameId, String sourceProjectVersion, String bootstrapHash) {
        public TaskFrame {
            taskId = required(taskId, "taskId");
            taskFrameId = required(taskFrameId, "taskFrameId");
            bootstrapHash = ChainValues.requiredSha256(bootstrapHash, "bootstrapHash");
        }
    }

    public record Plan(
            String planId,
            String taskFrameId,
            int revisionNumber,
            String revisionId,
            String checkpoint,
            long eventHead,
            String payloadHash) {
        public Plan {
            planId = required(planId, "planId");
            taskFrameId = required(taskFrameId, "taskFrameId");
            if (revisionNumber < 1 || eventHead < 0) {
                throw new IllegalArgumentException("invalid plan revision identity");
            }
            revisionId = required(revisionId, "revisionId");
            checkpoint = required(checkpoint, "checkpoint");
            payloadHash = ChainValues.requiredSha256(payloadHash, "payloadHash");
        }
    }

    public record Step(
            String planId,
            String revisionId,
            String stepId,
            String activationId,
            String checkpoint,
            long eventHead) {
        public Step {
            planId = required(planId, "planId");
            revisionId = required(revisionId, "revisionId");
            stepId = required(stepId, "stepId");
            activationId = required(activationId, "activationId");
            checkpoint = required(checkpoint, "checkpoint");
            if (eventHead < 0) {
                throw new IllegalArgumentException("eventHead must not be negative");
            }
        }
    }

    public record Proposal(
            String proposalId,
            String invocationId,
            String contextRevisionId,
            ChainRole role,
            ChainProposalKind kind,
            String payloadHash,
            List<String> sourceRefs,
            String bodyRef) {
        public Proposal {
            proposalId = required(proposalId, "proposalId");
            invocationId = required(invocationId, "invocationId");
            contextRevisionId = required(contextRevisionId, "contextRevisionId");
            role = Objects.requireNonNull(role, "role");
            kind = Objects.requireNonNull(kind, "kind");
            if (kind.role() != role) {
                throw new IllegalArgumentException("proposal kind does not belong to role");
            }
            payloadHash = ChainValues.requiredSha256(payloadHash, "payloadHash");
            sourceRefs = List.copyOf(Objects.requireNonNull(sourceRefs, "sourceRefs"));
        }
    }

    public record Action(
            String instructionId,
            String taskFrameId,
            String planRevisionId,
            String stepId,
            String activationId,
            String actionId,
            String idempotencyKey,
            String workspaceId,
            String baseCandidateId) {
        public Action {
            instructionId = required(instructionId, "instructionId");
            taskFrameId = required(taskFrameId, "taskFrameId");
            planRevisionId = required(planRevisionId, "planRevisionId");
            stepId = required(stepId, "stepId");
            activationId = required(activationId, "activationId");
            actionId = required(actionId, "actionId");
            idempotencyKey = required(idempotencyKey, "idempotencyKey");
            workspaceId = required(workspaceId, "workspaceId");
            baseCandidateId = required(baseCandidateId, "baseCandidateId");
        }
    }

    public record Candidate(
            String candidateId,
            String workspaceId,
            String baseProjectVersion,
            String artifactId,
            String fingerprint,
            String diffDigest) {
        public Candidate {
            candidateId = required(candidateId, "candidateId");
            workspaceId = required(workspaceId, "workspaceId");
            baseProjectVersion = required(baseProjectVersion, "baseProjectVersion");
            artifactId = required(artifactId, "artifactId");
            fingerprint = ChainValues.requiredSha256(fingerprint, "fingerprint");
            diffDigest = ChainValues.requiredSha256(diffDigest, "diffDigest");
        }
    }

    public record Validation(
            String validationId,
            String candidateArtifactId,
            String candidateFingerprint,
            String projectVersion,
            String profile,
            String requestDigest,
            String receiptDigest) {
        public Validation {
            validationId = required(validationId, "validationId");
            candidateArtifactId = required(candidateArtifactId, "candidateArtifactId");
            candidateFingerprint = required(candidateFingerprint, "candidateFingerprint");
            projectVersion = required(projectVersion, "projectVersion");
            profile = required(profile, "profile");
            requestDigest = ChainValues.requiredSha256(requestDigest, "requestDigest");
            receiptDigest = ChainValues.requiredSha256(receiptDigest, "receiptDigest");
        }
    }

    public record Readiness(
            String transitionId,
            String taskFrameId,
            String planRevisionId,
            String acceptedResultSetDigest,
            String artifactId,
            String candidateKey,
            String workspaceId,
            String validationId,
            String validationRequestDigest,
            String validationReceiptDigest,
            String publishRequirementDigest,
            String instructionId,
            String projectVersion) {
        public Readiness {
            transitionId = required(transitionId, "transitionId");
            taskFrameId = required(taskFrameId, "taskFrameId");
            planRevisionId = required(planRevisionId, "planRevisionId");
            candidateKey = required(candidateKey, "candidateKey");
            workspaceId = required(workspaceId, "workspaceId");
            validationId = required(validationId, "validationId");
            acceptedResultSetDigest = ChainValues.requiredSha256(
                    acceptedResultSetDigest, "acceptedResultSetDigest");
            publishRequirementDigest = ChainValues.requiredSha256(
                    publishRequirementDigest, "publishRequirementDigest");
            instructionId = required(instructionId, "instructionId");
            projectVersion = required(projectVersion, "projectVersion");
            if (NONE.equals(candidateKey)) {
                if (artifactId != null) {
                    throw new IllegalArgumentException("NONE Candidate cannot carry an artifact");
                }
            } else {
                artifactId = required(artifactId, "artifactId");
            }
            if (NONE.equals(validationId)) {
                if (validationRequestDigest != null || validationReceiptDigest != null) {
                    throw new IllegalArgumentException("NONE Validation cannot carry validation digests");
                }
            } else {
                validationRequestDigest = ChainValues.requiredSha256(
                        validationRequestDigest, "validationRequestDigest");
                validationReceiptDigest = ChainValues.requiredSha256(
                        validationReceiptDigest, "validationReceiptDigest");
            }
        }
    }

    public record Transition(ChainTransitionType type, String taskId, String sourceDecisionId, String targetIdentityDigest) {
        public Transition {
            type = Objects.requireNonNull(type, "type");
            taskId = required(taskId, "taskId");
            sourceDecisionId = required(sourceDecisionId, "sourceDecisionId");
            targetIdentityDigest = ChainValues.requiredSha256(targetIdentityDigest, "targetIdentityDigest");
        }

        public String transitionId() {
            return "transition." + ChainValues.sha256(
                    type.name() + "\0" + taskId + "\0" + sourceDecisionId + "\0" + targetIdentityDigest);
        }
    }

    public record Finalization(String readinessId, int attempt, String inputDigest) {
        public Finalization {
            readinessId = required(readinessId, "readinessId");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt exceeds the finalization mechanical-attempt policy");
            }
            inputDigest = ChainValues.requiredSha256(inputDigest, "inputDigest");
        }
    }

    public record Publish(
            String operationId,
            String idempotencyKey,
            String baseProjectVersion,
            String candidateId,
            String validationId,
            String resultProjectVersion) {
        public Publish {
            operationId = required(operationId, "operationId");
            idempotencyKey = required(idempotencyKey, "idempotencyKey");
            baseProjectVersion = required(baseProjectVersion, "baseProjectVersion");
            candidateId = required(candidateId, "candidateId");
            validationId = required(validationId, "validationId");
            resultProjectVersion = required(resultProjectVersion, "resultProjectVersion");
        }
    }

    public record Delivery(
            String deliveryId,
            String routeDecisionId,
            String taskOutcomeId,
            String gapId,
            String decisionId,
            String answerContentRef,
            int attempt) {
        public Delivery {
            deliveryId = required(deliveryId, "deliveryId");
            int sourceCount = (routeDecisionId == null ? 0 : 1)
                    + (taskOutcomeId == null ? 0 : 1)
                    + (gapId == null ? 0 : 1)
                    + (decisionId == null ? 0 : 1);
            if (sourceCount != 1) {
                throw new IllegalArgumentException(
                        "delivery requires exactly one route, outcome, gap, or decision source");
            }
            if (answerContentRef != null) {
                answerContentRef = required(answerContentRef, "answerContentRef");
            }
            if (attempt < 0) {
                throw new IllegalArgumentException("attempt exceeds the delivery-attempt policy");
            }
        }
    }

    private static String required(String value, String name) {
        return ChainValues.required(value, name);
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
