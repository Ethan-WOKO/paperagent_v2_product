package com.yanban.api.agent.v2.chain.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainCandidateMaterializationFailureRepositoryAdapter;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PersistedEffectResult;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projects formal action receipts and mechanically checks one failed-action repair. */
@Component
public final class ProductChainExecutorActionContextProjection {
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductChainModelRepositoryAdapter models;
    private final EffectOutcomeRepository outcomes;
    private final ObjectMapper json;
    private final ProductChainCandidateMaterializationFailureRepositoryAdapter
            candidateFailures;

    public ProductChainExecutorActionContextProjection(
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainModelRepositoryAdapter models,
            EffectOutcomeRepository outcomes,
            ObjectMapper json,
            ProductChainCandidateMaterializationFailureRepositoryAdapter
                    candidateFailures) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.models = Objects.requireNonNull(models, "models");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.json = Objects.requireNonNull(json, "json");
        this.candidateFailures = Objects.requireNonNull(
                candidateFailures, "candidateFailures");
    }

    public Projection project(String taskId, String stepId, String activationEventId) {
        List<ChainPersistenceRecords.ActionBindingRecord> actions = workflow
                .findActionBindings(taskId).stream()
                .filter(action -> action.stepId().equals(stepId)
                        && action.activationEventId().equals(activationEventId))
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.ActionBindingRecord::attemptNo))
                .toList();
        StringBuilder attempts = new StringBuilder();
        Failure latestFailure = null;
        Map<String, String> refs = new LinkedHashMap<>();
        Map<String, ChainPersistenceRecords.WorkspaceCandidateRecord>
                candidatesByAction = new LinkedHashMap<>();
        for (var candidate : workflow.findWorkspaceCandidates(taskId)) {
            if (!taskId.equals(candidate.taskId())
                    || candidatesByAction.put(candidate.actionId(), candidate)
                    != null) {
                throw new IllegalStateException(
                        "CHAIN_CANDIDATE_AUTHORITY_AMBIGUOUS");
            }
        }
        for (ChainPersistenceRecords.ActionBindingRecord action : actions) {
            // A failure is unresolved only while it is the latest formal
            // action outcome in this Step activation.
            latestFailure = null;
            if (!attempts.isEmpty()) attempts.append('\n');
            attempts.append("attempt=").append(action.attemptNo())
                    .append("; actionId=").append(action.actionId())
                    .append("; proposalId=").append(action.proposalId())
                    .append("; actionSignature=")
                    .append(action.actionSignatureSha256());
            refs.put("action." + action.attemptNo(), action.actionId());
            var candidate = candidatesByAction.get(action.actionId());
            var candidateFailure = candidateFailures
                    .findCandidateMaterializationFailure(
                            taskId, action.actionId()).orElse(null);
            if (candidate != null && candidateFailure != null) {
                throw new IllegalStateException(
                        "CHAIN_CANDIDATE_SUCCESS_FAILURE_CONFLICT");
            }
            if (candidate != null
                    && (!action.taskId().equals(candidate.taskId())
                    || !action.actionId().equals(candidate.actionId())
                    || !action.workspaceId().equals(candidate.workspaceId())
                    || !action.versionFenceSha256().equals(
                    candidate.versionFenceSha256()))) {
                throw new IllegalStateException(
                        "CHAIN_CANDIDATE_ACTION_MISMATCH");
            }
            PersistenceResult<PersistedEffectResult> persisted = outcomes
                    .findResult(new ToolCallId(action.actionId()));
            if (!persisted.successful()) {
                attempts.append("; result=NOT_RECORDED");
                if (candidate != null) {
                    attempts.append("; candidateId=")
                            .append(candidate.workspaceCandidateId())
                            .append("; candidateStatus=COMMITTED");
                    refs.put("candidate." + action.attemptNo(),
                            candidate.workspaceCandidateId());
                } else if (candidateFailure != null) {
                    latestFailure = appendCandidateFailure(
                            attempts, refs, action, candidateFailure);
                }
                continue;
            }
            ExecutionReceipt receipt = persisted.value().orElseThrow().receipt();
            attempts.append("; receiptId=").append(receipt.id().value())
                    .append("; status=").append(receipt.status())
                    .append("; exitCode=")
                    .append(receipt.exitCode().map(String::valueOf).orElse("NONE"))
                    .append("; resultCode=")
                    .append(receipt.resultCode().orElse("NONE"))
                    .append("; stdout=").append(output(receipt.standardOutput()))
                    .append("; stderr=").append(output(receipt.standardError()));
            refs.put("receipt." + action.attemptNo(), receipt.id().value());
            if (candidate != null) {
                if (receipt.status() != ReceiptStatus.SUCCESS) {
                    throw new IllegalStateException(
                            "CHAIN_CANDIDATE_RECEIPT_CONFLICT");
                }
                attempts.append("; candidateId=")
                        .append(candidate.workspaceCandidateId())
                        .append("; candidateStatus=COMMITTED");
                refs.put("candidate." + action.attemptNo(),
                        candidate.workspaceCandidateId());
            }
            if (candidateFailure != null) {
                if (receipt.status() != ReceiptStatus.SUCCESS) {
                    throw new IllegalStateException(
                            "CHAIN_CANDIDATE_FAILURE_RECEIPT_CONFLICT");
                }
                latestFailure = appendCandidateFailure(
                        attempts, refs, action, candidateFailure);
                continue;
            }
            if (receipt.status() != ReceiptStatus.SUCCESS) {
                latestFailure = new Failure(
                        action.actionId(), receipt.id().value(),
                        action.proposalId(), receipt.status(),
                        receipt.resultCode().orElseThrow());
            }
        }
        String body = attempts.isEmpty() ? "NO_FORMAL_ACTION_ATTEMPTS" : attempts.toString();
        Map<String, String> fields = Map.of(
                "action.currentStepAttemptTable", body,
                "action.latestOrUnresolvedReceiptAndErrorExpansion",
                latestFailure == null ? "NO_UNRESOLVED_FORMAL_FAILURE" : body);
        return new Projection(fields, Map.copyOf(refs), latestFailure);
    }

    private static Failure appendCandidateFailure(
            StringBuilder attempts, Map<String, String> refs,
            ChainPersistenceRecords.ActionBindingRecord action,
            ChainPersistenceRecords.CandidateMaterializationFailureRecord failure) {
        if (!action.taskId().equals(failure.taskId())
                || !action.actionId().equals(failure.actionId())
                || !action.workspaceId().equals(failure.workspaceId())
                || !action.baseCandidateKey().equals(
                failure.baseCandidateKey())
                || !action.versionFenceSha256().equals(
                failure.versionFenceSha256())) {
            throw new IllegalStateException(
                    "CHAIN_CANDIDATE_FAILURE_ACTION_MISMATCH");
        }
        attempts.append("; candidateFailureId=")
                .append(failure.candidateFailureId())
                .append("; candidateFailureCode=")
                .append(failure.errorCode());
        refs.put("candidateFailure." + action.attemptNo(),
                failure.candidateFailureId());
        return new Failure(action.actionId(),
                failure.candidateFailureId(), action.proposalId(), null,
                failure.errorCode());
    }

    public String validateRepair(
            ChainModelProtocolOutcome outcome,
            Failure failure) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(failure, "failure");
        if (!(outcome instanceof ChainModelProtocolOutcome.ProposalReady ready)
                || ready.proposal().proposalKind()
                != ChainProposalKind.EXECUTOR_TOOL_ACTION) {
            return null;
        }
        return validateRepair(toolAction(ready.proposal()), failure);
    }

    public String validateRepair(
            ProviderRoleOutput output,
            Failure failure) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(failure, "failure");
        if (!(output.payload() instanceof ExecutorPayload.ToolAction proposed)) {
            return null;
        }
        return validateRepair(proposed, failure);
    }

    private String validateRepair(
            ExecutorPayload.ToolAction proposed,
            Failure failure) {
        if (proposed.priorActionRef() == null || proposed.priorErrorRef() == null) {
            return "REPAIR_AUTHORITY_MISSING";
        }
        if (!failure.actionId().equals(proposed.priorActionRef())
                || !failure.errorRef().equals(proposed.priorErrorRef())) {
            return "REPAIR_AUTHORITY_MISMATCH";
        }
        ChainPersistenceRecords.ModelProposalRecord prior = models
                .findProposal(failure.proposalId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_REPAIR_PRIOR_PROPOSAL_MISSING"));
        if (sameInvocation(toolAction(prior), proposed)) {
            return "REPAIR_DID_NOT_CHANGE_ACTION";
        }
        return null;
    }

    private ExecutorPayload.ToolAction toolAction(
            ChainPersistenceRecords.ModelProposalRecord proposal) {
        String raw = "{\"schemaVersion\":\"1\",\"kind\":\""
                + proposal.proposalKind().wireName() + "\",\"payload\":"
                + proposal.payload().json() + "}";
        ProviderRoleOutput parsed = new StrictChainProviderOutputParser().parse(
                raw, ChainRole.EXECUTOR, ChainWorkState.EXECUTING, null);
        if (!(parsed.payload() instanceof ExecutorPayload.ToolAction action)) {
            throw new IllegalStateException("CHAIN_REPAIR_TOOL_ACTION_REQUIRED");
        }
        return action;
    }

    private boolean sameInvocation(
            ExecutorPayload.ToolAction left,
            ExecutorPayload.ToolAction right) {
        try {
            JsonNode leftArguments = json.readTree(left.completeArguments());
            JsonNode rightArguments = json.readTree(right.completeArguments());
            return left.toolId().equals(right.toolId())
                    && left.target().equals(right.target())
                    && leftArguments.equals(rightArguments);
        } catch (Exception invalidCanonicalArguments) {
            throw new IllegalStateException(
                    "CHAIN_REPAIR_ARGUMENTS_INVALID", invalidCanonicalArguments);
        }
    }

    private static String output(OutputCapture output) {
        if (output.inlineText().isPresent()) {
            return output.inlineText().orElseThrow();
        }
        if (output.artifactRef().isPresent()) {
            return "artifact:" + output.artifactRef().orElseThrow().value();
        }
        return "";
    }

    public record Projection(
            Map<String, String> fields,
            Map<String, String> authorityRefs,
            Failure latestFailure) {
        public Projection {
            fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
            authorityRefs = Map.copyOf(Objects.requireNonNull(
                    authorityRefs, "authorityRefs"));
        }
    }

    public record Failure(
            String actionId, String errorRef, String proposalId,
            ReceiptStatus receiptStatus, String failureCode) {
        public Failure {
            required(actionId, "actionId");
            required(errorRef, "errorRef");
            required(proposalId, "proposalId");
            required(failureCode, "failureCode");
        }

        public boolean isReceiptFailure() {
            return receiptStatus != null;
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
