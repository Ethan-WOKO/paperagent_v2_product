package io.paperagent.v2.chain.validation;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainValidationBundleRepository;
import io.paperagent.v2.chain.ChainValidationConclusion;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.RequirementDeclarationMode;
import io.paperagent.v2.contracts.TaskRequirements;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Sole mechanical writer of a plan-level closure over typed ValidationSets. */
public final class ChainValidationBundleRuntime {
    private final ChainValidationBundleRepository bundles;

    public ChainValidationBundleRuntime(
            ChainValidationBundleRepository bundles) {
        this.bundles = Objects.requireNonNull(bundles, "bundles");
    }

    public Outcome commit(CommitCommand command) {
        Objects.requireNonNull(command, "command");
        Map<String, ValidationRequirement> requirements = requirements(
                command.requirements());
        Map<String, StepBinding> bindings = bindings(
                command.steps(), requirements, command.scope().finalStepId());
        if (requirements.isEmpty()) {
            if (!command.sources().isEmpty()) {
                throw invalid("Validation bundle has sources without requirements");
            }
            return new NotRequired();
        }
        Map<String, FormalSource> sources = new HashMap<>();
        for (FormalSource source : command.sources()) {
            String stepId = source.validation().stepId();
            if (sources.put(stepId, source) != null) {
                throw invalid("Validation bundle has more than one set per Step");
            }
        }
        Set<String> requiredSteps = new HashSet<>();
        bindings.values().forEach(value -> requiredSteps.add(value.stepId()));
        if (!sources.keySet().equals(requiredSteps)) {
            throw invalid("Validation bundle sets must exactly cover required Steps");
        }

        List<VerifiedSource> verified = sources.values().stream()
                .map(source -> verifySource(command.scope(), source,
                        requirements, bindings))
                .sorted(Comparator.comparing(VerifiedSource::stepId)
                        .thenComparing(value -> value.validation()
                                .validationId()))
                .toList();
        var aggregate = ChainValidationBundleIdentity.aggregate(
                bundleScope(command.scope()), verified.stream().map(value ->
                        new ChainValidationBundleIdentity.Member(
                                value.stepId(),
                                value.validation().validationId(),
                                value.validation().requestDigest(),
                                value.validation().receiptSetDigest(),
                                value.validation().conclusionDigest()))
                        .toList());
        String requestDigest = aggregate.requestDigest();
        String receiptSetDigest = aggregate.receiptSetDigest();
        String conclusionDigest = aggregate.conclusionDigest();
        String bundleId = ChainValidationBundleIdentity.bundleId(
                bundleScope(command.scope()), aggregate);
        String eventId = "validation-bundle.event." + sha256(bundleId);
        var bundle = new ChainPersistenceRecords.ValidationBundleRecord(
                bundleId, command.scope().taskId(), eventId,
                command.scope().taskFrameId(), command.scope().planId(),
                command.scope().planRevisionId(),
                command.scope().planRevisionNumber(),
                command.scope().instructionId(),
                command.scope().finalStepId(), requestDigest,
                receiptSetDigest, conclusionDigest,
                ChainValidationConclusion.PASSED,
                command.scope().idempotencyKey(),
                command.scope().createdAt());
        List<ChainPersistenceRecords.ValidationBundleSetRecord> items =
                verified.stream().map(value ->
                        new ChainPersistenceRecords.ValidationBundleSetRecord(
                                bundleId, command.scope().taskId(),
                                value.stepId(),
                                value.validation().activationEventId(),
                                value.validation().validationId(),
                                value.validation().requestDigest(),
                                value.validation().receiptSetDigest(),
                                value.validation().conclusionDigest()))
                        .toList();
        String sourceIdentity = ChainValidationBundleIdentity
                .eventSourceIdentity(bundleId, aggregate);
        var appended = bundles.appendBundle(
                new ChainPersistenceRecords.AuthoritativeFact<>(
                        new ChainPersistenceRecords.AuthorityEventRequest(
                                eventId, command.scope().taskId(),
                                "VALIDATION_BUNDLE", null, sourceIdentity,
                                command.scope().createdAt()), bundle), items);
        if (!sameBundle(bundle, appended.bundle())
                || !items.equals(appended.sets())) {
            throw new IllegalStateException(
                    "Validation bundle replay changed immutable identity");
        }
        return new Committed(appended.bundle(), appended.sets(),
                appended.replayed());
    }

