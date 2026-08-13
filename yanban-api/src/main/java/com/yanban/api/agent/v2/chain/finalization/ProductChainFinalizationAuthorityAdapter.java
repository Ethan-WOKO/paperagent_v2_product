package com.yanban.api.agent.v2.chain.finalization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.api.agent.v2.chain.recovery.ProductChainReadinessAuthority;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.chain.ChainApplicability;
import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.ReflectorPayload;
import io.paperagent.v2.chain.finalization.ChainFinalizationAuthorityPort;
import io.paperagent.v2.chain.instruction.ChainInstructionStateReader;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.RequirementDeclarationMode;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Product projection of independent current authorities for finalization. */
@Component
public final class ProductChainFinalizationAuthorityAdapter
        implements ChainFinalizationAuthorityPort {
    private final ChainFoundationRepository foundations;
    private final ChainWorkflowRepository workflow;
    private final ChainFinalizationRepository finalization;
    private final ChainModelRepository models;
    private final ProductPlanBootstrapRepositoryAdapter bootstraps;
    private final ChainInstructionStateReader instructions;
    private final CandidateChangeArtifactService candidates;
    private final ProductChainReadinessAuthority readinessAuthority;
    private final ProjectService projects;
    private final ObjectMapper json;

    public ProductChainFinalizationAuthorityAdapter(
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization,
            ChainModelRepository models,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            CandidateChangeArtifactService candidates,
            ProductChainReadinessAuthority readinessAuthority,
            ProjectService projects,
            ObjectMapper json) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(
                finalization, "finalization");
        this.models = Objects.requireNonNull(models, "models");
        this.bootstraps = Objects.requireNonNull(bootstraps, "bootstraps");
        this.instructions = new ChainInstructionStateReader(
                foundations, workflow, finalization);
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.readinessAuthority = Objects.requireNonNull(
                readinessAuthority, "readinessAuthority");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Inspection inspect(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
        Objects.requireNonNull(readiness, "readiness");
        readinessAuthority.requireExact(readiness);
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(readiness.taskId()).orElseThrow(() ->
                        new IllegalStateException(
                                "finalization task authority is missing"));
        var instructionState = instructions.read(readiness.taskId());
        var instruction = instructionState.currentInstruction();
        if (!instruction.instructionId().equals(readiness.instructionId())) {
            throw new IllegalStateException(
                    "finalization instruction authority drift");
        }
        ChainPersistenceRecords.PlanBindingRecord plan = exactPlanBinding(
                readiness);
        FinalAssessment assessment = finalAssessment(readiness);
        List<String> frozenAccepted = frozenAccepted(readiness);
        boolean contractSatisfied = finalStepSatisfied(
                readiness, plan, frozenAccepted)
                && assessment.requirementsSatisfied();
        AcceptedProjection accepted = accepted(readiness, frozenAccepted);

        Candidate candidate = null;
        Validation validation = null;
        String currentVersion = readiness.projectVersion();
        if (task.projectId() != null) {
            try {
                currentVersion = projects.manifest(
                        task.userId(), task.projectId()).version();
            } catch (RuntimeException unavailable) {
                if (notFound(unavailable)) {
                    currentVersion = "MISSING_PROJECT";
                } else {
                if (!temporarilyUnavailable(unavailable)) throw unavailable;
                return new TemporarilyUnavailable(
                        "project-manifest:" + task.projectId());
                }
            }
        }
        if (!ChainIdentity.NONE.equals(readiness.candidateKey())) {
            try {
                CandidateArtifactResponse value = candidates.getCurrent(
                        task.userId(), readiness.artifactId());
                if (Objects.equals(value.projectId(), task.projectId())) {
                    candidate = new Candidate(readiness.candidateKey(),
                            readiness.workspaceId(), value.artifactId(),
                            value.fingerprint().sha256(),
                            value.projectVersion().value());
                }
            } catch (RuntimeException unavailable) {
                if (notFound(unavailable)) {
                    candidate = null;
                } else {
                if (!temporarilyUnavailable(unavailable)) throw unavailable;
                return new TemporarilyUnavailable(
                        "candidate:" + readiness.candidateKey());
                }
            }
        }
        if (!ChainIdentity.NONE.equals(readiness.validationId())) {
            validation = new Validation(readiness.validationId(),
                    candidate == null ? null : candidate.artifactId(),
                    candidate == null ? null : candidate.fingerprint(),
                    readiness.projectVersion(),
                    readiness.validationRequestDigest(),
                    readiness.validationReceiptDigest(),
                    Validation.Status.SUCCESSFUL);
        }
        return new Available(readiness.taskId(), instruction.instructionId(),
                plan.taskFrameId(), plan.planId(),
                readiness.finalPlanRevisionId(),
                readiness.finalPlanRevisionNumber(), readiness.finalStepId(),
                readiness.reviewDecisionId(),
                accepted.sha256(), accepted.cut(), contractSatisfied,
                readiness.coverage().sha256(), candidate,
                assessment.validationRequired(), validation,
                assessment.publishRequirement(),
                readiness.publishRequirementDigest(), currentVersion);
    }

    private ChainPersistenceRecords.PlanBindingRecord exactPlanBinding(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
        List<ChainPersistenceRecords.PlanBindingRecord> matches = workflow
                .findPlanBindings(readiness.taskId()).stream()
                .filter(item -> item.taskId().equals(readiness.taskId()))
                .filter(item -> item.instructionId().equals(
                        readiness.instructionId()))
                .filter(item -> item.taskFrameId().equals(
                        readiness.taskFrameId()))
                .filter(item -> item.planId().equals(
                        readiness.finalPlanId()))
                .filter(item -> item.planRevisionId().equals(
                        readiness.finalPlanRevisionId()))
                .filter(item -> item.planRevisionNumber()
                        == readiness.finalPlanRevisionNumber())
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "frozen Plan binding is missing or ambiguous");
        }
        return matches.get(0);
    }

    @Override
    public Optional<FailureHandoff> findFailureHandoff(
            FailureHandoffQuery query) {
        Objects.requireNonNull(query, "query");
        ChainPersistenceRecords.FinalizationCheckRecord check = finalization
                .findReadiness(query.taskId()).stream()
                .flatMap(item -> finalization.findFinalizationChecks(
                        item.readinessId()).stream())
                .filter(item -> item.taskId().equals(query.taskId()))
                .filter(item -> item.transitionId().equals(
                        query.finalizationTransitionId()))
                .filter(item -> item.finalizationCheckId().equals(
                        query.finalizationCheckId()))
                .filter(item -> item.resultStatus()
                        == ChainFinalization.Outcome.FAILED)
                .filter(item -> item.errorCode() != null)
                .filter(item -> item.failureDisposition()
                        == ChainFinalization.FailureHandling
                        .REFLECTOR_REQUIRED)
                .findFirst().orElse(null);
        if (check == null) return Optional.empty();
        Optional<FailureHandoff> review = workflow.findReviewDecisions(
                        query.taskId()).stream()
                .filter(item -> item.taskId().equals(query.taskId()))
                .filter(item -> item.reviewObjectType().equals(
                        "FINALIZATION_CHECK"))
                .filter(item -> item.reviewObjectId().equals(
                        query.finalizationCheckId()))
                .filter(item -> item.decisionKind()
                        == ChainProposalKind.REFLECTOR_REPLAN_REQUIRED
                        || item.decisionKind()
                        == ChainProposalKind.REFLECTOR_NEED_PERMISSION
                        || item.decisionKind()
                        == ChainProposalKind.REFLECTOR_TASK_FAILED)
                .map(item -> new FailureHandoff(
                        "REVIEW_DECISION", item.reviewDecisionId()))
                .findFirst();
        if (review.isPresent()) return review;
        ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                finalization.findReadinessById(check.readinessId())
                        .filter(item -> item.taskId().equals(query.taskId()))
                        .orElse(null);
        if (readiness == null) return Optional.empty();
        String sourceCommandId = foundations.findInstruction(
                        readiness.instructionId())
                .map(ChainPersistenceRecords.InstructionRecord::commandId)
                .orElse(null);
        if (sourceCommandId == null) return Optional.empty();
        return finalization.findTaskOutcome(query.taskId())
                .filter(item -> item.outcomeType()
                        == ChainTaskOutcomeStatus.FAILED)
                .filter(item -> item.sourceDecisionId().equals(
                        query.finalizationCheckId()))
                .filter(item -> item.sourceCommandId().equals(sourceCommandId))
                .filter(item -> "FINALIZATION".equals(
                        item.failureCategory()))
                .filter(item -> check.errorCode().name().equals(
                        item.failureCode()))
                .map(item -> new FailureHandoff(
                        "TASK_OUTCOME", item.outcomeId()));
    }

    private boolean finalStepSatisfied(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.PlanBindingRecord plan,
            List<String> frozenAccepted) {
        ChainPersistenceRecords.ReviewDecisionRecord readinessReview = workflow
                .findReviewDecisions(readiness.taskId()).stream()
                .filter(item -> item.taskId().equals(readiness.taskId()))
                .filter(item -> item.reviewDecisionId().equals(
                        readiness.reviewDecisionId()))
                .findFirst().orElse(null);
        if (readinessReview == null
                || (readinessReview.decisionKind()
                != ChainProposalKind
                .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE
                && readinessReview.decisionKind()
                != ChainProposalKind.REFLECTOR_READY_TO_FINALIZE)) {
            return false;
        }
        var transition = workflow.findTransition(readiness.transitionId())
                .orElse(null);
        if (transition == null
                || !transition.taskId().equals(readiness.taskId())
                || transition.transitionType()
                != ChainTransitionType.FINAL_STEP_READINESS
                || !transition.sourceDecisionId().equals(
                readiness.reviewDecisionId())) {
            return false;
        }
        List<ChainPersistenceRecords.TransitionStageRecord> stages = workflow
                .findTransitionStages(readiness.transitionId()).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.TransitionStageRecord
                                ::stageOrdinal))
                .toList();
        if (stages.size() != 6) return false;
        String acceptedResultId = eitherAuthorityRef(
                stages.get(1), "ACCEPTED_RESULT");
        List<ChainPersistenceRecords.CandidateStepResultRecord> results =
                workflow.findCandidateStepResults(readiness.taskId()).stream()
                        .filter(item -> item.taskFrameId().equals(
                                plan.taskFrameId()))
                        .filter(item -> item.planId().equals(plan.planId()))
                        .filter(item -> item.planRevisionId().equals(
                                plan.planRevisionId()))
                        .filter(item -> item.planRevisionNumber()
                                == plan.planRevisionNumber())
                        .filter(item -> item.instructionId().equals(
                                plan.instructionId()))
                        .filter(item -> item.stepId().equals(
                                readiness.finalStepId()))
                        .toList();
        List<ChainPersistenceRecords.AcceptedResultRecord> accepted =
                workflow.findAcceptedResults(readiness.taskId()).stream()
                .filter(item -> item.acceptedResultId().equals(
                        acceptedResultId))
                .filter(item -> results.stream().anyMatch(result ->
                        item.candidateResultId().equals(
                                result.candidateResultId())
                                && item.contentId().equals(
                                result.contentId())))
                .filter(item -> frozenAccepted.contains(
                        item.acceptedResultId()))
                .toList();
        if (accepted.size() != 1) return false;
        var exact = accepted.get(0);
        if (!transition.targetIdentityDigest().equals(
                exact.acceptedIdentitySha256())) {
            return false;
        }
        List<ChainPersistenceRecords.CandidateStepResultRecord>
                exactCandidates = results.stream()
                .filter(item -> item.candidateResultId().equals(
                        exact.candidateResultId()))
                .filter(item -> item.contentId().equals(exact.contentId()))
                .toList();
        if (exactCandidates.size() != 1
                || !exactReadinessStageSequence(
                transition, readiness, plan, stages, exact,
                exactCandidates.get(0))) {
            return false;
        }
        if (readinessReview.decisionKind()
                == ChainProposalKind
                .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE) {
            return exact.reviewDecisionId().equals(
                    readiness.reviewDecisionId())
                    && exact.transitionId().equals(readiness.transitionId());
        }
        if (!"ACCEPTED_RESULT".equals(
                stages.get(1).predecessorAuthorityType())
                || stages.get(1).successorAuthorityType() != null) {
            return false;
        }
        ChainPersistenceRecords.ReviewDecisionRecord acceptingReview = workflow
                .findReviewDecisions(readiness.taskId()).stream()
                .filter(item -> item.reviewDecisionId().equals(
                        exact.reviewDecisionId()))
                .filter(item -> item.taskId().equals(readiness.taskId()))
                .filter(item -> item.decisionKind()
                        == ChainProposalKind.REFLECTOR_ACCEPT_STEP)
                .filter(item -> "CANDIDATE_STEP_RESULT".equals(
                        item.reviewObjectType()))
                .filter(item -> item.reviewObjectId().equals(
                        exact.candidateResultId()))
                .findFirst().orElse(null);
        ChainPersistenceRecords.TransitionRecord acceptingTransition = workflow
                .findTransition(exact.transitionId()).orElse(null);
        return acceptingReview != null
                && acceptingTransition != null
                && acceptingTransition.taskId().equals(readiness.taskId())
                && acceptingTransition.transitionType()
                == ChainTransitionType.ACCEPT_STEP
                && acceptingTransition.sourceDecisionId().equals(
                acceptingReview.reviewDecisionId())
                && acceptingTransition.targetIdentityDigest().equals(
                exact.acceptedIdentitySha256());
    }

    private boolean exactReadinessStageSequence(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.PlanBindingRecord plan,
            List<ChainPersistenceRecords.TransitionStageRecord> stages,
            ChainPersistenceRecords.AcceptedResultRecord accepted,
            ChainPersistenceRecords.CandidateStepResultRecord candidate) {
        List<ChainTransitionStage> prefix = new ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            var stage = stages.get(index);
            if (!stage.transitionId().equals(transition.transitionId())
                    || !stage.taskId().equals(transition.taskId())
                    || stage.stageOrdinal() != index) {
                return false;
            }
            try {
                stage.validateNextFor(transition.transitionType(), prefix);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
            prefix.add(stage.stageCode());
        }
        if (!transition.transitionType().isCompleteSequence(prefix)
                || hasAuthority(stages.get(0))
                || hasAuthority(stages.get(5))
                || !accepted.acceptedResultId().equals(eitherAuthorityRef(
                stages.get(1), "ACCEPTED_RESULT"))) {
            return false;
        }
        String expectedStepEventId = "step.completed." + sha256(
                transition.taskId() + "\0" + plan.planRevisionId()
                        + "\0" + readiness.finalStepId() + "\0"
                        + candidate.activationEventId() + "\0"
                        + transition.transitionId());
        if (!expectedStepEventId.equals(
                eitherAuthorityRef(stages.get(3), "STEP_EVENT"))) {
            return false;
        }
        var applicability = stages.get(2);
        if (applicability.predecessorAuthorityType() != null) return false;
        List<ChainPersistenceRecords.ResultApplicabilityRecord> sourceSet =
                workflow.findApplicabilityDecisions(transition.taskId()).stream()
                        .filter(item -> item.taskId().equals(
                                transition.taskId()))
                        .filter(item -> transition.transitionId().equals(
                                item.sourceDecisionId()))
                        .toList();
        if (applicability.successorAuthorityType() == null) {
            if (!sourceSet.isEmpty()) return false;
        } else {
            Map<String, ChainPersistenceRecords.AcceptedResultRecord>
                    acceptedById = new HashMap<>();
            for (var item : workflow.findAcceptedResults(
                    transition.taskId())) {
                if (acceptedById.put(item.acceptedResultId(), item) != null) {
                    return false;
                }
            }
            Set<String> sourceAcceptedIds = sourceSet.stream()
                    .map(ChainPersistenceRecords.ResultApplicabilityRecord
                            ::acceptedResultId)
                    .collect(java.util.stream.Collectors.toSet());
            boolean currentAcceptedRemainsApplicable = sourceSet.stream()
                    .anyMatch(item -> item.acceptedResultId().equals(
                            accepted.acceptedResultId())
                            && item.conclusion()
                            == ChainApplicability.Outcome.APPLICABLE);
            boolean completeSourceSet = !sourceSet.isEmpty()
                    && sourceAcceptedIds.size() == sourceSet.size()
                    && currentAcceptedRemainsApplicable
                    && sourceSet.stream().allMatch(item ->
                    item.sourceType()
                            == ChainApplicability.SourceType.ACCEPT_STEP
                            && acceptedById.containsKey(
                            item.acceptedResultId())
                            && item.targetTaskFrameId().equals(
                            plan.taskFrameId())
                            && item.targetPlanId().equals(plan.planId())
                            && item.targetPlanRevisionId().equals(
                            plan.planRevisionId())
                            && item.targetCandidateKey().equals(
                            readiness.candidateKey())
                            && item.targetInstructionVersionId().equals(
                            plan.instructionId()));
            boolean stageRefInSourceSet = sourceSet.stream().anyMatch(item ->
                    item.applicabilityId().equals(
                            applicability.successorAuthorityRef()));
            if (!"RESULT_APPLICABILITY".equals(
                    applicability.successorAuthorityType())
                    || !completeSourceSet || !stageRefInSourceSet) {
                return false;
            }
        }
        var committed = stages.get(4);
        return committed.predecessorAuthorityType() == null
                && "FINALIZATION_READINESS".equals(
                committed.successorAuthorityType())
                && readiness.readinessId().equals(
                committed.successorAuthorityRef());
    }

    private static String eitherAuthorityRef(
            ChainPersistenceRecords.TransitionStageRecord stage,
            String type) {
        boolean predecessor = type.equals(stage.predecessorAuthorityType())
                && stage.successorAuthorityType() == null;
        boolean successor = type.equals(stage.successorAuthorityType())
                && stage.predecessorAuthorityType() == null;
        if (!predecessor && !successor) return null;
        return predecessor
                ? stage.predecessorAuthorityRef()
                : stage.successorAuthorityRef();
    }

    private static boolean hasAuthority(
            ChainPersistenceRecords.TransitionStageRecord stage) {
        return stage.predecessorAuthorityType() != null
                || stage.successorAuthorityType() != null;
    }

    private FinalAssessment finalAssessment(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
        ChainPersistenceRecords.ReviewDecisionRecord review = workflow
                .findReviewDecisions(readiness.taskId()).stream()
                .filter(item -> item.taskId().equals(readiness.taskId()))
                .filter(item -> item.reviewDecisionId().equals(
                        readiness.reviewDecisionId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "readiness ReviewDecision is missing"));
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(review.proposalId())
                .filter(item -> item.taskId().equals(readiness.taskId()))
                .filter(item -> item.proposalKind()
                        == review.decisionKind())
                .orElseThrow(() -> new IllegalStateException(
                        "readiness proposal is missing"));
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = models
                .findProposalStateEvents(proposal.proposalId()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence)).toList();
        require(!states.isEmpty(), "readiness proposal state is missing");
        var latest = states.get(states.size() - 1);
        require(latest.stateKind()
                        == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                        && "REVIEW_DECISION".equals(
                        latest.officialAuthorityType())
                        && review.reviewDecisionId().equals(
                        latest.officialAuthorityRef()),
                "readiness proposal is not bound to its ReviewDecision");
        String envelope = "{\"schemaVersion\":\"1\",\"kind\":\""
                + proposal.proposalKind().wireName() + "\",\"payload\":"
                + proposal.payload().json() + "}";
        var output = new StrictChainProviderOutputParser().parse(
                envelope, ChainRole.REFLECTOR,
                ChainWorkState.FINALIZING, null);
        ProposalFields.FinalizationAssessment value;
        if (output.payload() instanceof ReflectorPayload
                .ReadyToFinalize ready) {
            value = ready.finalization();
        } else if (output.payload() instanceof ReflectorPayload
                .AcceptStepAndReadyToFinalize ready) {
            value = ready.finalization();
        } else {
            throw new IllegalStateException(
                    "ReviewDecision is not READY finalization");
        }
        boolean requirements = value.requirementCoverage().stream()
                .allMatch(item -> item.status()
                        == ProposalFields.RequirementStatus.SATISFIED
                        || item.status()
                        == ProposalFields.RequirementStatus.NOT_APPLICABLE);
        var bootstrap = bootstraps.find(new PlanId(readiness.finalPlanId()))
                .filter(item -> item.taskFrame().id().value().equals(
                        readiness.taskFrameId()))
                .orElseThrow(() -> new IllegalStateException(
                        "readiness frozen TaskFrame is missing"));
        var taskRequirements = bootstrap.taskFrame().requirements();
        require(taskRequirements.declarationMode()
                        == RequirementDeclarationMode.EXPLICIT,
                "readiness TaskFrame requirements are legacy unspecified");
        boolean validationRequired = !taskRequirements
                .validationRequirements().isEmpty();
        boolean validationMatches = validationRequired
                ? value.validationAssessment().status()
                        == ProposalFields.AssessmentStatus.BOUND
                        && readiness.taskFrameId().equals(
                        value.validationAssessment().authorityRef())
                : value.validationAssessment().status()
                        == ProposalFields.AssessmentStatus.NOT_REQUIRED;
        require(validationMatches,
                "Reflector validation assessment changed the frozen TaskFrame requirement");
        ChainPublishRequirement publish = switch (
                taskRequirements.publishRequirement()) {
            case REQUIRED -> ChainPublishRequirement.REQUIRED;
            case NOT_REQUIRED -> ChainPublishRequirement.NOT_REQUIRED;
            case LEGACY_UNSPECIFIED -> throw new IllegalStateException(
                    "readiness publish requirement is legacy unspecified");
        };
        boolean publishMatches = publish == ChainPublishRequirement.REQUIRED
                ? value.publishRequirementAssessment().status()
                        == ProposalFields.AssessmentStatus.BOUND
                        && readiness.taskFrameId().equals(value
                        .publishRequirementAssessment().authorityRef())
                : value.publishRequirementAssessment().status()
                        == ProposalFields.AssessmentStatus.NOT_REQUIRED;
        require(publishMatches,
                "Reflector publish assessment changed the frozen TaskFrame requirement");
        return new FinalAssessment(requirements, validationRequired, publish);
    }

    private List<String> frozenAccepted(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
        List<String> frozen;
        try {
            frozen = json.readValue(readiness.acceptedSet().json(),
                    new TypeReference<List<String>>() { });
        } catch (Exception invalid) {
            throw new IllegalStateException(
                    "readiness accepted set is not canonical IDs", invalid);
        }
        require(new HashSet<>(frozen).size() == frozen.size()
                        && frozen.equals(frozen.stream().sorted().toList()),
                "readiness accepted set is not sorted/unique");
        return List.copyOf(frozen);
    }

    private AcceptedProjection accepted(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            List<String> frozen) {
        Map<String, Long> sequences = new HashMap<>();
        for (var event : foundations.findAuthorityEvents(
                readiness.taskId(), Long.MAX_VALUE)) {
            sequences.put(event.eventId(), event.eventSequence());
        }
        Set<String> formal = workflow.findAcceptedResults(
                        readiness.taskId()).stream()
                .map(ChainPersistenceRecords.AcceptedResultRecord
                        ::acceptedResultId)
                .collect(java.util.stream.Collectors.toSet());
        require(formal.containsAll(frozen),
                "readiness accepted set names a non-formal result");
        // A task that starts with no predecessor applicability facts records
        // an explicit empty applicability barrier (cut 0).  In that case the
        // readiness accepted set is already the authoritative accepted
        // projection; deriving it from applicability rows would incorrectly
        // turn the non-empty accepted set into [] and fail finalization.
        if (readiness.applicabilityCutEventSequence() == 0L) {
            return new AcceptedProjection(readiness.acceptedSet().sha256(), 0L);
        }
        Map<String, ChainPersistenceRecords.ResultApplicabilityRecord>
                current = new LinkedHashMap<>();
        long cut = 0L;
        for (var item : workflow.findApplicabilityDecisions(
                readiness.taskId()).stream()
                .filter(item -> item.targetTaskFrameId().equals(
                        readiness.taskFrameId()))
                .filter(item -> item.targetPlanId().equals(
                        readiness.finalPlanId()))
                .filter(item -> item.targetPlanRevisionId().equals(
                        readiness.finalPlanRevisionId()))
                .filter(item -> item.targetCandidateKey().equals(
                        readiness.candidateKey()))
                .filter(item -> item.targetInstructionVersionId().equals(
                        readiness.instructionId()))
                .sorted(Comparator.comparingLong(item -> sequences.getOrDefault(
                        item.eventId(), Long.MAX_VALUE))).toList()) {
            long sequence = sequences.getOrDefault(item.eventId(), -1L);
            require(sequence > 0,
                    "applicability fact lacks authority sequence");
            cut = Math.max(cut, sequence);
            current.put(item.acceptedResultId(), item);
        }
        require(cut == readiness.applicabilityCutEventSequence(),
                "readiness applicability cut is not the exact target cut");
        List<String> applicable = current.values().stream()
                .filter(item -> item.conclusion()
                        == ChainApplicability.Outcome.APPLICABLE)
                .map(ChainPersistenceRecords.ResultApplicabilityRecord
                        ::acceptedResultId)
                .distinct().sorted().toList();
        String encoded = canonicalArray(applicable);
        return new AcceptedProjection(sha256(encoded), cut);
    }

    private static String canonicalArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) json.append(',');
            json.append('"');
            for (char value : values.get(index).toCharArray()) {
                switch (value) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\b' -> json.append("\\b");
                    case '\f' -> json.append("\\f");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (value < 0x20) {
                            json.append(String.format("\\u%04x", (int) value));
                        } else json.append(value);
                    }
                }
            }
            json.append('"');
        }
        return json.append(']').toString();
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static boolean temporarilyUnavailable(RuntimeException failure) {
        if (failure instanceof TransientDataAccessException) return true;
        if (failure instanceof ResponseStatusException response) {
            HttpStatusCode status = response.getStatusCode();
            return status.is5xxServerError();
        }
        return false;
    }

    private static boolean notFound(RuntimeException failure) {
        return failure instanceof ResponseStatusException response
                && response.getStatusCode().value() == 404;
    }

    private record FinalAssessment(
            boolean requirementsSatisfied,
            boolean validationRequired,
            ChainPublishRequirement publishRequirement) {
    }

    private record AcceptedProjection(String sha256, long cut) {
    }
}
