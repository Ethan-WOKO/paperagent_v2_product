package io.paperagent.v2.chain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** One immutable Java record per V70-V73 table boundary. */
public final class ChainPersistenceRecords {
    private ChainPersistenceRecords() {
    }

    public record AppendResult<T>(T value, boolean replayed) {
        public AppendResult { Objects.requireNonNull(value, "value"); }
    }

    /** Caller-supplied immutable identity; repository allocates eventSequence under the task lock. */
    public record AuthorityEventRequest(
            String eventId, String taskId, String eventType, String transitionId,
            String sourceIdentitySha256, Instant committedAt) {
        public AuthorityEventRequest {
            required(eventId, "eventId"); required(taskId, "taskId"); required(eventType, "eventType");
            sha256Required(sourceIdentitySha256, "sourceIdentitySha256");
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    public interface TaskAuthorityFact {
        String taskId();
        String eventId();
    }

    public record AuthoritativeFact<T extends TaskAuthorityFact>(AuthorityEventRequest event, T fact) {
        public AuthoritativeFact {
            Objects.requireNonNull(event, "event"); Objects.requireNonNull(fact, "fact");
            if (!event.taskId().equals(fact.taskId()) || !event.eventId().equals(fact.eventId()))
                throw new IllegalArgumentException("authority event and fact identities must match");
        }
    }

    public record AuthoritativeAppendResult<T extends TaskAuthorityFact>(AuthorityEventRecord event, T fact, boolean replayed) {
        public AuthoritativeAppendResult {
            Objects.requireNonNull(event, "event"); Objects.requireNonNull(fact, "fact");
        }
    }

    public record CanonicalJson(int formatVersion, String sha256, String json) {
        public CanonicalJson {
            if (formatVersion != 1) throw new IllegalArgumentException("formatVersion must be 1");
            sha256Required(sha256, "sha256");
            required(json, "json");
        }
    }

    public record FormattedJson(int formatVersion, String json) {
        public FormattedJson {
            if (formatVersion != 1) throw new IllegalArgumentException("formatVersion must be 1");
            required(json, "json");
        }
    }

    // V70 foundations and ordinary Plan replan
    public record CommandRecord(
            String commandId, long userId, long sessionId, String clientRequestId, ChainInstructionRelation commandKind,
            String targetTaskId, String targetClientRequestId, String gapId, String requestSha256,
            Long turnId, Long userMessageId, String resultTaskId, String resultEventId,
            String resultInstructionId, ChainCommandStatus status, String resultCode,
            Instant createdAt, Instant committedAt) {
        public CommandRecord {
            required(commandId, "commandId"); positive(userId, "userId"); positive(sessionId, "sessionId");
            required(clientRequestId, "clientRequestId"); Objects.requireNonNull(commandKind, "commandKind");
            sha256Required(requestSha256, "requestSha256"); Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
            if (commandKind == ChainInstructionRelation.INITIAL) {
                if (targetTaskId != null || targetClientRequestId != null || gapId != null)
                    throw new IllegalArgumentException("INITIAL command cannot target an existing task or gap");
            } else {
                required(targetTaskId, "targetTaskId");
                required(targetClientRequestId, "targetClientRequestId");
                if (commandKind == ChainInstructionRelation.ANSWER_TO_PENDING_ITEM) {
                    required(gapId, "gapId");
                } else if (gapId != null) {
                    throw new IllegalArgumentException("only ANSWER_TO_PENDING_ITEM may target a gap");
                }
            }
            boolean noResultRefs = resultTaskId == null && resultEventId == null && resultInstructionId == null;
            boolean allResultRefs = resultTaskId != null && resultEventId != null && resultInstructionId != null;
            if (!noResultRefs && !allResultRefs) throw new IllegalArgumentException("result refs must be all-or-none");
            if (status == ChainCommandStatus.RECEIVED
                    && (committedAt != null || resultCode != null || !noResultRefs))
                throw new IllegalArgumentException("RECEIVED command cannot carry terminal identity");
            if (status == ChainCommandStatus.COMMITTED
                    && (committedAt == null || !allResultRefs || resultCode != null))
                throw new IllegalArgumentException("COMMITTED command requires result refs without failure code");
            if (status == ChainCommandStatus.FAILED && (committedAt == null || blank(resultCode)))
                throw new IllegalArgumentException("FAILED command requires committedAt and resultCode");
            if (status == ChainCommandStatus.COMMITTED && commandKind != ChainInstructionRelation.CANCEL
                    && (turnId == null || userMessageId == null))
                throw new IllegalArgumentException("committed body command requires turn and message");
        }
    }

    public record TaskRecord(
            String taskId, String createdByCommandId, String sourceInstructionId, String predecessorTaskId,
            long userId, long sessionId, long turnId, Long requestMessageId, String rootClientRequestId,
            String rootRequestSha256, Long projectId, String initialProjectVersion,
            long nextEventSequence, Instant createdAt) {
        public TaskRecord {
            required(taskId, "taskId"); required(createdByCommandId, "createdByCommandId");
            required(sourceInstructionId, "sourceInstructionId"); positive(userId, "userId");
            positive(sessionId, "sessionId"); positive(turnId, "turnId");
            required(rootClientRequestId, "rootClientRequestId");
            sha256Required(rootRequestSha256, "rootRequestSha256");
            paired(projectId, initialProjectVersion, "project identity");
            nonNegative(nextEventSequence, "nextEventSequence"); Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record AuthorityEventRecord(
            String eventId, String taskId, long eventSequence, String eventType,
            String transitionId, String sourceIdentitySha256, Instant committedAt) {
        public AuthorityEventRecord {
            required(eventId, "eventId"); required(taskId, "taskId"); positive(eventSequence, "eventSequence");
            required(eventType, "eventType"); sha256Required(sourceIdentitySha256, "sourceIdentitySha256");
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    public record InstructionRecord(
            String instructionId, String commandId, long sessionId, String originTaskId, Long messageId,
            String bodySha256, String messageIdentityKey, ChainInstructionRelation relationKind,
            String parentInstructionId, String answeredGapId, String effectiveBoundaryDigest, Instant createdAt) {
        public InstructionRecord {
            required(instructionId, "instructionId"); required(commandId, "commandId");
            positive(sessionId, "sessionId"); required(originTaskId, "originTaskId");
            required(messageIdentityKey, "messageIdentityKey"); Objects.requireNonNull(relationKind, "relationKind");
            required(effectiveBoundaryDigest, "effectiveBoundaryDigest"); Objects.requireNonNull(createdAt, "createdAt");
            if (relationKind == ChainInstructionRelation.CANCEL && (messageId != null || bodySha256 != null))
                throw new IllegalArgumentException("CANCEL must be bodyless");
            if (relationKind != ChainInstructionRelation.CANCEL && (messageId == null || bodySha256 == null))
                throw new IllegalArgumentException("non-CANCEL requires message identity and body digest");
            if (bodySha256 != null) sha256Required(bodySha256, "bodySha256");
            sha256Required(effectiveBoundaryDigest, "effectiveBoundaryDigest");
        }
    }

    public record TaskInstructionBindingRecord(
            String taskId, String eventId, String instructionId, long taskInstructionSequence,
            BindingRole relationRole, Instant createdAt) implements TaskAuthorityFact {
        public TaskInstructionBindingRecord {
            required(taskId, "taskId"); required(eventId, "eventId"); required(instructionId, "instructionId");
            positive(taskInstructionSequence, "taskInstructionSequence");
            Objects.requireNonNull(relationRole, "relationRole"); Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public enum BindingRole { ORIGIN, INHERITED_ROOT }

    // V71 context and model records
    public record ContextRevisionRecord(
            String contextRevisionId, String taskId, String parentContextRevisionId, ChainRole role,
            ChainWorkState workState, String callReason, String instructionId, String taskFrameId,
            String planId, String planRevisionId, Long planRevisionNumber, String stepId,
            String activationEventId, Long projectId, String projectVersion, String workspaceId,
            Long candidateArtifactId, String candidateFingerprint, String validationId,
            String validationRequestDigest, String validationReceiptDigest, String projectorSetVersion,
            String paginationVersion, String runtimePolicyVersion, ChainContextRevisionStatus status,
            int moduleCount, FormattedJson requestManifest, String requestDigest, String completionToken,
            String blockedErrorCode, String inputDigest, Instant createdAt, Instant completedAt) {
        public ContextRevisionRecord {
            required(contextRevisionId, "contextRevisionId"); required(taskId, "taskId");
            Objects.requireNonNull(role, "role"); Objects.requireNonNull(workState, "workState");
            required(callReason, "callReason"); required(instructionId, "instructionId");
            required(projectorSetVersion, "projectorSetVersion"); required(paginationVersion, "paginationVersion");
            required(runtimePolicyVersion, "runtimePolicyVersion"); Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
            if (moduleCount < 0 || moduleCount > 13) throw new IllegalArgumentException("invalid moduleCount");
            triple(planId, planRevisionId, planRevisionNumber, "plan identity");
            paired(stepId, activationEventId, "step identity");
            paired(projectId, projectVersion, "project identity");
            paired(candidateArtifactId, candidateFingerprint, "candidate identity");
            triple(validationId, validationRequestDigest, validationReceiptDigest, "validation identity");
            if (candidateFingerprint != null) sha256Required(candidateFingerprint, "candidateFingerprint");
            if (validationRequestDigest != null) sha256Required(validationRequestDigest, "validationRequestDigest");
            if (validationReceiptDigest != null) sha256Required(validationReceiptDigest, "validationReceiptDigest");
            if (requestDigest != null) sha256Required(requestDigest, "requestDigest");
            if (inputDigest != null) sha256Required(inputDigest, "inputDigest");
            if (status == ChainContextRevisionStatus.BUILDING
                    && (moduleCount > 12 || requestManifest != null || requestDigest != null
                    || completionToken != null || blockedErrorCode != null || inputDigest != null || completedAt != null))
                throw new IllegalArgumentException("BUILDING cannot carry terminal fields");
            if (status == ChainContextRevisionStatus.COMPLETE
                    && (moduleCount != 13 || requestManifest == null || blank(requestDigest)
                    || blank(completionToken) || blockedErrorCode != null || inputDigest != null || completedAt == null))
                throw new IllegalArgumentException("COMPLETE requires the full frozen request identity");
            if (status == ChainContextRevisionStatus.INPUT_BLOCKED
                    && (moduleCount != 13 || requestManifest == null || requestDigest != null
                    || !blank(completionToken) || blank(blockedErrorCode)
                    || blank(inputDigest) || completedAt == null))
                throw new IllegalArgumentException("INPUT_BLOCKED requires full input failure identity");
        }
    }

    public record ContextModuleRecord(
            String contextRevisionId, String taskId, int moduleOrdinal, ChainContextModule module,
            ChainContextModuleStatus presenceKind, CanonicalJson sourceVersion,
            CanonicalJson readBoundary, String projectionVersion, String paginationVersion,
            CanonicalJson projectionParameters, CanonicalJson projection, Instant createdAt) {
        public ContextModuleRecord {
            required(contextRevisionId, "contextRevisionId"); required(taskId, "taskId");
            Objects.requireNonNull(module, "module"); Objects.requireNonNull(presenceKind, "presenceKind");
            if (moduleOrdinal != module.ordinalCode())
                throw new IllegalArgumentException("moduleOrdinal must match the frozen module ordinal");
            Objects.requireNonNull(sourceVersion, "sourceVersion"); Objects.requireNonNull(readBoundary, "readBoundary");
            required(projectionVersion, "projectionVersion"); required(paginationVersion, "paginationVersion");
            Objects.requireNonNull(projectionParameters, "projectionParameters");
            Objects.requireNonNull(projection, "projection"); Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** Formal authority for a typed Context source failure before all modules can freeze. */
    public record ContextBuildFailureRecord(
            String contextBuildFailureId, String taskId, String eventId,
            String contextRevisionId, ChainRole role, ChainWorkState workState,
            String callReason, String instructionId, ChainContextModule failedModule,
            String errorCode, String projectorSetVersion, String paginationVersion,
            String runtimePolicyVersion, Instant createdAt) implements TaskAuthorityFact {
        public ContextBuildFailureRecord {
            required(contextBuildFailureId, "contextBuildFailureId");
            required(taskId, "taskId");
            required(eventId, "eventId");
            required(contextRevisionId, "contextRevisionId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(workState, "workState");
            required(callReason, "callReason");
            required(instructionId, "instructionId");
            Objects.requireNonNull(failedModule, "failedModule");
            if (!"CONTEXT_INPUT_BLOCKED".equals(errorCode)) {
                throw new IllegalArgumentException(
                        "ContextBuildFailure errorCode must be CONTEXT_INPUT_BLOCKED");
            }
            required(projectorSetVersion, "projectorSetVersion");
            required(paginationVersion, "paginationVersion");
            required(runtimePolicyVersion, "runtimePolicyVersion");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** Formal, sanitized refusal to materialize a Candidate for one action. */
    public record CandidateMaterializationFailureRecord(
            String candidateFailureId, String taskId, String eventId,
            String actionId, String workspaceId, String baseCandidateKey,
            String mutationAuthorityType, String mutationAuthorityRef,
            String versionFenceSha256, String errorCode, Instant createdAt)
            implements TaskAuthorityFact {
        public CandidateMaterializationFailureRecord {
            required(candidateFailureId, "candidateFailureId");
            required(taskId, "taskId"); required(eventId, "eventId");
            required(actionId, "actionId"); required(workspaceId, "workspaceId");
            required(baseCandidateKey, "baseCandidateKey");
            required(mutationAuthorityType, "mutationAuthorityType");
            if (!java.util.Set.of("WORKSPACE_CHANGE_BODY",
                    "TOOL_EFFECT_RESULT").contains(mutationAuthorityType)) {
                throw new IllegalArgumentException(
                        "unsupported Candidate mutation authority type");
            }
            required(mutationAuthorityRef, "mutationAuthorityRef");
            sha256Required(versionFenceSha256, "versionFenceSha256");
            required(errorCode, "errorCode");
            if (!java.util.Set.of(
                    "CANDIDATE_REPLACEMENT_BUNDLE_INVALID",
                    "CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS",
                    "CANDIDATE_REPLACEMENT_TOO_LARGE",
                    "CANDIDATE_NO_ACTUAL_CHANGE").contains(errorCode)) {
                throw new IllegalArgumentException(
                        "unsupported Candidate materialization errorCode");
            }
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record ModelInvocationRecord(
            String invocationId, String taskId, String contextRevisionId, String completionToken,
            ChainRole role, ChainWorkState workState, String callReason, String provider, String model,
            int invocationOrdinal, String runtimePolicyVersion, Instant createdAt) {
        public ModelInvocationRecord {
            required(invocationId, "invocationId"); required(taskId, "taskId");
            required(contextRevisionId, "contextRevisionId"); required(completionToken, "completionToken");
            Objects.requireNonNull(role, "role"); Objects.requireNonNull(workState, "workState");
            required(callReason, "callReason"); required(provider, "provider"); required(model, "model");
            positive(invocationOrdinal, "invocationOrdinal"); required(runtimePolicyVersion, "runtimePolicyVersion");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record ProviderAttemptRecord(
            String invocationId, int attemptNo, String taskId, long durationMs, String finishReason,
            ValidationStatus schemaValidationStatus, ValidationStatus proposalValidationStatus,
            String errorCode, Instant createdAt) {
        public ProviderAttemptRecord {
            required(invocationId, "invocationId"); positive(attemptNo, "attemptNo"); required(taskId, "taskId");
            nonNegative(durationMs, "durationMs"); Objects.requireNonNull(schemaValidationStatus, "schemaValidationStatus");
            Objects.requireNonNull(proposalValidationStatus, "proposalValidationStatus");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public enum ValidationStatus { NOT_RUN, PASSED, FAILED }

    public enum ProposalOfficialAuthorityType {
        ROUTE_DECISION,
        PLAN_BINDING,
        PLAN,
        ACTION_BINDING,
        ACTION_RECEIPT_STEP_BLOCK,
        CANDIDATE_STEP_RESULT,
        REVIEW_DECISION,
        PENDING_ITEM,
        ACCEPTED_RESULT,
        TASK_OUTCOME,
        DELIVERY,
        ANSWER,
        INSTRUCTION_DISPOSITION
    }

    public record InstructionDispositionRecord(
            String dispositionId, String taskId, String eventId,
            String proposalId, String instructionId, String classification,
            String oldTaskDisposition, boolean replyRequired,
            String continuationOrReintakePosition, boolean boundaryChanged,
            CanonicalJson applicability, CanonicalJson nonAuthoritativeReuseSuggestions,
            Instant createdAt) implements TaskAuthorityFact {
        public InstructionDispositionRecord {
            required(dispositionId, "dispositionId");
            required(taskId, "taskId");
            required(eventId, "eventId");
            required(proposalId, "proposalId");
            required(instructionId, "instructionId");
            required(classification, "classification");
            required(oldTaskDisposition, "oldTaskDisposition");
            required(continuationOrReintakePosition,
                    "continuationOrReintakePosition");
            Objects.requireNonNull(applicability, "applicability");
            Objects.requireNonNull(nonAuthoritativeReuseSuggestions,
                    "nonAuthoritativeReuseSuggestions");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record ContentRecord(
            String contentId, String taskId, String invocationId, ChainContentKind contentKind,
            String body, String bodySha256, String mediaType, Instant createdAt) {
        public ContentRecord {
            required(contentId, "contentId"); required(taskId, "taskId"); required(invocationId, "invocationId");
            Objects.requireNonNull(contentKind, "contentKind"); required(body, "body");
            sha256Required(bodySha256, "bodySha256"); required(mediaType, "mediaType");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record ModelProposalRecord(
            String proposalId, String taskId, String invocationId, int schemaVersion,
            ChainRole role, ChainProposalKind proposalKind, CanonicalJson payload,
            CanonicalJson sourceRefs, String bodyAuthorityType, String bodyAuthorityRef, Instant createdAt) {
        public ModelProposalRecord {
            required(proposalId, "proposalId"); required(taskId, "taskId"); required(invocationId, "invocationId");
            if (schemaVersion != 1) throw new IllegalArgumentException("schemaVersion must be 1");
            Objects.requireNonNull(role, "role"); Objects.requireNonNull(proposalKind, "proposalKind");
            if (proposalKind.role() != role) throw new IllegalArgumentException("proposal role-kind mismatch");
            Objects.requireNonNull(payload, "payload"); Objects.requireNonNull(sourceRefs, "sourceRefs");
            paired(bodyAuthorityType, bodyAuthorityRef, "body authority"); Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record ProposalStateEventRecord(
            String proposalId, long stateSequence, String taskId, String eventId,
            ChainProposalState stateKind, String officialAuthorityType,
            String officialAuthorityRef, Instant committedAt) implements TaskAuthorityFact {
        public ProposalStateEventRecord {
            required(proposalId, "proposalId"); positive(stateSequence, "stateSequence");
            required(taskId, "taskId"); required(eventId, "eventId"); Objects.requireNonNull(stateKind, "stateKind");
            paired(officialAuthorityType, officialAuthorityRef, "official authority");
            Objects.requireNonNull(committedAt, "committedAt");
        }

        public void validateNextFor(List<ChainProposalState> committedPrefix) {
            Objects.requireNonNull(committedPrefix, "committedPrefix");
            boolean hasOfficialAuthority = officialAuthorityType != null;
            if (stateKind == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT) {
                if (!hasOfficialAuthority) {
                    throw new IllegalArgumentException(
                            "replacement state requires official authority");
                }
            } else if (hasOfficialAuthority) {
                throw new IllegalArgumentException(
                        "initial proposal state cannot bind official authority");
            }
            if (committedPrefix.isEmpty()) {
                if (stateSequence != 1
                        || stateKind
                        == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT) {
                    throw new IllegalArgumentException(
                            "first proposal state must be accepted, rejected, or stale at sequence 1");
                }
                return;
            }
            if (committedPrefix.size() != 1
                    || committedPrefix.get(0) != ChainProposalState.ACCEPTED
                    || stateSequence != 2
                    || stateKind
                    != ChainProposalState.REPLACED_BY_OFFICIAL_RESULT) {
                throw new IllegalArgumentException(
                        "only accepted may be followed once by an official replacement at sequence 2");
            }
        }
    }

    // V72 workflow records
    public record TransitionRecord(
            String transitionId, String taskId, String eventId, ChainTransitionType transitionType,
            String sourceDecisionId, String targetIdentityDigest, Instant createdAt) implements TaskAuthorityFact {
        public TransitionRecord {
            required(transitionId, "transitionId"); required(taskId, "taskId"); required(eventId, "eventId");
            Objects.requireNonNull(transitionType, "transitionType"); required(sourceDecisionId, "sourceDecisionId");
            sha256Required(targetIdentityDigest, "targetIdentityDigest");
            String expectedTransitionId = new ChainIdentity.Transition(
                    transitionType, taskId, sourceDecisionId, targetIdentityDigest).transitionId();
            if (!expectedTransitionId.equals(transitionId))
                throw new IllegalArgumentException("transitionId does not match the frozen deterministic identity");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record TransitionStageRecord(
            String transitionId, ChainTransitionStage stageCode, String taskId, String eventId,
            int stageOrdinal, String predecessorAuthorityType, String predecessorAuthorityRef,
            String successorAuthorityType, String successorAuthorityRef, Instant committedAt) implements TaskAuthorityFact {
        public TransitionStageRecord {
            required(transitionId, "transitionId"); Objects.requireNonNull(stageCode, "stageCode");
            required(taskId, "taskId"); required(eventId, "eventId"); nonNegative(stageOrdinal, "stageOrdinal");
            paired(predecessorAuthorityType, predecessorAuthorityRef, "predecessor authority");
            paired(successorAuthorityType, successorAuthorityRef, "successor authority");
            Objects.requireNonNull(committedAt, "committedAt");
        }

        public void validateFor(ChainTransitionType transitionType) {
            Objects.requireNonNull(transitionType, "transitionType");
            if (!transitionType.isValidOrdinal(stageCode, stageOrdinal))
                throw new IllegalArgumentException("stage code/ordinal is not legal for the transition type");
        }

        public void validateNextFor(
                ChainTransitionType transitionType, java.util.List<ChainTransitionStage> committedStages) {
            validateFor(transitionType);
            java.util.List<ChainTransitionStage> frozenStages = java.util.List.copyOf(committedStages);
            if (stageOrdinal != frozenStages.size()
                    || !transitionType.validNextStages(frozenStages).contains(stageCode))
                throw new IllegalArgumentException("stage is not the legal next transition stage");
        }
    }

    public record RouteDecisionRecord(
            String routeDecisionId, String taskId, String eventId, String instructionId,
            String proposalId, RouteDecisionType decisionKind, int decisionOrdinal,
            ChainExecutionMode route, String routeReason, CanonicalJson directTaskSpecification,
            CanonicalJson userConstraints, CanonicalJson answerRequiredRefs,
            boolean needsTool, boolean needsNetwork, boolean needsProject,
            boolean needsPersistentProgress, String parentRouteDecisionId,
            String escalationReason, String transitionId, Instant createdAt) implements TaskAuthorityFact {
        public RouteDecisionRecord {
            required(routeDecisionId, "routeDecisionId"); required(taskId, "taskId"); required(eventId, "eventId");
            required(instructionId, "instructionId"); required(proposalId, "proposalId");
            Objects.requireNonNull(decisionKind, "decisionKind"); Objects.requireNonNull(route, "route");
            required(routeReason, "routeReason"); Objects.requireNonNull(createdAt, "createdAt");
            if (decisionKind == RouteDecisionType.INITIAL && (decisionOrdinal != 0 || parentRouteDecisionId != null))
                throw new IllegalArgumentException("INITIAL route must have ordinal zero and no parent");
            if (decisionKind == RouteDecisionType.ESCALATION
                    && (decisionOrdinal != 1 || blank(parentRouteDecisionId) || blank(escalationReason)
                    || route != ChainExecutionMode.PERSISTENT_PLAN_EXECUTE))
                throw new IllegalArgumentException("ESCALATION route identity is invalid");
            if (route == ChainExecutionMode.DIRECT
                    && (needsTool || needsNetwork || needsProject || needsPersistentProgress))
                throw new IllegalArgumentException("DIRECT cannot require a persistent boundary");
        }
    }

    public enum RouteDecisionType { INITIAL, ESCALATION }

    public record PlanBindingRecord(
            String planBindingId, String taskId, String eventId, String instructionId,
            String routeDecisionId, String taskFrameId, String planId, String planRevisionId,
            long planRevisionNumber, String authorityType, String authorityId,
            String authoritySha256, String transitionId, Instant createdAt) implements TaskAuthorityFact {
        public PlanBindingRecord {
            required(planBindingId, "planBindingId"); required(taskId, "taskId"); required(eventId, "eventId");
            required(instructionId, "instructionId"); required(routeDecisionId, "routeDecisionId");
            required(taskFrameId, "taskFrameId"); required(planId, "planId");
            required(planRevisionId, "planRevisionId"); positive(planRevisionNumber, "planRevisionNumber");
            required(authorityType, "authorityType"); required(authorityId, "authorityId");
            sha256Required(authoritySha256, "authoritySha256"); Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record CandidateStepResultRecord(
            String candidateResultId, String taskId, String eventId, String proposalId,
            String contentId, String instructionId, String taskFrameId, String planId,
            String planRevisionId, long planRevisionNumber, String stepId, String activationEventId,
            Long artifactId, String candidateFingerprint, String diffDigest,
            CanonicalJson receiptRefs, String validationId, String validationRequestDigest,
            String validationReceiptDigest, CanonicalJson evidenceRefs,
            String versionFenceSha256, Instant createdAt) implements TaskAuthorityFact {
        public CandidateStepResultRecord {
            required(candidateResultId, "candidateResultId"); required(taskId, "taskId"); required(eventId, "eventId");
            required(proposalId, "proposalId"); required(contentId, "contentId"); required(instructionId, "instructionId");
            required(taskFrameId, "taskFrameId"); required(planId, "planId");
            required(planRevisionId, "planRevisionId"); positive(planRevisionNumber, "planRevisionNumber");
            required(stepId, "stepId"); required(activationEventId, "activationEventId");
            Objects.requireNonNull(receiptRefs, "receiptRefs"); Objects.requireNonNull(evidenceRefs, "evidenceRefs");
            sha256Required(versionFenceSha256, "versionFenceSha256");
            Objects.requireNonNull(createdAt, "createdAt");
            if (artifactId == null && (candidateFingerprint != null || diffDigest != null))
                throw new IllegalArgumentException("candidate identity requires artifact");
            if (artifactId != null && (blank(candidateFingerprint) || blank(diffDigest)))
                throw new IllegalArgumentException("artifact requires candidate fingerprint and diff digest");
            triple(validationId, validationRequestDigest, validationReceiptDigest, "validation identity");
            if (candidateFingerprint != null) sha256Required(candidateFingerprint, "candidateFingerprint");
            if (diffDigest != null) sha256Required(diffDigest, "diffDigest");
            if (validationRequestDigest != null) sha256Required(validationRequestDigest, "validationRequestDigest");
            if (validationReceiptDigest != null) sha256Required(validationReceiptDigest, "validationReceiptDigest");
        }
    }

    /** One Step-scoped set of frozen typed Validation requirements. */
    public record ValidationSetRecord(
            String validationId, String taskId, String eventId,
            String taskFrameId, String planId, String planRevisionId,
            long planRevisionNumber, String stepId, String activationEventId,
            String requestDigest, String receiptSetDigest,
            String conclusionDigest, ChainValidationConclusion conclusion,
            String idempotencyKey, Instant createdAt)
            implements TaskAuthorityFact {
        public ValidationSetRecord {
            required(validationId, "validationId");
            required(taskId, "taskId");
            required(eventId, "eventId");
            required(taskFrameId, "taskFrameId");
            required(planId, "planId");
            required(planRevisionId, "planRevisionId");
            positive(planRevisionNumber, "planRevisionNumber");
            required(stepId, "stepId");
            required(activationEventId, "activationEventId");
            sha256Required(requestDigest, "requestDigest");
            sha256Required(receiptSetDigest, "receiptSetDigest");
            sha256Required(conclusionDigest, "conclusionDigest");
            Objects.requireNonNull(conclusion, "conclusion");
            required(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** Candidate-only item; it references but never copies the Receipt body. */
    public record CandidateValidationItemRecord(
            String validationId, String requirementId, String taskId,
            String requirementDigest, String candidateActionId,
            String validationActionId, String receiptId,
            String receiptPayloadSha256, String actionSignatureSha256,
            String workspaceCandidateId, String workspaceId, long artifactId,
            String candidateFingerprint, String baseProjectVersion,
            ChainValidationConclusion conclusion) {
        public CandidateValidationItemRecord {
            required(validationId, "validationId");
            required(requirementId, "requirementId");
            required(taskId, "taskId");
            sha256Required(requirementDigest, "requirementDigest");
            required(candidateActionId, "candidateActionId");
            required(validationActionId, "validationActionId");
            required(receiptId, "receiptId");
            sha256Required(receiptPayloadSha256, "receiptPayloadSha256");
            sha256Required(actionSignatureSha256, "actionSignatureSha256");
            required(workspaceCandidateId, "workspaceCandidateId");
            required(workspaceId, "workspaceId");
            positive(artifactId, "artifactId");
            sha256Required(candidateFingerprint, "candidateFingerprint");
            required(baseProjectVersion, "baseProjectVersion");
            Objects.requireNonNull(conclusion, "conclusion");
        }
    }

    /** Non-Candidate Action/Receipt item with no Project dependency. */
    public record ActionReceiptValidationItemRecord(
            String validationId, String requirementId, String taskId,
            String requirementDigest, String actionId, String receiptId,
            String receiptPayloadSha256, String actionSignatureSha256,
            ChainValidationConclusion conclusion) {
        public ActionReceiptValidationItemRecord {
            required(validationId, "validationId");
            required(requirementId, "requirementId");
            required(taskId, "taskId");
            sha256Required(requirementDigest, "requirementDigest");
            required(actionId, "actionId");
            required(receiptId, "receiptId");
            sha256Required(receiptPayloadSha256, "receiptPayloadSha256");
            sha256Required(actionSignatureSha256, "actionSignatureSha256");
            Objects.requireNonNull(conclusion, "conclusion");
        }
    }

    /** Plan-level, deterministic closure of all frozen Validation sets. */
    public record ValidationBundleRecord(
            String validationBundleId, String taskId, String eventId,
            String taskFrameId, String planId, String planRevisionId,
            long planRevisionNumber, String instructionId,
            String finalStepId, String requestDigest,
            String receiptSetDigest, String conclusionDigest,
            ChainValidationConclusion conclusion, String idempotencyKey,
            Instant createdAt) implements TaskAuthorityFact {
        public ValidationBundleRecord {
            required(validationBundleId, "validationBundleId");
            required(taskId, "taskId");
            required(eventId, "eventId");
            required(taskFrameId, "taskFrameId");
            required(planId, "planId");
            required(planRevisionId, "planRevisionId");
            positive(planRevisionNumber, "planRevisionNumber");
            required(instructionId, "instructionId");
            required(finalStepId, "finalStepId");
            sha256Required(requestDigest, "requestDigest");
            sha256Required(receiptSetDigest, "receiptSetDigest");
            sha256Required(conclusionDigest, "conclusionDigest");
            Objects.requireNonNull(conclusion, "conclusion");
            required(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** One immutable ValidationSet membership in a plan-level bundle. */
    public record ValidationBundleSetRecord(
            String validationBundleId, String taskId, String stepId,
            String activationEventId, String validationId,
            String validationRequestDigest,
            String validationReceiptSetDigest,
            String validationConclusionDigest) {
        public ValidationBundleSetRecord {
            required(validationBundleId, "validationBundleId");
            required(taskId, "taskId");
            required(stepId, "stepId");
            required(activationEventId, "activationEventId");
            required(validationId, "validationId");
            sha256Required(validationRequestDigest,
                    "validationRequestDigest");
            sha256Required(validationReceiptSetDigest,
                    "validationReceiptSetDigest");
            sha256Required(validationConclusionDigest,
                    "validationConclusionDigest");
        }
    }

    /** Formal current-Step block caused only by an exhausted Executor call. */
    public record ModelFailureStepBlockRecord(
            String stepBlockId, String taskId, String eventId,
            String invocationId, String contextRevisionId,
            String instructionId, String taskFrameId, String planId,
            String planRevisionId, long planRevisionNumber, String stepId,
            String activationEventId, String lastProviderAttemptRef,
            String failureCategory, String failureCode,
            String versionFenceSha256, Instant createdAt)
            implements TaskAuthorityFact {
        public ModelFailureStepBlockRecord {
            required(stepBlockId, "stepBlockId");
            required(taskId, "taskId");
            required(eventId, "eventId");
            required(invocationId, "invocationId");
            required(contextRevisionId, "contextRevisionId");
            required(instructionId, "instructionId");
            required(taskFrameId, "taskFrameId");
            required(planId, "planId");
            required(planRevisionId, "planRevisionId");
            positive(planRevisionNumber, "planRevisionNumber");
            required(stepId, "stepId");
            required(activationEventId, "activationEventId");
            required(lastProviderAttemptRef, "lastProviderAttemptRef");
            required(failureCategory, "failureCategory");
            required(failureCode, "failureCode");
            sha256Required(versionFenceSha256, "versionFenceSha256");
            Objects.requireNonNull(createdAt, "createdAt");
            if (!"MODEL".equals(failureCategory)
                    || !"MODEL_CALL_FAILED".equals(failureCode)) {
                throw new IllegalArgumentException(
                        "model failure Step block has fixed failure kind");
            }
        }
    }

    /** Formal current-Step block caused by an exhausted failed Action outcome. */
    public record ActionReceiptStepBlockRecord(
            String stepBlockId, String taskId, String eventId,
            String actionId, String failureAuthorityType,
            String failureAuthorityRef, String receiptId,
            String receiptPayloadSha256,
            String instructionId, String taskFrameId, String planId,
            String planRevisionId, long planRevisionNumber, String stepId,
            String activationEventId, String repairProposalId,
            String repairContextRevisionId,
            String repairProposalSignatureSha256,
            long progressAuthorityEventCut,
            String progressSnapshotDigestSha256,
            int thresholdObservedOccurrences, String receiptStatus,
            String failureCategory, String failureCode,
            String blockReasonCode, String runtimePolicyVersion,
            String versionFenceSha256, String blockIdentityDigestSha256,
            Instant createdAt)
            implements TaskAuthorityFact {
        public ActionReceiptStepBlockRecord {
            required(stepBlockId, "stepBlockId");
            required(taskId, "taskId");
            required(eventId, "eventId");
            required(actionId, "actionId");
            required(failureAuthorityType, "failureAuthorityType");
            required(failureAuthorityRef, "failureAuthorityRef");
            required(instructionId, "instructionId");
            required(taskFrameId, "taskFrameId");
            required(planId, "planId");
            required(planRevisionId, "planRevisionId");
            positive(planRevisionNumber, "planRevisionNumber");
            required(stepId, "stepId");
            required(activationEventId, "activationEventId");
            required(repairProposalId, "repairProposalId");
            required(repairContextRevisionId, "repairContextRevisionId");
            sha256Required(repairProposalSignatureSha256,
                    "repairProposalSignatureSha256");
            nonNegative(progressAuthorityEventCut,
                    "progressAuthorityEventCut");
            sha256Required(progressSnapshotDigestSha256,
                    "progressSnapshotDigestSha256");
            positive(thresholdObservedOccurrences,
                    "thresholdObservedOccurrences");
            required(failureCategory, "failureCategory");
            required(failureCode, "failureCode");
            required(blockReasonCode, "blockReasonCode");
            required(runtimePolicyVersion, "runtimePolicyVersion");
            sha256Required(versionFenceSha256, "versionFenceSha256");
            sha256Required(blockIdentityDigestSha256,
                    "blockIdentityDigestSha256");
            Objects.requireNonNull(createdAt, "createdAt");
            boolean receiptSource = "RECEIPT".equals(failureAuthorityType);
            boolean candidateSource = "CANDIDATE_MATERIALIZATION_FAILURE"
                    .equals(failureAuthorityType);
            if (!receiptSource && !candidateSource) {
                throw new IllegalArgumentException(
                        "unsupported action failure authority type");
            }
            if (receiptSource) {
                required(receiptId, "receiptId");
                sha256Required(receiptPayloadSha256,
                        "receiptPayloadSha256");
                required(receiptStatus, "receiptStatus");
                if (!failureAuthorityRef.equals(receiptId)
                        || !("FAILURE".equals(receiptStatus)
                        || "TIMEOUT".equals(receiptStatus)
                        || "CANCELLED".equals(receiptStatus))
                        || !"EXECUTION".equals(failureCategory)) {
                    throw new IllegalArgumentException(
                            "receipt action Step block authority is invalid");
                }
            } else if (receiptId != null || receiptPayloadSha256 != null
                    || receiptStatus != null
                    || !"CANDIDATE".equals(failureCategory)
                    || !java.util.Set.of(
                    "CANDIDATE_REPLACEMENT_BUNDLE_INVALID",
                    "CANDIDATE_PATH_UNRESOLVED_OR_AMBIGUOUS",
                    "CANDIDATE_REPLACEMENT_TOO_LARGE",
                    "CANDIDATE_NO_ACTUAL_CHANGE").contains(failureCode)) {
                throw new IllegalArgumentException(
                        "Candidate action Step block authority is invalid");
            }
            if (!("NO_PROGRESS_THRESHOLD_REACHED".equals(blockReasonCode)
                    || "REPEATED_ACTION_SIGNATURE".equals(blockReasonCode)
                    || "REPAIR_DID_NOT_CHANGE_ACTION".equals(blockReasonCode))) {
                throw new IllegalArgumentException(
                        "unsupported action receipt Step block reason");
            }
            ChainRuntimePolicy runtimePolicy =
                    ChainRuntimePolicy.requireVersion(runtimePolicyVersion);
            if ("NO_PROGRESS_THRESHOLD_REACHED".equals(blockReasonCode)
                    && thresholdObservedOccurrences
                    < runtimePolicy.noProgressThreshold()) {
                throw new IllegalArgumentException(
                        "no-progress block has insufficient observations");
            }
            if ("REPEATED_ACTION_SIGNATURE".equals(blockReasonCode)
                    && thresholdObservedOccurrences
                    < runtimePolicy.sameActionSignatureOccurrencesMax()) {
                throw new IllegalArgumentException(
                        "repeated-action block has insufficient observations");
            }
        }
    }

    public record ReviewDecisionRecord(
            String reviewDecisionId, String taskId, String eventId, String proposalId,
            String reviewObjectType, String reviewObjectId, ChainProposalKind decisionKind,
            String reason, CanonicalJson factRefs, String versionFenceSha256, Instant createdAt) implements TaskAuthorityFact {
        public ReviewDecisionRecord {
            required(reviewDecisionId, "reviewDecisionId"); required(taskId, "taskId"); required(eventId, "eventId");
            required(proposalId, "proposalId"); required(reviewObjectType, "reviewObjectType");
            required(reviewObjectId, "reviewObjectId"); Objects.requireNonNull(decisionKind, "decisionKind");
            if (decisionKind.role() != ChainRole.REFLECTOR) throw new IllegalArgumentException("review requires Reflector kind");
            required(reason, "reason"); Objects.requireNonNull(factRefs, "factRefs");
            sha256Required(versionFenceSha256, "versionFenceSha256");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record AcceptedResultRecord(
            String acceptedResultId, String taskId, String eventId, String candidateResultId,
            String reviewDecisionId, String transitionId, String contentId,
            String acceptedIdentitySha256, Instant createdAt) implements TaskAuthorityFact {
        public AcceptedResultRecord {
            required(acceptedResultId, "acceptedResultId"); required(taskId, "taskId"); required(eventId, "eventId");
            required(candidateResultId, "candidateResultId"); required(reviewDecisionId, "reviewDecisionId");
            required(transitionId, "transitionId"); required(contentId, "contentId");
            sha256Required(acceptedIdentitySha256, "acceptedIdentitySha256");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record ResultApplicabilityRecord(
            String applicabilityId, String taskId, String eventId, String acceptedResultId,
            ChainApplicability.SourceType sourceType, String sourceDecisionId, String targetTaskFrameId,
            String targetPlanId, String targetPlanRevisionId, String targetCandidateKey,
            String targetInstructionVersionId, ChainApplicability.Outcome conclusion,
            String reason, Instant createdAt) implements TaskAuthorityFact {
        public ResultApplicabilityRecord {
            required(applicabilityId, "applicabilityId"); required(taskId, "taskId"); required(eventId, "eventId");
            asciiRequired(acceptedResultId, "acceptedResultId"); Objects.requireNonNull(sourceType, "sourceType");
            asciiRequired(sourceDecisionId, "sourceDecisionId");
            asciiRequired(targetTaskFrameId, "targetTaskFrameId");
            asciiRequired(targetPlanId, "targetPlanId");
            asciiRequired(targetPlanRevisionId, "targetPlanRevisionId");
            asciiRequired(targetCandidateKey, "targetCandidateKey");
            asciiRequired(targetInstructionVersionId, "targetInstructionVersionId");
            Objects.requireNonNull(conclusion, "conclusion"); required(reason, "reason");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record PendingItemRecord(
            String gapId, String taskId, String eventId, String sourceProposalId,
            ChainPendingItemType pendingType, String gapIdentitySha256, CanonicalJson missingFields,
            String permissionScope, String question, String expectedFormat, ChainRole validationRole,
            ChainRole resumeRole, CanonicalJson resumePosition, String versionFenceSha256, Instant createdAt)
            implements TaskAuthorityFact {
        public PendingItemRecord {
            required(gapId, "gapId"); required(taskId, "taskId"); required(eventId, "eventId");
            required(sourceProposalId, "sourceProposalId"); Objects.requireNonNull(pendingType, "pendingType");
            sha256Required(gapIdentitySha256, "gapIdentitySha256");
            Objects.requireNonNull(missingFields, "missingFields");
            required(question, "question"); required(expectedFormat, "expectedFormat");
            Objects.requireNonNull(validationRole, "validationRole"); Objects.requireNonNull(resumeRole, "resumeRole");
            Objects.requireNonNull(resumePosition, "resumePosition");
            sha256Required(versionFenceSha256, "versionFenceSha256");
            Objects.requireNonNull(createdAt, "createdAt");
            if ((pendingType == ChainPendingItemType.PERMISSION) != (permissionScope != null))
                throw new IllegalArgumentException("permission scope must match pending type");
        }
    }

    public record PendingItemEventRecord(
            String gapId, int responseRound, ChainPendingItemStatus eventKind, String taskId, String eventId,
            String answerInstructionId, String validationInvocationId,
            GapValidation.Outcome gapValidationOutcome, CanonicalJson detail, Instant committedAt)
            implements TaskAuthorityFact {
        public PendingItemEventRecord {
            required(gapId, "gapId"); nonNegative(responseRound, "responseRound");
            Objects.requireNonNull(eventKind, "eventKind");
            required(taskId, "taskId"); required(eventId, "eventId"); Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    public record PermissionDecisionRecord(
            String permissionDecisionId, String taskId, String eventId, String gapId,
            String permissionScope, String productAuthorityType, String productAuthorityRef,
            ChainPermissionDecision decision, String reason, Instant createdAt) implements TaskAuthorityFact {
        public PermissionDecisionRecord {
            required(permissionDecisionId, "permissionDecisionId"); required(taskId, "taskId");
            required(eventId, "eventId"); required(gapId, "gapId"); required(permissionScope, "permissionScope");
            required(productAuthorityType, "productAuthorityType"); required(productAuthorityRef, "productAuthorityRef");
            Objects.requireNonNull(decision, "decision"); required(reason, "reason");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record ActionBindingRecord(
            String actionId, String taskId, String eventId, String proposalId, int attemptNo,
            String actionSignatureSha256, String idempotencyKey, String instructionId,
            String taskFrameId, String planId, String planRevisionId, String stepId,
            String activationEventId, String workspaceId, String baseCandidateKey,
            String effectIntentId, String dispatchRef, String resultAuthorityType,
            String resultAuthorityRef, String versionFenceSha256, Instant createdAt) implements TaskAuthorityFact {
        public ActionBindingRecord {
            required(actionId, "actionId"); required(taskId, "taskId"); required(eventId, "eventId");
            required(proposalId, "proposalId"); positive(attemptNo, "attemptNo");
            sha256Required(actionSignatureSha256, "actionSignatureSha256");
            required(idempotencyKey, "idempotencyKey");
            required(instructionId, "instructionId"); required(taskFrameId, "taskFrameId"); required(planId, "planId");
            required(planRevisionId, "planRevisionId"); required(stepId, "stepId");
            required(activationEventId, "activationEventId"); required(workspaceId, "workspaceId");
            required(baseCandidateKey, "baseCandidateKey"); paired(resultAuthorityType, resultAuthorityRef, "result authority");
            sha256Required(versionFenceSha256, "versionFenceSha256");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record WorkspaceCandidateRecord(
            String workspaceCandidateId, String taskId, String eventId, String actionId,
            String workspaceId, String baseProjectVersion, long artifactId,
            String candidateFingerprint, String diffDigest, String versionFenceSha256, Instant createdAt)
            implements TaskAuthorityFact {
        public WorkspaceCandidateRecord {
            required(workspaceCandidateId, "workspaceCandidateId"); required(taskId, "taskId");
            required(eventId, "eventId"); required(actionId, "actionId"); required(workspaceId, "workspaceId");
            required(baseProjectVersion, "baseProjectVersion"); positive(artifactId, "artifactId");
            sha256Required(candidateFingerprint, "candidateFingerprint");
            sha256Required(diffDigest, "diffDigest");
            sha256Required(versionFenceSha256, "versionFenceSha256");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    // V73 finalization and delivery records
    public record FinalizationReadinessRecord(
            String readinessId, String taskId, String eventId, String transitionId,
            String readinessScopeKey, String taskFrameId, String finalPlanId,
            String finalPlanRevisionId, long finalPlanRevisionNumber, String finalStepId,
            String reviewDecisionId, CanonicalJson acceptedSet, long applicabilityCutEventSequence,
            Long artifactId, String candidateKey, String workspaceId, String validationId,
            String validationRequestDigest, String validationReceiptDigest, CanonicalJson coverage,
            ChainPublishRequirement publishRequirement, String publishRequirementDigest, String instructionId,
            String projectVersion, Instant createdAt) implements TaskAuthorityFact {
        public FinalizationReadinessRecord {
            required(readinessId, "readinessId"); required(taskId, "taskId"); required(eventId, "eventId");
            required(transitionId, "transitionId"); sha256Required(readinessScopeKey, "readinessScopeKey");
            required(taskFrameId, "taskFrameId"); required(finalPlanId, "finalPlanId");
            required(finalPlanRevisionId, "finalPlanRevisionId");
            positive(finalPlanRevisionNumber, "finalPlanRevisionNumber"); required(finalStepId, "finalStepId");
            required(reviewDecisionId, "reviewDecisionId"); Objects.requireNonNull(acceptedSet, "acceptedSet");
            nonNegative(applicabilityCutEventSequence, "applicabilityCutEventSequence");
            required(candidateKey, "candidateKey"); required(workspaceId, "workspaceId");
            required(validationId, "validationId"); Objects.requireNonNull(coverage, "coverage");
            Objects.requireNonNull(publishRequirement, "publishRequirement");
            sha256Required(publishRequirementDigest, "publishRequirementDigest");
            required(instructionId, "instructionId");
            required(projectVersion, "projectVersion"); Objects.requireNonNull(createdAt, "createdAt");
            if (ChainIdentity.NONE.equals(candidateKey) != (artifactId == null))
                throw new IllegalArgumentException("candidate key and artifact identity do not match");
            if (ChainIdentity.NONE.equals(validationId)) {
                if (validationRequestDigest != null || validationReceiptDigest != null)
                    throw new IllegalArgumentException("NONE validation cannot carry digests");
            } else if (blank(validationRequestDigest) || blank(validationReceiptDigest)) {
                throw new IllegalArgumentException("validation requires request and receipt digests");
            } else {
                sha256Required(validationRequestDigest, "validationRequestDigest");
                sha256Required(validationReceiptDigest, "validationReceiptDigest");
            }
        }
    }

    public record FinalizationCheckRecord(
            String finalizationCheckId, String taskId, String eventId, String readinessId,
            String transitionId, int attemptNo, String taskFrameId, String finalPlanRevisionId,
            String acceptedSetSha256, String candidateKey, String workspaceId, String validationId,
            String validationRequestDigest, String validationReceiptDigest,
            String publishRequirementDigest, String instructionId, String projectVersion,
            String inputDigest, String contentDigest, String publishDigest,
            ChainFinalization.Outcome resultStatus, ChainFinalization.ErrorCode errorCode,
            ChainFinalization.FailureHandling failureDisposition,
            String runtimePolicyVersion, Instant createdAt) implements TaskAuthorityFact {
        public FinalizationCheckRecord {
            required(finalizationCheckId, "finalizationCheckId"); required(taskId, "taskId");
            required(eventId, "eventId"); required(readinessId, "readinessId"); required(transitionId, "transitionId");
            positive(attemptNo, "attemptNo");
            required(taskFrameId, "taskFrameId");
            required(finalPlanRevisionId, "finalPlanRevisionId");
            sha256Required(acceptedSetSha256, "acceptedSetSha256");
            required(candidateKey, "candidateKey"); required(workspaceId, "workspaceId");
            required(validationId, "validationId");
            sha256Required(publishRequirementDigest, "publishRequirementDigest");
            required(instructionId, "instructionId"); required(projectVersion, "projectVersion");
            sha256Required(inputDigest, "inputDigest"); sha256Required(contentDigest, "contentDigest");
            sha256Required(publishDigest, "publishDigest"); Objects.requireNonNull(resultStatus, "resultStatus");
            Objects.requireNonNull(failureDisposition, "failureDisposition");
            required(runtimePolicyVersion, "runtimePolicyVersion"); Objects.requireNonNull(createdAt, "createdAt");
            if (resultStatus == ChainFinalization.Outcome.PASSED
                    && (errorCode != null || failureDisposition != ChainFinalization.FailureHandling.NONE))
                throw new IllegalArgumentException("PASSED check cannot carry failure fields");
            if (resultStatus == ChainFinalization.Outcome.FAILED
                    && (errorCode == null || failureDisposition == ChainFinalization.FailureHandling.NONE))
                throw new IllegalArgumentException("FAILED check requires error and disposition");
            if (failureDisposition == ChainFinalization.FailureHandling.RETRYABLE
                    && errorCode != ChainFinalization.ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)
                throw new IllegalArgumentException("only temporary authority failure is retryable");
            if (ChainIdentity.NONE.equals(validationId)) {
                if (validationRequestDigest != null || validationReceiptDigest != null)
                    throw new IllegalArgumentException("NONE validation cannot carry digests");
            } else {
                sha256Required(validationRequestDigest, "validationRequestDigest");
                sha256Required(validationReceiptDigest, "validationReceiptDigest");
            }
        }
    }

    public record TaskOutcomeRecord(
            String outcomeId, String taskId, String eventId, String sourceCommandId,
            ChainTaskOutcomeStatus outcomeType, String instructionId, String taskFrameId,
            String finalPlanId, String finalPlanRevisionId, CanonicalJson coverage,
            CanonicalJson acceptedSet, Long finalArtifactId, String candidateKey,
            String finalizationReadinessId, String finalizationCheckId,
            String validationId, String validationRequestDigest,
            String validationReceiptDigest,
            ChainPublishRequirement publishRequirement,
            String publishRequirementDigest,
            String publishOperationId, String publishedProjectVersion,
            Long publishedRevisionId, String publishReceiptId, CanonicalJson incompleteItems,
            CanonicalJson limitations, CanonicalJson risks, String failureCategory,
            String failureCode, String sourceDecisionId, Instant createdAt) implements TaskAuthorityFact {
        /** Source-compatible constructor for terminal outcomes without a finalization root. */
        public TaskOutcomeRecord(
                String outcomeId, String taskId, String eventId,
                String sourceCommandId, ChainTaskOutcomeStatus outcomeType,
                String instructionId, String taskFrameId, String finalPlanId,
                String finalPlanRevisionId, CanonicalJson coverage,
                CanonicalJson acceptedSet, Long finalArtifactId,
                String candidateKey, String validationId,
                String publishOperationId, String publishedProjectVersion,
                Long publishedRevisionId, String publishReceiptId,
                CanonicalJson incompleteItems, CanonicalJson limitations,
                CanonicalJson risks, String failureCategory,
                String failureCode, String sourceDecisionId,
                Instant createdAt) {
            this(outcomeId, taskId, eventId, sourceCommandId, outcomeType,
                    instructionId, taskFrameId, finalPlanId,
                    finalPlanRevisionId, coverage, acceptedSet,
                    finalArtifactId, candidateKey, null, null, validationId,
                    null, null, null, null, publishOperationId,
                    publishedProjectVersion, publishedRevisionId,
                    publishReceiptId, incompleteItems, limitations, risks,
                    failureCategory, failureCode, sourceDecisionId, createdAt);
        }

        public TaskOutcomeRecord {
            required(outcomeId, "outcomeId"); required(taskId, "taskId"); required(eventId, "eventId");
            required(sourceCommandId, "sourceCommandId"); Objects.requireNonNull(outcomeType, "outcomeType");
            required(instructionId, "instructionId"); Objects.requireNonNull(coverage, "coverage");
            Objects.requireNonNull(acceptedSet, "acceptedSet"); required(candidateKey, "candidateKey");
            required(validationId, "validationId"); Objects.requireNonNull(incompleteItems, "incompleteItems");
            Objects.requireNonNull(limitations, "limitations"); Objects.requireNonNull(risks, "risks");
            required(sourceDecisionId, "sourceDecisionId"); Objects.requireNonNull(createdAt, "createdAt");
            triple(taskFrameId, finalPlanId, finalPlanRevisionId, "TaskFrame/Plan identity");
            if (ChainIdentity.NONE.equals(candidateKey) != (finalArtifactId == null))
                throw new IllegalArgumentException("candidate key and final artifact identity do not match");
            int finalizationFields = (finalizationReadinessId == null ? 0 : 1)
                    + (finalizationCheckId == null ? 0 : 1)
                    + (publishRequirement == null ? 0 : 1)
                    + (publishRequirementDigest == null ? 0 : 1);
            if (finalizationFields != 0 && finalizationFields != 4)
                throw new IllegalArgumentException("finalization result identity must be all-or-none");
            if (publishRequirementDigest != null)
                sha256Required(publishRequirementDigest, "publishRequirementDigest");
            if (finalizationFields == 0) {
                if (validationRequestDigest != null || validationReceiptDigest != null)
                    throw new IllegalArgumentException(
                            "outcome without finalization result cannot carry validation digests");
            } else if (ChainIdentity.NONE.equals(validationId)) {
                if (validationRequestDigest != null || validationReceiptDigest != null)
                    throw new IllegalArgumentException("NONE validation cannot carry digests");
            } else {
                sha256Required(validationRequestDigest, "validationRequestDigest");
                sha256Required(validationReceiptDigest, "validationReceiptDigest");
            }
            boolean anyPublish = publishOperationId != null || publishedProjectVersion != null
                    || publishedRevisionId != null || publishReceiptId != null;
            boolean allPublish = publishOperationId != null && publishedProjectVersion != null
                    && publishedRevisionId != null && publishReceiptId != null;
            if (anyPublish && !allPublish) throw new IllegalArgumentException("publish result identity must be all-or-none");
            if (publishRequirement == ChainPublishRequirement.NOT_REQUIRED && anyPublish)
                throw new IllegalArgumentException("NOT_REQUIRED outcome cannot carry publish result identity");
            if (outcomeType == ChainTaskOutcomeStatus.COMPLETED
                    && publishRequirement == ChainPublishRequirement.REQUIRED && !allPublish)
                throw new IllegalArgumentException("REQUIRED completed outcome requires publish result identity");
            if (outcomeType == ChainTaskOutcomeStatus.FAILED && (blank(failureCategory) || blank(failureCode)))
                throw new IllegalArgumentException("FAILED outcome requires failure category and code");
            if (outcomeType != ChainTaskOutcomeStatus.FAILED
                    && (failureCategory != null || failureCode != null))
                throw new IllegalArgumentException("non-FAILED outcome cannot carry failure category or code");
        }
    }

    public record DeliveryRecord(
            String deliveryId, String taskId, String eventId, String sourceCommandId,
            String routeDecisionId, String taskOutcomeId, String gapId, String decisionId,
            String answerContentId, Long assistantMessageId, Instant createdAt) implements TaskAuthorityFact {
        public DeliveryRecord {
            required(deliveryId, "deliveryId"); required(taskId, "taskId"); required(eventId, "eventId");
            required(sourceCommandId, "sourceCommandId"); Objects.requireNonNull(createdAt, "createdAt");
            int sources = (routeDecisionId == null ? 0 : 1) + (taskOutcomeId == null ? 0 : 1)
                    + (gapId == null ? 0 : 1) + (decisionId == null ? 0 : 1);
            if (sources != 1)
                throw new IllegalArgumentException("delivery requires exactly one route/outcome/gap/decision source");
            paired(answerContentId, assistantMessageId, "answer/message binding");
        }
    }

    public record DeliveryEventRecord(
            String deliveryId, long eventSequence, String taskId, String eventId,
            ChainDeliveryStatus eventKind, int attemptNo, String errorCode,
            String runtimePolicyVersion, Instant committedAt) implements TaskAuthorityFact {
        public DeliveryEventRecord {
            required(deliveryId, "deliveryId"); positive(eventSequence, "eventSequence");
            required(taskId, "taskId"); required(eventId, "eventId");
            Objects.requireNonNull(eventKind, "eventKind"); nonNegative(attemptNo, "attemptNo");
            required(runtimePolicyVersion, "runtimePolicyVersion"); Objects.requireNonNull(committedAt, "committedAt");
            if (eventKind == ChainDeliveryStatus.PENDING && (attemptNo != 0 || errorCode != null))
                throw new IllegalArgumentException("PENDING delivery requires attempt zero and no error");
            if (eventKind == ChainDeliveryStatus.SUCCEEDED && (attemptNo == 0 || errorCode != null))
                throw new IllegalArgumentException("SUCCEEDED delivery requires a positive attempt and no error");
            if ((eventKind == ChainDeliveryStatus.RETRYING
                    || eventKind == ChainDeliveryStatus.DELIVERY_FAILED)
                    && (attemptNo == 0 || blank(errorCode)))
                throw new IllegalArgumentException(eventKind + " requires a positive attempt and errorCode");
        }
    }

    private static void required(String value, String name) {
        if (blank(value)) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static void asciiRequired(String value, String name) {
        required(value, name);
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7f)
                throw new IllegalArgumentException(name + " must contain only ASCII system-identity characters");
        }
    }

    private static void sha256Required(String value, String name) {
        required(value, name);
        if (value.length() != 64) throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f')))
                throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
    }

    private static void positive(long value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void nonNegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
    }

    private static void paired(Object left, Object right, String name) {
        if ((left == null) != (right == null)) throw new IllegalArgumentException(name + " must be paired");
    }

    private static void triple(Object first, Object second, Object third, String name) {
        int present = (first == null ? 0 : 1) + (second == null ? 0 : 1) + (third == null ? 0 : 1);
        if (present != 0 && present != 3) throw new IllegalArgumentException(name + " must be all-or-none");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
