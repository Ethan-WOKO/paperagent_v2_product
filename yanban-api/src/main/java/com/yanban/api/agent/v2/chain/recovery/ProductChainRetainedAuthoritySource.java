package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainStepStatus;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.chain.step.ChainActionProgressIdentity;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.persistence.VersionedCheckpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads retained V2 core, Effect, Validation and Publish authorities by the
 * exact identities frozen in the chain cut.
 */
public final class ProductChainRetainedAuthoritySource
        implements ProductChainRecoverySource.StableAuthoritySource {
    private static final int MAX_STABLE_READ_ATTEMPTS = 3;

    private final StepRecoveryRepository steps;
    private final EffectIntentRepository intents;
    private final EffectOutcomeRepository outcomes;
    private final ChainWorkflowRepository workflow;
    private final io.paperagent.v2.chain.finalization
            .ChainFinalizationAuthorityPort finalization;
    private final PublishAttemptLookup publishes;

    public ProductChainRetainedAuthoritySource(
            StepRecoveryRepository steps,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            ChainWorkflowRepository workflow,
            io.paperagent.v2.chain.finalization
                    .ChainFinalizationAuthorityPort finalization,
            PublishAttemptLookup publishes) {
        this.steps = Objects.requireNonNull(steps, "steps");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(
                finalization, "finalization");
        this.publishes = Objects.requireNonNull(publishes, "publishes");
    }

    @Override
    public ProductChainRecoverySource.StableAuthoritySnapshot freeze(
            ProductChainRecoverySource.StableAuthorityRequest request) {
        Objects.requireNonNull(request, "request");
        for (int attempt = 1; attempt <= MAX_STABLE_READ_ATTEMPTS; attempt++) {
            StableRead first = read(request);
            StableRead verified = read(request);
            if (first.equals(verified)) {
                return new ProductChainRecoverySource.StableAuthoritySnapshot(
                        request.taskId(), request.chainAuthorityCut(),
                        "retained-v2-authority-sha256=" + first.digest(),
                        first.facts(), first.stepState());
            }
        }
        throw new IllegalStateException(
                "retained recovery authorities did not stabilize");
    }

    private StableRead read(
            ProductChainRecoverySource.StableAuthorityRequest request) {
        List<ProductChainRecoverySource.StableAuthorityFact> facts =
                new ArrayList<>();
        Optional<ProductChainRecoverySource.StepState> stepState =
                readStep(request, facts);
        readEffects(request, facts);
        readFinalization(request, facts);
        List<ProductChainRecoverySource.StableAuthorityFact> ordered = facts
                .stream().sorted(Comparator
                        .comparing((ProductChainRecoverySource
                                .StableAuthorityFact value) ->
                                value.kind().name())
                        .thenComparing(ProductChainRecoverySource
                                .StableAuthorityFact::authorityType)
                        .thenComparing(ProductChainRecoverySource
                                .StableAuthorityFact::authorityRef))
                .toList();
        String digest = sha256(ordered.stream().map(value ->
                value.kind() + "\0" + value.authorityType() + "\0"
                        + value.authorityRef() + "\0"
                        + value.identityDigest() + "\0" + value.status())
                .toList());
        return new StableRead(ordered, stepState, digest);
    }

    private Optional<ProductChainRecoverySource.StepState> readStep(
            ProductChainRecoverySource.StableAuthorityRequest request,
            List<ProductChainRecoverySource.StableAuthorityFact> facts) {
        if (request.planBindings().isEmpty()) {
            return Optional.empty();
        }
        ChainPersistenceRecords.PlanBindingRecord binding = request
                .planBindings().get(request.planBindings().size() - 1);
        PersistenceResult<StepRecoverySnapshot> result = steps.inspect(
                new PlanId(binding.planId()));
        if (!result.successful()) {
            String status = result.failure().orElseThrow().code().name();
            facts.add(fact(
                    ProductChainRecoverySource.StableFactKind
                            .TASKFRAME_PLAN_STEP,
                    "STEP_RECOVERY", binding.planId(),
                    identity(binding.planBindingId(), binding.taskFrameId(),
                            binding.planId(), binding.planRevisionId()), status));
            return Optional.empty();
        }
        StepRecoverySnapshot snapshot = result.value().orElseThrow();
        Plan plan = plan(snapshot);
        TaskFrame taskFrame = taskFrame(snapshot);
        VersionedCheckpoint checkpoint = checkpoint(snapshot);
        var revision = plan.latestRevision();
        PlanRevision boundRevision = boundRevision(plan, binding);
        if (!binding.taskFrameId().equals(taskFrame.id().value())
                || !binding.planId().equals(plan.id().value())
                || boundRevision == null
                || !completionLineageIsExact(plan, boundRevision)
                || !checkpoint.checkpoint().revisionId().equals(revision.id())
                || checkpoint.checkpoint().revisionNumber()
                        != revision.number()) {
            throw new IllegalStateException(
                    "retained Step authority conflicts with the Plan binding");
        }
        facts.add(fact(ProductChainRecoverySource.StableFactKind
                        .TASKFRAME_PLAN_STEP,
                "TASK_FRAME", taskFrame.id().value(),
                identity(taskFrame.id().value(), binding.planBindingId()),
                "FROZEN"));
        facts.add(fact(ProductChainRecoverySource.StableFactKind
                        .TASKFRAME_PLAN_STEP,
                "PLAN_REVISION", boundRevision.id().value(),
                identity(plan.id().value(), boundRevision.id().value(),
                        Long.toString(boundRevision.number())),
                checkpoint.checkpoint().planState().name()));
        facts.add(fact(ProductChainRecoverySource.StableFactKind
                        .TASKFRAME_PLAN_STEP,
                "CHECKPOINT", plan.id().value() + ":"
                        + checkpoint.version(),
                identity(plan.id().value(), revision.id().value(),
                        Long.toString(checkpoint.version()),
                        Long.toString(checkpoint.checkpoint()
                                .lastEventSequence())),
                checkpoint.checkpoint().planState().name()));
        return Optional.of(stepState(
                snapshot, boundRevision.id().value()));
    }

    private static PlanRevision boundRevision(
            Plan plan, ChainPersistenceRecords.PlanBindingRecord binding) {
        List<PlanRevision> matches = plan.revisions().stream()
                .filter(value -> value.id().value().equals(
                        binding.planRevisionId()))
                .filter(value -> value.number()
                        == binding.planRevisionNumber())
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean completionLineageIsExact(
            Plan plan, PlanRevision bound) {
        int boundIndex = plan.revisions().indexOf(bound);
        if (boundIndex < 0) return false;
        for (int index = boundIndex + 1;
                index < plan.revisions().size(); index++) {
            PlanRevision previous = plan.revisions().get(index - 1);
            PlanRevision current = plan.revisions().get(index);
            if (!current.parentRevisionId().equals(
                        Optional.of(previous.id()))
                    || current.number() != previous.number() + 1
                    || !current.taskFrameId().equals(previous.taskFrameId())
                    || !current.steps().equals(previous.steps())
                    || current.completedFacts().size()
                        != previous.completedFacts().size() + 1
                    || !current.completedFacts().entrySet().containsAll(
                        previous.completedFacts().entrySet())) {
                return false;
            }
        }
        return true;
    }

    private static Plan plan(StepRecoverySnapshot snapshot) {
        if (snapshot instanceof PersistedStepRecoveryReady ready) {
            return ready.plan();
        }
        if (snapshot instanceof PersistedStepRecoveryActive active) {
            return active.plan();
        }
        return ((PersistedStepRecoverySucceeded) snapshot).plan();
    }

    private static TaskFrame taskFrame(StepRecoverySnapshot snapshot) {
        if (snapshot instanceof PersistedStepRecoveryReady ready) {
            return ready.taskFrame();
        }
        if (snapshot instanceof PersistedStepRecoveryActive active) {
            return active.taskFrame();
        }
        return ((PersistedStepRecoverySucceeded) snapshot).taskFrame();
    }

    private static VersionedCheckpoint checkpoint(
            StepRecoverySnapshot snapshot) {
        if (snapshot instanceof PersistedStepRecoveryReady ready) {
            return ready.checkpoint();
        }
        if (snapshot instanceof PersistedStepRecoveryActive active) {
            return active.checkpoint();
        }
        return ((PersistedStepRecoverySucceeded) snapshot).checkpoint();
    }

    private static ProductChainRecoverySource.StepState stepState(
            StepRecoverySnapshot snapshot, String revisionId) {
        if (snapshot instanceof PersistedStepRecoveryReady ready) {
            long sequence = ready.checkpoint().checkpoint()
                    .lastEventSequence();
            return new ProductChainRecoverySource.StepState(
                    revisionId, ready.readyStepId().value(), null,
                    ChainStepStatus.READY, "CHECKPOINT",
                    ready.planId().value() + ":"
                            + ready.checkpoint().version(), sequence);
        }
        if (snapshot instanceof PersistedStepRecoveryActive active) {
            return new ProductChainRecoverySource.StepState(
                    revisionId, active.activation().stepId().value(),
                    active.activation().activationEvent().id().value(),
                    ChainStepStatus.ACTIVE, "STEP_ACTIVATION",
                    active.activation().activationEvent().id().value(),
                    active.activation().activationEvent().sequence());
        }
        PersistedStepRecoverySucceeded succeeded =
                (PersistedStepRecoverySucceeded) snapshot;
        var steps = succeeded.plan().latestRevision().steps();
        if (steps.isEmpty()) {
            throw new IllegalStateException(
                    "successful retained Plan has no final Step");
        }
        String stepId = steps.get(steps.size() - 1).id().value();
        return new ProductChainRecoverySource.StepState(
                revisionId, stepId, null, ChainStepStatus.COMPLETED,
                "CHECKPOINT", succeeded.planId().value() + ":"
                + succeeded.checkpoint().version(),
                succeeded.checkpoint().checkpoint().lastEventSequence());
    }

    private void readEffects(
            ProductChainRecoverySource.StableAuthorityRequest request,
            List<ProductChainRecoverySource.StableAuthorityFact> facts) {
        for (ChainPersistenceRecords.ActionBindingRecord action
                : request.actions()) {
            ToolCallId toolCallId = new ToolCallId(action.actionId());
            PersistenceResult<PersistedEffectIntent> intentResult =
                    intents.find(toolCallId);
            PersistedEffectIntent intent = intentResult.successful()
                    ? intentResult.value().orElseThrow() : null;
            String intentFailure = intent == null
                    ? failureCode(intentResult) : null;
            if (intent != null) {
                if (!intent.intent().toolCallId().equals(toolCallId)
                        || !intent.intent().planId().value().equals(
                        action.planId())
                        || !intent.intent().stepId().value().equals(
                        action.stepId())
                        || !intent.activationEventId().value().equals(
                        action.activationEventId())) {
                    throw new IllegalStateException(
                            "EffectIntent conflicts with ActionBinding");
                }
                facts.add(fact(ProductChainRecoverySource.StableFactKind
                                .EFFECT_INTENT_RECEIPT_ERROR,
                        "EFFECT_INTENT", action.actionId(),
                        identity(action.actionId(), action.planId(),
                                action.stepId(), action.activationEventId(),
                                Long.toString(intent.fencingToken())),
                        "COMMITTED"));
            } else {
                facts.add(fact(ProductChainRecoverySource.StableFactKind
                                .EFFECT_INTENT_RECEIPT_ERROR,
                        "EFFECT_INTENT", action.actionId(),
                        identity(action.actionId(), intentFailure),
                        intentFailure));
            }

            PersistenceResult<PersistedEffectResult> outcomeResult =
                    outcomes.findResult(toolCallId);
            PersistedEffectResult outcome = outcomeResult.successful()
                    ? outcomeResult.value().orElseThrow() : null;
            String inFlightStatus;
            if (outcome == null) {
                inFlightStatus = intent == null
                        ? ("NOT_FOUND".equals(intentFailure)
                        ? "NOT_DISPATCHED"
                        : "INTENT_AUTHORITY_" + intentFailure)
                        : "UNKNOWN";
            } else {
                var receipt = outcome.receipt();
                if (!receipt.toolCallId().equals(toolCallId)) {
                    throw new IllegalStateException(
                            "Effect receipt belongs to another action");
                }
                facts.add(fact(ProductChainRecoverySource.StableFactKind
                                .EFFECT_INTENT_RECEIPT_ERROR,
                        "RECEIPT", receipt.id().value(),
                        identity(receipt.id().value(), action.actionId(),
                                receipt.status().name(),
                                receipt.resultCode().orElse("NONE")),
                        receipt.status().name()));
                facts.add(fact(ProductChainRecoverySource.StableFactKind
                                .EFFECT_INTENT_RECEIPT_ERROR,
                        "ACTION_RECEIPT_PROGRESS_IDENTITY", action.actionId(),
                        ChainActionProgressIdentity.receipt(
                                action.actionSignatureSha256(), receipt,
                                candidateEvidence(request.taskId(),
                                        action.actionId())),
                        receipt.status().name()));
                if (receipt.status() != ReceiptStatus.SUCCESS) {
                    facts.add(fact(ProductChainRecoverySource.StableFactKind
                                    .EFFECT_INTENT_RECEIPT_ERROR,
                            "EFFECT_ERROR", receipt.id().value(),
                            identity(receipt.id().value(),
                                    receipt.resultCode().orElse(
                                            receipt.status().name())),
                            receipt.status().name()));
                }
                inFlightStatus = "RESOLVED_" + receipt.status().name();
            }
            facts.add(fact(ProductChainRecoverySource.StableFactKind
                            .IN_FLIGHT_ACTION,
                    "ACTION_EFFECT_STATE", action.actionId(),
                    identity(action.actionId(), action.idempotencyKey(),
                            inFlightStatus), inFlightStatus));
        }
    }

    private List<String> candidateEvidence(
            String taskId, String actionId) {
        var candidates = workflow.findWorkspaceCandidates(taskId).stream()
                .filter(value -> value.actionId().equals(actionId)).toList();
        if (candidates.size() > 1) {
            throw new IllegalStateException(
                    "Workspace Candidate progress authority is ambiguous");
        }
        return candidates.isEmpty() ? List.of() : List.of(
                candidates.get(0).candidateFingerprint(),
                candidates.get(0).diffDigest());
    }

    private void readFinalization(
            ProductChainRecoverySource.StableAuthorityRequest request,
            List<ProductChainRecoverySource.StableAuthorityFact> facts) {
        for (ChainPersistenceRecords.FinalizationReadinessRecord readiness
                : request.readiness()) {
            var inspection = Objects.requireNonNull(
                    finalization.inspect(readiness),
                    "finalization authority inspection");
            if (inspection instanceof io.paperagent.v2.chain.finalization
                    .ChainFinalizationAuthorityPort.TemporarilyUnavailable
                    unavailable) {
                facts.add(fact(ProductChainRecoverySource.StableFactKind
                                .VALIDATION_AND_PUBLISH,
                        "FINALIZATION_AUTHORITY", unavailable.authorityRef(),
                        identity(readiness.readinessId(),
                                unavailable.authorityRef()),
                        "TEMPORARILY_UNAVAILABLE"));
            } else {
                var available = (io.paperagent.v2.chain.finalization
                        .ChainFinalizationAuthorityPort.Available) inspection;
                if (!available.taskId().equals(request.taskId())
                        || !available.taskFrameId().equals(
                        readiness.taskFrameId())
                        || !available.planRevisionId().equals(
                        readiness.finalPlanRevisionId())) {
                    throw new IllegalStateException(
                            "finalization authority conflicts with readiness");
                }
                if (available.candidate() != null) {
                    var candidate = available.candidate();
                    facts.add(fact(ProductChainRecoverySource.StableFactKind
                                    .WORKSPACE_CANDIDATE,
                            "CANDIDATE_AUTHORITY", candidate.candidateKey(),
                            identity(candidate.candidateKey(),
                                    candidate.workspaceId(),
                                    Long.toString(candidate.artifactId()),
                                    candidate.fingerprint(),
                                    candidate.baseProjectVersion()),
                            "AVAILABLE"));
                }
                if (available.validation() != null) {
                    var validation = available.validation();
                    facts.add(fact(ProductChainRecoverySource.StableFactKind
                                    .VALIDATION_AND_PUBLISH,
                            "VALIDATION", validation.validationId(),
                            identity(validation.validationId(),
                                    Objects.toString(
                                            validation.candidateArtifactId(),
                                            "NONE"),
                                    Objects.toString(
                                            validation.candidateFingerprint(),
                                            "NONE"),
                                    validation.projectVersion(),
                                    validation.requestDigest(),
                                    validation.receiptDigest()),
                            validation.status().name()));
                }
                facts.add(fact(ProductChainRecoverySource.StableFactKind
                                .VALIDATION_AND_PUBLISH,
                        "PUBLISH_REQUIREMENT", readiness.readinessId(),
                        identity(readiness.readinessId(),
                                available.publishRequirement().name(),
                                available.publishRequirementDigest()),
                        available.publishRequirement().name()));
            }
            List<ChainPersistenceRecords.FinalizationCheckRecord> checks =
                    request.finalizationChecks().getOrDefault(
                            readiness.readinessId(), List.of());
            if (!checks.isEmpty()) {
                ChainPersistenceRecords.FinalizationCheckRecord latest = checks
                        .get(checks.size() - 1);
                publishes.find(new PublishAttemptQuery(
                                request.task(), readiness, latest))
                        .ifPresent(attempt -> facts.add(fact(
                                ProductChainRecoverySource.StableFactKind
                                        .VALIDATION_AND_PUBLISH,
                                "PUBLISH_ATTEMPT", attempt.authorityRef(),
                                attempt.identityDigest(),
                                attempt.status())));
            }
        }
    }

    private static String failureCode(PersistenceResult<?> result) {
        return result.failure().orElseThrow().code().name();
    }

    private static ProductChainRecoverySource.StableAuthorityFact fact(
            ProductChainRecoverySource.StableFactKind kind,
            String type, String ref, String digest, String status) {
        return new ProductChainRecoverySource.StableAuthorityFact(
                kind, type, ref, digest, status);
    }

    private static String identity(String... values) {
        return sha256(List.of(values));
    }

    private static String sha256(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(Objects.requireNonNull(value, "digest value")
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @FunctionalInterface
    public interface PublishAttemptLookup {
        Optional<PublishAttempt> find(PublishAttemptQuery query);
    }

    public record PublishAttemptQuery(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        public PublishAttemptQuery {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(readiness, "readiness");
            Objects.requireNonNull(check, "check");
        }
    }

    public record PublishAttempt(
            String authorityRef,
            String identityDigest,
            String status) {
        public PublishAttempt {
            required(authorityRef, "authorityRef");
            required(identityDigest, "identityDigest");
            required(status, "status");
        }
    }

    private record StableRead(
            List<ProductChainRecoverySource.StableAuthorityFact> facts,
            Optional<ProductChainRecoverySource.StepState> stepState,
            String digest) {
        StableRead {
            facts = List.copyOf(facts);
            Objects.requireNonNull(stepState, "stepState");
            required(digest, "digest");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
