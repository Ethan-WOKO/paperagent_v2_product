package com.yanban.api.agent.v2.chain.recovery;

import com.yanban.api.agent.v2.chain.publish.ProductChainPublishAuthoritySource;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapCodec;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanRevisionAuthoritySource;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ChainValidationBundleRepository;
import io.paperagent.v2.chain.ChainValidationRepository;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Exact readers shared by the transition-specific recovery verifiers. */
final class ProductChainRecoveryAuthorityLookup {
    private final ChainFoundationRepository foundations;
    private final ChainContextRepository contexts;
    private final ChainWorkflowRepository workflow;
    private final ChainFinalizationRepository finalization;
    private final ChainModelRepository models;
    private final ProductChainStepAuthorityAdapter steps;
    private final ProductPlanBootstrapRepositoryAdapter bootstraps;
    private final ProductPlanBootstrapCodec bootstrapCodec;
    private final ProductPlanRevisionAuthoritySource revisionAuthorities;
    private final ProductChainPublishAuthoritySource publishes;
    private final ChainValidationBundleRepository validationBundles;
    private final ChainValidationRepository validations;

    ProductChainRecoveryAuthorityLookup(
            ChainFoundationRepository foundations,
            ChainContextRepository contexts,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization,
            ChainModelRepository models,
            ProductChainStepAuthorityAdapter steps,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductPlanBootstrapCodec bootstrapCodec,
            ProductPlanRevisionAuthoritySource revisionAuthorities,
            ProductChainPublishAuthoritySource publishes,
            ChainValidationBundleRepository validationBundles,
            ChainValidationRepository validations) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.models = Objects.requireNonNull(models, "models");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.bootstraps = Objects.requireNonNull(bootstraps, "bootstraps");
        this.bootstrapCodec = Objects.requireNonNull(
                bootstrapCodec, "bootstrapCodec");
        this.revisionAuthorities = Objects.requireNonNull(
                revisionAuthorities, "revisionAuthorities");
        this.publishes = Objects.requireNonNull(publishes, "publishes");
        this.validationBundles = Objects.requireNonNull(
                validationBundles, "validationBundles");
        this.validations = Objects.requireNonNull(validations, "validations");
    }

    void verifyStoredTransition(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.TransitionStageRecord stage) {
        exact(stage.taskId().equals(transition.taskId())
                        && stage.transitionId().equals(
                        transition.transitionId()),
                "transition stage identity drift");
        try {
            stage.validateFor(transition.transitionType());
        } catch (IllegalArgumentException invalid) {
            throw invalid("transition type-stage mismatch");
        }
        var stored = workflow.findTransition(transition.transitionId())
                .orElseThrow(() -> invalid("transition is missing"));
        exact(stored.equals(transition), "transition authority drift");
        foundations.findTask(transition.taskId())
                .filter(task -> task.taskId().equals(transition.taskId()))
                .orElseThrow(() -> invalid("task authority missing"));
    }

    List<ChainStepAuthorityPort.StepEvent> allStepEvents(String taskId) {
        List<ChainStepAuthorityPort.StepEvent> result = new ArrayList<>();
        Set<String> revisions = new HashSet<>();
        for (var binding : workflow.findPlanBindings(taskId)) {
            if (binding.taskId().equals(taskId)
                    && revisions.add(binding.planRevisionId())) {
                result.addAll(steps.findStepEvents(
                        taskId, binding.planRevisionId()));
            }
        }
        return List.copyOf(result);
    }

    List<ChainStepAuthorityPort.StepEvent> transitionStepEvents(
            ChainPersistenceRecords.TransitionRecord transition) {
        return allStepEvents(transition.taskId()).stream()
                .filter(value -> value.command().transitionId().equals(
                        transition.transitionId())
                        && value.command().sourceDecisionId().equals(
                        transition.sourceDecisionId()))
                .toList();
    }

    ChainFoundationRepository foundations() {
        return foundations;
    }

    ChainContextRepository contexts() {
        return contexts;
    }

    ChainWorkflowRepository workflow() {
        return workflow;
    }

    ChainFinalizationRepository finalization() {
        return finalization;
    }

    ChainModelRepository models() {
        return models;
    }

    ChainStepAuthorityPort steps() {
        return steps;
    }

    java.util.Optional<io.paperagent.v2.contracts.PlanRevision>
            planRevision(String taskId, String planRevisionId) {
        return steps.findPlanRevision(taskId, planRevisionId);
    }

    ProductPlanBootstrapRepositoryAdapter bootstraps() {
        return bootstraps;
    }

    ProductPlanBootstrapCodec bootstrapCodec() {
        return bootstrapCodec;
    }

    ProductPlanRevisionAuthoritySource revisionAuthorities() {
        return revisionAuthorities;
    }

    ProductChainPublishAuthoritySource publishes() {
        return publishes;
    }

    ChainValidationBundleRepository validationBundles() {
        return validationBundles;
    }

    ChainValidationRepository validations() {
        return validations;
    }

    static ChainCompositeTransitionRuntime.AuthorityVerification verified() {
        return ChainCompositeTransitionRuntime.AuthorityVerification.verified();
    }

    static ChainCompositeTransitionRuntime.AuthorityVerification verifiedEmpty() {
        return ChainCompositeTransitionRuntime.AuthorityVerification
                .verifiedEmpty();
    }

    static ChainCompositeTransitionRuntime.AuthorityVerification verifiedNone(
            ChainPersistenceRecords.TransitionStageRecord stage) {
        exact(stage.predecessorAuthorityType() == null
                        && stage.predecessorAuthorityRef() == null
                        && stage.successorAuthorityType() == null
                        && stage.successorAuthorityRef() == null,
                "authority-free stage carries a reference");
        return verified();
    }

    static void requireSuccessor(
            ChainPersistenceRecords.TransitionStageRecord stage,
            Set<String> allowed) {
        exact(stage.predecessorAuthorityType() == null
                        && stage.predecessorAuthorityRef() == null
                        && allowed.contains(stage.successorAuthorityType())
                        && stage.successorAuthorityRef() != null,
                "successor authority type is invalid");
    }

    static void optionalSuccessor(
            ChainPersistenceRecords.TransitionStageRecord stage, String type) {
        boolean empty = stage.predecessorAuthorityType() == null
                && stage.predecessorAuthorityRef() == null
                && stage.successorAuthorityType() == null
                && stage.successorAuthorityRef() == null;
        boolean successor = stage.predecessorAuthorityType() == null
                && stage.predecessorAuthorityRef() == null
                && type.equals(stage.successorAuthorityType())
                && stage.successorAuthorityRef() != null;
        exact(empty || successor,
                "optional successor authority type is invalid");
    }

    static void requirePredecessor(
            ChainPersistenceRecords.TransitionStageRecord stage, String type) {
        exact(type.equals(stage.predecessorAuthorityType())
                        && stage.predecessorAuthorityRef() != null
                        && stage.successorAuthorityType() == null
                        && stage.successorAuthorityRef() == null,
                "predecessor authority type is invalid");
    }

    static String requireEither(
            ChainPersistenceRecords.TransitionStageRecord stage, String type) {
        boolean predecessor = type.equals(stage.predecessorAuthorityType())
                && stage.predecessorAuthorityRef() != null
                && stage.successorAuthorityType() == null
                && stage.successorAuthorityRef() == null;
        boolean successor = type.equals(stage.successorAuthorityType())
                && stage.successorAuthorityRef() != null
                && stage.predecessorAuthorityType() == null
                && stage.predecessorAuthorityRef() == null;
        exact(predecessor || successor,
                "either-direction authority type is invalid");
        return predecessor ? stage.predecessorAuthorityRef()
                : stage.successorAuthorityRef();
    }

    static void canonical(
            ChainPersistenceRecords.CanonicalJson value, String label) {
        exact(value != null && value.sha256().equals(sha256(value.json())),
                label + " digest drift");
    }

    static <T> T one(
            List<T> values, Predicate<T> predicate, String label) {
        List<T> matches = values.stream().filter(predicate).toList();
        if (matches.size() != 1) {
            throw invalid(label + " missing or ambiguous");
        }
        return matches.get(0);
    }

    static void exact(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    static IllegalStateException invalid(String message) {
        return new IllegalStateException(
                "CHAIN_RECOVERY_STAGE_AUTHORITY_INVALID: " + message);
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String canonicalStringArray(java.util.Collection<String> values) {
        return values.stream().sorted().map(value -> {
            StringBuilder escaped = new StringBuilder("\"");
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '\"' -> escaped.append("\\\"");
                    case '\\' -> escaped.append("\\\\");
                    case '\b' -> escaped.append("\\b");
                    case '\f' -> escaped.append("\\f");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            escaped.append(String.format(
                                    java.util.Locale.ROOT,
                                    "\\u%04x", (int) character));
                        } else {
                            escaped.append(character);
                        }
                    }
                }
            }
            return escaped.append('\"').toString();
        }).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
