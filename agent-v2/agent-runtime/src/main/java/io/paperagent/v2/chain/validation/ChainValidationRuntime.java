package io.paperagent.v2.chain.validation;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainValidationConclusion;
import io.paperagent.v2.chain.ChainValidationRepository;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Sole mechanical writer of typed, Receipt-referencing Validation sets. */
public final class ChainValidationRuntime {
    private final ChainValidationAuthorityPort authority;
    private final ChainValidationRepository validations;

    public ChainValidationRuntime(
            ChainValidationAuthorityPort authority,
            ChainValidationRepository validations) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.validations = Objects.requireNonNull(validations, "validations");
    }

    public CommitResult commit(CommitCommand command) {
        Objects.requireNonNull(command, "command");
        Map<String, ValidationRequirement> requirements = new HashMap<>();
        for (ValidationRequirement requirement : command.requirements()) {
            if (requirements.put(requirement.requirementId(), requirement)
                    != null) {
                throw new IllegalArgumentException(
                        "Validation requirementId must be unique");
            }
        }
        if (requirements.isEmpty()) {
            throw new IllegalArgumentException(
                    "Required Validation set cannot be empty");
        }
        Map<String, ProposalFields.ValidationSource> sources = new HashMap<>();
        for (ProposalFields.ValidationSource source : command.sources()) {
            if (sources.put(source.requirementId(), source) != null) {
                throw new IllegalArgumentException(
                        "Validation source requirementId must be unique");
            }
        }
        if (!sources.keySet().equals(requirements.keySet())) {
            throw new IllegalArgumentException(
                    "Validation sources must exactly cover frozen requirements");
        }

        Scope scope = command.scope();
        List<ChainPersistenceRecords.CandidateValidationItemRecord>
                candidateItems = new ArrayList<>();
        List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                actionItems = new ArrayList<>();
        List<ChainValidationIdentity.RequestIdentity> requestIdentities =
                new ArrayList<>();
        List<ChainValidationIdentity.ReceiptIdentity> receiptIdentities =
                new ArrayList<>();
        List<ChainValidationIdentity.ConclusionIdentity> conclusionIdentities =
                new ArrayList<>();
        String provisionalId = "validation.pending";
        for (ValidationRequirement requirement : requirements.values().stream()
                .sorted(Comparator.comparing(
                        ValidationRequirement::requirementId)).toList()) {
            String requirementDigest = ChainValidationIdentity
                    .requirementDigest(requirement);
            String receiptRef = sources.get(
                    requirement.requirementId()).receiptRef();
            if (requirement.subject() == ValidationSubject.CANDIDATE) {
                var verified = authority.verifyCandidate(
                        scope, requirement, receiptRef);
                requireReceiptRef(receiptRef, verified.receiptId());
                candidateItems.add(new ChainPersistenceRecords
                        .CandidateValidationItemRecord(
                        provisionalId, requirement.requirementId(),
                        scope.taskId(), requirementDigest,
                        verified.candidateActionId(),
                        verified.validationActionId(), verified.receiptId(),
                        verified.receiptPayloadSha256(),
                        verified.actionSignatureSha256(),
                        verified.workspaceCandidateId(), verified.workspaceId(),
                        verified.artifactId(), verified.candidateFingerprint(),
                        verified.baseProjectVersion(),
                        ChainValidationConclusion.PASSED));
                requestIdentities.add(new ChainValidationIdentity.RequestIdentity(
                        requirement.requirementId(), requirementDigest,
                        requirement.subject(), ChainValidationIdentity
                        .candidateSubject(verified)));
                receiptIdentities.add(new ChainValidationIdentity.ReceiptIdentity(
                        requirement.requirementId(), verified.receiptId(),
                        verified.receiptPayloadSha256()));
            } else {
                var verified = authority.verifyActionReceipt(
                        scope, requirement, receiptRef);
                requireReceiptRef(receiptRef, verified.receiptId());
                actionItems.add(new ChainPersistenceRecords
                        .ActionReceiptValidationItemRecord(
                        provisionalId, requirement.requirementId(),
                        scope.taskId(), requirementDigest,
                        verified.actionId(), verified.receiptId(),
                        verified.receiptPayloadSha256(),
                        verified.actionSignatureSha256(),
                        ChainValidationConclusion.PASSED));
                requestIdentities.add(new ChainValidationIdentity.RequestIdentity(
                        requirement.requirementId(), requirementDigest,
                        requirement.subject(), ChainValidationIdentity
                        .actionSubject(verified)));
                receiptIdentities.add(new ChainValidationIdentity.ReceiptIdentity(
                        requirement.requirementId(), verified.receiptId(),
                        verified.receiptPayloadSha256()));
            }
            conclusionIdentities.add(new ChainValidationIdentity.ConclusionIdentity(
                    requirement.requirementId(),
                    ChainValidationConclusion.PASSED));
        }

        var identityScope = new ChainValidationIdentity.SetScope(
                scope.taskId(), scope.taskFrameId(), scope.planId(),
                scope.planRevisionId(), scope.planRevisionNumber(),
                scope.stepId(), scope.activationEventId());
        String requestDigest = ChainValidationIdentity.requestDigest(
                identityScope, requestIdentities);
        String receiptSetDigest = ChainValidationIdentity.receiptSetDigest(
                receiptIdentities);
        String conclusionDigest = ChainValidationIdentity.conclusionDigest(
                conclusionIdentities);
        String validationId = "validation." + ChainValidationIdentity.sha256(
                scope.taskId() + "\0" + scope.planRevisionId() + "\0"
                        + scope.stepId() + "\0" + scope.activationEventId()
                        + "\0" + requestDigest + "\0" + receiptSetDigest);
        candidateItems = candidateItems.stream().map(item ->
                new ChainPersistenceRecords.CandidateValidationItemRecord(
                        validationId, item.requirementId(), item.taskId(),
                        item.requirementDigest(), item.candidateActionId(),
                        item.validationActionId(), item.receiptId(),
                        item.receiptPayloadSha256(),
                        item.actionSignatureSha256(),
                        item.workspaceCandidateId(), item.workspaceId(),
                        item.artifactId(), item.candidateFingerprint(),
                        item.baseProjectVersion(), item.conclusion())).toList();
        actionItems = actionItems.stream().map(item ->
                new ChainPersistenceRecords.ActionReceiptValidationItemRecord(
                        validationId, item.requirementId(), item.taskId(),
                        item.requirementDigest(), item.actionId(),
                        item.receiptId(), item.receiptPayloadSha256(),
                        item.actionSignatureSha256(), item.conclusion())).toList();
        String eventId = "validation.event."
                + ChainValidationIdentity.sha256(validationId);
        var set = new ChainPersistenceRecords.ValidationSetRecord(
                validationId, scope.taskId(), eventId, scope.taskFrameId(),
                scope.planId(), scope.planRevisionId(),
                scope.planRevisionNumber(), scope.stepId(),
                scope.activationEventId(), requestDigest, receiptSetDigest,
                conclusionDigest, ChainValidationConclusion.PASSED,
                scope.idempotencyKey(), scope.createdAt());
        String sourceIdentity = ChainValidationIdentity.sha256(
                validationId + "\0" + requestDigest
                + "\0" + receiptSetDigest + "\0" + conclusionDigest);
        var appended = validations.appendValidation(
                new ChainPersistenceRecords.AuthoritativeFact<>(
                        new ChainPersistenceRecords.AuthorityEventRequest(
                                eventId, scope.taskId(), "VALIDATION", null,
                                sourceIdentity, scope.createdAt()), set),
                candidateItems, actionItems);
        if (!sameSet(set, appended.validation())
                || !candidateItems.equals(appended.candidateItems())
                || !actionItems.equals(appended.actionReceiptItems())) {
            throw new IllegalStateException(
                    "Validation append/replay changed immutable identity");
        }
        return new CommitResult(appended.validation(),
                appended.candidateItems(), appended.actionReceiptItems(),
                appended.replayed());
    }

    public static String receiptSetDigest(
            List<ReceiptIdentity> identities) {
        return ChainValidationIdentity.receiptSetDigest(identities.stream()
                .map(value -> new ChainValidationIdentity.ReceiptIdentity(
                        value.requirementId(), value.receiptId(),
                        value.originalPayloadSha256()))
                .toList());
    }

    private static void requireReceiptRef(String requested, String verified) {
        if (!Objects.equals(requested, verified)) {
            throw new IllegalStateException(
                    "Validation source does not bind verified Receipt");
        }
    }

    private static boolean sameSet(
            ChainPersistenceRecords.ValidationSetRecord left,
            ChainPersistenceRecords.ValidationSetRecord right) {
        return left.validationId().equals(right.validationId())
                && left.taskId().equals(right.taskId())
                && left.eventId().equals(right.eventId())
                && left.taskFrameId().equals(right.taskFrameId())
                && left.planId().equals(right.planId())
                && left.planRevisionId().equals(right.planRevisionId())
                && left.planRevisionNumber() == right.planRevisionNumber()
                && left.stepId().equals(right.stepId())
                && left.activationEventId().equals(right.activationEventId())
                && left.requestDigest().equals(right.requestDigest())
                && left.receiptSetDigest().equals(right.receiptSetDigest())
                && left.conclusionDigest().equals(right.conclusionDigest())
                && left.conclusion() == right.conclusion()
                && left.idempotencyKey().equals(right.idempotencyKey());
    }

    public record Scope(
            String taskId, String taskFrameId, String planId,
            String planRevisionId, long planRevisionNumber, String stepId,
            String activationEventId, String idempotencyKey,
            Instant createdAt) {
        public Scope {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(taskFrameId, "taskFrameId");
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(planRevisionId, "planRevisionId");
            if (planRevisionNumber < 1) {
                throw new IllegalArgumentException(
                        "planRevisionNumber must be positive");
            }
            Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(activationEventId, "activationEventId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record CommitCommand(
            Scope scope,
            List<ValidationRequirement> requirements,
            List<ProposalFields.ValidationSource> sources) {
        public CommitCommand {
            Objects.requireNonNull(scope, "scope");
            requirements = List.copyOf(requirements);
            sources = List.copyOf(sources);
        }
    }

    public record ReceiptIdentity(
            String requirementId,
            String receiptId,
            String originalPayloadSha256) {
    }

    public record CommitResult(
            ChainPersistenceRecords.ValidationSetRecord validation,
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    candidateItems,
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    actionReceiptItems,
            boolean replayed) {
        public CommitResult {
            candidateItems = List.copyOf(candidateItems);
            actionReceiptItems = List.copyOf(actionReceiptItems);
        }
    }
}
