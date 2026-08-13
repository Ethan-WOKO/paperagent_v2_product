package io.paperagent.v2.chain.instruction;

import io.paperagent.v2.chain.ChainInstructionDispositionWriter;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.PlannerPayload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Sole authority writer for an accepted planner user-instruction disposition. */
public final class ChainInstructionDispositionRuntime {
    private final ChainModelRepository models;
    private final ChainInstructionDispositionWriter writer;
    private final ProposalOfficialBinder proposalBinder;

    public ChainInstructionDispositionRuntime(
            ChainModelRepository models,
            ChainInstructionDispositionWriter writer,
            ProposalOfficialBinder proposalBinder) {
        this.models = Objects.requireNonNull(models, "models");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.proposalBinder = Objects.requireNonNull(proposalBinder,
                "proposalBinder");
    }

    public CommitResult commit(CommitRequest request) {
        Objects.requireNonNull(request, "request");
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(request.proposalId()).orElseThrow(() -> failure(
                        "CHAIN_DISPOSITION_PROPOSAL_NOT_FOUND"));
        if (!proposal.taskId().equals(request.taskId())
                || proposal.proposalKind()
                != ChainProposalKind.PLANNER_USER_INSTRUCTION_DISPOSITION) {
            throw failure("CHAIN_DISPOSITION_PROPOSAL_INVALID");
        }
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = models
                .findProposalStateEvents(request.proposalId());
        if (states.size() != 1
                || states.get(0).stateKind() != ChainProposalState.ACCEPTED) {
            throw failure("CHAIN_DISPOSITION_PROPOSAL_NOT_ACCEPTED");
        }
        PlannerPayload.UserInstructionDisposition payload = request.payload();
        if (!payload.instructionRef().equals(request.instructionId())) {
            throw failure("CHAIN_DISPOSITION_PAYLOAD_INVALID");
        }
        String dispositionId = "disposition." + sha256(
                request.taskId() + "\0" + request.proposalId() + "\0"
                        + request.instructionId());
        String eventId = request.eventId();
        ChainPersistenceRecords.InstructionDispositionRecord requested =
                new ChainPersistenceRecords.InstructionDispositionRecord(
                        dispositionId, request.taskId(), eventId,
                        request.proposalId(), request.instructionId(),
                        payload.classification(), payload.oldTaskDisposition(),
                        payload.replyRequired(),
                        payload.continuationOrReintakePosition(),
                        payload.boundaryChanged(),
                        canonicalApplicability(payload.applicability()),
                        canonicalStrings(payload.nonAuthoritativeReuseSuggestions()),
                        request.committedAt());
        ChainPersistenceRecords.AuthorityEventRequest authority =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        eventId, request.taskId(), "INSTRUCTION_DISPOSITION",
                        null, sha256(dispositionId + "\0" + request.proposalId()),
                        request.committedAt());
        ChainPersistenceRecords.InstructionDispositionRecord stored = writer
                .appendInstructionDisposition(
                        new ChainPersistenceRecords.AuthoritativeFact<>(
                                authority, requested)).fact();
        if (!stored.equals(requested)) {
            throw failure("CHAIN_DISPOSITION_REPLAY_MISMATCH");
        }
        proposalBinder.bindOfficialResult(request.taskId(), request.proposalId(),
                "INSTRUCTION_DISPOSITION", dispositionId);
        return new CommitResult(stored, false);
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalApplicability(
            List<io.paperagent.v2.chain.ProposalFields.ApplicabilitySuggestion> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            var value = values.get(i);
            json.append("{\"acceptedResultId\":\"")
                    .append(escape(value.acceptedResultId()))
                    .append("\",\"outcome\":\"")
                    .append(value.outcome().name())
                    .append("\",\"reason\":\"")
                    .append(escape(value.reason()))
                    .append("\",\"usePosition\":\"")
                    .append(escape(value.usePosition())).append("\"}");
        }
        json.append(']');
        return canonical(json.toString());
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalStrings(
            List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(escape(values.get(i))).append('"');
        }
        json.append(']');
        return canonical(json.toString());
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(String json) {
        return new ChainPersistenceRecords.CanonicalJson(1, sha256(json), json);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    public record CommitRequest(
            String taskId, String proposalId, String instructionId,
            String eventId, PlannerPayload.UserInstructionDisposition payload,
            Instant committedAt) {
        public CommitRequest {
            required(taskId, "taskId");
            required(proposalId, "proposalId");
            required(instructionId, "instructionId");
            required(eventId, "eventId");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    public record CommitResult(
            ChainPersistenceRecords.InstructionDispositionRecord disposition,
            boolean replayed) {
    }

    public interface ProposalOfficialBinder {
        void bindOfficialResult(String taskId, String proposalId,
                                String authorityType, String authorityRef);
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
