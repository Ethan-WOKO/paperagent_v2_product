package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.ChainPersistenceRecords.ActionBindingRecord;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainWorkflowRepository;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Mechanical Executor repair, duplicate-action and unknown-effect gate. */
public final class ChainExecutorRepairService {
    private final ChainRuntimePolicy policy;
    private final ChainProgressPolicy progress;
    private final ChainWorkflowRepository workflows;
    private final ChainProgressAuthorityPort progressAuthority;

    public ChainExecutorRepairService(
            ChainRuntimePolicy policy,
            ChainWorkflowRepository workflows,
            ChainProgressAuthorityPort progressAuthority) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.progress = new ChainProgressPolicy(policy);
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.progressAuthority = Objects.requireNonNull(
                progressAuthority, "progressAuthority");
    }

    public RepairDecision decide(RepairRequest request) {
        Objects.requireNonNull(request, "request");
        List<ActionBindingRecord> history = workflows.findActionBindings(
                        request.taskId()).stream()
                .filter(action -> action.taskId().equals(request.taskId())
                        && action.stepId().equals(request.stepId())
                        && action.activationEventId().equals(
                        request.activationEventId()))
                .sorted(Comparator.comparingInt(
                        ActionBindingRecord::attemptNo))
                .toList();
        validateCompleteActionHistory(history);
        ChainProgressAuthorityPort.ProgressSnapshot progressSnapshot =
                Objects.requireNonNull(progressAuthority.readProgress(
                                request.taskId(), request.stepId(),
                                request.activationEventId()),
                        "formal progress snapshot");
        if (request.effectStatusUnknown()) {
            List<ActionBindingRecord> inFlight = workflows
                    .findInFlightActions(request.taskId()).stream()
                    .filter(action -> action.taskId().equals(request.taskId())
                            && action.stepId().equals(request.stepId())
                            && action.activationEventId().equals(
                            request.activationEventId()))
                    .toList();
            verifyInFlightCut(history, inFlight);
            return inFlight.size() == 1
                    ? new RepairDecision(
                            RepairNext.RECONCILE_SAME_ACTION,
                            inFlight.get(0).actionId(),
                            "EFFECT_STATUS_UNKNOWN")
                    : new RepairDecision(
                            RepairNext.BLOCK_FOR_REFLECTOR, null,
                            "UNKNOWN_EFFECT_ACTION_AMBIGUOUS");
        }
        ChainProgressPolicy.Assessment progressAssessment =
                progress.assess(progressSnapshot.markers());
        if (progressAssessment.decision()
                == ChainProgressPolicy.ProgressDecision
                .BLOCK_FOR_REFLECTOR) {
            return new RepairDecision(
                    RepairNext.BLOCK_FOR_REFLECTOR, null,
                    "NO_PROGRESS_THRESHOLD_REACHED");
        }
        long sameSignature = history.stream().filter(action ->
                action.actionSignatureSha256().equals(
                        request.proposedActionSignatureSha256())).count();
        if (sameSignature
                >= policy.sameActionSignatureOccurrencesMax()) {
            return new RepairDecision(
                    RepairNext.BLOCK_FOR_REFLECTOR, null,
                    "REPEATED_ACTION_SIGNATURE");
        }
        if (request.lastErrorRef() != null) {
            ActionBindingRecord previous = history.stream()
                    .filter(action -> action.actionId().equals(
                            request.lastActionId()))
                    .findFirst().orElseThrow(() -> new ChainStepException(
                            "CHAIN_REPAIR_ACTION_NOT_FOUND",
                            "repair must bind the failed formal action"));
            if (previous.actionSignatureSha256().equals(
                    request.proposedActionSignatureSha256())) {
                return new RepairDecision(
                        RepairNext.BLOCK_FOR_REFLECTOR,
                        previous.actionId(),
                        "REPAIR_DID_NOT_CHANGE_ACTION");
            }
            return new RepairDecision(
                    RepairNext.CALL_EXECUTOR_REPAIR,
                    previous.actionId(),
                    "ORDINARY_ACTION_FAILURE");
        }
        return new RepairDecision(
                RepairNext.CONTINUE_EXECUTOR, null, "PROGRESS_AVAILABLE");
    }

    public record RepairRequest(
            String taskId,
            String stepId,
            String activationEventId,
            String proposedActionSignatureSha256,
            boolean effectStatusUnknown,
            String lastActionId,
            String lastErrorRef,
            String changeDigest) {
        public RepairRequest {
            taskId = required(taskId, "taskId");
            stepId = required(stepId, "stepId");
            activationEventId = required(
                    activationEventId, "activationEventId");
            sha(proposedActionSignatureSha256,
                    "proposedActionSignatureSha256");
            boolean hasRepair = lastActionId != null
                    || lastErrorRef != null || changeDigest != null;
            boolean completeRepair = lastActionId != null
                    && lastErrorRef != null && changeDigest != null;
            if (hasRepair != completeRepair) {
                throw new IllegalArgumentException(
                        "repair action, error and change digest must be complete");
            }
            if (changeDigest != null) {
                sha(changeDigest, "changeDigest");
            }
        }
    }

    private static void validateCompleteActionHistory(
            List<ActionBindingRecord> history) {
        Set<String> actionIds = new HashSet<>();
        Set<Integer> attempts = new HashSet<>();
        int expectedAttempt = 1;
        for (ActionBindingRecord action : history) {
            if (!actionIds.add(action.actionId())
                    || !attempts.add(action.attemptNo())
                    || action.attemptNo() != expectedAttempt++) {
                throw new ChainStepException(
                        "CHAIN_REPAIR_ACTION_HISTORY_INVALID",
                        "formal action history is not one complete attempt prefix");
            }
        }
    }

    private static void verifyInFlightCut(
            List<ActionBindingRecord> history,
            List<ActionBindingRecord> inFlight) {
        Set<String> actual = new HashSet<>();
        for (ActionBindingRecord action : inFlight) {
            if (!actual.add(action.actionId()) || !history.contains(action)
                    || action.resultAuthorityType() != null) {
                throw new ChainStepException(
                        "CHAIN_REPAIR_INFLIGHT_CUT_INVALID",
                        "in-flight action query conflicts with formal history");
            }
        }
    }

    public enum RepairNext {
        CONTINUE_EXECUTOR,
        CALL_EXECUTOR_REPAIR,
        RECONCILE_SAME_ACTION,
        BLOCK_FOR_REFLECTOR
    }

    public record RepairDecision(
            RepairNext next,
            String boundActionId,
            String reasonCode) {
        public RepairDecision {
            Objects.requireNonNull(next, "next");
            reasonCode = required(reasonCode, "reasonCode");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void sha(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be lowercase SHA-256");
        }
    }
}