    private static Map<String, ValidationRequirement> requirements(
            TaskRequirements frozen) {
        Objects.requireNonNull(frozen, "requirements");
        if (frozen.declarationMode() != RequirementDeclarationMode.EXPLICIT) {
            throw invalid("Validation bundle requires explicit TaskRequirements");
        }
        Map<String, ValidationRequirement> result = new LinkedHashMap<>();
        for (ValidationRequirement requirement
                : frozen.validationRequirements()) {
            if (result.put(requirement.requirementId(), requirement) != null) {
                throw invalid("Frozen Validation requirement IDs are not unique");
            }
        }
        return result;
    }

    private static Map<String, StepBinding> bindings(
            List<PlanStep> steps,
            Map<String, ValidationRequirement> requirements,
            String finalStepId) {
        if (steps.isEmpty()
                || !steps.get(steps.size() - 1).id().value().equals(
                finalStepId)) {
            throw invalid("Validation bundle final Step is not frozen Plan tail");
        }
        Map<String, StepBinding> result = new HashMap<>();
        Set<String> stepIds = new HashSet<>();
        for (PlanStep step : steps) {
            if (!stepIds.add(step.id().value())) {
                throw invalid("Frozen Plan Step IDs are not unique");
            }
            for (String requirementId : step.validationRequirementIds()) {
                ValidationRequirement requirement = requirements.get(
                        requirementId);
                if (requirement == null
                        || !step.completionCriteria().contains(
                        requirement.completionCondition())
                        || result.put(requirementId, new StepBinding(
                        step.id().value(), requirement)) != null) {
                    throw invalid("Frozen Validation binding is invalid");
                }
            }
        }
        if (!result.keySet().equals(requirements.keySet())) {
            throw invalid("Frozen Validation bindings are incomplete");
        }
        return result;
    }

