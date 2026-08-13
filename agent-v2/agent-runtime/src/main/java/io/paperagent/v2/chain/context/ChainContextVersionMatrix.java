package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Frozen source-version vector, read-boundary and EMPTY watermark for each module. */
public final class ChainContextVersionMatrix {
    private static final Map<ChainContextModule, VersionRequirement> REQUIREMENTS = build();

    private ChainContextVersionMatrix() {
    }

    public static VersionRequirement requirement(ChainContextModule module) {
        return REQUIREMENTS.get(Objects.requireNonNull(module, "module"));
    }

    public static List<VersionRequirement> entries() {
        return ChainContextInputMatrix.orderedModules().stream()
                .map(REQUIREMENTS::get)
                .toList();
    }

    public record VersionRequirement(
            ChainContextModule module,
            List<String> sourceVersionFields,
            List<String> readBoundaryFields,
            String emptyWatermark,
            boolean emptyAllowed) {
        public VersionRequirement {
            Objects.requireNonNull(module, "module");
            sourceVersionFields = nonEmpty(sourceVersionFields, "sourceVersionFields");
            readBoundaryFields = nonEmpty(readBoundaryFields, "readBoundaryFields");
            if (emptyWatermark == null || emptyWatermark.isBlank()) {
                throw new IllegalArgumentException("emptyWatermark must not be blank");
            }
        }
    }

    private static Map<ChainContextModule, VersionRequirement> build() {
        EnumMap<ChainContextModule, VersionRequirement> values =
                new EnumMap<>(ChainContextModule.class);
        add(values, ChainContextModule.USER_INSTRUCTION_CHAIN,
                List.of("taskInstructionBindingHead", "instructionId", "messageIdAndBodyHash"),
                List.of("taskInstructionSequenceCut"), "taskInstructionSequenceCut=0", true);
        add(values, ChainContextModule.CONVERSATION_CONTEXT,
                List.of("summaryIdentityUpdatedAtCoverageAndDigest", "messageCut"),
                List.of("sessionId", "maxMessageId"), "summary=NONE,maxMessageId=0", true);
        add(values, ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                List.of("projectVersion", "manifestFingerprint", "explicitInputRefVector"),
                List.of("projectAndVersion", "completeManifestCut"), "project=NONE,input=[]", true);
        add(values, ChainContextModule.TASK_CONTRACT,
                List.of("taskFrameId", "taskFramePayloadHash"),
                List.of("taskIdentity"), "taskFrame=NONE@instructionVersion", true);
        add(values, ChainContextModule.PLAN_AND_STEP_CONTRACT,
                List.of("planId", "revisionIdentity", "checkpoint", "v2EventSequence", "payloadHash"),
                List.of("stableV2PlanCut", "chainAuthorityEventCut"),
                "plan=NONE,revision=0,v2EventSequence=0", true);
        add(values, ChainContextModule.TASK_AND_STEP_RUNTIME_STATE,
                List.of("chainEventCut", "stepRecoveryHead", "acceptedResultAndApplicabilityCut", "outcomeId"),
                List.of("taskEventSequence", "checkpointHead"), "allCuts=0", true);
        add(values, ChainContextModule.CURRENT_STEP_ACTION_TOOLS_AND_ERRORS,
                List.of("actionCut", "effectIntent", "progress", "receiptAndOutcomeIds"),
                List.of("planStepActivationActionFence"), "actionSequence=0", true);
        add(values, ChainContextModule.WORKSPACE_AND_CANDIDATE,
                List.of("workspaceConfirmationFingerprint", "candidateBindingSequence", "artifactFingerprintAndDiff"),
                List.of("projectVersion", "workspace", "candidate"),
                "workspace=NONE,candidateSequence=0", true);
        add(values, ChainContextModule.VALIDATION_AND_PUBLISH,
                List.of("validationIdentityStatusAndDigest", "readiness", "finalizationAttempt", "publishOperationAndVersion"),
                List.of("candidate", "workspace", "validationCut"), "allSequences=0", true);
        add(values, ChainContextModule.REVIEW_DECISIONS_AND_PENDING_ITEMS,
                List.of("reviewCut", "pendingCut", "permissionCut", "transitionCut"),
                List.of("taskEventCut", "planAndStepBinding"), "allCuts=0", true);
        add(values, ChainContextModule.MEMORY_RETRIEVAL_AND_KNOWLEDGE_EVIDENCE,
                List.of("frozenCatalogIdentityAndDigest", "exactEvidenceRefVector"),
                List.of("taskCatalogCut"), "emptyCatalogDigestAndObservationCuts", true);
        add(values, ChainContextModule.RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS,
                List.of("contractSchema", "rolePrompt", "providerSchema", "toolCatalogDigest",
                        "skillVersion", "permissionSnapshot", "productBoundaryVersion"),
                List.of("role", "authenticatedPermissionCut"), "EMPTY_ILLEGAL", false);
        add(values, ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS,
                List.of("priorInvocationCut", "proposalStateCut", "currentRevisionAndCallReason"),
                List.of("priorInvocationOrdinal"), "priorInvocationOrdinal=0", true);
        if (values.size() != 13) {
            throw new ExceptionInInitializerError("incomplete context version matrix");
        }
        return Map.copyOf(values);
    }

    private static void add(
            Map<ChainContextModule, VersionRequirement> values,
            ChainContextModule module,
            List<String> source,
            List<String> boundary,
            String emptyWatermark,
            boolean emptyAllowed) {
        values.put(module, new VersionRequirement(
                module, source, boundary, emptyWatermark, emptyAllowed));
    }

    private static List<String> nonEmpty(List<String> values, String name) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, name));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return copy;
    }
}
