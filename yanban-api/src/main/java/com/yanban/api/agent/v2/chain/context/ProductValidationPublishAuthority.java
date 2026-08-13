package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainValidationBundleRepositoryAdapter;
import com.yanban.api.agent.v2.chain.finalization.ProductChainTerminalOutcomeAuthority;
import com.yanban.api.agent.v2.chain.persistence.ProductChainValidationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.publish.ProductChainPublishAuthoritySource;
import com.yanban.api.agent.v2.chain.recovery.ProductChainFinalizationRecoverySource;
import com.yanban.api.agent.v2.chain.validation.ProductChainValidationAuthority;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainValidationConclusion;
import io.paperagent.v2.chain.validation.ChainValidationBundleIdentity;
import io.paperagent.v2.chain.validation.ChainValidationIdentity;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.RequirementDeclarationMode;
import io.paperagent.v2.contracts.TaskRequirements;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads the exact formal validation/finalization/publish authority cut. */
final class ProductValidationPublishAuthority {
    private final ChainFoundationRepository foundations;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ChainFinalizationRepository finalization;
    private final ProductChainValidationRepositoryAdapter validations;
    private final ProductChainValidationBundleRepositoryAdapter bundles;
    private final ProductPlanBootstrapRepositoryAdapter bootstraps;
    private final ProductChainStepAuthorityAdapter steps;
    private final ProductChainValidationAuthority receiptBodies;
    private final ProductChainPublishAuthoritySource publishes;
    private final ProductValidationPublishAuthorityCut authorityCut;
    private final ProductChainTerminalOutcomeAuthority terminalOutcomes;