    private static VerifiedSource verifySource(
            Scope scope,
            FormalSource source,
            Map<String, ValidationRequirement> requirements,
            Map<String, StepBinding> bindings) {
        var set = source.validation();
        var validationEvent = source.validationEvent();
        var result = source.stepResult();
        if (!scope.taskId().equals(set.taskId())
                || !scope.taskFrameId().equals(set.taskFrameId())
                || !scope.planId().equals(set.planId())
                || !scope.planRevisionId().equals(set.planRevisionId())
                || scope.planRevisionNumber() != set.planRevisionNumber()
                || set.conclusion() != ChainValidationConclusion.PASSED) {
            throw invalid("ValidationSet does not match frozen bundle scope");
        }
        String expectedEventSource = sha256(set.validationId() + "\0"
                + set.requestDigest() + "\0" + set.receiptSetDigest() + "\0"
                + set.conclusionDigest());
        if (!set.eventId().equals(validationEvent.eventId())
                || !set.taskId().equals(validationEvent.taskId())
                || !"VALIDATION".equals(validationEvent.eventType())
                || validationEvent.transitionId() != null
                || !expectedEventSource.equals(
                validationEvent.sourceIdentitySha256())) {
            throw invalid("ValidationSet does not bind its AuthorityEvent");
        }
        if (!scope.taskId().equals(result.taskId())
                || !scope.instructionId().equals(result.instructionId())
                || !scope.taskFrameId().equals(result.taskFrameId())
                || !scope.planId().equals(result.planId())
                || !scope.planRevisionId().equals(result.planRevisionId())
                || scope.planRevisionNumber() != result.planRevisionNumber()
                || !set.stepId().equals(result.stepId())
                || !set.activationEventId().equals(
                result.activationEventId())
                || !set.validationId().equals(result.validationId())
                || !set.requestDigest().equals(
                result.validationRequestDigest())
                || !set.receiptSetDigest().equals(
                result.validationReceiptDigest())) {
            throw invalid("CandidateStepResult does not bind ValidationSet");
        }
        List<String> receiptRefs = distinctSorted(source.receiptRefs());
        if (!canonicalArray(receiptRefs).equals(result.receiptRefs().json())
                || !sha256(canonicalArray(receiptRefs)).equals(
                result.receiptRefs().sha256())) {
            throw invalid("Decoded receiptRefs do not match CandidateStepResult");
        }

        Map<String, ChainPersistenceRecords.CandidateValidationItemRecord>
                candidates = uniqueCandidates(source.candidateItems());
        Map<String, ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                actions = uniqueActions(source.actionReceiptItems());
        Set<String> overlappingIds = new HashSet<>(candidates.keySet());
        overlappingIds.retainAll(actions.keySet());
        if (!overlappingIds.isEmpty()) {
            throw invalid("Validation requirement has more than one typed item");
        }
        Set<String> actualIds = new HashSet<>(candidates.keySet());
        actualIds.addAll(actions.keySet());
        Set<String> expectedIds = new HashSet<>();
        bindings.forEach((id, binding) -> {
            if (binding.stepId().equals(set.stepId())) expectedIds.add(id);
        });
        if (!actualIds.equals(expectedIds)) {
            throw invalid("ValidationSet items do not exactly cover its Step");
        }
        Set<String> itemReceiptIds = new HashSet<>();
        List<SetRequestIdentity> requestIdentities = new ArrayList<>();
        List<ChainValidationIdentity.ReceiptIdentity> receiptIdentities =
                new ArrayList<>();
        List<ChainValidationIdentity.ConclusionIdentity> conclusionIdentities =
                new ArrayList<>();
        for (String id : expectedIds.stream().sorted().toList()) {
            ValidationRequirement requirement = requirements.get(id);
            String digest = requirementDigest(requirement);
            if (requirement.subject() == ValidationSubject.CANDIDATE) {
                var item = candidates.get(id);
                if (item == null) throw invalid(
                        "Candidate requirement has wrong typed item");
                verifyCommon(set, id, digest, item.validationId(),
                        item.taskId(), item.requirementDigest(),
                        item.conclusion());
                verifyCandidateBinding(scope, source.workspaceCandidate(),
                        result, item);
                itemReceiptIds.add(item.receiptId());
                requestIdentities.add(new SetRequestIdentity(id, digest,
                        requirement.subject(), ChainValidationIdentity
                        .candidateSubject(item)));
                receiptIdentities.add(new ChainValidationIdentity.ReceiptIdentity(id,
                        item.receiptId(), item.receiptPayloadSha256()));
            } else {
                var item = actions.get(id);
                if (item == null) throw invalid(
                        "Action receipt requirement has wrong typed item");
                verifyCommon(set, id, digest, item.validationId(),
                        item.taskId(), item.requirementDigest(),
                        item.conclusion());
                itemReceiptIds.add(item.receiptId());
                requestIdentities.add(new SetRequestIdentity(id, digest,
                        requirement.subject(), ChainValidationIdentity
                        .actionSubject(item)));
                receiptIdentities.add(new ChainValidationIdentity.ReceiptIdentity(id,
                        item.receiptId(), item.receiptPayloadSha256()));
            }
            conclusionIdentities.add(new ChainValidationIdentity.ConclusionIdentity(id,
                    ChainValidationConclusion.PASSED));
        }
        if (!new HashSet<>(receiptRefs).containsAll(itemReceiptIds)) {
            throw invalid("CandidateStepResult receiptRefs omit Validation items");
        }
        List<ChainValidationIdentity.RequestIdentity> canonicalRequests =
                requestIdentities.stream().map(value ->
                        new ChainValidationIdentity.RequestIdentity(
                                value.requirementId(),
                                value.requirementDigest(), value.subject(),
                                value.subjectIdentity())).toList();
        var setScope = new ChainValidationIdentity.SetScope(
                set.taskId(), set.taskFrameId(), set.planId(),
                set.planRevisionId(), set.planRevisionNumber(), set.stepId(),
                set.activationEventId());
        String expectedRequest = ChainValidationIdentity.requestDigest(
                setScope, canonicalRequests);
        String expectedReceipts = ChainValidationIdentity.receiptSetDigest(
                receiptIdentities);
        String expectedConclusion = ChainValidationIdentity.conclusionDigest(
                conclusionIdentities);
        String expectedId = "validation." + sha256(set.taskId() + "\0"
                + set.planRevisionId() + "\0" + set.stepId() + "\0"
                + set.activationEventId() + "\0" + expectedRequest + "\0"
                + expectedReceipts);
        if (!expectedId.equals(set.validationId())
                || !expectedRequest.equals(set.requestDigest())
                || !expectedReceipts.equals(set.receiptSetDigest())
                || !expectedConclusion.equals(set.conclusionDigest())) {
            throw invalid("ValidationSet digest identity is invalid");
        }
        return new VerifiedSource(set.stepId(), set);
    }

