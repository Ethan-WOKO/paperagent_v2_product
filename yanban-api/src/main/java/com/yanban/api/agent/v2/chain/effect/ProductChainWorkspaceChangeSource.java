package com.yanban.api.agent.v2.chain.effect;

import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords.ActionBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContentRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelProposalRecord;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Resolves a WORKSPACE_CHANGE body only through its formal ActionBinding. */
@Component
public final class ProductChainWorkspaceChangeSource
        implements ChainEffectRuntime.WorkspaceChangeSource {
    private final ChainWorkflowRepository workflow;
    private final ChainModelRepository models;

    public ProductChainWorkspaceChangeSource(
            ChainWorkflowRepository workflow, ChainModelRepository models) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.models = Objects.requireNonNull(models, "models");
    }

    @Override
    public ChainEffectRuntime.WorkspaceChangeBinding loadAccepted(
            String taskId, String actionId) {
        List<ActionBindingRecord> actions = workflow.findActionBindings(taskId)
                .stream().filter(value -> value.actionId().equals(actionId))
                .toList();
        require(actions.size() == 1,
                "Workspace change requires exactly one formal action binding");
        ActionBindingRecord action = actions.get(0);
        ModelProposalRecord proposal = models.findProposal(action.proposalId())
                .orElseThrow(() -> failure(
                        "Workspace change proposal is unavailable"));
        require(action.taskId().equals(taskId)
                        && proposal.taskId().equals(taskId)
                        && proposal.proposalKind()
                        == ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE
                        && ChainContentKind.WORKSPACE_CHANGE_BODY.name().equals(
                        proposal.bodyAuthorityType())
                        && proposal.bodyAuthorityRef() != null,
                "Workspace change proposal authority is invalid");
        ContentRecord body = models.findContent(proposal.bodyAuthorityRef())
                .orElseThrow(() -> failure(
                        "Workspace change body is unavailable"));
        require(body.contentId().equals(proposal.bodyAuthorityRef())
                        && body.taskId().equals(taskId)
                        && body.invocationId().equals(proposal.invocationId())
                        && body.contentKind()
                        == ChainContentKind.WORKSPACE_CHANGE_BODY,
                "Workspace change body belongs to another proposal");
        return new ChainEffectRuntime.WorkspaceChangeBinding(
                taskId, proposal.proposalId(), action.actionId(),
                body.contentId());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw failure(message);
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }
}
