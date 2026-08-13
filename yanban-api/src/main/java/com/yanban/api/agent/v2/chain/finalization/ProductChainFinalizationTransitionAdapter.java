package com.yanban.api.agent.v2.chain.finalization;

import com.yanban.api.agent.v2.chain.publish.ProductChainPublishAuthoritySource;
import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainTransitionWriter;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.finalization.ChainFinalizationTransitionPort;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Product append boundary for a recoverable frozen FINALIZATION prefix. */
@Component
public final class ProductChainFinalizationTransitionAdapter
        implements ChainFinalizationTransitionPort {
    private final ChainWorkflowRepository workflow;
    private final ChainFoundationRepository foundations;
    private final ChainTransitionWriter writer;
    private final ChainFinalizationRepository finalization;
    private final ProductChainPublishAuthoritySource publishes;

    public ProductChainFinalizationTransitionAdapter(
            ChainWorkflowRepository workflow,
            ChainFoundationRepository foundations,
            ChainTransitionWriter writer,
            ChainFinalizationRepository finalization,
            ProductChainPublishAuthoritySource publishes) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.foundations = Objects.requireNonNull(
                foundations, "foundations");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.finalization = Objects.requireNonNull(
                finalization, "finalization");
        this.publishes = Objects.requireNonNull(publishes, "publishes");
    }

    @Override
    public void advance(AdvanceCommand command) {
        Objects.requireNonNull(command, "command");
        ChainPersistenceRecords.TransitionRecord transition =
                ensureTransition(command);
        List<ChainPersistenceRecords.TransitionStageRecord> stored =
                prefix(transition);
        if (stored.size() > command.requiredPrefix().size()) {
            verifyRequiredBeginning(command, stored);
            return;
        }
        verifyStoredBeginning(command, stored);
        for (int ordinal = stored.size();
             ordinal < command.requiredPrefix().size(); ordinal++) {
            ChainFinalizationTransitionPort.StageAuthority evidence =
                    command.requiredPrefix().get(ordinal);
            verifyEvidence(command, transition, evidence);
            String eventId = "transition.stage." + sha256(
                    transition.transitionId() + "\0" + evidence.stage());
            ChainPersistenceRecords.TransitionStageRecord requested =
                    new ChainPersistenceRecords.TransitionStageRecord(
                            transition.transitionId(), evidence.stage(),
                            transition.taskId(), eventId, ordinal,
                            evidence.predecessorAuthorityType(),
                            evidence.predecessorAuthorityRef(),
                            evidence.successorAuthorityType(),
                            evidence.successorAuthorityRef(),
                            command.committedAt());
            ChainPersistenceRecords.AuthorityEventRequest event =
                    new ChainPersistenceRecords.AuthorityEventRequest(
                            eventId, transition.taskId(), "TRANSITION_STAGE",
                            transition.transitionId(),
                            transition.targetIdentityDigest(),
                            command.committedAt());
            var appended = writer.appendTransitionStage(
                    new ChainPersistenceRecords.AuthoritativeFact<>(
                            event, requested));
            requireSameIgnoringTime(requested, appended.fact());
            requireEvent(event, appended.event(),
                    appended.fact().committedAt());
            stored = append(stored, appended.fact());
        }
    }

    private ChainPersistenceRecords.TransitionRecord ensureTransition(
            AdvanceCommand command) {
        ChainPersistenceRecords.TransitionRecord existing = workflow
                .findTransition(command.transitionId()).orElse(null);
        if (existing != null) {
            if (!existing.taskId().equals(command.taskId())
                    || existing.transitionType()
                    != ChainTransitionType.FINALIZATION
                    || !existing.sourceDecisionId().equals(
                    command.sourceDecisionId())
                    || !existing.targetIdentityDigest().equals(
                    command.targetIdentityDigest())) {
                throw new IllegalStateException(
                        "FINALIZATION transition identity changed");
            }
            return existing;
        }
        String eventId = "transition.open." + sha256(
                command.transitionId());
        ChainPersistenceRecords.TransitionRecord requested =
                new ChainPersistenceRecords.TransitionRecord(
                        command.transitionId(), command.taskId(), eventId,
                        ChainTransitionType.FINALIZATION,
                        command.sourceDecisionId(),
                        command.targetIdentityDigest(), command.committedAt());
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        eventId, command.taskId(), "TRANSITION",
                        command.transitionId(),
                        command.targetIdentityDigest(), command.committedAt());
        var appended = writer.appendTransition(
                new ChainPersistenceRecords.AuthoritativeFact<>(
                        event, requested));
        requireSameIgnoringTime(requested, appended.fact());
        requireEvent(event, appended.event(), appended.fact().createdAt());
        return appended.fact();
    }

    private List<ChainPersistenceRecords.TransitionStageRecord> prefix(
            ChainPersistenceRecords.TransitionRecord transition) {
        List<ChainPersistenceRecords.TransitionStageRecord> stages = workflow
                .findTransitionStages(transition.transitionId()).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.TransitionStageRecord
                                ::stageOrdinal))
                .toList();
        List<ChainTransitionStage> committed = new ArrayList<>();
        Set<ChainTransitionStage> unique = new HashSet<>();
        for (int index = 0; index < stages.size(); index++) {
            ChainPersistenceRecords.TransitionStageRecord stage =
                    stages.get(index);
            if (!stage.taskId().equals(transition.taskId())
                    || !stage.transitionId().equals(
                    transition.transitionId())
                    || stage.stageOrdinal() != index
                    || !unique.add(stage.stageCode())) {
                throw new IllegalStateException(
                        "FINALIZATION transition prefix is inconsistent");
            }
            stage.validateNextFor(ChainTransitionType.FINALIZATION, committed);
            committed.add(stage.stageCode());
        }
        return stages;
    }

    private void verifyEvidence(
            AdvanceCommand command,
            ChainPersistenceRecords.TransitionRecord transition,
            ChainFinalizationTransitionPort.StageAuthority evidence) {
        switch (evidence.stage()) {
            case OPEN, COMPLETE -> { }
            case READINESS_VERIFIED -> {
                var readiness = finalization.findReadinessById(
                                evidence.predecessorAuthorityRef())
                        .orElseThrow(() -> failure(
                                "readiness authority is missing"));
                require(readiness.taskId().equals(command.taskId())
                                && readinessDigest(readiness).equals(
                                command.targetIdentityDigest()),
                        "readiness does not bind FINALIZATION target");
            }
            case FINALIZATION_CHECK_COMMITTED -> {
                ChainPersistenceRecords.FinalizationCheckRecord check =
                        check(command.taskId(),
                                evidence.successorAuthorityRef());
                require(check.transitionId().equals(transition.transitionId()),
                        "FinalizationCheck binds another transition");
            }
            case PUBLISH_COMMITTED_OR_NOT_REQUIRED -> {
                ChainPersistenceRecords.FinalizationCheckRecord check =
                        terminalCheck(command.taskId(),
                                transition.transitionId());
                ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                        finalization.findReadinessById(check.readinessId())
                                .filter(value -> value.taskId().equals(
                                        command.taskId()))
                                .orElseThrow(() -> failure(
                                        "publish readiness is missing"));
                require(check.resultStatus()
                                == ChainFinalization.Outcome.PASSED,
                        "publish stage requires PASSED FinalizationCheck");
                if (evidence.successorAuthorityRef() != null) {
                    var operation = publishes.requireExactSuccess(
                            evidence.successorAuthorityRef(), readiness, check);
                    require(readiness.publishRequirement()
                                    == ChainPublishRequirement.REQUIRED
                                    && operation.succeeded(),
                            "publish receipt is not a successful Project operation");
                } else {
                    require(readiness.publishRequirement()
                                    == ChainPublishRequirement.NOT_REQUIRED,
                            "publish omission requires NOT_REQUIRED authority");
                }
            }
            case TASK_OUTCOME_COMMITTED -> {
                var outcome = finalization.findTaskOutcome(command.taskId())
                        .filter(value -> value.outcomeId().equals(
                                evidence.successorAuthorityRef()))
                        .orElseThrow(() -> failure(
                                "TaskOutcome authority is missing"));
                require(outcome.sourceDecisionId().equals(
                                transition.transitionId()),
                        "TaskOutcome binds another finalization transition");
            }
            case FAILED_CHECK_HANDOFF_COMMITTED -> {
                ChainPersistenceRecords.FinalizationCheckRecord check =
                        terminalCheck(command.taskId(),
                                transition.transitionId());
                ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                        finalization.findReadinessById(check.readinessId())
                                .filter(value -> value.taskId().equals(
                                        command.taskId()))
                                .orElseThrow(() -> failure(
                                        "failure readiness is missing"));
                com.yanban.api.agent.v2.chain.publish
                        .ProductChainPublishAuthoritySource.Operation
                        publishFailure = null;
                if (check.resultStatus()
                        == ChainFinalization.Outcome.PASSED) {
                    require("PUBLISH_FAILURE".equals(
                                    evidence.predecessorAuthorityType()),
                            "PASSED check failure branch requires publish failure");
                    publishFailure = publishes.requireExactFailure(
                            evidence.predecessorAuthorityRef(),
                            readiness,
                            check);
                } else {
                    require(check.resultStatus()
                                    == ChainFinalization.Outcome.FAILED
                                    && evidence.predecessorAuthorityType() == null,
                            "failed handoff requires exact check/publish branch");
                }
                String handoffObjectType = publishFailure == null
                        ? "FINALIZATION_CHECK" : "PUBLISH_FAILURE";
                String handoffObjectId = publishFailure == null
                        ? check.finalizationCheckId()
                        : publishFailure.formalRef();
                boolean review = workflow.findReviewDecisions(
                                command.taskId()).stream()
                        .anyMatch(value -> value.taskId().equals(
                                command.taskId())
                                && value.reviewDecisionId().equals(
                                evidence.successorAuthorityRef())
                                && handoffObjectType.equals(
                                value.reviewObjectType())
                                && handoffObjectId.equals(
                                value.reviewObjectId())
                                && (value.decisionKind()
                                == ChainProposalKind.REFLECTOR_REPLAN_REQUIRED
                                || value.decisionKind()
                                == ChainProposalKind.REFLECTOR_NEED_PERMISSION
                                || value.decisionKind()
                                == ChainProposalKind.REFLECTOR_TASK_FAILED));
                String sourceCommandId = foundations.findInstruction(
                                readiness.instructionId())
                        .map(ChainPersistenceRecords.InstructionRecord::commandId)
                        .orElseThrow(() -> failure(
                                "failure instruction command is missing"));
                String expectedFailureCode = publishFailure == null
                        ? check.errorCode().name()
                        : com.yanban.api.agent.v2.chain.publish
                        .ProductChainPublishAuthoritySource.publishErrorCode(
                                publishFailure).name();
                String expectedDecisionId = publishFailure == null
                        ? check.finalizationCheckId()
                        : publishFailure.formalRef();
                String expectedFailureCategory = publishFailure == null
                        ? "FINALIZATION" : "PUBLISH";
                boolean outcome = finalization.findTaskOutcome(
                                command.taskId())
                        .filter(value -> value.outcomeId().equals(
                                evidence.successorAuthorityRef()))
                        .filter(value -> value.outcomeType()
                                == ChainTaskOutcomeStatus.FAILED)
                        .filter(value -> value.sourceDecisionId().equals(
                                expectedDecisionId))
                        .filter(value -> value.sourceCommandId().equals(
                                sourceCommandId))
                        .filter(value -> expectedFailureCategory.equals(
                                value.failureCategory()))
                        .filter(value -> expectedFailureCode.equals(
                                value.failureCode()))
                        .isPresent();
                require(review || outcome,
                        "failed-check handoff authority is missing");
            }
            default -> throw failure(
                    "unsupported FINALIZATION transition stage");
        }
    }

    private ChainPersistenceRecords.FinalizationCheckRecord check(
            String taskId, String checkId) {
        return finalization.findReadiness(taskId).stream()
                .flatMap(value -> finalization.findFinalizationChecks(
                        value.readinessId()).stream())
                .filter(value -> value.finalizationCheckId().equals(checkId))
                .findFirst().orElseGet(() -> workflowCheckFallback(checkId));
    }

    private ChainPersistenceRecords.FinalizationCheckRecord
            workflowCheckFallback(String checkId) {
        throw failure("FinalizationCheck authority is missing: " + checkId);
    }

    private ChainPersistenceRecords.FinalizationCheckRecord terminalCheck(
            String taskId, String transitionId) {
        List<ChainPersistenceRecords.FinalizationCheckRecord> matches =
                finalization.findReadiness(taskId).stream()
                        .flatMap(value -> finalization
                                .findFinalizationChecks(value.readinessId())
                                .stream())
                        .filter(value -> transitionId.equals(
                                value.transitionId()))
                        .toList();
        return matches.stream().max(Comparator.comparingInt(
                        ChainPersistenceRecords.FinalizationCheckRecord
                                ::attemptNo))
                .orElseThrow(() -> failure(
                        "terminal FinalizationCheck is missing"));
    }

    private static void verifyStoredBeginning(
            AdvanceCommand command,
            List<ChainPersistenceRecords.TransitionStageRecord> stored) {
        for (int index = 0; index < stored.size(); index++) {
            requireSame(command.requiredPrefix().get(index),
                    stored.get(index));
        }
    }

    private static void verifyRequiredBeginning(
            AdvanceCommand command,
            List<ChainPersistenceRecords.TransitionStageRecord> stored) {
        for (int index = 0;
             index < command.requiredPrefix().size(); index++) {
            requireSame(command.requiredPrefix().get(index),
                    stored.get(index));
        }
    }

    private static void requireSame(
            ChainFinalizationTransitionPort.StageAuthority expected,
            ChainPersistenceRecords.TransitionStageRecord actual) {
        require(expected.stage() == actual.stageCode()
                        && Objects.equals(expected.predecessorAuthorityType(),
                        actual.predecessorAuthorityType())
                        && Objects.equals(expected.predecessorAuthorityRef(),
                        actual.predecessorAuthorityRef())
                        && Objects.equals(expected.successorAuthorityType(),
                        actual.successorAuthorityType())
                        && Objects.equals(expected.successorAuthorityRef(),
                        actual.successorAuthorityRef()),
                "FINALIZATION transition replay changed evidence");
    }

    private static void requireSameIgnoringTime(
            Record expected, Record actual) {
        var expectedComponents = expected.getClass().getRecordComponents();
        try {
            for (var component : expectedComponents) {
                if (component.getName().equals("createdAt")
                        || component.getName().equals("committedAt")) {
                    continue;
                }
                require(Objects.equals(
                                component.getAccessor().invoke(expected),
                                component.getAccessor().invoke(actual)),
                        "formal transition append changed immutable fields");
            }
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireEvent(
            ChainPersistenceRecords.AuthorityEventRequest expected,
            ChainPersistenceRecords.AuthorityEventRecord actual,
            java.time.Instant factTime) {
        require(expected.eventId().equals(actual.eventId())
                        && expected.taskId().equals(actual.taskId())
                        && expected.eventType().equals(actual.eventType())
                        && Objects.equals(expected.transitionId(),
                        actual.transitionId())
                        && expected.sourceIdentitySha256().equals(
                        actual.sourceIdentitySha256())
                        && factTime.equals(actual.committedAt()),
                "transition authority event changed immutable fields");
    }

    private static <T> List<T> append(List<T> values, T value) {
        ArrayList<T> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String readinessDigest(
            ChainPersistenceRecords.FinalizationReadinessRecord value) {
        return sha256(value.readinessId() + "\0" + value.taskId() + "\0"
                + value.transitionId() + "\0" + value.taskFrameId() + "\0"
                + value.finalPlanId() + "\0" + value.finalPlanRevisionId()
                + "\0" + value.finalPlanRevisionNumber() + "\0"
                + value.finalStepId() + "\0" + value.reviewDecisionId()
                + "\0" + value.acceptedSet().sha256() + "\0"
                + value.applicabilityCutEventSequence() + "\0"
                + Objects.toString(value.artifactId(), "NONE") + "\0"
                + value.candidateKey() + "\0" + value.workspaceId() + "\0"
                + value.validationId() + "\0"
                + Objects.toString(value.validationRequestDigest(), "NONE")
                + "\0"
                + Objects.toString(value.validationReceiptDigest(), "NONE")
                + "\0" + value.coverage().sha256() + "\0"
                + value.publishRequirement() + "\0"
                + value.publishRequirementDigest() + "\0"
                + value.instructionId() + "\0" + value.projectVersion());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw failure(message);
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }
}
