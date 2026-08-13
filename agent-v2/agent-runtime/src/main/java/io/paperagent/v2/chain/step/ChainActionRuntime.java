package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.*;
import io.paperagent.v2.chain.ChainPersistenceRecords.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Sole runtime writer for formal TOOL_ACTION and WORKSPACE_CHANGE bindings; it
 * never dispatches effects or mutates a Workspace itself.
 */
public final class ChainActionRuntime {
    private final ChainModelRepository models;
    private final ChainContextRepository contexts;
    private final ChainWorkflowRepository workflows;
    private final ChainActionBindingWriter actions;
    private final ChainActionProposalBinder proposalBinder;
    private final ChainStepCommitGate commitGate;

    public ChainActionRuntime(
            ChainModelRepository models,
            ChainContextRepository contexts,
            ChainWorkflowRepository workflows,
            ChainActionBindingWriter actions,
            ChainActionProposalBinder proposalBinder,
            ChainStepCommitGate commitGate) {
        this.models = Objects.requireNonNull(models, "models");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.proposalBinder = Objects.requireNonNull(
                proposalBinder, "proposalBinder");
        this.commitGate = Objects.requireNonNull(commitGate, "commitGate");
    }

    public AuthoritativeAppendResult<ActionBindingRecord> commit(
            ActionCommand command) {
        Objects.requireNonNull(command, "command");
        ModelProposalRecord proposal = models.findProposal(
                        command.proposalId())
                .orElseThrow(() -> failure("CHAIN_ACTION_PROPOSAL_MISSING",
                        "TOOL_ACTION proposal does not exist"));
        boolean toolAction = proposal.proposalKind()
                == ChainProposalKind.EXECUTOR_TOOL_ACTION;
        boolean workspaceChange = proposal.proposalKind()
                == ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE;
        if (!proposal.taskId().equals(command.taskId())
                || proposal.role() != ChainRole.EXECUTOR
                || (!toolAction && !workspaceChange)) {
            throw failure("CHAIN_ACTION_PROPOSAL_INVALID",
                    "action binding requires an Executor TOOL_ACTION or WORKSPACE_CHANGE proposal");
        }
        String signature = actionSignature(proposal, toolAction);
        ModelInvocationRecord invocation = models.findInvocation(
                        proposal.invocationId())
                .orElseThrow(() -> failure("CHAIN_ACTION_INVOCATION_MISSING",
                        "TOOL_ACTION invocation does not exist"));
        ContextRevisionRecord context = contexts.findContextRevision(
                        invocation.contextRevisionId())
                .orElseThrow(() -> failure("CHAIN_ACTION_CONTEXT_MISSING",
                        "TOOL_ACTION frozen context does not exist"));
        if (!invocation.taskId().equals(command.taskId())
                || invocation.role() != ChainRole.EXECUTOR
                || context.status() != ChainContextRevisionStatus.COMPLETE
                || !context.taskId().equals(command.taskId())
                || context.role() != ChainRole.EXECUTOR
                || !Objects.equals(invocation.completionToken(),
                context.completionToken())
                || invocation.workState() != context.workState()
                || context.taskFrameId() == null
                || context.planId() == null
                || context.planRevisionId() == null
                || context.stepId() == null
                || context.workspaceId() == null) {
            throw failure("CHAIN_ACTION_CONTEXT_INVALID",
                    "action proposal is not bound to a complete Step context");
        }
        String officialActionId = validateAcceptedProposal(proposal);
        List<ActionBindingRecord> history = workflows.findActionBindings(
                        command.taskId()).stream()
                .filter(value -> value.stepId().equals(context.stepId())
                        && value.activationEventId().equals(
                        context.activationEventId()))
                .sorted(Comparator.comparingInt(
                        ActionBindingRecord::attemptNo))
                .toList();
        for (int index = 0; index < history.size(); index++) {
            if (history.get(index).attemptNo() != index + 1) {
                throw failure("CHAIN_ACTION_ATTEMPT_PREFIX_INVALID",
                        "formal action attempts are not a complete prefix");
            }
        }
        String baseCandidate = context.candidateFingerprint() == null
                ? ChainIdentity.NONE : context.candidateFingerprint();
        String versionFence = sha256(context.requestDigest() + "\0"
                + context.workspaceId() + "\0" + baseCandidate);
        List<ActionBindingRecord> existingForProposal = history.stream()
                .filter(value -> value.proposalId().equals(
                        proposal.proposalId())).toList();
        if (existingForProposal.size() > 1) {
            throw failure("CHAIN_ACTION_PROPOSAL_BINDING_DUPLICATE",
                    "proposal has multiple formal action bindings");
        }
        if (existingForProposal.size() == 1) {
            ActionBindingRecord existing = existingForProposal.get(0);
            if (officialActionId != null
                    && !officialActionId.equals(existing.actionId())) {
                throw failure("CHAIN_ACTION_OFFICIAL_BINDING_INVALID",
                        "proposal official binding differs from formal action");
            }
            verifyStableBinding(existing, context, proposal,
                    baseCandidate, signature, versionFence);
            AuthoritativeAppendResult<ActionBindingRecord> replay =
                    appendOnly(existing);
            if (officialActionId == null) {
                bindOfficial(proposal, existing, command.committedAt());
            }
            return replay;
        }
        if (officialActionId != null) {
            throw failure("CHAIN_ACTION_OFFICIAL_BINDING_INVALID",
                    "proposal references a missing formal action binding");
        }
        commitGate.requireCurrent(new ChainStepCommitGate.GateQuery(
                ChainStepCommitGate.CommitKind.ACTION_BINDING,
                command.taskId(), context.instructionId(),
                context.taskFrameId(), context.planId(),
                context.planRevisionId(), context.stepId(),
                context.activationEventId()));
        int attempt = history.size() + 1;
        String actionId = "action." + sha256(command.taskId() + "\0"
                + context.planRevisionId() + "\0" + context.stepId() + "\0"
                + context.activationEventId() + "\0" + attempt + "\0"
                + proposal.proposalKind().name() + "\0" + signature);
        String idempotencyKey = "action.idempotency." + sha256(
                actionId + "\0" + versionFence);
        String eventId = "action.binding." + sha256(actionId);
        new ChainIdentity.Action(
                context.instructionId(), context.taskFrameId(),
                context.planRevisionId(), context.stepId(),
                context.activationEventId(), actionId, idempotencyKey,
                context.workspaceId(), baseCandidate);
        ActionBindingRecord fact = new ActionBindingRecord(
                actionId, command.taskId(), eventId, proposal.proposalId(),
                attempt, signature, idempotencyKey, context.instructionId(),
                context.taskFrameId(), context.planId(),
                context.planRevisionId(), context.stepId(),
                context.activationEventId(), context.workspaceId(),
                baseCandidate, null, null, null, null, versionFence,
                command.committedAt());
        AuthoritativeAppendResult<ActionBindingRecord> appended =
                appendOnly(fact);
        bindOfficial(proposal, fact, command.committedAt());
        return appended;
    }

