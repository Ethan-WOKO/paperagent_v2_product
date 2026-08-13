package com.yanban.api.agent.v2.chain.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainValidationBundleRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainValidationRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainValidationRepository;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.validation.ChainValidationBundleRuntime;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.TaskRequirements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Builds one typed plan ValidationBundle from exact product authorities. */
@Component
public final class ProductChainValidationBundleAuthority {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChainFoundationRepository foundations;
    private final ChainModelRepository models;
    private final ChainWorkflowRepository workflows;
    private final ChainStepAuthorityPort stepAuthority;
    private final ChainValidationRepository validations;
    private final ChainValidationBundleRuntime runtime;
    private final RequirementsAuthority requirementsAuthority;
    private final PlanRevisionAuthority planRevisionAuthority;

    @Autowired
    public ProductChainValidationBundleAuthority(
            ChainFoundationRepository foundations,
            ProductChainModelRepositoryAdapter models,
            ChainWorkflowRepository workflows,
            ProductChainStepAuthorityAdapter stepAuthority,
            ProductChainValidationRepositoryAdapter validations,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductChainValidationBundleRepositoryAdapter bundles) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.models = Objects.requireNonNull(models, "models");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.stepAuthority = Objects.requireNonNull(
                stepAuthority, "stepAuthority");
        this.validations = Objects.requireNonNull(validations, "validations");
        Objects.requireNonNull(bootstraps, "bootstraps");
        this.requirementsAuthority = (planId, taskFrameId) -> {
            var stored = bootstraps.find(new PlanId(planId))
                    .orElseThrow(() -> invalid(
                            "CHAIN_VALIDATION_BUNDLE_TASK_FRAME_NOT_FOUND"));
            if (!stored.plan().id().value().equals(planId)
                    || !stored.taskFrame().id().value().equals(taskFrameId)
                    || !stored.plan().taskFrameId().equals(
                    stored.taskFrame().id())) {
                throw invalid(
                        "CHAIN_VALIDATION_BUNDLE_TASK_FRAME_AUTHORITY_INVALID");
            }
            return stored.taskFrame().requirements();
        };
        this.planRevisionAuthority = stepAuthority::findPlanRevision;
        this.runtime = new ChainValidationBundleRuntime(
                Objects.requireNonNull(bundles, "bundles"));
    }

    ProductChainValidationBundleAuthority(
            ChainFoundationRepository foundations,
            ChainModelRepository models,
            ChainWorkflowRepository workflows,
            ChainStepAuthorityPort stepAuthority,
            ChainValidationRepository validations,
            RequirementsAuthority requirementsAuthority,
            PlanRevisionAuthority planRevisionAuthority,
            ChainValidationBundleRuntime runtime) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.models = Objects.requireNonNull(models, "models");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.stepAuthority = Objects.requireNonNull(
                stepAuthority, "stepAuthority");
        this.validations = Objects.requireNonNull(validations, "validations");
        this.requirementsAuthority = Objects.requireNonNull(
                requirementsAuthority, "requirementsAuthority");
        this.planRevisionAuthority = Objects.requireNonNull(
                planRevisionAuthority, "planRevisionAuthority");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public ChainValidationBundleRuntime.Outcome build(BuildCommand command) {
        Objects.requireNonNull(command, "command");
        Boundary boundary = verifyBoundary(command);
        List<ChainValidationBundleRuntime.FormalSource> sources = new ArrayList<>();
        for (PlanStep step : command.planRevision().steps()) {
            if (!step.validationRequirementIds().isEmpty()) {
                sources.add(source(boundary, step));
            }
        }
        return runtime.commit(new ChainValidationBundleRuntime.CommitCommand(
                new ChainValidationBundleRuntime.Scope(
                        boundary.taskId(), boundary.taskFrameId(),
                        boundary.planId(), boundary.planRevisionId(),
                        boundary.planRevisionNumber(), boundary.instructionId(),
                        command.finalStepId(), command.idempotencyKey(),
                        command.createdAt()),
                requirementsAuthority.load(
                        boundary.planId(), boundary.taskFrameId()),
                command.planRevision().steps(), sources));
    }

    private Boundary verifyBoundary(BuildCommand command) {
        var task = command.task();
        var binding = command.planBinding();
        var revision = command.planRevision();
        var authoritativeRevision = planRevisionAuthority.find(
                        task.taskId(), binding.planRevisionId())
                .orElseThrow(() -> invalid(
                        "CHAIN_VALIDATION_BUNDLE_PLAN_REVISION_NOT_FOUND"));
        if (!task.taskId().equals(binding.taskId())
                || !authoritativeRevision.equals(revision)
                || !binding.taskFrameId().equals(
                revision.taskFrameId().value())
                || !binding.planRevisionId().equals(revision.id().value())
                || binding.planRevisionNumber() != revision.number()
                || revision.steps().isEmpty()
                || !revision.steps().get(revision.steps().size() - 1)
                .id().value().equals(command.finalStepId())) {
            throw invalid("CHAIN_VALIDATION_BUNDLE_BOUNDARY_INVALID");
        }
        var plan = stepAuthority.findPlan(
                        task.taskId(), revision.id().value())
                .orElseThrow(() -> invalid(
                        "CHAIN_VALIDATION_BUNDLE_PLAN_NOT_FOUND"));
        if (!plan.taskId().equals(task.taskId())
                || !plan.taskFrameId().equals(binding.taskFrameId())
                || !plan.planId().equals(binding.planId())
                || !plan.planRevisionId().equals(binding.planRevisionId())
                || !plan.targetInstructionVersionId().equals(
                binding.instructionId())
                || !sameSteps(revision.steps(), plan.steps())) {
            throw invalid("CHAIN_VALIDATION_BUNDLE_PLAN_AUTHORITY_INVALID");
        }
        return new Boundary(task.taskId(), binding.instructionId(),
                binding.taskFrameId(), binding.planId(),
                binding.planRevisionId(), binding.planRevisionNumber());
    }

    private ChainValidationBundleRuntime.FormalSource source(
            Boundary boundary, PlanStep step) {
        List<ChainStepAuthorityPort.StepEvent> currentEvents = stepAuthority
                .findStepEvents(boundary.taskId(), boundary.planRevisionId())
                .stream().filter(value -> value.command().taskId().equals(
                                boundary.taskId())
                        && value.command().planRevisionId().equals(
                        boundary.planRevisionId())
                        && value.command().stepId().equals(step.id().value()))
                .toList();
        ChainStepAuthorityPort.StepEvent completed = one(
                currentEvents,
                value -> value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.COMPLETED,
                "CHAIN_VALIDATION_BUNDLE_STEP_COMPLETION_NOT_EXACT");
        ChainStepAuthorityPort.StepEvent activated = one(
                currentEvents,
                value -> value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.ACTIVATED
                        && value.command().activationEventId().equals(
                        completed.command().activationEventId()),
                "CHAIN_VALIDATION_BUNDLE_STEP_ACTIVATION_NOT_EXACT");
        if (!activated.command().eventId().equals(
                completed.command().activationEventId())) {
            throw invalid("CHAIN_VALIDATION_BUNDLE_STEP_ACTIVATION_INVALID");
        }
        if (currentEvents.stream().anyMatch(value ->
                value.command().activationEventId().equals(
                        completed.command().activationEventId())
                        && value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind
                        .SUPERSEDED_BY_REPLAN)) {
            throw invalid("CHAIN_VALIDATION_BUNDLE_STEP_SUPERSEDED");
        }

        ChainPersistenceRecords.CandidateStepResultRecord candidate = one(
                workflows.findCandidateStepResults(boundary.taskId()),
                value -> matches(value, boundary, step.id().value(),
                        completed.command().activationEventId()),
                "CHAIN_VALIDATION_BUNDLE_STEP_RESULT_NOT_EXACT");
        ChainPersistenceRecords.AcceptedResultRecord accepted = one(
                workflows.findAcceptedResults(boundary.taskId()),
                value -> value.taskId().equals(boundary.taskId())
                        && value.candidateResultId().equals(
                        candidate.candidateResultId()),
                "CHAIN_VALIDATION_BUNDLE_ACCEPTED_RESULT_NOT_EXACT");
        ChainPersistenceRecords.ReviewDecisionRecord review = one(
                workflows.findReviewDecisions(boundary.taskId()),
                value -> value.taskId().equals(boundary.taskId())
                        && value.reviewDecisionId().equals(
                        accepted.reviewDecisionId()),
                "CHAIN_VALIDATION_BUNDLE_REVIEW_NOT_EXACT");
        verifyAcceptance(candidate, accepted, review, completed);

        if (candidate.validationId() == null) {
            throw invalid(
                    "CHAIN_VALIDATION_BUNDLE_VALIDATION_SET_MISSING");
        }
        var set = validations.findValidation(candidate.validationId())
                .orElseThrow(() -> invalid(
                        "CHAIN_VALIDATION_BUNDLE_VALIDATION_SET_MISSING"));
        var candidateItems = validations.findCandidateItems(
                set.validationId());
        var actionItems = validations.findActionReceiptItems(
                set.validationId());
        var event = one(authorityEvents(boundary.taskId()),
                value -> value.eventId().equals(set.eventId()),
                "CHAIN_VALIDATION_BUNDLE_VALIDATION_EVENT_NOT_EXACT");
        var workspace = workspace(boundary.taskId(), candidateItems);
        return new ChainValidationBundleRuntime.FormalSource(
                set, event, candidateItems, actionItems, candidate,
                workspace, receiptRefs(candidate.receiptRefs()));
    }

    private void verifyAcceptance(
            ChainPersistenceRecords.CandidateStepResultRecord candidate,
            ChainPersistenceRecords.AcceptedResultRecord accepted,
            ChainPersistenceRecords.ReviewDecisionRecord review,
            ChainStepAuthorityPort.StepEvent completed) {
        boolean accepting = review.decisionKind()
                == ChainProposalKind.REFLECTOR_ACCEPT_STEP
                || review.decisionKind()
                == ChainProposalKind
                .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE;
        ChainTransitionType expectedTransition = review.decisionKind()
                == ChainProposalKind.REFLECTOR_ACCEPT_STEP
                ? ChainTransitionType.ACCEPT_STEP
                : ChainTransitionType.FINAL_STEP_READINESS;
        var transition = workflows.findTransition(accepted.transitionId())
                .orElseThrow(() -> invalid(
                        "CHAIN_VALIDATION_BUNDLE_TRANSITION_MISSING"));
        String expectedAcceptedIdentity = sha256(
                candidate.candidateResultId() + "\0"
                        + review.reviewDecisionId() + "\0"
                        + candidate.contentId());
        verifyProposalBinding(candidate.taskId(), candidate.proposalId(),
                ChainProposalKind.EXECUTOR_STEP_RESULT,
                "CANDIDATE_STEP_RESULT", candidate.candidateResultId());
        verifyProposalBinding(review.taskId(), review.proposalId(),
                review.decisionKind(), "REVIEW_DECISION",
                review.reviewDecisionId());
        if (!sha256(review.factRefs().json()).equals(
                review.factRefs().sha256())
                || !accepted.acceptedIdentitySha256().equals(
                expectedAcceptedIdentity)
                || !accepted.taskId().equals(candidate.taskId())
                || !accepted.contentId().equals(candidate.contentId())
                || !"CANDIDATE_STEP_RESULT".equals(
                review.reviewObjectType())
                || !review.reviewObjectId().equals(
                candidate.candidateResultId())
                || !accepting
                || !completed.command().transitionId().equals(
                accepted.transitionId())
                || !completed.command().sourceDecisionId().equals(
                review.reviewDecisionId())
                || !transition.taskId().equals(candidate.taskId())
                || transition.transitionType() != expectedTransition
                || !transition.sourceDecisionId().equals(
                review.reviewDecisionId())
                || !transition.targetIdentityDigest().equals(
                accepted.acceptedIdentitySha256())) {
            throw invalid("CHAIN_VALIDATION_BUNDLE_ACCEPTANCE_INVALID");
        }
        List<ChainPersistenceRecords.TransitionStageRecord> stages = workflows
                .findTransitionStages(transition.transitionId()).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.TransitionStageRecord
                                ::stageOrdinal))
                .toList();
        List<ChainTransitionStage> prefix = new ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            var stage = stages.get(index);
            if (!stage.taskId().equals(candidate.taskId())
                    || !stage.transitionId().equals(
                    transition.transitionId())
                    || stage.stageOrdinal() != index) {
                throw invalid(
                        "CHAIN_VALIDATION_BUNDLE_TRANSITION_STAGES_INVALID");
            }
            try {
                stage.validateNextFor(transition.transitionType(), prefix);
            } catch (IllegalArgumentException malformed) {
                throw invalid(
                        "CHAIN_VALIDATION_BUNDLE_TRANSITION_STAGES_INVALID");
            }
            prefix.add(stage.stageCode());
        }
        verifyBuildableStageSequence(transition.transitionType(), prefix);
        ChainTransitionStage acceptedStage = transition.transitionType()
                == ChainTransitionType.ACCEPT_STEP
                ? ChainTransitionStage.ACCEPTED_RESULT_COMMITTED
                : ChainTransitionStage
                .ACCEPTED_RESULT_COMMITTED_OR_VERIFIED;
        ChainTransitionStage completedStage = transition.transitionType()
                == ChainTransitionType.ACCEPT_STEP
                ? ChainTransitionStage.STEP_COMPLETED
                : ChainTransitionStage.STEP_COMPLETED_OR_VERIFIED;
        if (transition.transitionType()
                == ChainTransitionType.FINAL_STEP_READINESS
                && (!authorityFree(stage(stages, ChainTransitionStage.OPEN))
                || !authorityFree(stage(stages, ChainTransitionStage
                .APPLICABILITY_COMMITTED_OR_EMPTY)))) {
            throw invalid(
                    "CHAIN_VALIDATION_BUNDLE_TRANSITION_STAGES_INVALID");
        }
        boolean boundAccepted = successor(stage(stages, acceptedStage),
                "ACCEPTED_RESULT", accepted.acceptedResultId());
        boolean boundCompleted = successor(stage(stages, completedStage),
                "STEP_EVENT", completed.command().eventId());
        if (!boundAccepted || !boundCompleted) {
            throw invalid(
                    "CHAIN_VALIDATION_BUNDLE_TRANSITION_STAGES_INVALID");
        }
    }

    private void verifyProposalBinding(
            String taskId, String proposalId, ChainProposalKind expectedKind,
            String authorityType, String authorityRef) {
        var proposal = models.findProposal(proposalId)
                .filter(value -> value.taskId().equals(taskId)
                        && value.proposalKind() == expectedKind)
                .orElseThrow(() -> invalid(
                        "CHAIN_VALIDATION_BUNDLE_PROPOSAL_AUTHORITY_INVALID"));
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = models
                .findProposalStateEvents(proposal.proposalId()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence))
                .toList();
        List<ChainProposalState> prefix = new ArrayList<>();
        for (var state : states) {
            if (!state.taskId().equals(taskId)
                    || !state.proposalId().equals(proposal.proposalId())) {
                throw invalid(
                        "CHAIN_VALIDATION_BUNDLE_PROPOSAL_AUTHORITY_INVALID");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalidState) {
                throw invalid(
                        "CHAIN_VALIDATION_BUNDLE_PROPOSAL_AUTHORITY_INVALID");
            }
            prefix.add(state.stateKind());
        }
        if (states.size() != 2
                || states.get(0).stateKind() != ChainProposalState.ACCEPTED
                || states.get(1).stateKind()
                != ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                || !authorityType.equals(
                states.get(1).officialAuthorityType())
                || !authorityRef.equals(
                states.get(1).officialAuthorityRef())) {
            throw invalid(
                    "CHAIN_VALIDATION_BUNDLE_PROPOSAL_AUTHORITY_INVALID");
        }
    }

    private static void verifyBuildableStageSequence(
            ChainTransitionType transitionType,
            List<ChainTransitionStage> stages) {
        if (transitionType == ChainTransitionType.ACCEPT_STEP) {
            if (!transitionType.isCompleteSequence(stages)) {
                throw invalid(
                        "CHAIN_VALIDATION_BUNDLE_TRANSITION_STAGES_INVALID");
            }
            return;
        }
        List<ChainTransitionStage> buildPoint = List.of(
                ChainTransitionStage.OPEN,
                ChainTransitionStage.ACCEPTED_RESULT_COMMITTED_OR_VERIFIED,
                ChainTransitionStage.APPLICABILITY_COMMITTED_OR_EMPTY,
                ChainTransitionStage.STEP_COMPLETED_OR_VERIFIED);
        if (transitionType != ChainTransitionType.FINAL_STEP_READINESS
                || !stages.equals(buildPoint)) {
            throw invalid(
                    "CHAIN_VALIDATION_BUNDLE_TRANSITION_STAGES_INVALID");
        }
    }

    private static ChainPersistenceRecords.TransitionStageRecord stage(
            List<ChainPersistenceRecords.TransitionStageRecord> stages,
            ChainTransitionStage kind) {
        return one(stages, value -> value.stageCode() == kind,
                "CHAIN_VALIDATION_BUNDLE_TRANSITION_STAGES_INVALID");
    }

    private static boolean successor(
            ChainPersistenceRecords.TransitionStageRecord stage,
            String type, String ref) {
        return stage.predecessorAuthorityType() == null
                && type.equals(stage.successorAuthorityType())
                && ref.equals(stage.successorAuthorityRef());
    }

    private static boolean authorityFree(
            ChainPersistenceRecords.TransitionStageRecord stage) {
        return stage.predecessorAuthorityType() == null
                && stage.predecessorAuthorityRef() == null
                && stage.successorAuthorityType() == null
                && stage.successorAuthorityRef() == null;
    }

    private ChainPersistenceRecords.WorkspaceCandidateRecord workspace(
            String taskId,
            List<ChainPersistenceRecords.CandidateValidationItemRecord> items) {
        if (items.isEmpty()) return null;
        Set<String> ids = items.stream()
                .map(ChainPersistenceRecords.CandidateValidationItemRecord
                        ::workspaceCandidateId)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.size() != 1) {
            throw invalid("CHAIN_VALIDATION_BUNDLE_WORKSPACE_NOT_EXACT");
        }
        String id = ids.iterator().next();
        return one(workflows.findWorkspaceCandidates(taskId),
                value -> value.taskId().equals(taskId)
                        && value.workspaceCandidateId().equals(id),
                "CHAIN_VALIDATION_BUNDLE_WORKSPACE_NOT_EXACT");
    }

    private List<ChainPersistenceRecords.AuthorityEventRecord>
            authorityEvents(String taskId) {
        long cut = foundations.highestAuthorityEventSequence(taskId);
        return foundations.findAuthorityEvents(taskId, cut);
    }

    private static List<String> receiptRefs(
            ChainPersistenceRecords.CanonicalJson value) {
        try {
            if (!sha256(value.json()).equals(value.sha256())) {
                throw invalid("CHAIN_VALIDATION_BUNDLE_RECEIPT_REFS_INVALID");
            }
            JsonNode root = JSON.readTree(value.json());
            if (!root.isArray()) {
                throw invalid("CHAIN_VALIDATION_BUNDLE_RECEIPT_REFS_INVALID");
            }
            List<String> result = new ArrayList<>();
            for (JsonNode item : root) {
                if (!item.isTextual() || item.textValue().isBlank()) {
                    throw invalid(
                            "CHAIN_VALIDATION_BUNDLE_RECEIPT_REFS_INVALID");
                }
                result.add(item.textValue());
            }
            return List.copyOf(result);
        } catch (ProductChainValidationBundleException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw invalid("CHAIN_VALIDATION_BUNDLE_RECEIPT_REFS_INVALID");
        }
    }

    private static boolean matches(
            ChainPersistenceRecords.CandidateStepResultRecord value,
            Boundary boundary, String stepId, String activationId) {
        return value.taskId().equals(boundary.taskId())
                && value.instructionId().equals(boundary.instructionId())
                && value.taskFrameId().equals(boundary.taskFrameId())
                && value.planId().equals(boundary.planId())
                && value.planRevisionId().equals(boundary.planRevisionId())
                && value.planRevisionNumber()
                == boundary.planRevisionNumber()
                && value.stepId().equals(stepId)
                && value.activationEventId().equals(activationId);
    }

    private static boolean sameSteps(
            List<PlanStep> expected,
            List<ChainStepAuthorityPort.StepDefinition> actual) {
        if (expected.size() != actual.size()) return false;
        Map<String, ChainStepAuthorityPort.StepDefinition> byId =
                new HashMap<>();
        for (var step : actual) {
            if (byId.put(step.stepId(), step) != null) return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            PlanStep step = expected.get(index);
            var authority = byId.get(step.id().value());
            Set<String> dependencies = step.dependencies().stream()
                    .map(value -> value.value())
                    .collect(java.util.stream.Collectors.toSet());
            if (authority == null || authority.stableOrder() != index + 1
                    || !authority.prerequisiteStepIds().equals(dependencies)) {
                return false;
            }
        }
        return true;
    }

    private static <T> T one(
            List<T> values, Predicate<T> predicate, String code) {
        List<T> matches = values.stream().filter(predicate).toList();
        if (matches.size() != 1) throw invalid(code);
        return matches.get(0);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ProductChainValidationBundleException invalid(String code) {
        return new ProductChainValidationBundleException(code);
    }

    public record BuildCommand(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.PlanBindingRecord planBinding,
            PlanRevision planRevision,
            String finalStepId,
            String idempotencyKey,
            Instant createdAt) {
        public BuildCommand {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(planBinding, "planBinding");
            Objects.requireNonNull(planRevision, "planRevision");
            finalStepId = required(finalStepId, "finalStepId");
            idempotencyKey = required(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public static final class ProductChainValidationBundleException
            extends IllegalStateException {
        ProductChainValidationBundleException(String code) {
            super(code);
        }
    }

    private record Boundary(
            String taskId, String instructionId, String taskFrameId,
            String planId, String planRevisionId,
            long planRevisionNumber) {
    }

    @FunctionalInterface
    interface RequirementsAuthority {
        TaskRequirements load(String planId, String taskFrameId);
    }

    @FunctionalInterface
    interface PlanRevisionAuthority {
        Optional<PlanRevision> find(String taskId, String planRevisionId);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
