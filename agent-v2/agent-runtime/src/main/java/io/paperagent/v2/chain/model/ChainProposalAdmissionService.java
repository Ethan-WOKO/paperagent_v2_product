package io.paperagent.v2.chain.model;

import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainProposalStateWriter;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Separates a validated proposal from its accepted/rejected/stale authority state. */
public final class ChainProposalAdmissionService {
    private final ChainModelRepository models;
    private final ChainProposalStateWriter states;
    private final ChainProposalCurrentFence currentFence;

    public ChainProposalAdmissionService(
            ChainModelRepository models,
            ChainProposalStateWriter states,
            ChainProposalCurrentFence currentFence) {
        this.models = Objects.requireNonNull(models, "models");
        this.states = Objects.requireNonNull(states, "states");
        this.currentFence = Objects.requireNonNull(currentFence, "currentFence");
    }

    public AdmissionResult admit(AdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(request.proposalId()).orElseThrow(() ->
                        new IllegalArgumentException("proposal does not exist"));
        ChainPersistenceRecords.ModelInvocationRecord invocation = models
                .findInvocation(proposal.invocationId()).orElseThrow(() ->
                        new IllegalStateException("proposal invocation does not exist"));
        if (!proposal.taskId().equals(request.taskId())
                || !invocation.taskId().equals(request.taskId())) {
            throw new IllegalArgumentException("proposal admission task mismatch");
        }

        List<ChainPersistenceRecords.ProposalStateEventRecord> committed =
                models.findProposalStateEvents(request.proposalId());
        if (!committed.isEmpty()) {
            validateCommittedPrefix(committed, request.taskId());
            ChainPersistenceRecords.ProposalStateEventRecord existingEvent = committed.get(0);
            ChainProposalState existing = existingEvent.stateKind();
            if (!existingEvent.eventId().equals(request.eventId())
                    || (!request.acceptRequested() && existing != ChainProposalState.REJECTED)
                    || (request.acceptRequested()
                    && existing != ChainProposalState.ACCEPTED
                    && existing != ChainProposalState.STALE)) {
                throw new IllegalStateException("conflicting proposal admission replay");
            }
            ChainPersistenceRecords.ProposalStateEventRecord latest =
                    committed.get(committed.size() - 1);
            return new AdmissionResult(
                    latest, latest.stateKind() == ChainProposalState.ACCEPTED, true);
        }

        ChainProposalState decision;
        if (!request.acceptRequested()) {
            decision = ChainProposalState.REJECTED;
        } else {
            boolean current = currentFence.isCurrent(new ChainProposalCurrentFence.Check(
                    request.taskId(), invocation.invocationId(), invocation.contextRevisionId(),
                    invocation.role(), invocation.workState()));
            decision = current ? ChainProposalState.ACCEPTED : ChainProposalState.STALE;
        }
        ChainPersistenceRecords.ProposalStateEventRecord fact =
                new ChainPersistenceRecords.ProposalStateEventRecord(
                        request.proposalId(), 1, request.taskId(), request.eventId(), decision,
                        null, null, request.committedAt());
        fact.validateNextFor(List.of());
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        request.eventId(), request.taskId(), "PROPOSAL_" + decision.name(),
                        request.transitionId(), request.sourceIdentitySha256(), request.committedAt());
        ChainPersistenceRecords.ProposalStateEventRecord stored = states.appendProposalState(
                new ChainPersistenceRecords.AuthoritativeFact<>(event, fact)).fact();
        return new AdmissionResult(stored, decision == ChainProposalState.ACCEPTED, false);
    }

    public AdmissionResult replaceByOfficialResult(OfficialReplacement request) {
        Objects.requireNonNull(request, "request");
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(request.proposalId()).orElseThrow(() ->
                        new IllegalArgumentException("proposal does not exist"));
        if (!proposal.taskId().equals(request.taskId())) {
            throw new IllegalArgumentException("proposal replacement task mismatch");
        }
        List<ChainPersistenceRecords.ProposalStateEventRecord> committed =
                models.findProposalStateEvents(request.proposalId());
        validateCommittedPrefix(committed, request.taskId());
        if (committed.size() == 2) {
            ChainPersistenceRecords.ProposalStateEventRecord existing = committed.get(1);
            if (!existing.eventId().equals(request.eventId())
                    || !Objects.equals(existing.officialAuthorityType(), request.officialAuthorityType().name())
                    || !Objects.equals(existing.officialAuthorityRef(), request.officialAuthorityRef())) {
                throw new IllegalStateException("conflicting official-result replay");
            }
            return new AdmissionResult(existing, false, true);
        }
        if (committed.size() != 1 || committed.get(0).stateKind() != ChainProposalState.ACCEPTED) {
            throw new IllegalStateException("only an accepted proposal may bind its official result");
        }
        ChainPersistenceRecords.ProposalStateEventRecord fact =
                new ChainPersistenceRecords.ProposalStateEventRecord(
                        request.proposalId(), 2, request.taskId(), request.eventId(),
                        ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                        request.officialAuthorityType().name(), request.officialAuthorityRef(),
                        request.committedAt());
        fact.validateNextFor(List.of(ChainProposalState.ACCEPTED));
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        request.eventId(), request.taskId(),
                        "PROPOSAL_REPLACED_BY_OFFICIAL_RESULT",
                        request.transitionId(), request.sourceIdentitySha256(), request.committedAt());
        ChainPersistenceRecords.ProposalStateEventRecord stored = states.appendProposalState(
                new ChainPersistenceRecords.AuthoritativeFact<>(event, fact)).fact();
        return new AdmissionResult(stored, false, false);
    }

    private static void validateCommittedPrefix(
            List<ChainPersistenceRecords.ProposalStateEventRecord> committed,
            String taskId) {
        List<ChainProposalState> prefix = new java.util.ArrayList<>();
        for (int index = 0; index < committed.size(); index++) {
            ChainPersistenceRecords.ProposalStateEventRecord event = committed.get(index);
            if (!event.taskId().equals(taskId) || event.stateSequence() != index + 1L) {
                throw new IllegalStateException("invalid proposal state prefix identity");
            }
            try {
                event.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException("invalid proposal state prefix", invalid);
            }
            prefix.add(event.stateKind());
        }
    }

    public record AdmissionRequest(
            String proposalId,
            String taskId,
            String eventId,
            boolean acceptRequested,
            String transitionId,
            String sourceIdentitySha256,
            Instant committedAt) {
        public AdmissionRequest {
            proposalId = required(proposalId, "proposalId");
            taskId = required(taskId, "taskId");
            eventId = required(eventId, "eventId");
            sourceIdentitySha256 = sha256(sourceIdentitySha256);
            committedAt = Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    public record OfficialReplacement(
            String proposalId,
            String taskId,
            String eventId,
            ChainPersistenceRecords.ProposalOfficialAuthorityType officialAuthorityType,
            String officialAuthorityRef,
            String transitionId,
            String sourceIdentitySha256,
            Instant committedAt) {
        public OfficialReplacement {
            proposalId = required(proposalId, "proposalId");
            taskId = required(taskId, "taskId");
            eventId = required(eventId, "eventId");
            officialAuthorityType = Objects.requireNonNull(officialAuthorityType, "officialAuthorityType");
            officialAuthorityRef = required(officialAuthorityRef, "officialAuthorityRef");
            sourceIdentitySha256 = sha256(sourceIdentitySha256);
            committedAt = Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    public record AdmissionResult(
            ChainPersistenceRecords.ProposalStateEventRecord state,
            boolean executable,
            boolean replayed) {
        public AdmissionResult {
            Objects.requireNonNull(state, "state");
            if (executable != (state.stateKind() == ChainProposalState.ACCEPTED)) {
                throw new IllegalArgumentException("only ACCEPTED is executable");
            }
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static String sha256(String value) {
        value = required(value, "sourceIdentitySha256");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sourceIdentitySha256 must be lowercase SHA-256");
        }
        return value;
    }
}