    private void verifyStableBinding(
            ActionBindingRecord action,
            ContextRevisionRecord context,
            ModelProposalRecord proposal,
            String baseCandidate,
            String signature,
            String versionFence) {
        String expectedActionId = "action." + sha256(action.taskId() + "\0"
                + context.planRevisionId() + "\0" + context.stepId() + "\0"
                + context.activationEventId() + "\0" + action.attemptNo()
                + "\0" + proposal.proposalKind().name() + "\0" + signature);
        String expectedIdempotency = "action.idempotency." + sha256(
                expectedActionId + "\0" + versionFence);
        String expectedEventId = "action.binding." + sha256(expectedActionId);
        if (!action.actionId().equals(expectedActionId)
                || !action.eventId().equals(expectedEventId)
                || !action.actionSignatureSha256().equals(signature)
                || !action.idempotencyKey().equals(expectedIdempotency)
                || !action.instructionId().equals(context.instructionId())
                || !action.taskFrameId().equals(context.taskFrameId())
                || !action.planId().equals(context.planId())
                || !action.planRevisionId().equals(
                context.planRevisionId())
                || !action.stepId().equals(context.stepId())
                || !action.activationEventId().equals(
                context.activationEventId())
                || !action.workspaceId().equals(context.workspaceId())
                || !action.baseCandidateKey().equals(baseCandidate)
                || !action.versionFenceSha256().equals(versionFence)
                || action.effectIntentId() != null
                || action.dispatchRef() != null
                || action.resultAuthorityType() != null) {
            throw failure("CHAIN_ACTION_STABLE_IDENTITY_MISMATCH",
                    "formal action binding differs from its frozen identity");
        }
    }