    ProductValidationPublishAuthority(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            ChainFinalizationRepository finalization,
            ProductChainValidationRepositoryAdapter validations,
            ProductChainValidationBundleRepositoryAdapter bundles,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductChainStepAuthorityAdapter steps,
            ProductChainValidationAuthority receiptBodies,
            ProductChainTerminalOutcomeAuthority terminalOutcomes,
            ProductChainPublishAuthoritySource publishes) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.validations = Objects.requireNonNull(validations, "validations");
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.bootstraps = Objects.requireNonNull(bootstraps, "bootstraps");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.receiptBodies = Objects.requireNonNull(
                receiptBodies, "receiptBodies");
        this.terminalOutcomes = Objects.requireNonNull(
                terminalOutcomes, "terminalOutcomes");
        this.publishes = Objects.requireNonNull(publishes, "publishes");
        this.authorityCut = new ProductValidationPublishAuthorityCut(
                foundations);
    }

    ProductValidationPublishFacts load(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        boolean directAnswer = ProductDirectAnswerContextAuthority
                .isDirectAnswer(building);
        if (directAnswer) {
            ProductDirectAnswerContextAuthority.require(building, workflow);
        }
        var task = foundations.findTask(building.taskId())
                .orElseThrow(() -> blocked("task is missing"));
        verifyTaskProject(building, task);
        var prefix = authorityCut.prefix(building.taskId());
        long eventCut = prefix.eventCut();
        Map<String, Long> sequences = prefix.sequences();
        var candidates = ProductValidationPublishAuthorityCut.records(
                building.taskId(),
                workflow.findCandidateStepResults(building.taskId()), sequences);
        var workspaceCandidates = ProductValidationPublishAuthorityCut.records(
                building.taskId(),
                workflow.findWorkspaceCandidates(building.taskId()), sequences);
        var readinessValues = ProductValidationPublishAuthorityCut.records(
                building.taskId(),
                finalization.findReadiness(building.taskId()), sequences);
        var outcome = finalization.findTaskOutcome(building.taskId())
                .orElse(null);
        if (outcome != null) ProductValidationPublishAuthorityCut.visible(
                outcome, building.taskId(), sequences);
        var terminal = building.role() == ChainRole.ANSWER
                && outcome != null
                ? terminalOutcomes.requireExact(task, outcome) : null;
        if (terminal != null && terminal.readiness() != null) {
            ProductValidationPublishAuthorityCut.visible(
                    terminal.readiness(), building.taskId(), sequences);
        }
        if (terminal != null && terminal.check() != null) {
            ProductValidationPublishAuthorityCut.visible(
                    terminal.check(), building.taskId(), sequences);
        }
        var readiness = terminal == null
                ? ProductValidationPublishIdentity.readiness(
                building, readinessValues, sequences)
                : terminal.readiness();
        var requirements = requirements(building);
        var revision = planRevision(building);
        var currentResult = currentResult(building, candidates);
        var validation = validation(building, readiness, requirements,
                revision, currentResult, sequences, prefix.events());
        var candidate = ProductValidationPublishIdentity.candidate(
                building, readiness, revision, candidates);
        var workspaceCandidate = ProductValidationPublishIdentity.workspace(
                building, readiness, candidate, workspaceCandidates);
        var checks = terminal != null && terminal.check() != null
                ? List.of(terminal.check()) : checks(readiness, sequences);
        var latest = terminal != null ? terminal.check()
                : checks.isEmpty() ? null : checks.get(checks.size() - 1);
        if (!directAnswer) {
            ProductValidationPublishIdentity.verifyOutcome(
                    building, outcome, readiness, candidate);
        }
        var publish = publish(readiness, latest, outcome);
        return new ProductValidationPublishFacts(
                building, task, eventCut, sequences, workspaceCandidate,
                candidate, requirements != null && !requirements
                .validationRequirements().isEmpty(), validation,
                readiness, checks,
                latest, outcome,
                publish.operation(), publish.failure());
    }

    private TaskRequirements requirements(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        if (building.planId() == null) return null;
        var stored = bootstraps.find(new PlanId(building.planId()))
                .orElseThrow(() -> blocked(
                        "Validation TaskFrame authority is missing"));
        if (!stored.plan().id().value().equals(building.planId())
                || !stored.taskFrame().id().value().equals(
                building.taskFrameId())
                || stored.taskFrame().requirements().declarationMode()
                != RequirementDeclarationMode.EXPLICIT) {
            throw blocked("Validation TaskFrame authority is inconsistent");
        }
        return stored.taskFrame().requirements();
    }

    private PlanRevision planRevision(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        if (building.planRevisionId() == null) return null;
        PlanRevision value = steps.findPlanRevision(
                        building.taskId(), building.planRevisionId())
                .orElseThrow(() -> blocked(
                        "Validation PlanRevision authority is missing"));
        if (!value.id().value().equals(building.planRevisionId())
                || value.number() != building.planRevisionNumber()
                || !value.taskFrameId().value().equals(
                building.taskFrameId())) {
            throw blocked("Validation PlanRevision identity mismatches");
        }
        return value;
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord
            currentResult(
                    ChainPersistenceRecords.ContextRevisionRecord building,
                    List<ChainPersistenceRecords.CandidateStepResultRecord>
                            values) {
        if (building.stepId() == null) return null;
        List<ChainPersistenceRecords.CandidateStepResultRecord> exact = values
                .stream()
                .filter(value -> value.instructionId().equals(
                        building.instructionId()))
                .filter(value -> value.taskFrameId().equals(
                        building.taskFrameId()))
                .filter(value -> value.planId().equals(building.planId()))
                .filter(value -> value.planRevisionId().equals(
                        building.planRevisionId()))
                .filter(value -> value.planRevisionNumber()
                        == building.planRevisionNumber())
                .filter(value -> value.stepId().equals(building.stepId()))
                .filter(value -> value.activationEventId().equals(
                        building.activationEventId()))
                .toList();
        if (exact.size() > 1) {
            throw blocked("current Step Validation result is ambiguous");
        }
        return exact.isEmpty() ? null : exact.get(0);
    }

    private ProductTypedValidationView validation(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            TaskRequirements requirements,
            PlanRevision revision,
            ChainPersistenceRecords.CandidateStepResultRecord currentResult,
            Map<String, Long> sequences,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> events) {
        if (requirements == null) {
            return null;
        }
        if (building.role() == ChainRole.ANSWER && readiness == null) {
            return null;
        }
        if (readiness != null) {
            return planValidation(building, readiness, requirements,
                    revision, sequences, events);
        }
        return currentStepValidation(building, requirements, revision,
                currentResult, sequences, events);
    }

    private ProductTypedValidationView currentStepValidation(
            ChainPersistenceRecords.ContextRevisionRecord building,
            TaskRequirements requirements,
            PlanRevision revision,
            ChainPersistenceRecords.CandidateStepResultRecord result,
            Map<String, Long> sequences,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> events) {
        if (building.stepId() == null) return null;
        PlanStep step = exactStep(revision, building.stepId());
        Map<String, ValidationRequirement> expected = requirementsFor(
                requirements, step.validationRequirementIds());
        if (result == null) {
            if (building.validationId() != null) {
                throw blocked("Context names Validation without a Step result");
            }
            return null;
        }
        if (expected.isEmpty()) {
            if (result.validationId() != null
                    || building.validationId() != null) {
                throw blocked("current Step carries unrequired Validation");
            }
            return null;
        }
        if (result.validationId() == null
                || result.validationRequestDigest() == null
                || result.validationReceiptDigest() == null) {
            throw blocked("current Step required Validation is missing");
        }
        verifyContextTriple(building, result.validationId(),
                result.validationRequestDigest(),
                result.validationReceiptDigest());
        ProductTypedValidationView.SetView set = set(
                result.validationId(), building, result, expected, sequences,
                events);
        var validation = set.validation();
        return new ProductTypedValidationView(
                ProductTypedValidationView.Scope.CURRENT_STEP,
                validation.validationId(), validation.requestDigest(),
                validation.receiptSetDigest(),
                validation.conclusionDigest(), validation.conclusion(),
                List.of(set));
    }

    private ProductTypedValidationView planValidation(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            TaskRequirements requirements,
            PlanRevision revision,
            Map<String, Long> sequences,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> events) {
        if (requirements.validationRequirements().isEmpty()) {
            if (!io.paperagent.v2.chain.ChainIdentity.NONE.equals(
                    readiness.validationId())
                    || readiness.validationRequestDigest() != null
                    || readiness.validationReceiptDigest() != null
                    || building.validationId() != null) {
                throw blocked("NOT_REQUIRED Validation identity is invalid");
            }
            return null;
        }
        if (io.paperagent.v2.chain.ChainIdentity.NONE.equals(
                readiness.validationId())) {
            throw blocked("required plan ValidationBundle is missing");
        }
        verifyContextTriple(building, readiness.validationId(),
                readiness.validationRequestDigest(),
                readiness.validationReceiptDigest());
        var bundle = bundles.findBundle(readiness.validationId())
                .orElseThrow(() -> blocked(
                        "plan ValidationBundle is missing"));
        ProductValidationPublishAuthorityCut.visible(
                bundle, building.taskId(), sequences);
        if (!bundle.validationBundleId().equals(readiness.validationId())
                || !bundle.taskFrameId().equals(building.taskFrameId())
                || !bundle.planId().equals(building.planId())
                || !bundle.planRevisionId().equals(
                building.planRevisionId())
                || bundle.planRevisionNumber()
                != building.planRevisionNumber()
                || !bundle.instructionId().equals(building.instructionId())
                || !bundle.finalStepId().equals(readiness.finalStepId())
                || !bundle.requestDigest().equals(
                readiness.validationRequestDigest())
                || !bundle.receiptSetDigest().equals(
                readiness.validationReceiptDigest())
                || bundle.conclusion() != ChainValidationConclusion.PASSED
                || !validationEvent(bundle, events)) {
            throw blocked("plan ValidationBundle identity mismatches");
        }
        Map<String, PlanStep> planSteps = new HashMap<>();
        revision.steps().forEach(step -> planSteps.put(step.id().value(), step));
        List<ChainPersistenceRecords.ValidationBundleSetRecord> members =
                bundles.findBundleSets(bundle.validationBundleId());
        List<ProductTypedValidationView.SetView> setViews = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        Set<String> memberSteps = new HashSet<>();
        Set<String> memberValidations = new HashSet<>();
        for (var member : members) {
            PlanStep step = planSteps.get(member.stepId());
            if (step == null || !member.taskId().equals(building.taskId())
                    || !member.validationBundleId().equals(
                    bundle.validationBundleId())) {
                throw blocked("ValidationBundle membership is invalid");
            }
            Map<String, ValidationRequirement> expected = requirementsFor(
                    requirements, step.validationRequirementIds());
            if (expected.isEmpty()
                    || !memberSteps.add(member.stepId())
                    || !memberValidations.add(member.validationId())) {
                throw blocked("ValidationBundle membership is not unique");
            }
            for (String requirementId : expected.keySet()) {
                if (!covered.add(requirementId)) {
                    throw blocked(
                            "ValidationBundle requirement is duplicated");
                }
            }
            var result = exactResultForMember(
                    building, member, sequences);
            var set = set(member.validationId(), building, result,
                    expected, sequences, events);
            if (!member.activationEventId().equals(
                    set.validation().activationEventId())
                    || !member.validationRequestDigest().equals(
                    set.validation().requestDigest())
                    || !member.validationReceiptSetDigest().equals(
                    set.validation().receiptSetDigest())
                    || !member.validationConclusionDigest().equals(
                    set.validation().conclusionDigest())) {
                throw blocked("ValidationBundle member digest mismatches");
            }
            setViews.add(set);
        }
        Set<String> all = requirements.validationRequirements().stream()
                .map(ValidationRequirement::requirementId)
                .collect(java.util.stream.Collectors.toSet());
        if (!covered.equals(all) || members.size() != setViews.size()
                || !bundleIdentityMatches(bundle, members)) {
            throw blocked("ValidationBundle coverage or digest is invalid");
        }
        setViews.sort(Comparator.comparing(value ->
                value.validation().stepId()));
        return new ProductTypedValidationView(
                ProductTypedValidationView.Scope.PLAN,
                bundle.validationBundleId(), bundle.requestDigest(),
                bundle.receiptSetDigest(), bundle.conclusionDigest(),
                bundle.conclusion(), setViews);
    }

    private ChainPersistenceRecords.CandidateStepResultRecord
            exactResultForMember(
                    ChainPersistenceRecords.ContextRevisionRecord building,
                    ChainPersistenceRecords.ValidationBundleSetRecord member,
                    Map<String, Long> sequences) {
        List<ChainPersistenceRecords.CandidateStepResultRecord> exact =
                ProductValidationPublishAuthorityCut.records(
                        building.taskId(), workflow.findCandidateStepResults(
                                building.taskId()), sequences).stream()
                        .filter(value -> value.instructionId().equals(
                                building.instructionId()))
                        .filter(value -> value.taskFrameId().equals(
                                building.taskFrameId()))
                        .filter(value -> value.planId().equals(
                                building.planId()))
                        .filter(value -> value.planRevisionId().equals(
                                building.planRevisionId()))
                        .filter(value -> value.planRevisionNumber()
                                == building.planRevisionNumber())
                        .filter(value -> value.stepId().equals(
                                member.stepId()))
                        .filter(value -> value.activationEventId().equals(
                                member.activationEventId()))
                        .filter(value -> Objects.equals(value.validationId(),
                                member.validationId()))
                        .toList();
        if (exact.size() != 1) {
            throw blocked("ValidationBundle Step result is not exact");
        }
        return exact.get(0);
    }

    private ProductTypedValidationView.SetView set(
            String validationId,
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.CandidateStepResultRecord result,
            Map<String, ValidationRequirement> expected,
            Map<String, Long> sequences,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> events) {
        var set = validations.findValidation(validationId)
                .orElseThrow(() -> blocked("typed ValidationSet is missing"));
        ProductValidationPublishAuthorityCut.visible(
                set, building.taskId(), sequences);
        List<ChainPersistenceRecords.CandidateValidationItemRecord>
                candidateItems = validations.findCandidateItems(validationId);
        List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                actionItems = validations.findActionReceiptItems(validationId);
        verifySet(building, result, set, candidateItems, actionItems,
                expected, events);
        List<ProductTypedValidationView.ReceiptView> bodies =
                building.role() == ChainRole.ANSWER
                        ? receiptBodies(set, candidateItems, actionItems)
                        : List.of();
        return new ProductTypedValidationView.SetView(
                set, candidateItems, actionItems, bodies);
    }

    private List<ProductTypedValidationView.ReceiptView> receiptBodies(
            ChainPersistenceRecords.ValidationSetRecord set,
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    candidateItems,
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    actionItems) {
        List<ProductTypedValidationView.ReceiptView> result =
                new ArrayList<>();
        for (var item : candidateItems) {
            result.add(new ProductTypedValidationView.ReceiptView(
                    item.requirementId(), receiptBodies.exactReceiptBody(
                    set, item.validationActionId(), item.receiptId(),
                    item.receiptPayloadSha256())));
        }
        for (var item : actionItems) {
            result.add(new ProductTypedValidationView.ReceiptView(
                    item.requirementId(), receiptBodies.exactReceiptBody(
                    set, item.actionId(), item.receiptId(),
                    item.receiptPayloadSha256())));
        }
        result.sort(Comparator.comparing(
                ProductTypedValidationView.ReceiptView::requirementId));
        return List.copyOf(result);
    }

    private static void verifySet(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.CandidateStepResultRecord result,
            ChainPersistenceRecords.ValidationSetRecord set,
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    candidateItems,
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    actionItems,
            Map<String, ValidationRequirement> expected,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> events) {
        if (!set.taskFrameId().equals(building.taskFrameId())
                || !set.planId().equals(building.planId())
                || !set.planRevisionId().equals(building.planRevisionId())
                || set.planRevisionNumber()
                != building.planRevisionNumber()
                || !set.stepId().equals(result.stepId())
                || !set.activationEventId().equals(
                result.activationEventId())
                || !set.validationId().equals(result.validationId())
                || !set.requestDigest().equals(
                result.validationRequestDigest())
                || !set.receiptSetDigest().equals(
                result.validationReceiptDigest())
                || set.conclusion() != ChainValidationConclusion.PASSED
                || !setEvent(set, events)) {
            throw blocked("typed ValidationSet identity mismatches");
        }
        Map<String, ChainPersistenceRecords.CandidateValidationItemRecord>
                candidates = uniqueCandidates(candidateItems);
        Map<String, ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                actions = uniqueActions(actionItems);
        Set<String> actual = new HashSet<>(candidates.keySet());
        if (!java.util.Collections.disjoint(
                candidates.keySet(), actions.keySet())) {
            throw blocked("typed Validation items do not match requirements");
        }
        actual.addAll(actions.keySet());
        if (!actual.equals(expected.keySet())) {
            throw blocked("typed Validation items do not match requirements");
        }
        List<ChainValidationIdentity.RequestIdentity> requests =
                new ArrayList<>();
        List<ChainValidationIdentity.ReceiptIdentity> receipts =
                new ArrayList<>();
        List<ChainValidationIdentity.ConclusionIdentity> conclusions =
                new ArrayList<>();
        for (String id : expected.keySet().stream().sorted().toList()) {
            ValidationRequirement requirement = expected.get(id);
            String requirementDigest = ChainValidationIdentity
                    .requirementDigest(requirement);
            if (requirement.subject() == ValidationSubject.CANDIDATE) {
                var item = candidates.get(id);
                if (item == null || !common(set, item.validationId(),
                        item.taskId(), item.requirementDigest(),
                        item.conclusion(), requirementDigest)) {
                    throw blocked("Candidate Validation item is invalid");
                }
                requests.add(new ChainValidationIdentity.RequestIdentity(
                        id, requirementDigest, requirement.subject(),
                        ChainValidationIdentity.candidateSubject(item)));
                receipts.add(new ChainValidationIdentity.ReceiptIdentity(
                        id, item.receiptId(), item.receiptPayloadSha256()));
            } else {
                var item = actions.get(id);
                if (item == null || !common(set, item.validationId(),
                        item.taskId(), item.requirementDigest(),
                        item.conclusion(), requirementDigest)) {
                    throw blocked("Action Receipt Validation item is invalid");
                }
                requests.add(new ChainValidationIdentity.RequestIdentity(
                        id, requirementDigest, requirement.subject(),
                        ChainValidationIdentity.actionSubject(item)));
                receipts.add(new ChainValidationIdentity.ReceiptIdentity(
                        id, item.receiptId(), item.receiptPayloadSha256()));
            }
            conclusions.add(new ChainValidationIdentity.ConclusionIdentity(
                    id, ChainValidationConclusion.PASSED));
        }
        var scope = new ChainValidationIdentity.SetScope(
                set.taskId(), set.taskFrameId(), set.planId(),
                set.planRevisionId(), set.planRevisionNumber(), set.stepId(),
                set.activationEventId());
        String request = ChainValidationIdentity.requestDigest(scope, requests);
        String receipt = ChainValidationIdentity.receiptSetDigest(receipts);
        String conclusion = ChainValidationIdentity.conclusionDigest(
                conclusions);
        String expectedId = "validation." + sha256(set.taskId() + "\0"
                + set.planRevisionId() + "\0" + set.stepId() + "\0"
                + set.activationEventId() + "\0" + request + "\0"
                + receipt);
        if (!expectedId.equals(set.validationId())
                || !request.equals(set.requestDigest())
                || !receipt.equals(set.receiptSetDigest())
                || !conclusion.equals(set.conclusionDigest())) {
            throw blocked("typed ValidationSet digest identity is invalid");
        }
    }

    private static boolean common(
            ChainPersistenceRecords.ValidationSetRecord set,
            String validationId, String taskId, String requirementDigest,
            ChainValidationConclusion conclusion,
            String expectedRequirementDigest) {
        return set.validationId().equals(validationId)
                && set.taskId().equals(taskId)
                && expectedRequirementDigest.equals(requirementDigest)
                && conclusion == ChainValidationConclusion.PASSED;
    }

    private static Map<String, ChainPersistenceRecords
            .CandidateValidationItemRecord> uniqueCandidates(
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    values) {
        Map<String, ChainPersistenceRecords.CandidateValidationItemRecord>
                result = new HashMap<>();
        for (var value : values) {
            if (result.put(value.requirementId(), value) != null) {
                throw blocked("Candidate Validation item is duplicated");
            }
        }
        return result;
    }

    private static Map<String, ChainPersistenceRecords
            .ActionReceiptValidationItemRecord> uniqueActions(
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    values) {
        Map<String, ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                result = new HashMap<>();
        for (var value : values) {
            if (result.put(value.requirementId(), value) != null) {
                throw blocked("Action Validation item is duplicated");
            }
        }
        return result;
    }

    private static Map<String, ValidationRequirement> requirementsFor(
            TaskRequirements requirements, List<String> ids) {
        Map<String, ValidationRequirement> all = new HashMap<>();
        requirements.validationRequirements().forEach(value ->
                all.put(value.requirementId(), value));
        Map<String, ValidationRequirement> result = new HashMap<>();
        for (String id : ids) {
            ValidationRequirement value = all.get(id);
            if (value == null || result.put(id, value) != null) {
                throw blocked("Plan Step Validation binding is invalid");
            }
        }
        return Map.copyOf(result);
    }

    private static PlanStep exactStep(PlanRevision revision, String stepId) {
        List<PlanStep> exact = revision.steps().stream()
                .filter(value -> value.id().value().equals(stepId)).toList();
        if (exact.size() != 1) {
            throw blocked("current Validation Step is not exact");
        }
        return exact.get(0);
    }

    private static void verifyContextTriple(
            ChainPersistenceRecords.ContextRevisionRecord building,
            String id, String request, String receipt) {
        if (building.validationId() != null
                && (!building.validationId().equals(id)
                || !building.validationRequestDigest().equals(request)
                || !building.validationReceiptDigest().equals(receipt))) {
            throw blocked("ContextRevision Validation identity mismatches");
        }
    }

    private static boolean setEvent(
            ChainPersistenceRecords.ValidationSetRecord set,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> events) {
        var event = events.get(set.eventId());
        String source = sha256(set.validationId() + "\0"
                + set.requestDigest() + "\0" + set.receiptSetDigest()
                + "\0" + set.conclusionDigest());
        return event != null && event.taskId().equals(set.taskId())
                && "VALIDATION".equals(event.eventType())
                && event.transitionId() == null
                && source.equals(event.sourceIdentitySha256());
    }

    private static boolean validationEvent(
            ChainPersistenceRecords.ValidationBundleRecord bundle,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> events) {
        var event = events.get(bundle.eventId());
        var aggregate = new ChainValidationBundleIdentity.Aggregate(
                bundle.requestDigest(), bundle.receiptSetDigest(),
                bundle.conclusionDigest());
        String source = ChainValidationBundleIdentity.eventSourceIdentity(
                bundle.validationBundleId(), aggregate);
        return event != null && event.taskId().equals(bundle.taskId())
                && "VALIDATION_BUNDLE".equals(event.eventType())
                && event.transitionId() == null
                && source.equals(event.sourceIdentitySha256());
    }

    private static boolean bundleIdentityMatches(
            ChainPersistenceRecords.ValidationBundleRecord bundle,
            List<ChainPersistenceRecords.ValidationBundleSetRecord> values) {
        var scope = new ChainValidationBundleIdentity.Scope(
                bundle.taskId(), bundle.taskFrameId(), bundle.planId(),
                bundle.planRevisionId(), bundle.planRevisionNumber(),
                bundle.instructionId(), bundle.finalStepId());
        List<ChainValidationBundleIdentity.Member> members = values.stream()
                .map(value -> new ChainValidationBundleIdentity.Member(
                        value.stepId(), value.validationId(),
                        value.validationRequestDigest(),
                        value.validationReceiptSetDigest(),
                        value.validationConclusionDigest()))
                .toList();
        try {
            var aggregate = ChainValidationBundleIdentity.aggregate(
                    scope, members);
            return bundle.requestDigest().equals(aggregate.requestDigest())
                    && bundle.receiptSetDigest().equals(
                    aggregate.receiptSetDigest())
                    && bundle.conclusionDigest().equals(
                    aggregate.conclusionDigest())
                    && bundle.validationBundleId().equals(
                    ChainValidationBundleIdentity.bundleId(scope, aggregate));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private List<ChainPersistenceRecords.FinalizationCheckRecord> checks(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            Map<String, Long> sequences) {
        if (readiness == null) return List.of();
        var values = ProductValidationPublishAuthorityCut.records(
                readiness.taskId(), finalization.findFinalizationChecks(
                        readiness.readinessId()), sequences)
                .stream().sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.FinalizationCheckRecord
                                ::attemptNo)).toList();
        long previousEvent = 0;
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            long event = sequences.get(value.eventId());
            if (!value.readinessId().equals(readiness.readinessId())
                    || value.attemptNo() != index + 1
                    || event <= previousEvent
                    || !value.taskFrameId().equals(readiness.taskFrameId())
                    || !value.finalPlanRevisionId().equals(
                    readiness.finalPlanRevisionId())
                    || !value.candidateKey().equals(readiness.candidateKey())
                    || !value.workspaceId().equals(readiness.workspaceId())
                    || !value.validationId().equals(readiness.validationId())
                    || !Objects.equals(value.validationRequestDigest(),
                    readiness.validationRequestDigest())
                    || !Objects.equals(value.validationReceiptDigest(),
                    readiness.validationReceiptDigest())
                    || !value.publishRequirementDigest().equals(
                    readiness.publishRequirementDigest())
                    || !value.projectVersion().equals(
                    readiness.projectVersion())) {
                throw blocked("finalization attempt prefix is inconsistent");
            }
            previousEvent = event;
        }
        return values;
    }

    private PublishFacts publish(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check,
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        if (readiness == null || check == null
                || check.resultStatus() != ChainFinalization.Outcome.PASSED) {
            if (outcome != null && outcome.publishOperationId() != null) {
                throw blocked("TaskOutcome publishes without a passed finalization");
            }
            return new PublishFacts(null, null);
        }
        if (readiness.publishRequirement() == ChainPublishRequirement.NOT_REQUIRED) {
            if (outcome != null && (outcome.publishOperationId() != null
                    || outcome.publishedProjectVersion() != null
                    || outcome.publishReceiptId() != null)) {
                throw blocked("NOT_REQUIRED finalization carries publish result");
            }
            return new PublishFacts(null, null);
        }
        var transition = workflow.findTransition(check.transitionId())
                .filter(value -> value.taskId().equals(readiness.taskId()))
                .filter(value -> value.transitionType()
                        == ChainTransitionType.FINALIZATION)
                .orElseThrow(() -> blocked("finalization transition is missing"));
        var success = publishes.findExactSuccess(readiness, check).orElse(null);
        var failure = success == null
                ? publishes.find(transition, readiness, check).orElse(null)
                : null;
        if (outcome != null) {
            if (success == null
                    || !success.formalRef().equals(outcome.publishOperationId())
                    || !success.formalRef().equals(outcome.publishReceiptId())
                    || !Objects.equals(success.resultRevisionId(),
                    outcome.publishedRevisionId())
                    || !Objects.equals(success.resultVersion(),
                    outcome.publishedProjectVersion())) {
                throw blocked("TaskOutcome publish result identity mismatches");
            }
        }
        return new PublishFacts(success, failure);
    }

    private static void verifyTaskProject(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.TaskRecord task) {
        if (!task.taskId().equals(building.taskId())
                || !Objects.equals(task.projectId(), building.projectId())) {
            throw blocked("ContextRevision task/Project identity mismatches");
        }
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(
                ChainContextModule.VALIDATION_AND_PUBLISH, reason);
    }

    private static String sha256(String value) {
        return ProductChainContractProjectionCodec.sha256(value);
    }

    private record PublishFacts(
            ProductChainPublishAuthoritySource.Operation operation,
            ProductChainFinalizationRecoverySource.PublishFailure failure) {
    }
}
