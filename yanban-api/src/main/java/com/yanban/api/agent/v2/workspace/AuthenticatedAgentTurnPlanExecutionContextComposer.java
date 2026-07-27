package com.yanban.api.agent.v2.workspace;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.agent.v2.adapter.bootstrap.ProductWorkspaceIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.project.ProjectStorageProperties;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanExecutionContextRepository;
import io.paperagent.v2.persistence.PlanExecutionContextSnapshot;
import io.paperagent.v2.runtime.execution.context.composition.DefaultPlanExecutionContextComposer;
import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionOutcome;
import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionRequest;
import io.paperagent.v2.workspace.WorkspacePort;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Internal composition of one authenticated Project turn into its persisted
 * pre-step V2 execution context.
 */
@Service
public final class AuthenticatedAgentTurnPlanExecutionContextComposer {
    private static final String CONTEXT_PATH = "planExecutionContext";

    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final ProductWorkspaceIdDerivation workspaceIds;
    private final ProjectStorageProperties projectStorage;
    private final AuthenticatedAgentTurnWorkspacePortFactory workspaces;
    private final ExecutionStartRecoveryRepository executionStarts;
    private final PlanExecutionContextRepository executionContexts;
    private final LeaseRepository leases;

    public AuthenticatedAgentTurnPlanExecutionContextComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            ProductWorkspaceIdDerivation workspaceIds,
            ProjectStorageProperties projectStorage,
            AuthenticatedAgentTurnWorkspacePortFactory workspaces,
            ExecutionStartRecoveryRepository executionStarts,
            PlanExecutionContextRepository executionContexts,
            LeaseRepository leases) {
        this.contexts = contexts;
        this.planIds = planIds;
        this.workspaceIds = workspaceIds;
        this.projectStorage = projectStorage;
        this.workspaces = workspaces;
        this.executionStarts = executionStarts;
        this.executionContexts = executionContexts;
        this.leases = leases;
    }

    public PlanExecutionContextCompositionOutcome compose(
            Long userId,
            Long turnId,
            AuthenticatedAgentTurnPlanExecutionContextCommand command) {
        VerifiedAgentTurnProductContext context = contexts.resolve(userId, turnId);
        if (command == null) {
            throw failure(
                    AuthenticatedPlanExecutionContextCompositionCode.INVALID_COMMAND,
                    "command");
        }
        AgentRunIdentity identity =
                requireProjectContext(context, userId, turnId);
        PlanId planId = planIds.derive(identity);
        WorkspaceMaterializationSpec proposed = proposedSpec(context, identity);
        Optional<WorkspaceMaterializationSpec> proposal =
                shapeProposal(planId, proposed, executionContexts.inspect(planId));
        WorkspacePort workspace = workspaces.create(context);
        return new DefaultPlanExecutionContextComposer(
                executionStarts,
                executionContexts,
                leases,
                workspace)
                .compose(new PlanExecutionContextCompositionRequest(
                        planId,
                        proposal,
                        command.attempt()));
    }

    private WorkspaceMaterializationSpec proposedSpec(
            VerifiedAgentTurnProductContext context,
            AgentRunIdentity identity) {
        long maxFileBytes = projectStorage.getMaxFileBytes();
        long maxTotalBytes = projectStorage.getMaxTotalBytes();
        int maxFiles = projectStorage.getMaxFiles();
        if (maxFileBytes < 0 || maxTotalBytes < 0 || maxFiles < 0) {
            throw failure(
                    AuthenticatedPlanExecutionContextCompositionCode
                            .INVALID_WORKSPACE_LIMITS,
                    "projectStorage.limits");
        }
        return new WorkspaceMaterializationSpec(
                workspaceIds.derive(identity),
                new ProjectVersionRef(
                        String.valueOf(identity.projectId()),
                        context.projectVersionId().orElseThrow(() -> failure(
                                AuthenticatedPlanExecutionContextCompositionCode
                                        .PROJECT_CONTEXT_REQUIRED,
                                "agentTurn.projectVersion"))),
                new WorkspaceMaterializationLimits(
                        maxFileBytes,
                        maxTotalBytes,
                        maxFiles));
    }

    private static AgentRunIdentity requireProjectContext(
            VerifiedAgentTurnProductContext context,
            Long userId,
            Long turnId) {
        if (context == null || context.identity() == null) {
            throw failure(
                    AuthenticatedPlanExecutionContextCompositionCode
                            .PROJECT_CONTEXT_REQUIRED,
                    "agentTurn.context");
        }
        AgentRunIdentity identity = context.identity();
        if (!"AGENT_TURN".equals(identity.source())
                || userId == null
                || !userId.equals(identity.userId())
                || turnId == null
                || !String.valueOf(turnId).equals(identity.sourceId())
                || identity.userId() == null
                || identity.userId() <= 0
                || identity.sessionId() == null
                || identity.sessionId() <= 0
                || identity.projectId() == null
                || identity.projectId() <= 0
                || context.projectVersionId().isEmpty()
                || context.projectVersionId().orElseThrow().isBlank()) {
            throw failure(
                    AuthenticatedPlanExecutionContextCompositionCode
                            .PROJECT_CONTEXT_REQUIRED,
                    "agentTurn.projectContext");
        }
        return identity;
    }

    private static Optional<WorkspaceMaterializationSpec> shapeProposal(
            PlanId planId,
            WorkspaceMaterializationSpec proposed,
            PersistenceResult<PlanExecutionContextSnapshot> inspection) {
        if (inspection == null) {
            throw invalidPreflight();
        }
        if (inspection.outcome() == PersistenceOutcome.REJECTED) {
            PersistenceFailure failure = inspection.failure().orElse(null);
            if (failure != null
                    && failure.code() == PersistenceErrorCode.NOT_FOUND
                    && (CONTEXT_PATH.equals(failure.path())
                            || "planId".equals(failure.path()))) {
                return Optional.of(proposed);
            }
            throw invalidPreflight();
        }
        if (inspection.outcome() != PersistenceOutcome.FOUND
                || inspection.failure().isPresent()) {
            throw invalidPreflight();
        }
        PlanExecutionContextSnapshot snapshot = inspection.value().orElse(null);
        if (snapshot == null || !planId.equals(snapshot.planId())) {
            throw invalidPreflight();
        }
        return Optional.empty();
    }

    private static AuthenticatedPlanExecutionContextCompositionException
            invalidPreflight() {
        return failure(
                AuthenticatedPlanExecutionContextCompositionCode
                        .INVALID_CONTEXT_PREFLIGHT,
                CONTEXT_PATH);
    }

    private static AuthenticatedPlanExecutionContextCompositionException failure(
            AuthenticatedPlanExecutionContextCompositionCode code,
            String path) {
        return new AuthenticatedPlanExecutionContextCompositionException(
                code, path);
    }
}