    private String actionSignature(
            ModelProposalRecord proposal, boolean toolAction) {
        if (toolAction) {
            if (proposal.bodyAuthorityType() != null
                    || proposal.bodyAuthorityRef() != null) {
                throw failure("CHAIN_ACTION_PROPOSAL_INVALID",
                        "TOOL_ACTION cannot carry a body authority");
            }
            return proposal.payload().sha256();
        }
        if (!ChainContentKind.WORKSPACE_CHANGE_BODY.name().equals(
                proposal.bodyAuthorityType())
                || proposal.bodyAuthorityRef() == null) {
            throw failure("CHAIN_ACTION_PROPOSAL_INVALID",
                    "WORKSPACE_CHANGE requires its formal change-body authority");
        }
        ContentRecord body = models.findContent(proposal.bodyAuthorityRef())
                .orElseThrow(() -> failure("CHAIN_ACTION_PROPOSAL_INVALID",
                        "WORKSPACE_CHANGE body authority does not exist"));
        if (!body.taskId().equals(proposal.taskId())
                || !body.invocationId().equals(proposal.invocationId())
                || body.contentKind() != ChainContentKind.WORKSPACE_CHANGE_BODY
                || !body.contentId().equals(proposal.bodyAuthorityRef())
                || !body.bodySha256().equals(sha256(body.body()))) {
            throw failure("CHAIN_ACTION_PROPOSAL_INVALID",
                    "WORKSPACE_CHANGE body authority identity mismatch");
        }
        return sha256(proposal.payload().sha256() + "\0" + body.bodySha256());
    }

    private AuthoritativeAppendResult<ActionBindingRecord> appendOnly(
            ActionBindingRecord fact) {
        AuthorityEventRequest event = new AuthorityEventRequest(
                fact.eventId(), fact.taskId(), "ACTION_BINDING", null,
                fact.versionFenceSha256(), fact.createdAt());
        AuthoritativeAppendResult<ActionBindingRecord> appended =
                actions.appendActionBinding(new AuthoritativeFact<>(event, fact));
        if (!sameAuthorityEvent(event, appended.event())
                || !sameActionBinding(fact, appended.fact())) {
            throw failure("CHAIN_ACTION_REPLAY_MISMATCH",
                    "action writer returned another binding");
        }
        return appended;
    }

    private static boolean sameAuthorityEvent(
            AuthorityEventRequest expected,
            ChainPersistenceRecords.AuthorityEventRecord actual) {
        return actual.eventId().equals(expected.eventId())
                && actual.taskId().equals(expected.taskId())
                && actual.eventType().equals(expected.eventType())
                && Objects.equals(actual.transitionId(), expected.transitionId())
                && actual.sourceIdentitySha256().equals(
                expected.sourceIdentitySha256());
    }

