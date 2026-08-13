package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainApplicability;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainValidationConclusion;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import io.paperagent.v2.chain.validation.ChainValidationBundleIdentity;
import io.paperagent.v2.chain.validation.ChainValidationIdentity;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.RequirementDeclarationMode;
import io.paperagent.v2.contracts.ValidationSubject;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Verifies final-Step acceptance and readiness without conflating its modes. */
final class ProductChainReadinessStageVerifier
        implements ProductChainTransitionStageVerifier {
    private final ProductChainRecoveryAuthorityLookup authorities;
    private final ProductChainStepAcceptanceAuthority acceptance;
    private final ProductChainStepScheduleAuthority schedule;

    ProductChainReadinessStageVerifier(
            ProductChainRecoveryAuthorityLookup authorities) {
        this.authorities = Objects.requireNonNull(authorities, "authorities");
        this.acceptance = new ProductChainStepAcceptanceAuthority(authorities);
        this.schedule = new ProductChainStepScheduleAuthority(authorities);
    }

    @Override
    public ChainCompositeTransitionRuntime.AuthorityVerification verify(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage) {
        return switch (stage.stageCode()) {
            case OPEN, COMPLETE ->
                    ProductChainRecoveryAuthorityLookup.verifiedNone(stage);
            case ACCEPTED_RESULT_COMMITTED_OR_VERIFIED -> {
                String ref = ProductChainRecoveryAuthorityLookup.requireEither(
                        stage, "ACCEPTED_RESULT");
                accepted(transition, stage, ref);
                yield ProductChainRecoveryAuthorityLookup.verified();
            }
            case APPLICABILITY_COMMITTED_OR_EMPTY ->
                    acceptance.applicability(
                            transition, stage, acceptedFromStage(transition),
                            true);
            case STEP_COMPLETED_OR_VERIFIED -> {
                String ref = ProductChainRecoveryAuthorityLookup.requireEither(
                        stage, "STEP_EVENT");
                var graph = acceptedFromStage(transition);
                String ownerTransition = stage.predecessorAuthorityType() == null
                        ? transition.transitionId()
                        : graph.accepted().transitionId();
                acceptance.stepEvent(transition, graph, ref,
                        ChainStepAuthorityPort.StepEventKind.COMPLETED,
                        ownerTransition);
                yield ProductChainRecoveryAuthorityLookup.verified();
            }
            case READINESS_COMMITTED -> verifyReadiness(transition, stage);
            default -> throw ProductChainRecoveryAuthorityLookup.invalid(
                    "unsupported FINAL_STEP_READINESS stage");
        };
    }

    private ProductChainStepAcceptanceAuthority.AcceptedGraph accepted(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage, String ref) {
        if (stage.successorAuthorityType() != null) {
            return acceptance.current(transition, ref,
                    ChainProposalKind
                            .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE);
        }
        return acceptance.priorForReadiness(transition, ref);
    }

    private ProductChainStepAcceptanceAuthority.AcceptedGraph acceptedFromStage(
            ChainPersistenceRecords.TransitionRecord transition) {
        var stage = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findTransitionStages(
                        transition.transitionId()),
                value -> value.stageCode()
                        == ChainTransitionStage
                        .ACCEPTED_RESULT_COMMITTED_OR_VERIFIED,
                "readiness AcceptedResult stage");
        return accepted(transition, stage,
                ProductChainRecoveryAuthorityLookup.requireEither(
                        stage, "ACCEPTED_RESULT"));
    }

    private ChainCompositeTransitionRuntime.AuthorityVerification
            verifyReadiness(
                    ChainPersistenceRecords.TransitionRecord transition,
                    ChainPersistenceRecords.TransitionStageRecord stage) {
        ProductChainRecoveryAuthorityLookup.requireSuccessor(
                stage, Set.of("FINALIZATION_READINESS"));
        var graph = acceptedFromStage(transition);
        var applicabilityStage = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findTransitionStages(
                        transition.transitionId()),
                value -> value.stageCode()
                        == ChainTransitionStage
                        .APPLICABILITY_COMMITTED_OR_EMPTY,
                "readiness applicability stage");
        acceptance.applicability(
                transition, applicabilityStage, graph, true);
        var completionStage = ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findTransitionStages(
                        transition.transitionId()),
                value -> value.stageCode()
                        == ChainTransitionStage.STEP_COMPLETED_OR_VERIFIED,
                "readiness completion stage");
        String completionRef = ProductChainRecoveryAuthorityLookup
                .requireEither(completionStage, "STEP_EVENT");
        String completionTransition =
                completionStage.predecessorAuthorityType() == null
                        ? transition.transitionId()
                        : graph.accepted().transitionId();
        acceptance.stepEvent(transition, graph, completionRef,
                ChainStepAuthorityPort.StepEventKind.COMPLETED,
                completionTransition);
        var readiness = authorities.finalization().findReadinessById(
                        stage.successorAuthorityRef())
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "FinalizationReadiness authority missing"));
        ProductChainRecoveryAuthorityLookup.canonical(
                readiness.acceptedSet(), "readiness accepted set");
        ProductChainRecoveryAuthorityLookup.canonical(
                readiness.coverage(), "readiness coverage");
        var plan = schedule.exactAllTerminal(
                transition.taskId(), graph.candidate().planRevisionId(),
                graph.candidate().stepId());
        var task = authorities.foundations().findTask(transition.taskId())
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "readiness Task missing"));
        ProductChainRecoveryAuthorityLookup.exact(
                readiness.taskId().equals(transition.taskId())
                        && readiness.transitionId().equals(
                        transition.transitionId())
                        && readiness.reviewDecisionId().equals(
                        transition.sourceDecisionId())
                        && readiness.taskFrameId().equals(
                        graph.candidate().taskFrameId())
                        && readiness.finalPlanId().equals(
                        graph.candidate().planId())
                        && readiness.finalPlanRevisionId().equals(
                        graph.candidate().planRevisionId())
                        && readiness.finalPlanRevisionNumber()
                        == graph.candidate().planRevisionNumber()
                        && readiness.finalStepId().equals(
                        graph.candidate().stepId())
                        && readiness.instructionId().equals(
                        graph.candidate().instructionId())
                        && Objects.equals(readiness.artifactId(),
                        graph.candidate().artifactId())
                        && readiness.projectVersion().equals(
                        Objects.requireNonNullElse(
                                task.initialProjectVersion(),
                                ChainIdentity.NONE))
                        && readiness.publishRequirementDigest().equals(
                        ProductChainRecoveryAuthorityLookup.sha256(
                                "publish\0"
                                        + readiness.publishRequirement().name()))
                        && plan.steps().stream().anyMatch(value ->
                        value.stepId().equals(readiness.finalStepId())),
                "FinalizationReadiness identity drift");
        verifyCandidate(readiness, graph.candidate());
        verifyValidationBundle(readiness, plan);
        verifyAcceptedAndApplicability(
                transition, readiness, graph, applicabilityStage);
        return ProductChainRecoveryAuthorityLookup.verified();
    }

    private void verifyAcceptedAndApplicability(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ProductChainStepAcceptanceAuthority.AcceptedGraph graph,
            ChainPersistenceRecords.TransitionStageRecord stage) {
        List<ChainPersistenceRecords.ResultApplicabilityRecord> sourceSet =
                authorities.workflow().findApplicabilityDecisions(
                                transition.taskId()).stream()
                        .filter(value -> value.taskId().equals(
                                transition.taskId())
                                && value.sourceType()
                                == ChainApplicability.SourceType.ACCEPT_STEP
                                && value.sourceDecisionId().equals(
                                transition.transitionId()))
                        .toList();
        Set<String> acceptedIds = sourceSet.isEmpty()
                ? Set.of(graph.accepted().acceptedResultId())
                : sourceSet.stream()
                .filter(value -> value.conclusion()
                        == ChainApplicability.Outcome.APPLICABLE)
                .map(ChainPersistenceRecords.ResultApplicabilityRecord
                        ::acceptedResultId)
                .collect(java.util.stream.Collectors.toSet());
        ProductChainRecoveryAuthorityLookup.exact(
                acceptedIds.contains(graph.accepted().acceptedResultId())
                        && acceptedIds.size() == (sourceSet.isEmpty()
                        ? 1 : sourceSet.stream().filter(value ->
                        value.conclusion()
                                == ChainApplicability.Outcome.APPLICABLE)
                        .count()),
                "readiness accepted source set is incomplete or duplicated");
        Set<String> formalAccepted = authorities.workflow()
                .findAcceptedResults(transition.taskId()).stream()
                .map(ChainPersistenceRecords.AcceptedResultRecord
                        ::acceptedResultId)
                .collect(java.util.stream.Collectors.toSet());
        String expectedJson = ProductChainRecoveryAuthorityLookup
                .canonicalStringArray(acceptedIds);
        ProductChainRecoveryAuthorityLookup.exact(
                formalAccepted.containsAll(acceptedIds)
                        && readiness.acceptedSet().json().equals(expectedJson)
                        && readiness.acceptedSet().sha256().equals(
                        ProductChainRecoveryAuthorityLookup.sha256(
                                expectedJson)),
                "readiness acceptedSet differs from formal applicability");
        long expectedCut = 0;
        if (!sourceSet.isEmpty()) {
            var authorityEvents = authorities.foundations()
                    .findAuthorityEvents(transition.taskId(),
                            authorities.foundations()
                                    .highestAuthorityEventSequence(
                                            transition.taskId()));
            for (var applicability : sourceSet) {
                String identityDigest =
                        ProductChainRecoveryAuthorityLookup.sha256(
                                applicability.acceptedResultId() + "\0"
                                        + applicability.sourceType() + "\0"
                                        + applicability.sourceDecisionId()
                                        + "\0"
                                        + applicability.targetTaskFrameId()
                                        + "\0"
                                        + applicability.targetPlanId() + "\0"
                                        + applicability
                                        .targetPlanRevisionId() + "\0"
                                        + applicability.targetCandidateKey()
                                        + "\0" + applicability
                                        .targetInstructionVersionId());
                var event = ProductChainRecoveryAuthorityLookup.one(
                        authorityEvents,
                        value -> value.eventId().equals(
                                        applicability.eventId())
                                && value.eventType().equals(
                                "RESULT_APPLICABILITY")
                                && Objects.equals(value.transitionId(),
                                transition.transitionId())
                                && value.sourceIdentitySha256().equals(
                                identityDigest),
                        "readiness applicability authority event");
                expectedCut = Math.max(expectedCut, event.eventSequence());
            }
        }
        ProductChainRecoveryAuthorityLookup.exact(
                readiness.applicabilityCutEventSequence() == expectedCut
                        && (stage.successorAuthorityRef() == null
                        ? sourceSet.isEmpty()
                        : sourceSet.stream().anyMatch(value ->
                        value.applicabilityId().equals(
                                stage.successorAuthorityRef()))),
                "readiness applicability cut or stage source set drift");
    }

    private void verifyCandidate(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.CandidateStepResultRecord candidate) {
        if (candidate.artifactId() == null) {
            ProductChainRecoveryAuthorityLookup.exact(
                    ChainIdentity.NONE.equals(readiness.candidateKey())
                            && ChainIdentity.NONE.equals(
                            readiness.workspaceId()),
                    "authority-free readiness carries a Candidate");
            return;
        }
        ProductChainRecoveryAuthorityLookup.one(
                authorities.workflow().findWorkspaceCandidates(
                        readiness.taskId()),
                value -> value.workspaceCandidateId().equals(
                                readiness.candidateKey())
                        && Objects.equals(value.artifactId(),
                        candidate.artifactId())
                        && value.candidateFingerprint().equals(
                                candidate.candidateFingerprint())
                        && value.workspaceId().equals(readiness.workspaceId()),
                "readiness WorkspaceCandidate");
    }

    private void verifyValidationBundle(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainStepAuthorityPort.PlanSnapshot plan) {
        var bootstrap = authorities.bootstraps()
                .find(new PlanId(readiness.finalPlanId()))
                .filter(value -> value.taskFrame().id().value().equals(
                        readiness.taskFrameId()))
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "readiness frozen TaskFrame is missing"));
        ProductChainRecoveryAuthorityLookup.exact(
                bootstrap.taskFrame().requirements().declarationMode()
                        == RequirementDeclarationMode.EXPLICIT,
                "readiness TaskFrame requirements are not explicit");
        boolean required = !bootstrap.taskFrame().requirements()
                .validationRequirements().isEmpty();
        if (!required) {
            ProductChainRecoveryAuthorityLookup.exact(
                    ChainIdentity.NONE.equals(readiness.validationId())
                            && readiness.validationRequestDigest() == null
                            && readiness.validationReceiptDigest() == null,
                    "NOT_REQUIRED readiness carries a ValidationBundle");
            return;
        }
        ProductChainRecoveryAuthorityLookup.exact(
                !ChainIdentity.NONE.equals(readiness.validationId()),
                "required readiness lacks a ValidationBundle");
        var bundle = authorities.validationBundles()
                .findBundle(readiness.validationId())
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "readiness ValidationBundle is missing"));
        ProductChainRecoveryAuthorityLookup.exact(
                bundle.validationBundleId().equals(readiness.validationId())
                        && bundle.taskId().equals(readiness.taskId())
                        && bundle.taskFrameId().equals(readiness.taskFrameId())
                        && bundle.planId().equals(readiness.finalPlanId())
                        && bundle.planRevisionId().equals(
                        readiness.finalPlanRevisionId())
                        && bundle.planRevisionNumber()
                        == readiness.finalPlanRevisionNumber()
                        && bundle.instructionId().equals(
                        readiness.instructionId())
                        && bundle.finalStepId().equals(readiness.finalStepId())
                        && bundle.requestDigest().equals(
                        readiness.validationRequestDigest())
                        && bundle.receiptSetDigest().equals(
                        readiness.validationReceiptDigest())
                        && bundle.conclusion()
                        == ChainValidationConclusion.PASSED,
                "readiness ValidationBundle root identity drift");
        var events = authorities.foundations().findAuthorityEvents(
                readiness.taskId(), authorities.foundations()
                        .highestAuthorityEventSequence(readiness.taskId()));
        var scope = new ChainValidationBundleIdentity.Scope(
                bundle.taskId(), bundle.taskFrameId(), bundle.planId(),
                bundle.planRevisionId(), bundle.planRevisionNumber(),
                bundle.instructionId(), bundle.finalStepId());
        var storedAggregate = new ChainValidationBundleIdentity.Aggregate(
                bundle.requestDigest(), bundle.receiptSetDigest(),
                bundle.conclusionDigest());
        String sourceIdentity = ChainValidationBundleIdentity
                .eventSourceIdentity(bundle.validationBundleId(),
                        storedAggregate);
        ProductChainRecoveryAuthorityLookup.one(events,
                value -> value.eventId().equals(bundle.eventId())
                        && value.taskId().equals(readiness.taskId())
                        && value.eventType().equals("VALIDATION_BUNDLE")
                        && value.transitionId() == null
                        && value.sourceIdentitySha256().equals(sourceIdentity),
                "readiness ValidationBundle authority event");
        var sets = authorities.validationBundles().findBundleSets(
                bundle.validationBundleId());
        var revision = authorities.planRevision(readiness.taskId(),
                        readiness.finalPlanRevisionId())
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "readiness exact PlanRevision is missing"));
        ProductChainRecoveryAuthorityLookup.exact(
                revision.id().value().equals(readiness.finalPlanRevisionId())
                        && revision.taskFrameId().value().equals(
                        readiness.taskFrameId())
                        && revision.number()
                        == readiness.finalPlanRevisionNumber()
                        && revision.steps().get(revision.steps().size() - 1)
                        .id().value().equals(readiness.finalStepId()),
                "readiness PlanRevision root identity drift");
        Map<String, io.paperagent.v2.contracts.PlanStep> requiredSteps =
                new HashMap<>();
        revision.steps().stream()
                .filter(value -> !value.validationRequirementIds().isEmpty())
                .forEach(value -> requiredSteps.put(
                        value.id().value(), value));
        Map<String, io.paperagent.v2.contracts.ValidationRequirement>
                requirements = new HashMap<>();
        bootstrap.taskFrame().requirements().validationRequirements()
                .forEach(value -> requirements.put(
                        value.requirementId(), value));
        Set<String> boundRequirementIds = revision.steps().stream()
                .flatMap(value -> value.validationRequirementIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        ProductChainRecoveryAuthorityLookup.exact(
                requirements.keySet().equals(boundRequirementIds),
                "readiness frozen Validation requirement coverage drift");
        Set<String> planStepIds = plan.steps().stream()
                .map(ChainStepAuthorityPort.StepDefinition::stepId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> memberSteps = new HashSet<>();
        Set<String> memberValidations = new HashSet<>();
        List<ChainValidationBundleIdentity.Member> identities = sets.stream()
                .map(value -> verifyMember(readiness, bundle, events,
                        requiredSteps, requirements, planStepIds, memberSteps,
                        memberValidations, value))
                .toList();
        ProductChainRecoveryAuthorityLookup.exact(
                !sets.isEmpty()
                        && memberSteps.equals(requiredSteps.keySet()),
                "readiness ValidationBundle membership coverage drift");
        var recomputed = ChainValidationBundleIdentity.aggregate(
                scope, identities);
        ProductChainRecoveryAuthorityLookup.exact(
                recomputed.equals(storedAggregate)
                        && ChainValidationBundleIdentity.bundleId(
                        scope, recomputed).equals(bundle.validationBundleId()),
                "readiness ValidationBundle aggregate identity drift");
    }

    private ChainValidationBundleIdentity.Member verifyMember(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.ValidationBundleRecord bundle,
            List<ChainPersistenceRecords.AuthorityEventRecord> events,
            Map<String, io.paperagent.v2.contracts.PlanStep> requiredSteps,
            Map<String, io.paperagent.v2.contracts.ValidationRequirement>
                    requirements,
            Set<String> planStepIds, Set<String> memberSteps,
            Set<String> memberValidations,
            ChainPersistenceRecords.ValidationBundleSetRecord member) {
        var step = requiredSteps.get(member.stepId());
        ProductChainRecoveryAuthorityLookup.exact(
                member.validationBundleId().equals(bundle.validationBundleId())
                        && member.taskId().equals(readiness.taskId())
                        && planStepIds.contains(member.stepId())
                        && step != null && memberSteps.add(member.stepId())
                        && memberValidations.add(member.validationId())
                        && completedMember(readiness, member),
                "readiness ValidationBundle membership root drift");
        var validation = authorities.validations()
                .findValidation(member.validationId())
                .orElseThrow(() -> ProductChainRecoveryAuthorityLookup.invalid(
                        "ValidationBundle member ValidationSet is missing"));
        ProductChainRecoveryAuthorityLookup.exact(
                validation.validationId().equals(member.validationId())
                        && validation.taskId().equals(readiness.taskId())
                        && validation.taskFrameId().equals(
                        readiness.taskFrameId())
                        && validation.planId().equals(readiness.finalPlanId())
                        && validation.planRevisionId().equals(
                        readiness.finalPlanRevisionId())
                        && validation.planRevisionNumber()
                        == readiness.finalPlanRevisionNumber()
                        && validation.stepId().equals(member.stepId())
                        && validation.activationEventId().equals(
                        member.activationEventId())
                        && validation.requestDigest().equals(
                        member.validationRequestDigest())
                        && validation.receiptSetDigest().equals(
                        member.validationReceiptSetDigest())
                        && validation.conclusionDigest().equals(
                        member.validationConclusionDigest())
                        && validation.conclusion()
                        == ChainValidationConclusion.PASSED,
                "ValidationBundle member ValidationSet identity drift");
        String setSource = ProductChainRecoveryAuthorityLookup.sha256(
                validation.validationId() + "\0" + validation.requestDigest()
                        + "\0" + validation.receiptSetDigest() + "\0"
                        + validation.conclusionDigest());
        ProductChainRecoveryAuthorityLookup.one(events,
                value -> value.eventId().equals(validation.eventId())
                        && value.taskId().equals(readiness.taskId())
                        && value.eventType().equals("VALIDATION")
                        && value.transitionId() == null
                        && value.sourceIdentitySha256().equals(setSource),
                "ValidationBundle member ValidationSet authority event");
        Set<String> actual = new HashSet<>();
        List<ChainValidationIdentity.RequestIdentity> requestIdentities =
                new java.util.ArrayList<>();
        List<ChainValidationIdentity.ReceiptIdentity> receiptIdentities =
                new java.util.ArrayList<>();
        List<ChainValidationIdentity.ConclusionIdentity>
                conclusionIdentities = new java.util.ArrayList<>();
        authorities.validations().findCandidateItems(member.validationId())
                .forEach(value -> {
                    var requirement = requirements.get(value.requirementId());
                    verifyItemRoot(validation, value.validationId(),
                            value.taskId(), value.requirementId(),
                            value.requirementDigest(), value.conclusion(),
                            requirement, ValidationSubject.CANDIDATE, actual);
                    requestIdentities.add(
                            new ChainValidationIdentity.RequestIdentity(
                                    value.requirementId(),
                                    value.requirementDigest(),
                                    requirement.subject(),
                                    ChainValidationIdentity.candidateSubject(
                                            value)));
                    receiptIdentities.add(
                            new ChainValidationIdentity.ReceiptIdentity(
                                    value.requirementId(), value.receiptId(),
                                    value.receiptPayloadSha256()));
                    conclusionIdentities.add(
                            new ChainValidationIdentity.ConclusionIdentity(
                                    value.requirementId(), value.conclusion()));
                });
        authorities.validations().findActionReceiptItems(member.validationId())
                .forEach(value -> {
                    var requirement = requirements.get(value.requirementId());
                    verifyItemRoot(validation, value.validationId(),
                            value.taskId(), value.requirementId(),
                            value.requirementDigest(), value.conclusion(),
                            requirement, ValidationSubject.ACTION_RECEIPT,
                            actual);
                    requestIdentities.add(
                            new ChainValidationIdentity.RequestIdentity(
                                    value.requirementId(),
                                    value.requirementDigest(),
                                    requirement.subject(),
                                    ChainValidationIdentity.actionSubject(
                                            value)));
                    receiptIdentities.add(
                            new ChainValidationIdentity.ReceiptIdentity(
                                    value.requirementId(), value.receiptId(),
                                    value.receiptPayloadSha256()));
                    conclusionIdentities.add(
                            new ChainValidationIdentity.ConclusionIdentity(
                                    value.requirementId(), value.conclusion()));
                });
        ProductChainRecoveryAuthorityLookup.exact(
                actual.equals(new HashSet<>(
                        step.validationRequirementIds())),
                "ValidationBundle member requirement coverage drift");
        var setScope = new ChainValidationIdentity.SetScope(
                validation.taskId(), validation.taskFrameId(),
                validation.planId(), validation.planRevisionId(),
                validation.planRevisionNumber(), validation.stepId(),
                validation.activationEventId());
        ProductChainRecoveryAuthorityLookup.exact(
                ChainValidationIdentity.requestDigest(
                        setScope, requestIdentities).equals(
                        validation.requestDigest())
                        && ChainValidationIdentity.receiptSetDigest(
                        receiptIdentities).equals(
                        validation.receiptSetDigest())
                        && ChainValidationIdentity.conclusionDigest(
                        conclusionIdentities).equals(
                        validation.conclusionDigest()),
                "ValidationBundle member ValidationSet digest drift");
        return new ChainValidationBundleIdentity.Member(
                member.stepId(), member.validationId(),
                validation.requestDigest(), validation.receiptSetDigest(),
                validation.conclusionDigest());
    }

    private static void verifyItemRoot(
            ChainPersistenceRecords.ValidationSetRecord validation,
            String validationId, String taskId, String requirementId,
            String requirementDigest, ChainValidationConclusion conclusion,
            io.paperagent.v2.contracts.ValidationRequirement requirement,
            ValidationSubject expectedSubject, Set<String> actual) {
        ProductChainRecoveryAuthorityLookup.exact(
                requirement != null
                        && requirement.subject() == expectedSubject
                        && validation.validationId().equals(validationId)
                        && validation.taskId().equals(taskId)
                        && ChainValidationIdentity.requirementDigest(
                        requirement).equals(requirementDigest)
                        && conclusion == ChainValidationConclusion.PASSED
                        && actual.add(requirementId),
                "ValidationBundle typed item identity drift");
    }

    private boolean completedMember(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.ValidationBundleSetRecord member) {
        var events = authorities.steps().findStepEvents(
                        readiness.taskId(), readiness.finalPlanRevisionId())
                .stream().filter(value -> value.command().stepId().equals(
                                member.stepId())
                        && value.command().activationEventId().equals(
                                member.activationEventId()))
                .toList();
        List<ChainStepAuthorityPort.StepEvent> completed = events.stream()
                .filter(value -> value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.COMPLETED)
                .toList();
        List<ChainStepAuthorityPort.StepEvent> activated = events.stream()
                .filter(value -> value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.ACTIVATED)
                .toList();
        return completed.size() == 1 && activated.size() == 1
                && activated.get(0).command().eventId().equals(
                member.activationEventId())
                && events.stream().noneMatch(value ->
                value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind
                        .SUPERSEDED_BY_REPLAN);
    }
}
