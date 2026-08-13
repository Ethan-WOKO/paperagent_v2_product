package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.context.ProductChainContextSourceFactory;
import com.yanban.api.agent.v2.chain.context.ProductChainModelCallIdentity;
import com.yanban.api.agent.v2.chain.context.ProductChainRuntimePolicySource;
import com.yanban.api.agent.v2.chain.model.ProductChainChatModelAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainModelEndpoint;
import com.yanban.api.agent.v2.chain.model.ProductChainModelEndpointResolver;
import com.yanban.api.agent.v2.chain.model.ProductChainModelMaterializationAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainProposalAdmissionAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatModelProvider;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.context.ChainContextFreezeOutcome;
import io.paperagent.v2.chain.context.DefaultChainContextManager;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.model.ChainModelProtocolRequest;
import io.paperagent.v2.chain.model.ChainModelProtocolService;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/** Invokes one gap-bound validation model turn and admits only its typed proposal. */
@Component
public final class ProductChainPendingItemModelInvoker {
    private final ProductChainContextRepositoryAdapter contexts;
    private final ProductChainModelRepositoryAdapter models;
    private final ProductChainContextSourceFactory contextSources;
    private final ProductChainModelCallIdentity modelCallIdentity;
    private final UserSettingsService settings;
    private final ChatModelProvider provider;
    private final PlatformTransactionManager transactions;
    private final NamedParameterJdbcTemplate jdbc;

    public ProductChainPendingItemModelInvoker(
            ProductChainContextRepositoryAdapter contexts,
            ProductChainModelRepositoryAdapter models,
            ProductChainContextSourceFactory contextSources,
            ProductChainModelCallIdentity modelCallIdentity,
            UserSettingsService settings,
            @Qualifier("chatModelProvider") ChatModelProvider provider,
            PlatformTransactionManager transactions,
            NamedParameterJdbcTemplate jdbc) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.models = Objects.requireNonNull(models, "models");
        this.contextSources = Objects.requireNonNull(contextSources, "contextSources");
        this.modelCallIdentity = Objects.requireNonNull(modelCallIdentity, "modelCallIdentity");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public void invoke(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.PlanBindingRecord plan,
            com.yanban.api.agent.v2.chain.recovery.ProductChainRecoverySource
                    .FrozenStepInput step,
            com.yanban.api.agent.v2.chain.recovery.ProductChainRecoverySource
                    .FrozenCandidateInput candidate,
            ChainRole role, String gapId, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(role, "role");
        required(gapId, "gapId");
        Objects.requireNonNull(now, "now");
        if (!gapId.equals(instruction.answeredGapId())) {
            throw failure("CHAIN_PENDING_MODEL_INSTRUCTION_GAP_MISMATCH");
        }
        if (role == ChainRole.EXECUTOR && (plan == null || step == null)) {
            throw failure("CHAIN_PENDING_EXECUTOR_FROZEN_STEP_MISSING");
        }
        if (role == ChainRole.EXECUTOR
                && step.status()
                != io.paperagent.v2.chain.ChainStepStatus.ACTIVE) {
            throw failure("CHAIN_PENDING_EXECUTOR_STEP_NOT_ACTIVE");
        }
        String callReason = "PENDING_ITEM_VALIDATION";
        String contextId = "context." + sha256(task.taskId() + "\0" + gapId
                + "\0" + instruction.instructionId() + "\0" + role.name());
        String invocationId = "invocation." + sha256(contextId);
        ProductChainModelCallIdentity.Binding identity = modelCallIdentity.bind(
                task.taskId(), contextId, invocationId);
        var manager = new DefaultChainContextManager(
                contexts, contexts, contextSources.source());
        String workspaceId = role == ChainRole.EXECUTOR
                ? "product-workspace." + sha256(
                        "workspace\0AGENT_TURN:" + task.turnId()) : null;
        var exactCandidate = role == ChainRole.EXECUTOR ? candidate : null;
        if (exactCandidate != null && !exactCandidate.workspaceId().equals(
                workspaceId)) {
            throw failure("CHAIN_PENDING_CANDIDATE_WORKSPACE_MISMATCH");
        }
        var runtimePolicy = ProductChainRuntimePolicySource.forTask(
                contexts, task.taskId());
        var building = new ChainPersistenceRecords.ContextRevisionRecord(
                identity.contextRevisionId(), task.taskId(),
                identity.parentContextRevisionId(),
                role, ChainWorkState.VALIDATING_PENDING_ITEM, callReason,
                instruction.instructionId(), plan == null ? null
                : plan.taskFrameId(), plan == null ? null : plan.planId(),
                plan == null ? null : plan.planRevisionId(),
                plan == null ? null : (long) plan.planRevisionNumber(),
                step == null ? null : step.stepId(),
                step == null ? null : step.activationEventId(),
                task.projectId(), task.initialProjectVersion(), workspaceId,
                exactCandidate == null ? null : exactCandidate.artifactId(),
                exactCandidate == null ? null
                        : exactCandidate.candidateFingerprint(),
                null, null, null,
                "chain-product-projector-v1", "v1",
                runtimePolicy.policyVersion(),
                ChainContextRevisionStatus.BUILDING, 0, null, null, null,
                null, null, now, null);
        ChainContextFreezeOutcome frozen = manager.freeze(
                new io.paperagent.v2.chain.context.ChainContextFreezeRequest(
                        building,
                        runtimePolicy.contextRequestCharactersMax()));
        if (!(frozen instanceof ChainContextFreezeOutcome.Complete complete)) {
            throw failure("CHAIN_PENDING_MODEL_CONTEXT_BLOCKED");
        }
        UserSettingsService.ModelEndpoint endpoint = settings.resolveModelEndpoint(
                task.userId(), session.getModelProviderSnapshot(),
                session.getModelSnapshot());
        ProductChainModelEndpointResolver resolver = ignored ->
                new ProductChainModelEndpoint(
                        endpoint.providerKey(), endpoint.modelName(),
                        endpoint.apiKey(), endpoint.apiUrl());
        ChainModelProtocolService protocol = new ChainModelProtocolService(
                manager, models, models,
                new ProductChainModelMaterializationAdapter(
                        models, models, models, transactions),
                new ProductChainChatModelAdapter(provider, resolver),
                (raw, selectedRole, state, boundGap) ->
                        new StrictChainProviderOutputParser().parse(
                                raw, selectedRole, state, boundGap));
        ChainModelProtocolOutcome outcome = protocol.invoke(
                new ChainModelProtocolRequest(
                        task.taskId(), identity.invocationId(),
                        identity.contextRevisionId(),
                        complete.context().revision().completionToken(), role,
                        ChainWorkState.VALIDATING_PENDING_ITEM, callReason,
                        endpoint.providerKey(), endpoint.modelName(),
                        identity.invocationOrdinal(), gapId, now));
        if (!(outcome instanceof ChainModelProtocolOutcome.ProposalReady ready)) {
            throw failure("CHAIN_PENDING_MODEL_PROPOSAL_NOT_READY");
        }
        new ProductChainProposalAdmissionAdapter(
                jdbc, transactions, models, models).admit(
                new io.paperagent.v2.chain.model
                        .ChainProposalAdmissionService.AdmissionRequest(
                        ready.proposal().proposalId(), task.taskId(),
                        "proposal-accepted." + sha256(
                                ready.proposal().proposalId()), true, null,
                        ready.proposal().payload().sha256(), now));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(
                field + " must not be blank");
        return value;
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