    /** Product persistence owns audit time; every immutable business field stays exact. */
    private static boolean sameActionBinding(
            ActionBindingRecord expected, ActionBindingRecord actual) {
        return actual.actionId().equals(expected.actionId())
                && actual.taskId().equals(expected.taskId())
                && actual.eventId().equals(expected.eventId())
                && actual.proposalId().equals(expected.proposalId())
                && actual.attemptNo() == expected.attemptNo()
                && actual.actionSignatureSha256().equals(
                expected.actionSignatureSha256())
                && actual.idempotencyKey().equals(expected.idempotencyKey())
                && actual.instructionId().equals(expected.instructionId())
                && actual.taskFrameId().equals(expected.taskFrameId())
                && actual.planId().equals(expected.planId())
                && actual.planRevisionId().equals(expected.planRevisionId())
                && actual.stepId().equals(expected.stepId())
                && actual.activationEventId().equals(expected.activationEventId())
                && actual.workspaceId().equals(expected.workspaceId())
                && actual.baseCandidateKey().equals(expected.baseCandidateKey())
                && Objects.equals(actual.effectIntentId(), expected.effectIntentId())
                && Objects.equals(actual.dispatchRef(), expected.dispatchRef())
                && Objects.equals(
                actual.resultAuthorityType(), expected.resultAuthorityType())
                && Objects.equals(
                actual.resultAuthorityRef(), expected.resultAuthorityRef())
                && actual.versionFenceSha256().equals(
                expected.versionFenceSha256());
    }

    private String validateAcceptedProposal(ModelProposalRecord proposal) {
        List<ProposalStateEventRecord> states = models
                .findProposalStateEvents(proposal.proposalId()).stream()
                .sorted(Comparator.comparingLong(
                        ProposalStateEventRecord::stateSequence)).toList();
        List<ChainProposalState> prefix = new java.util.ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            ProposalStateEventRecord state = states.get(index);
            if (!state.proposalId().equals(proposal.proposalId())
                    || !state.taskId().equals(proposal.taskId())
                    || state.stateSequence() != index + 1L) {
                throw failure("CHAIN_ACTION_PROPOSAL_PREFIX_INVALID",
                        "proposal state identity is invalid");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure("CHAIN_ACTION_PROPOSAL_PREFIX_INVALID",
                        "proposal state prefix is invalid");
            }
            prefix.add(state.stateKind());
        }
        if (states.isEmpty()
                || states.get(0).stateKind() != ChainProposalState.ACCEPTED) {
            throw failure("CHAIN_ACTION_PROPOSAL_NOT_ACCEPTED",
                    "TOOL_ACTION proposal is not accepted");
        }
        if (states.size() == 2
                && (!"ACTION_BINDING".equals(
                states.get(1).officialAuthorityType())
                || !states.get(1).officialAuthorityRef().startsWith(
                "action."))) {
            throw failure("CHAIN_ACTION_OFFICIAL_BINDING_INVALID",
                    "proposal is bound to another official result");
        }
        return states.size() == 2
                ? states.get(1).officialAuthorityRef() : null;
    }

    private void bindOfficial(
            ModelProposalRecord proposal,
            ActionBindingRecord action,
            Instant committedAt) {
        String eventId = "proposal.action-bound." + sha256(
                proposal.proposalId() + "\0" + action.actionId());
        ProposalStateEventRecord state = proposalBinder.bindAction(
                new ChainActionProposalBinder.Binding(
                        proposal.proposalId(), proposal.taskId(), eventId,
                        action.actionId(), action.versionFenceSha256(),
                        committedAt));
        if (!state.proposalId().equals(proposal.proposalId())
                || !state.taskId().equals(proposal.taskId())
                || !state.eventId().equals(eventId)
                || state.stateSequence() != 2
                || state.stateKind()
                != ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                || !"ACTION_BINDING".equals(state.officialAuthorityType())
                || !action.actionId().equals(state.officialAuthorityRef())) {
            throw failure("CHAIN_ACTION_OFFICIAL_BINDING_INVALID",
                    "proposal binder returned another action authority");
        }
    }

    public record ActionCommand(
            String taskId, String proposalId, Instant committedAt) {
        public ActionCommand {
            if (taskId == null || taskId.isBlank()
                    || proposalId == null || proposalId.isBlank()) {
                throw new IllegalArgumentException(
                        "taskId and proposalId must not be blank");
            }
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    private static ChainStepException failure(String code, String message) {
        return new ChainStepException(code, message);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