    private static void verifyCandidateBinding(
            Scope scope,
            ChainPersistenceRecords.WorkspaceCandidateRecord workspace,
            ChainPersistenceRecords.CandidateStepResultRecord result,
            ChainPersistenceRecords.CandidateValidationItemRecord item) {
        if (workspace == null) {
            throw invalid("Candidate Validation requires WorkspaceCandidate");
        }
        if (!scope.taskId().equals(workspace.taskId())
                || !item.candidateActionId().equals(workspace.actionId())
                || !item.workspaceCandidateId().equals(
                workspace.workspaceCandidateId())
                || !item.workspaceId().equals(workspace.workspaceId())
                || item.artifactId() != workspace.artifactId()
                || !item.candidateFingerprint().equals(
                workspace.candidateFingerprint())
                || !item.baseProjectVersion().equals(
                workspace.baseProjectVersion())
                || !Objects.equals(result.artifactId(), workspace.artifactId())
                || !Objects.equals(result.candidateFingerprint(),
                workspace.candidateFingerprint())
                || !Objects.equals(result.diffDigest(), workspace.diffDigest())
                || !result.versionFenceSha256().equals(
                workspace.versionFenceSha256())) {
            throw invalid("Candidate sources do not bind one exact WorkspaceCandidate");
        }
    }

    private static void verifyCommon(
            ChainPersistenceRecords.ValidationSetRecord set,
            String requirementId,
            String requirementDigest,
            String itemValidationId,
            String itemTaskId,
            String itemRequirementDigest,
            ChainValidationConclusion conclusion) {
        if (!set.validationId().equals(itemValidationId)
                || !set.taskId().equals(itemTaskId)
                || !requirementDigest.equals(itemRequirementDigest)
                || conclusion != ChainValidationConclusion.PASSED) {
            throw invalid("Validation item identity or conclusion is invalid");
        }
    }

    private static Map<String, ChainPersistenceRecords
            .CandidateValidationItemRecord> uniqueCandidates(
            List<ChainPersistenceRecords.CandidateValidationItemRecord> values) {
        Map<String, ChainPersistenceRecords.CandidateValidationItemRecord>
                result = new HashMap<>();
        for (var value : values) if (result.put(
                value.requirementId(), value) != null) {
            throw invalid("Candidate Validation item IDs are not unique");
        }
        return result;
    }

    private static Map<String, ChainPersistenceRecords
            .ActionReceiptValidationItemRecord> uniqueActions(
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    values) {
        Map<String, ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                result = new HashMap<>();
        for (var value : values) if (result.put(
                value.requirementId(), value) != null) {
            throw invalid("Action Validation item IDs are not unique");
        }
        return result;
    }

    private static String requirementDigest(ValidationRequirement value) {
        return ChainValidationIdentity.requirementDigest(value);
    }

    private static ChainValidationBundleIdentity.Scope bundleScope(
            Scope value) {
        return new ChainValidationBundleIdentity.Scope(
                value.taskId(), value.taskFrameId(), value.planId(),
                value.planRevisionId(), value.planRevisionNumber(),
                value.instructionId(), value.finalStepId());
    }

    private static List<String> distinctSorted(List<String> values) {
        if (new HashSet<>(values).size() != values.size()) {
            throw invalid("Decoded receiptRefs are not unique");
        }
        return values.stream().sorted().toList();
    }

    private static String canonicalArray(List<String> values) {
        return values.stream().map(value -> "\"" + value
                        .replace("\\", "\\\\").replace("\"", "\\\"")
                        + "\"")
                .reduce((left, right) -> left + "," + right)
                .map(value -> "[" + value + "]").orElse("[]");
    }

    private static boolean sameBundle(
            ChainPersistenceRecords.ValidationBundleRecord left,
            ChainPersistenceRecords.ValidationBundleRecord right) {
        return left.validationBundleId().equals(right.validationBundleId())
                && left.taskId().equals(right.taskId())
                && left.eventId().equals(right.eventId())
                && left.taskFrameId().equals(right.taskFrameId())
                && left.planId().equals(right.planId())
                && left.planRevisionId().equals(right.planRevisionId())
                && left.planRevisionNumber() == right.planRevisionNumber()
                && left.instructionId().equals(right.instructionId())
                && left.finalStepId().equals(right.finalStepId())
                && left.requestDigest().equals(right.requestDigest())
                && left.receiptSetDigest().equals(right.receiptSetDigest())
                && left.conclusionDigest().equals(right.conclusionDigest())
                && left.conclusion() == right.conclusion()
                && left.idempotencyKey().equals(right.idempotencyKey());
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Scope(
            String taskId, String taskFrameId, String planId,
            String planRevisionId, long planRevisionNumber,
            String instructionId, String finalStepId,
            String idempotencyKey, Instant createdAt) {
        public Scope {
            required(taskId, "taskId");
            required(taskFrameId, "taskFrameId");
            required(planId, "planId");
            required(planRevisionId, "planRevisionId");
            if (planRevisionNumber < 1) throw invalid(
                    "planRevisionNumber must be positive");
            required(instructionId, "instructionId");
            required(finalStepId, "finalStepId");
            required(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record FormalSource(
            ChainPersistenceRecords.ValidationSetRecord validation,
            ChainPersistenceRecords.AuthorityEventRecord validationEvent,
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    candidateItems,
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    actionReceiptItems,
            ChainPersistenceRecords.CandidateStepResultRecord stepResult,
            ChainPersistenceRecords.WorkspaceCandidateRecord workspaceCandidate,
            List<String> receiptRefs) {
        public FormalSource {
            Objects.requireNonNull(validation, "validation");
            Objects.requireNonNull(validationEvent, "validationEvent");
            candidateItems = List.copyOf(candidateItems);
            actionReceiptItems = List.copyOf(actionReceiptItems);
            Objects.requireNonNull(stepResult, "stepResult");
            receiptRefs = List.copyOf(receiptRefs);
        }
    }

    public record CommitCommand(
            Scope scope, TaskRequirements requirements,
            List<PlanStep> steps, List<FormalSource> sources) {
        public CommitCommand {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(requirements, "requirements");
            steps = List.copyOf(steps);
            sources = List.copyOf(sources);
        }
    }

    public sealed interface Outcome permits NotRequired, Committed {
    }

    public record NotRequired() implements Outcome {
    }

    public record Committed(
            ChainPersistenceRecords.ValidationBundleRecord bundle,
            List<ChainPersistenceRecords.ValidationBundleSetRecord> sets,
            boolean replayed) implements Outcome {
        public Committed {
            sets = List.copyOf(sets);
        }
    }

    private record StepBinding(
            String stepId, ValidationRequirement requirement) {
    }

    private record VerifiedSource(
            String stepId,
            ChainPersistenceRecords.ValidationSetRecord validation) {
    }

    private record SetRequestIdentity(
            String requirementId, String requirementDigest,
            ValidationSubject subject, String subjectIdentity) {
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        return value;
    }
}
