package com.yanban.api.agent.v2.chain.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.v2.chain.delivery.ProductChainDeliveryMessageAdapter;
import com.yanban.api.agent.v2.chain.finalization.ProductChainTerminalOutcomeAuthority;
import com.yanban.api.agent.v2.chain.context.ProductChainContextSourceFactory;
import com.yanban.api.agent.v2.chain.context.ProductChainRuntimePolicySource;
import com.yanban.api.agent.v2.chain.context.ProductChainModelCallIdentity;
import com.yanban.api.agent.v2.chain.context.ProductChainContextIdentity;
import com.yanban.api.agent.v2.chain.model.ProductChainChatModelAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainModelEndpoint;
import com.yanban.api.agent.v2.chain.model.ProductChainModelMaterializationAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainProposalAdmissionAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatModelProvider;
import io.paperagent.v2.chain.AnswerPayload;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainDeliveryWriter;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.context.ChainContextFreezeOutcome;
import io.paperagent.v2.chain.context.DefaultChainContextManager;
import io.paperagent.v2.chain.delivery.ChainDeliveryMessagePort;
import io.paperagent.v2.chain.delivery.ChainDeliveryRuntime;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.model.ChainModelProtocolRequest;
import io.paperagent.v2.chain.model.ChainModelProtocolService;
import io.paperagent.v2.chain.model.ChainProviderProtocolCode;
import io.paperagent.v2.chain.model.ChainProviderProtocolException;
import io.paperagent.v2.chain.model.ChainRoleOutputDecoder;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import io.paperagent.v2.chain.instruction.ChainInstructionStateReader;
import io.paperagent.v2.chain.route.ChainRouteRuntime;
import io.paperagent.v2.contracts.PlanId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Runs the Answer model turn and binds its formal payload to Delivery. */
@Component
public final class ProductChainAnswerDeliveryProgression {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ProductChainContextRepositoryAdapter contexts;
    private final ProductChainModelRepositoryAdapter models;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductChainFoundationRepositoryAdapter foundations;
    private final ChainFinalizationRepository finalization;
    private final ProductChainExecutorProgression executor;
    private final UserSettingsService settings;
    private final ChatModelProvider provider;
    private final PlatformTransactionManager transactions;
    private final NamedParameterJdbcTemplate jdbc;
    private final ChainDeliveryWriter deliveryWriter;
    private final ChainDeliveryMessagePort messages;
    private final ProductChainContextSourceFactory contextSources;
    private final ProductChainModelCallIdentity modelCallIdentity;
    private final ProductChainTerminalOutcomeAuthority terminalOutcomes;

    public ProductChainAnswerDeliveryProgression(
            ProductChainContextRepositoryAdapter contexts,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainFoundationRepositoryAdapter foundations,
            ChainFinalizationRepository finalization,
            ProductChainExecutorProgression executor,
            UserSettingsService settings,
            @Qualifier("chatModelProvider") ChatModelProvider provider,
            PlatformTransactionManager transactions,
            NamedParameterJdbcTemplate jdbc,
            ProductChainFinalizationRepositoryAdapter deliveryWriter,
            ProductChainDeliveryMessageAdapter messages,
            ProductChainContextSourceFactory contextSources,
            ProductChainModelCallIdentity modelCallIdentity,
            ProductChainTerminalOutcomeAuthority terminalOutcomes) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.models = Objects.requireNonNull(models, "models");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.deliveryWriter = Objects.requireNonNull(deliveryWriter, "deliveryWriter");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.contextSources = Objects.requireNonNull(
                contextSources, "contextSources");
        this.modelCallIdentity = Objects.requireNonNull(
                modelCallIdentity, "modelCallIdentity");
        this.terminalOutcomes = Objects.requireNonNull(
                terminalOutcomes, "terminalOutcomes");
    }

    /**
     * Invokes one Answer model turn for the selector's exact DIRECT route and
     * persists only its accepted proposal. Official consumption is a separate
     * replayable boundary owned by {@link #consumeAcceptedDirect}.
     */
    public DirectProposal invokeDirectAnswer(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String routeDecisionId,
            Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(now, "now");
        if (!Objects.equals(session.getId(), task.sessionId())
                || !Objects.equals(session.getUserId(), task.userId())
                || !Objects.equals(session.getProjectId(),
                task.projectId())) {
            throw new IllegalStateException(
                    "CHAIN_DIRECT_ANSWER_SESSION_IDENTITY_INVALID");
        }
        ChainPersistenceRecords.RouteDecisionRecord route =
                requireCurrentDirectRoute(
                        task, instruction, routeDecisionId, false);
        String contextId = directContextId(
                task.taskId(), route.routeDecisionId());
        String invocationId = identity("invocation", contextId);
        ProductChainModelCallIdentity.Binding callIdentity =
                modelCallIdentity.bind(
                        task.taskId(), contextId, invocationId);
        DefaultChainContextManager manager = new DefaultChainContextManager(
                contexts, contexts, contextSources.source());
        ChainPersistenceRecords.ContextRevisionRecord building =
                new ChainPersistenceRecords.ContextRevisionRecord(
                        callIdentity.contextRevisionId(), task.taskId(),
                        callIdentity.parentContextRevisionId(),
                        ChainRole.ANSWER,
                        ChainWorkState.DIRECT_ANSWERING,
                        "DIRECT_ROUTE", instruction.instructionId(),
                        null, null, null, null, null, null,
                        task.projectId(), task.initialProjectVersion(),
                        null, null, null, null, null, null,
                        "chain-product-projector-v1", "v1",
                        ProductChainRuntimePolicySource.forTask(
                                contexts, task.taskId()).policyVersion(),
                        ChainContextRevisionStatus.BUILDING, 0,
                        null, null, null, null, null, now, null);
        ChainContextFreezeOutcome frozen = manager.freeze(
                new io.paperagent.v2.chain.context
                        .ChainContextFreezeRequest(building,
                        ProductChainRuntimePolicySource.forTask(
                                contexts, task.taskId())
                                .contextRequestCharactersMax()));
        if (!(frozen instanceof ChainContextFreezeOutcome.Complete complete)) {
            throw new IllegalStateException(
                    "Direct Answer context input is blocked");
        }
        UserSettingsService.ModelEndpoint endpoint =
                settings.resolveModelEndpoint(
                        task.userId(), session.getModelProviderSnapshot(),
                        session.getModelSnapshot());
        ChainRoleOutputDecoder decoder = (raw, role, state, gap) -> {
            var decoded = new StrictChainProviderOutputParser()
                    .parse(raw, role, state, gap);
            validateDirectRoutePayload(decoded.payload(), route);
            return decoded;
        };
        ChainModelProtocolService protocol = new ChainModelProtocolService(
                manager, models, models,
                new ProductChainModelMaterializationAdapter(
                        models, models, models, transactions),
                new ProductChainChatModelAdapter(provider, request ->
                        new ProductChainModelEndpoint(
                                endpoint.providerKey(), endpoint.modelName(),
                                endpoint.apiKey(), endpoint.apiUrl())),
                decoder);
        ChainModelProtocolOutcome result = protocol.invoke(
                new ChainModelProtocolRequest(
                        task.taskId(), callIdentity.invocationId(),
                        callIdentity.contextRevisionId(),
                        complete.context().revision().completionToken(),
                        ChainRole.ANSWER,
                        ChainWorkState.DIRECT_ANSWERING,
                        "DIRECT_ROUTE", endpoint.providerKey(),
                        endpoint.modelName(),
                        callIdentity.invocationOrdinal(), null, now));
        if (!(result instanceof ChainModelProtocolOutcome.ProposalReady ready)
                || ready.proposal().role() != ChainRole.ANSWER
                || (ready.proposal().proposalKind()
                != ChainProposalKind.ANSWER_DIRECT_ANSWER
                && ready.proposal().proposalKind()
                != ChainProposalKind.ANSWER_ESCALATE_TO_PERSISTENT)) {
            throw new IllegalStateException(
                    "CHAIN_DIRECT_ANSWER_PROPOSAL_MISSING");
        }
        AnswerPayload payload = decodeDirectProposal(ready);
        validateDirectRoutePayload(payload, route);
        ProductChainProposalAdmissionAdapter admission =
                new ProductChainProposalAdmissionAdapter(
                        jdbc, transactions, models, models);
        admission.admit(new io.paperagent.v2.chain.model
                .ChainProposalAdmissionService.AdmissionRequest(
                        ready.proposal().proposalId(), task.taskId(),
                        identity("answer-proposal-accepted",
                                ready.proposal().proposalId()),
                        true, null, ready.proposal().payload().sha256(), now));
        return new DirectProposal(
                ready.proposal().proposalId(),
                ready.proposal().proposalKind(), callIdentity.invocationId(),
                route.routeDecisionId());
    }

    /**
     * Consumes an exact accepted Direct-Answer proposal without invoking the
     * model again. Direct answers become RouteSource Delivery; escalation is
     * committed by the existing monotonic route runtime.
     */
    public DirectConsumption consumeAcceptedDirect(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String routeDecisionId,
            String proposalId,
            Instant now) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(now, "now");
        ChainPersistenceRecords.RouteDecisionRecord route =
                requireCurrentDirectRoute(
                        task, instruction, routeDecisionId, true);
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(required(proposalId, "proposalId"))
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_DIRECT_ANSWER_PROPOSAL_MISSING"));
        ChainPersistenceRecords.ModelInvocationRecord invocation = models
                .findInvocation(proposal.invocationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_DIRECT_ANSWER_INVOCATION_MISSING"));
        ChainPersistenceRecords.ContextRevisionRecord context = contexts
                .findContextRevision(invocation.contextRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_DIRECT_ANSWER_CONTEXT_MISSING"));
        validateDirectInvocationIdentity(
                task, instruction, route, proposal, invocation, context);
        validateAcceptedStatePrefix(
                proposal, models.findProposalStateEvents(proposalId));
        List<ChainPersistenceRecords.RouteDecisionRecord> escalations =
                workflow.findRouteDecisions(task.taskId()).stream()
                        .filter(value -> value.instructionId().equals(
                                instruction.instructionId()))
                        .filter(value -> value.decisionKind()
                                == ChainPersistenceRecords
                                .RouteDecisionType.ESCALATION)
                        .toList();
        if (proposal.proposalKind()
                == ChainProposalKind.ANSWER_DIRECT_ANSWER
                && !escalations.isEmpty()) {
            throw new IllegalStateException(
                    "CHAIN_DIRECT_ANSWER_ROUTE_ALREADY_ESCALATED");
        }
        if (proposal.proposalKind()
                == ChainProposalKind.ANSWER_ESCALATE_TO_PERSISTENT
                && !escalations.isEmpty()
                && (escalations.size() != 1
                || !proposal.proposalId().equals(
                escalations.get(0).proposalId())
                || !route.routeDecisionId().equals(
                escalations.get(0).parentRouteDecisionId()))) {
            throw new IllegalStateException(
                    "CHAIN_DIRECT_ESCALATION_REPLAY_IDENTITY_INVALID");
        }

        ProductChainProposalAdmissionAdapter admission =
                new ProductChainProposalAdmissionAdapter(
                        jdbc, transactions, models, models);
        if (proposal.proposalKind()
                == ChainProposalKind.ANSWER_DIRECT_ANSWER) {
            ChainPersistenceRecords.ContentRecord body = models
                    .findContent(proposal.bodyAuthorityRef())
                    .orElseThrow(() -> new IllegalStateException(
                            "CHAIN_DIRECT_ANSWER_BODY_MISSING"));
            AnswerPayload payload = decodeDirectProposal(
                    new ChainModelProtocolOutcome.ProposalReady(
                            proposal, body, 0, true));
            validateDirectRoutePayload(payload, route);
            ChainDeliveryRuntime runtime = directDeliveryRuntime(
                    admission, proposal, now);
            ChainDeliveryRuntime.Started started = runtime.begin(
                    new ChainDeliveryRuntime.BeginCommand(
                            task.taskId(), instruction.commandId(),
                            proposal.proposalId(),
                            new ChainDeliveryRuntime.RouteSource(
                                    route.routeDecisionId()),
                            payload, now));
            return new DirectDelivered(runtime.attempt(
                    task.taskId(), started.delivery().deliveryId(), now));
        }
        if (proposal.proposalKind()
                != ChainProposalKind.ANSWER_ESCALATE_TO_PERSISTENT) {
            throw new IllegalStateException(
                    "CHAIN_DIRECT_ANSWER_PROPOSAL_KIND_INVALID");
        }
        AnswerPayload payload = decodeDirectProposal(
                new ChainModelProtocolOutcome.ProposalReady(
                        proposal, null, 0, true));
        validateDirectRoutePayload(payload, route);
        ChainRouteRuntime routeRuntime = new ChainRouteRuntime(
                models, workflow, workflow,
                new ChainInstructionStateReader(
                        foundations, workflow, finalization),
                (taskId, acceptedProposalId, type, ref) ->
                        admission.replaceByOfficialResult(
                                new io.paperagent.v2.chain.model
                                        .ChainProposalAdmissionService
                                        .OfficialReplacement(
                                                acceptedProposalId, taskId,
                                                identity("route-bound", ref),
                                                ChainPersistenceRecords
                                                        .ProposalOfficialAuthorityType
                                                        .ROUTE_DECISION,
                                                ref, null,
                                                proposal.payload().sha256(),
                                                now)));
        ChainPersistenceRecords.RouteDecisionRecord escalated = routeRuntime
                .escalate(new ChainRouteRuntime.EscalationRequest(
                                new ChainRouteRuntime.CommonRequest(
                                        task.taskId(),
                                        instruction.instructionId(),
                                        proposal.proposalId(),
                                        identity("route-escalation-event",
                                                proposal.proposalId()),
                                        now)),
                        (AnswerPayload.EscalateToPersistent) payload);
        return new DirectEscalated(escalated);
    }

    private ChainDeliveryRuntime directDeliveryRuntime(
            ProductChainProposalAdmissionAdapter admission,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            Instant now) {
        return new ChainDeliveryRuntime(
                foundations, workflow, finalization, models,
                deliveryWriter, messages,
                (taskId, proposalId, type, ref) ->
                        admission.replaceByOfficialResult(
                                new io.paperagent.v2.chain.model
                                        .ChainProposalAdmissionService
                                        .OfficialReplacement(
                                                proposalId, taskId,
                                                identity("delivery-bound", ref),
                                                ChainPersistenceRecords
                                                        .ProposalOfficialAuthorityType
                                                        .DELIVERY,
                                                ref, null,
                                                proposal.payload().sha256(),
                                                now)),
                boundTaskId -> ProductChainRuntimePolicySource.forTask(
                        contexts, boundTaskId));
    }

    /** Invokes Answer for one exact open PendingItem and only accepts it. */
    public PendingAnswerProposal invokePendingItemAnswer(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String gapId,
            Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(now, "now");
        if (!Objects.equals(session.getId(), task.sessionId())
                || !Objects.equals(session.getUserId(), task.userId())
                || !Objects.equals(session.getProjectId(), task.projectId())) {
            throw new IllegalStateException(
                    "CHAIN_PENDING_ANSWER_SESSION_IDENTITY_INVALID");
        }
        ChainPersistenceRecords.PendingItemRecord pending =
                requireOpenPending(task.taskId(), gapId);
        ChainWorkState state = pending.pendingType()
                == ChainPendingItemType.PERMISSION
                ? ChainWorkState.WAITING_PERMISSION
                : ChainWorkState.WAITING_USER;
        String contextId = ProductChainContextIdentity.pendingItemAnswer(
                task.taskId(), pending.gapId(), state.name());
        String invocationId = identity("invocation", contextId);
        ProductChainModelCallIdentity.Binding callIdentity = modelCallIdentity
                .bind(task.taskId(), contextId, invocationId);
        DefaultChainContextManager manager = new DefaultChainContextManager(
                contexts, contexts, contextSources.source());
        var building = new ChainPersistenceRecords.ContextRevisionRecord(
                callIdentity.contextRevisionId(), task.taskId(),
                callIdentity.parentContextRevisionId(), ChainRole.ANSWER,
                state, "PENDING_ITEM", instruction.instructionId(),
                null, null, null, null, null, null,
                task.projectId(), task.initialProjectVersion(),
                null, null, null, null, null, null,
                "chain-product-projector-v1", "v1",
                ProductChainRuntimePolicySource.forTask(
                        contexts, task.taskId()).policyVersion(),
                ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, now, null);
        ChainContextFreezeOutcome frozen = manager.freeze(
                new io.paperagent.v2.chain.context
                        .ChainContextFreezeRequest(building,
                        ProductChainRuntimePolicySource.forTask(
                                contexts, task.taskId())
                                .contextRequestCharactersMax()));
        if (!(frozen instanceof ChainContextFreezeOutcome.Complete complete)) {
            throw new IllegalStateException(
                    "Pending Answer context input is blocked");
        }
        UserSettingsService.ModelEndpoint endpoint = settings
                .resolveModelEndpoint(task.userId(),
                        session.getModelProviderSnapshot(),
                        session.getModelSnapshot());
        ChainRoleOutputDecoder decoder = (raw, role, workState, gap) -> {
            var decoded = new StrictChainProviderOutputParser().parse(
                    raw, role, workState, null);
            if (!(decoded.payload() instanceof AnswerPayload.UserQuestion value)
                    || !value.gapId().equals(pending.gapId())) {
                throw new IllegalStateException(
                        "CHAIN_PENDING_ANSWER_GAP_IDENTITY_INVALID");
            }
            return decoded;
        };
        ChainModelProtocolService protocol = new ChainModelProtocolService(
                manager, models, models,
                new ProductChainModelMaterializationAdapter(
                        models, models, models, transactions),
                new ProductChainChatModelAdapter(provider, request ->
                        new ProductChainModelEndpoint(
                                endpoint.providerKey(), endpoint.modelName(),
                                endpoint.apiKey(), endpoint.apiUrl())), decoder);
        ChainModelProtocolOutcome result = protocol.invoke(
                new ChainModelProtocolRequest(task.taskId(),
                        callIdentity.invocationId(),
                        callIdentity.contextRevisionId(),
                        complete.context().revision().completionToken(),
                        ChainRole.ANSWER, state, "PENDING_ITEM",
                        endpoint.providerKey(), endpoint.modelName(),
                        callIdentity.invocationOrdinal(), null, now));
        if (!(result instanceof ChainModelProtocolOutcome.ProposalReady ready)
                || ready.proposal().role() != ChainRole.ANSWER
                || ready.proposal().proposalKind()
                != ChainProposalKind.ANSWER_USER_QUESTION) {
            throw new IllegalStateException(
                    "CHAIN_PENDING_ANSWER_PROPOSAL_MISSING");
        }
        ProductChainProposalAdmissionAdapter admission =
                new ProductChainProposalAdmissionAdapter(
                        jdbc, transactions, models, models);
        admission.admit(new io.paperagent.v2.chain.model
                .ChainProposalAdmissionService.AdmissionRequest(
                ready.proposal().proposalId(), task.taskId(),
                identity("answer-proposal-accepted",
                        ready.proposal().proposalId()),
                true, null, ready.proposal().payload().sha256(), now));
        return new PendingAnswerProposal(ready.proposal().proposalId(),
                callIdentity.invocationId(), pending.gapId(), state);
    }

    /** Consumes one exact accepted PendingItem Answer without another model call. */
    public PendingConsumption consumeAcceptedPendingItem(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String gapId,
            String proposalId,
            Instant now) {
        ChainPersistenceRecords.PendingItemRecord pending =
                requireOpenPending(task.taskId(), gapId);
        ChainWorkState expectedState = pending.pendingType()
                == ChainPendingItemType.PERMISSION
                ? ChainWorkState.WAITING_PERMISSION
                : ChainWorkState.WAITING_USER;
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(required(proposalId, "proposalId"))
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_PENDING_ANSWER_PROPOSAL_MISSING"));
        ChainPersistenceRecords.ModelInvocationRecord invocation = models
                .findInvocation(proposal.invocationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_PENDING_ANSWER_INVOCATION_MISSING"));
        ChainPersistenceRecords.ContextRevisionRecord context = contexts
                .findContextRevision(invocation.contextRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_PENDING_ANSWER_CONTEXT_MISSING"));
        String expectedContextId = ProductChainContextIdentity
                .pendingItemAnswer(task.taskId(), pending.gapId(),
                        expectedState.name());
        if (!proposal.taskId().equals(task.taskId())
                || proposal.role() != ChainRole.ANSWER
                || proposal.proposalKind()
                != ChainProposalKind.ANSWER_USER_QUESTION
                || !"ANSWER_BODY".equals(proposal.bodyAuthorityType())
                || !proposal.invocationId().equals(invocation.invocationId())
                || !invocation.taskId().equals(task.taskId())
                || invocation.role() != ChainRole.ANSWER
                || invocation.workState() != expectedState
                || !"PENDING_ITEM".equals(invocation.callReason())
                || !invocation.contextRevisionId().equals(expectedContextId)
                || !context.contextRevisionId().equals(expectedContextId)
                || context.status() != ChainContextRevisionStatus.COMPLETE
                || context.role() != ChainRole.ANSWER
                || context.workState() != expectedState
                || !"PENDING_ITEM".equals(context.callReason())
                || !context.instructionId().equals(
                        instruction.instructionId())
                || context.taskFrameId() != null
                || context.planId() != null
                || context.planRevisionId() != null
                || !Objects.equals(context.projectId(), task.projectId())
                || !Objects.equals(context.projectVersion(),
                        task.initialProjectVersion())) {
            throw new IllegalStateException(
                    "CHAIN_PENDING_ANSWER_INVOCATION_IDENTITY_INVALID");
        }
        List<ChainPersistenceRecords.ProposalStateEventRecord> states =
                acceptedPersistentStatePrefix(proposal,
                        models.findProposalStateEvents(proposal.proposalId()));
        ChainPersistenceRecords.ContentRecord body = models
                .findContent(proposal.bodyAuthorityRef())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_PENDING_ANSWER_BODY_MISSING"));
        AnswerPayload payload = decodeCanonicalAnswerPayload(
                new ChainModelProtocolOutcome.ProposalReady(
                        proposal, body, 0, true), expectedState,
                null);
        if (!(payload instanceof AnswerPayload.UserQuestion question)
                || !question.gapId().equals(pending.gapId())) {
            throw new IllegalStateException(
                    "CHAIN_PENDING_ANSWER_GAP_IDENTITY_INVALID");
        }
        if (states.size() == 2) {
            List<ChainPersistenceRecords.DeliveryRecord> deliveries =
                    finalization.findDeliveries(task.taskId()).stream()
                            .filter(value -> value.deliveryId().equals(
                                    states.get(1).officialAuthorityRef()))
                            .filter(value -> Objects.equals(value.gapId(),
                                    pending.gapId()))
                            .filter(value -> value.sourceCommandId().equals(
                                    instruction.commandId()))
                            .filter(value -> Objects.equals(
                                    value.answerContentId(),
                                    proposal.bodyAuthorityRef()))
                            .toList();
            if (deliveries.size() != 1) {
                throw new IllegalStateException(
                        "CHAIN_PENDING_ANSWER_BOUND_DELIVERY_INVALID");
            }
            return new PendingBound(deliveries.get(0));
        }
        ProductChainProposalAdmissionAdapter admission =
                new ProductChainProposalAdmissionAdapter(
                        jdbc, transactions, models, models);
        ChainDeliveryRuntime runtime = new ChainDeliveryRuntime(
                foundations, workflow, finalization, models,
                deliveryWriter, messages,
                (boundTask, boundProposal, type, ref) ->
                        admission.replaceByOfficialResult(
                                new io.paperagent.v2.chain.model
                                        .ChainProposalAdmissionService
                                        .OfficialReplacement(
                                        boundProposal, boundTask,
                                        identity("delivery-bound", ref),
                                        ChainPersistenceRecords
                                                .ProposalOfficialAuthorityType
                                                .DELIVERY,
                                        ref, null,
                                        proposal.payload().sha256(), now)),
                taskId -> ProductChainRuntimePolicySource.forTask(
                        contexts, taskId));
        ChainDeliveryRuntime.Started started = runtime.begin(
                new ChainDeliveryRuntime.BeginCommand(task.taskId(),
                        instruction.commandId(), proposal.proposalId(),
                        new ChainDeliveryRuntime.GapSource(pending.gapId()),
                        payload, now));
        return new PendingDelivered(runtime.attempt(task.taskId(),
                started.delivery().deliveryId(), now));
    }

    /** Invokes persistent Answer and leaves only its accepted proposal. */
    public PersistentAnswerProposal invokePersistentAnswer(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(now, "now");
        if (!Objects.equals(session.getId(), task.sessionId())
                || !Objects.equals(session.getUserId(), task.userId())
                || !Objects.equals(session.getProjectId(),
                        task.projectId())) {
            throw new IllegalStateException(
                    "CHAIN_ANSWER_SESSION_IDENTITY_INVALID");
        }
        ChainPersistenceRecords.TaskOutcomeRecord outcome = finalization
                .findTaskOutcome(task.taskId())
                .orElseThrow(() -> new IllegalStateException("CHAIN_ANSWER_TASK_OUTCOME_MISSING"));
        var terminal = terminalOutcomes.requireExact(task, outcome);
        boolean completed = outcome.outcomeType() == ChainTaskOutcomeStatus.COMPLETED;
        ChainWorkState answerState = completed
                ? ChainWorkState.DELIVERING : ChainWorkState.TERMINAL;
        List<ChainPersistenceRecords.PlanBindingRecord> exactBindings = workflow
                .findPlanBindings(task.taskId()).stream()
                .filter(value -> Objects.equals(value.taskFrameId(),
                        outcome.taskFrameId()))
                .filter(value -> Objects.equals(value.planId(),
                        outcome.finalPlanId()))
                .filter(value -> Objects.equals(value.planRevisionId(),
                        outcome.finalPlanRevisionId()))
                .toList();
        if (exactBindings.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_ANSWER_PLAN_BINDING_MISSING_OR_AMBIGUOUS");
        }
        ChainPersistenceRecords.PlanBindingRecord binding =
                exactBindings.get(0);
        String answerStepId = terminal.finalStepId();
        String answerActivationId = terminal.activationEventId();
        if (answerStepId == null) {
            if (completed) {
                throw new IllegalStateException(
                        "CHAIN_ANSWER_TERMINAL_STEP_MISSING");
            }
            var fallback = executor.latestActivation(
                    task.taskId(), binding.planRevisionId());
            answerStepId = fallback.command().stepId();
            answerActivationId = fallback.command().activationEventId();
        }
        executor.step(binding, answerStepId);
        String workspaceId = "product-workspace." + sha256(
                "workspace\0AGENT_TURN:" + task.turnId());
        List<String> expectedArtifactRefs = outcome.finalArtifactId() == null
                ? List.of(io.paperagent.v2.chain.ChainIdentity.NONE)
                : List.of(ChainIdentity.candidateArtifactRef(
                        outcome.finalArtifactId()), outcome.candidateKey());
        String contextValidationId = terminal.validation().validationId();
        ValidationIdentity contextValidation = validationIdentity(
                terminal.validation(), contextValidationId);
        String validationRef = contextValidationId == null
                ? io.paperagent.v2.chain.ChainIdentity.NONE
                : contextValidationId;
        String publishRef = outcome.publishReceiptId() == null
                || outcome.publishReceiptId().isBlank() ? "NONE" : outcome.publishReceiptId();
        String contextCandidateFingerprint = outcomeCandidateFingerprint(
                outcome, workflow.findWorkspaceCandidates(task.taskId()));
        long authorityCut = foundations.highestAuthorityEventSequence(task.taskId());
        String latestDecisionRef = latestDecisionRef(
                workflow.findReviewDecisions(task.taskId()),
                foundations.findAuthorityEvents(task.taskId(), authorityCut));
        String contextId = ProductChainContextIdentity.taskOutcomeAnswer(
                task.taskId(), outcome.outcomeId());
        String invocationId = identity("invocation", contextId);
        ProductChainModelCallIdentity.Binding callIdentity =
                modelCallIdentity.bind(
                        task.taskId(), contextId, invocationId);
        DefaultChainContextManager manager = new DefaultChainContextManager(
                contexts, contexts, contextSources.source());
        ChainPersistenceRecords.ContextRevisionRecord building =
                new ChainPersistenceRecords.ContextRevisionRecord(
                        callIdentity.contextRevisionId(), task.taskId(),
                        callIdentity.parentContextRevisionId(), ChainRole.ANSWER,
                        answerState, "TASK_OUTCOME",
                        instruction.instructionId(), binding.taskFrameId(), binding.planId(),
                        binding.planRevisionId(), binding.planRevisionNumber(),
                        answerStepId, answerActivationId,
                        task.projectId(), task.initialProjectVersion(), workspaceId,
                        outcome.finalArtifactId(), contextCandidateFingerprint,
                        contextValidation.validationId(),
                        contextValidation.requestDigest(),
                        contextValidation.receiptDigest(),
                        "chain-product-projector-v1", "v1",
                        ProductChainRuntimePolicySource.forTask(
                                contexts, task.taskId()).policyVersion(),
                        ChainContextRevisionStatus.BUILDING, 0,
                        null, null, null, null, null, now, null);
        ChainContextFreezeOutcome frozen = manager.freeze(
                new io.paperagent.v2.chain.context.ChainContextFreezeRequest(
                        building, ProductChainRuntimePolicySource.forTask(
                                contexts, task.taskId())
                                .contextRequestCharactersMax()));
        if (!(frozen instanceof ChainContextFreezeOutcome.Complete complete)) {
            throw new IllegalStateException("Answer context input is blocked");
        }
        UserSettingsService.ModelEndpoint endpoint = settings.resolveModelEndpoint(
                task.userId(), session.getModelProviderSnapshot(), session.getModelSnapshot());
        ChainRoleOutputDecoder decoder = (raw, role, state, gap) -> {
            var decoded = new StrictChainProviderOutputParser()
                    .parse(raw, role, state, gap);
            validateTerminalAnswerRefs(decoded.payload(), completed, outcome,
                    latestDecisionRef, expectedArtifactRefs, validationRef, publishRef);
            return decoded;
        };
        ChainModelProtocolService protocol = new ChainModelProtocolService(
                manager, models, models,
                new ProductChainModelMaterializationAdapter(models, models, models, transactions),
                new ProductChainChatModelAdapter(provider, request ->
                        new ProductChainModelEndpoint(endpoint.providerKey(), endpoint.modelName(),
                                endpoint.apiKey(), endpoint.apiUrl())), decoder);
        ChainModelProtocolOutcome result = protocol.invoke(new ChainModelProtocolRequest(
                task.taskId(), callIdentity.invocationId(),
                callIdentity.contextRevisionId(),
                complete.context().revision().completionToken(), ChainRole.ANSWER,
                answerState, "TASK_OUTCOME", endpoint.providerKey(),
                endpoint.modelName(), callIdentity.invocationOrdinal(), null,
                now));
        ChainProposalKind expectedAnswerKind = completed
                ? ChainProposalKind.ANSWER_FINAL_DELIVERY
                : ChainProposalKind.ANSWER_STATUS_OR_FAILURE;
        if (!(result instanceof ChainModelProtocolOutcome.ProposalReady ready)
                || ready.proposal().role() != ChainRole.ANSWER
                || ready.proposal().proposalKind() != expectedAnswerKind) {
            throw new IllegalStateException(completed
                    ? "CHAIN_ANSWER_FINAL_DELIVERY_PROPOSAL_MISSING"
                    : "CHAIN_ANSWER_STATUS_FAILURE_PROPOSAL_MISSING");
        }
        AnswerPayload payload = decodeCanonicalAnswerPayload(ready);
        ProductChainProposalAdmissionAdapter admission =
                new ProductChainProposalAdmissionAdapter(jdbc, transactions, models, models);
        admission.admit(new io.paperagent.v2.chain.model.ChainProposalAdmissionService.AdmissionRequest(
                ready.proposal().proposalId(), task.taskId(),
                identity("answer-proposal-accepted", ready.proposal().proposalId()), true,
                null, ready.proposal().payload().sha256(), now));
        return new PersistentAnswerProposal(ready.proposal().proposalId(),
                ready.proposal().proposalKind(), callIdentity.invocationId(),
                outcome.outcomeId());
    }

    /** Compatibility composition for the synchronous product entrypoint. */
    public ChainDeliveryRuntime.Attempted deliver(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            Instant now) {
        PersistentAnswerProposal proposal = invokePersistentAnswer(
                session, task, instruction, now);
        PersistentConsumption consumed = consumeAcceptedPersistent(
                task, instruction, proposal.outcomeId(),
                proposal.proposalId(), now);
        if (consumed instanceof PersistentDelivered delivered) {
            return delivered.attempted();
        }
        throw new IllegalStateException(
                "CHAIN_ANSWER_SYNCHRONOUS_PROPOSAL_ALREADY_BOUND");
    }

    /**
     * Consumes one exact accepted persistent Answer proposal without another
     * model call. An already-bound Delivery is returned read-only.
     */
    public PersistentConsumption consumeAcceptedPersistent(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String proposalId,
            Instant now) {
        ChainPersistenceRecords.TaskOutcomeRecord outcome = finalization
                .findTaskOutcome(task.taskId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_ANSWER_TASK_OUTCOME_MISSING"));
        return consumeAcceptedPersistent(task, instruction,
                outcome.outcomeId(), proposalId, now);
    }

    public PersistentConsumption consumeAcceptedPersistent(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String outcomeId,
            String proposalId,
            Instant now) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(now, "now");
        ChainPersistenceRecords.TaskOutcomeRecord outcome = finalization
                .findTaskOutcome(task.taskId())
                .filter(value -> value.outcomeId().equals(
                        required(outcomeId, "outcomeId")))
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_ANSWER_TASK_OUTCOME_IDENTITY_INVALID"));
        boolean completed = outcome.outcomeType()
                == ChainTaskOutcomeStatus.COMPLETED;
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(required(proposalId, "proposalId"))
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_ANSWER_PROPOSAL_MISSING"));
        ChainPersistenceRecords.ModelInvocationRecord invocation = models
                .findInvocation(proposal.invocationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_ANSWER_INVOCATION_MISSING"));
        ChainPersistenceRecords.ContextRevisionRecord context = contexts
                .findContextRevision(invocation.contextRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_ANSWER_CONTEXT_MISSING"));
        validatePersistentInvocationIdentity(task, instruction, outcome,
                proposal, invocation, context, completed);
        List<ChainPersistenceRecords.ProposalStateEventRecord> states =
                acceptedPersistentStatePrefix(proposal,
                        models.findProposalStateEvents(proposal.proposalId()));
        ChainPersistenceRecords.ContentRecord body = models
                .findContent(proposal.bodyAuthorityRef())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_ANSWER_BODY_MISSING"));
        AnswerPayload payload = decodeCanonicalAnswerPayload(
                new ChainModelProtocolOutcome.ProposalReady(
                        proposal, body, 0, true));
        validatePersistentPayload(task, outcome, payload, completed);
        if (states.size() == 2) {
            String deliveryId = states.get(1).officialAuthorityRef();
            List<ChainPersistenceRecords.DeliveryRecord> deliveries =
                    finalization.findDeliveries(task.taskId()).stream()
                            .filter(value -> value.deliveryId().equals(
                                    deliveryId))
                            .filter(value -> Objects.equals(
                                    value.taskOutcomeId(), outcome.outcomeId()))
                            .filter(value -> value.sourceCommandId().equals(
                                    task.createdByCommandId()))
                            .filter(value -> Objects.equals(
                                    value.answerContentId(),
                                    proposal.bodyAuthorityRef()))
                            .toList();
            if (deliveries.size() != 1) {
                throw new IllegalStateException(
                        "CHAIN_ANSWER_BOUND_DELIVERY_INVALID");
            }
            return new PersistentBound(deliveries.get(0));
        }
        ProductChainProposalAdmissionAdapter admission =
                new ProductChainProposalAdmissionAdapter(
                        jdbc, transactions, models, models);
        ChainDeliveryRuntime runtime = new ChainDeliveryRuntime(
                foundations, workflow, finalization, models,
                deliveryWriter, messages, (taskId, boundProposalId, type, ref) ->
                admission.replaceByOfficialResult(
                        new io.paperagent.v2.chain.model.ChainProposalAdmissionService.OfficialReplacement(
                                boundProposalId, taskId,
                                identity("delivery-bound", ref),
                                ChainPersistenceRecords.ProposalOfficialAuthorityType.DELIVERY,
                                ref, null, proposal.payload().sha256(), now)),
                taskId -> ProductChainRuntimePolicySource.forTask(
                        contexts, taskId));
        ChainDeliveryRuntime.Started started = runtime.begin(
                new ChainDeliveryRuntime.BeginCommand(task.taskId(), task.createdByCommandId(),
                        proposal.proposalId(),
                        new ChainDeliveryRuntime.TaskOutcomeSource(outcome.outcomeId()),
                        payload, now));
        return new PersistentDelivered(runtime.attempt(
                task.taskId(), started.delivery().deliveryId(), now));
    }

    private void validatePersistentInvocationIdentity(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            ChainPersistenceRecords.ContextRevisionRecord context,
            boolean completed) {
        ChainProposalKind expected = completed
                ? ChainProposalKind.ANSWER_FINAL_DELIVERY
                : ChainProposalKind.ANSWER_STATUS_OR_FAILURE;
        ChainWorkState expectedState = completed
                ? ChainWorkState.DELIVERING : ChainWorkState.TERMINAL;
        List<ChainPersistenceRecords.PlanBindingRecord> bindings = workflow
                .findPlanBindings(task.taskId()).stream()
                .filter(value -> value.taskFrameId().equals(
                        outcome.taskFrameId()))
                .filter(value -> value.planId().equals(outcome.finalPlanId()))
                .filter(value -> value.planRevisionId().equals(
                        outcome.finalPlanRevisionId()))
                .toList();
        if (bindings.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_ANSWER_PLAN_BINDING_MISSING_OR_AMBIGUOUS");
        }
        ChainPersistenceRecords.PlanBindingRecord binding = bindings.get(0);
        var terminal = terminalOutcomes.requireExact(task, outcome);
        String expectedStepId = terminal.finalStepId();
        String expectedActivationId = terminal.activationEventId();
        if (expectedStepId == null) {
            var fallback = executor.latestActivation(
                    task.taskId(), binding.planRevisionId());
            expectedStepId = fallback.command().stepId();
            expectedActivationId = fallback.command().activationEventId();
        }
        String expectedContextId = ProductChainContextIdentity
                .taskOutcomeAnswer(task.taskId(), outcome.outcomeId());
        List<ChainPersistenceRecords.WorkspaceCandidateRecord> candidates =
                outcome.finalArtifactId() == null ? List.of() : workflow
                .findWorkspaceCandidates(task.taskId()).stream()
                .filter(value -> outcome.candidateKey().equals(
                        value.workspaceCandidateId())
                        || outcome.candidateKey().equals(
                        value.candidateFingerprint()))
                .filter(value -> value.artifactId()
                        == outcome.finalArtifactId())
                .toList();
        if (outcome.finalArtifactId() != null && candidates.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_ANSWER_CANDIDATE_IDENTITY_INVALID");
        }
        String expectedFingerprint = candidates.isEmpty() ? null
                : candidates.get(0).candidateFingerprint();
        String validationId = terminal.validation().validationId();
        ValidationIdentity validation = validationIdentity(
                terminal.validation(), validationId);
        if (!proposal.taskId().equals(task.taskId())
                || proposal.role() != ChainRole.ANSWER
                || proposal.proposalKind() != expected
                || !"ANSWER_BODY".equals(proposal.bodyAuthorityType())
                || proposal.bodyAuthorityRef() == null
                || !proposal.invocationId().equals(invocation.invocationId())
                || !invocation.taskId().equals(task.taskId())
                || invocation.role() != ChainRole.ANSWER
                || invocation.workState() != expectedState
                || !"TASK_OUTCOME".equals(invocation.callReason())
                || !context.contextRevisionId().equals(
                        invocation.contextRevisionId())
                || !context.contextRevisionId().equals(expectedContextId)
                || !invocation.invocationId().equals(
                        identity("invocation", expectedContextId))
                || !context.taskId().equals(task.taskId())
                || context.role() != ChainRole.ANSWER
                || context.workState() != expectedState
                || !"TASK_OUTCOME".equals(context.callReason())
                || context.status() != ChainContextRevisionStatus.COMPLETE
                || !context.instructionId().equals(
                        instruction.instructionId())
                || !Objects.equals(context.taskFrameId(),
                        binding.taskFrameId())
                || !Objects.equals(context.planId(), binding.planId())
                || !Objects.equals(context.planRevisionId(),
                        binding.planRevisionId())
                || !Objects.equals(context.planRevisionNumber(),
                        binding.planRevisionNumber())
                || !Objects.equals(context.stepId(), expectedStepId)
                || !Objects.equals(context.activationEventId(),
                        expectedActivationId)
                || !Objects.equals(context.projectId(), task.projectId())
                || !Objects.equals(context.projectVersion(),
                        task.initialProjectVersion())
                || !Objects.equals(context.candidateArtifactId(),
                        outcome.finalArtifactId())
                || !Objects.equals(context.candidateFingerprint(),
                        expectedFingerprint)
                || !Objects.equals(context.validationId(),
                        validation.validationId())
                || !Objects.equals(context.validationRequestDigest(),
                        validation.requestDigest())
                || !Objects.equals(context.validationReceiptDigest(),
                        validation.receiptDigest())) {
            throw new IllegalStateException(
                    "CHAIN_ANSWER_INVOCATION_IDENTITY_INVALID");
        }
    }

    private List<ChainPersistenceRecords.ProposalStateEventRecord>
            acceptedPersistentStatePrefix(
                    ChainPersistenceRecords.ModelProposalRecord proposal,
                    List<ChainPersistenceRecords.ProposalStateEventRecord>
                            events) {
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = events
                .stream().sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence)).toList();
        if (states.isEmpty() || states.size() > 2) {
            throw new IllegalStateException(
                    "CHAIN_ANSWER_PROPOSAL_STATE_INVALID");
        }
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            var state = states.get(index);
            if (!state.taskId().equals(proposal.taskId())
                    || !state.proposalId().equals(proposal.proposalId())
                    || state.stateSequence() != index + 1L) {
                throw new IllegalStateException(
                        "CHAIN_ANSWER_PROPOSAL_STATE_INVALID");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "CHAIN_ANSWER_PROPOSAL_STATE_INVALID", invalid);
            }
            prefix.add(state.stateKind());
        }
        if (states.get(0).stateKind() != ChainProposalState.ACCEPTED
                || states.get(0).officialAuthorityType() != null
                || states.get(0).officialAuthorityRef() != null
                || (states.size() == 2
                && !"DELIVERY".equals(
                        states.get(1).officialAuthorityType()))) {
            throw new IllegalStateException(
                    "CHAIN_ANSWER_PROPOSAL_NOT_ACCEPTED_OR_BOUND_ELSEWHERE");
        }
        return states;
    }

    private void validatePersistentPayload(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            AnswerPayload payload,
            boolean completed) {
        if (completed && !(payload instanceof AnswerPayload.FinalDelivery)) {
            throw new IllegalStateException(
                    "CHAIN_ANSWER_PAYLOAD_TYPE_MISMATCH");
        }
        if (!completed
                && !(payload instanceof AnswerPayload.StatusOrFailure)) {
            throw new IllegalStateException(
                    "CHAIN_ANSWER_STATUS_FAILURE_PAYLOAD_TYPE_MISMATCH");
        }
        List<String> artifactRefs = outcome.finalArtifactId() == null
                ? List.of(ChainIdentity.NONE)
                : List.of(ChainIdentity.candidateArtifactRef(
                        outcome.finalArtifactId()),
                        outcome.candidateKey());
        String validationRef = outcome.validationId() == null
                || outcome.validationId().isBlank()
                ? ChainIdentity.NONE : outcome.validationId();
        String publishRef = outcome.publishReceiptId() == null
                || outcome.publishReceiptId().isBlank()
                ? "NONE" : outcome.publishReceiptId();
        long cut = foundations.highestAuthorityEventSequence(task.taskId());
        String decisionRef = latestDecisionRef(
                workflow.findReviewDecisions(task.taskId()),
                foundations.findAuthorityEvents(task.taskId(), cut));
        validateTerminalAnswerRefs(payload, completed, outcome, decisionRef,
                artifactRefs, validationRef, publishRef);
    }

    private ChainPersistenceRecords.PendingItemRecord requireOpenPending(
            String taskId, String gapId) {
        List<ChainPersistenceRecords.PendingItemRecord> items = workflow
                .findPendingItems(required(taskId, "taskId")).stream()
                .filter(value -> value.gapId().equals(
                        required(gapId, "gapId")))
                .toList();
        if (items.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_PENDING_ANSWER_ITEM_MISSING_OR_AMBIGUOUS");
        }
        List<ChainPersistenceRecords.PendingItemEventRecord> events = workflow
                .findPendingItemEvents(gapId).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.PendingItemEventRecord
                                ::responseRound))
                .toList();
        for (var event : events) {
            if (!event.taskId().equals(taskId)
                    || !event.gapId().equals(gapId)) {
                throw new IllegalStateException(
                        "CHAIN_PENDING_ANSWER_EVENT_IDENTITY_INVALID");
            }
        }
        if (!events.isEmpty() && events.get(events.size() - 1).eventKind()
                != ChainPendingItemStatus.PENDING) {
            throw new IllegalStateException(
                    "CHAIN_PENDING_ANSWER_ITEM_NOT_OPEN");
        }
        return items.get(0);
    }

    /**
     * Continues one already-created Delivery without invoking Answer again.
     * The stable Delivery runtime revalidates the exact task, content,
     * accepted proposal, authority-event prefix and retry policy before it
     * writes the next attempt.
     */
    public ChainDeliveryRuntime.Attempted retryDelivery(
            String taskId, String deliveryId, Instant now) {
        taskId = required(taskId, "taskId");
        deliveryId = required(deliveryId, "deliveryId");
        Objects.requireNonNull(now, "now");
        ChainDeliveryRuntime runtime = new ChainDeliveryRuntime(
                foundations, workflow, finalization, models,
                deliveryWriter, messages,
                (ignoredTask, ignoredProposal, ignoredType, ignoredRef) -> {
                    throw new IllegalStateException(
                            "CHAIN_DELIVERY_RETRY_CANNOT_REBIND_PROPOSAL");
                }, boundTaskId -> ProductChainRuntimePolicySource.forTask(
                        contexts, boundTaskId));
        return runtime.attempt(taskId, deliveryId, now);
    }

    static ValidationIdentity validationIdentity(
            ProductChainTerminalOutcomeAuthority.ValidationIdentity terminal,
            String validationId) {
        if (validationId == null) {
            return ValidationIdentity.none();
        }
        if (!validationId.equals(terminal.validationId())
                || terminal.requestDigest() == null
                || terminal.receiptDigest() == null) {
            throw new IllegalStateException(
                    "CHAIN_ANSWER_VALIDATION_IDENTITY_MISSING");
        }
        return new ValidationIdentity(validationId,
                terminal.requestDigest(), terminal.receiptDigest());
    }

    static String outcomeCandidateFingerprint(
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            List<ChainPersistenceRecords.WorkspaceCandidateRecord> candidates) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(candidates, "candidates");
        if (outcome.finalArtifactId() == null) {
            return null;
        }
        List<ChainPersistenceRecords.WorkspaceCandidateRecord> exact = candidates
                .stream()
                .filter(value -> Objects.equals(value.taskId(), outcome.taskId()))
                .filter(value -> Objects.equals(value.artifactId(),
                        outcome.finalArtifactId()))
                .filter(value -> outcome.candidateKey().equals(
                        value.workspaceCandidateId())
                        || outcome.candidateKey().equals(
                        value.candidateFingerprint()))
                .toList();
        if (exact.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_ANSWER_CANDIDATE_IDENTITY_MISSING_OR_AMBIGUOUS");
        }
        return exact.get(0).candidateFingerprint();
    }

    record ValidationIdentity(
            String validationId,
            String requestDigest,
            String receiptDigest) {
        ValidationIdentity {
            int present = (validationId == null ? 0 : 1)
                    + (requestDigest == null ? 0 : 1)
                    + (receiptDigest == null ? 0 : 1);
            if (present != 0 && present != 3) {
                throw new IllegalStateException(
                        "CHAIN_ANSWER_VALIDATION_IDENTITY_INCOMPLETE");
            }
        }

        static ValidationIdentity none() {
            return new ValidationIdentity(null, null, null);
        }
    }

    static String latestDecisionRef(
            List<ChainPersistenceRecords.ReviewDecisionRecord> decisions,
            List<ChainPersistenceRecords.AuthorityEventRecord> authorityEvents) {
        Map<String, Long> sequenceByEvent = authorityEvents.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        ChainPersistenceRecords.AuthorityEventRecord::eventId,
                        ChainPersistenceRecords.AuthorityEventRecord::eventSequence));
        return decisions.stream()
                .filter(value -> sequenceByEvent.containsKey(value.eventId()))
                .max(Comparator.comparingLong(value ->
                        sequenceByEvent.get(value.eventId())))
                .map(ChainPersistenceRecords.ReviewDecisionRecord::reviewDecisionId)
                .orElse(io.paperagent.v2.chain.ChainIdentity.NONE);
    }

    static void validateTerminalAnswerRefs(
            io.paperagent.v2.chain.ChainProposalPayload payload,
            boolean completed,
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            String latestDecisionRef,
            List<String> expectedArtifactRefs,
            String validationRef,
            String publishRef) {
        boolean valid;
        if (completed) {
            valid = payload instanceof AnswerPayload.FinalDelivery answer
                    && outcome.outcomeId().equals(answer.taskOutcomeRef())
                    && expectedArtifactRefs.equals(answer.artifactAndCandidateRefs())
                    && validationRef.equals(answer.validationRef())
                    && publishRef.equals(answer.publishRef());
        } else {
            valid = payload instanceof AnswerPayload.StatusOrFailure answer
                    && outcome.outcomeId().equals(answer.taskOrStepStatusRef())
                    && latestDecisionRef.equals(answer.latestDecisionRef())
                    && outcome.outcomeId().equals(answer.blockerOrTaskOutcomeRef());
        }
        if (!valid) {
            String diagnostic = completed
                    ? "final Answer refs must exactly copy the formal TaskOutcome refs shown in Context"
                    : "status Answer refs must exactly copy taskOrStepStatusRef="
                    + outcome.outcomeId() + "; blockerOrTaskOutcomeRef="
                    + outcome.outcomeId() + "; latestDecisionRef="
                    + latestDecisionRef;
            throw new ChainProviderProtocolException(
                    ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    "$.payload",
                    diagnostic);
        }
    }

    /**
     * Model protocol payloads are wire-shaped on input, but persisted proposal
     * payloads replace inline bodies with their content authority refs. Restore
     * only that already-validated body at the Delivery boundary before using
     * the strict wire decoder again.
     */
    static AnswerPayload decodeCanonicalAnswerPayload(
            ChainModelProtocolOutcome.ProposalReady ready) {
        Objects.requireNonNull(ready, "ready");
        ChainWorkState state = ready.proposal().proposalKind()
                    == ChainProposalKind.ANSWER_FINAL_DELIVERY
                    ? ChainWorkState.DELIVERING : ChainWorkState.TERMINAL;
        return (AnswerPayload) ProductChainPersistedProposalDecoder.decode(
                ready, state, null).payload();
    }

    private static AnswerPayload decodeCanonicalAnswerPayload(
            ChainModelProtocolOutcome.ProposalReady ready,
            ChainWorkState state,
            String gapId) {
        Objects.requireNonNull(ready, "ready");
        return (AnswerPayload) ProductChainPersistedProposalDecoder.decode(
                ready, state, gapId).payload();
    }

    private static AnswerPayload decodeCanonicalAnswerPayload(
            String encoded, ChainWorkState state, String gapId) {
        return (AnswerPayload) new StrictChainProviderOutputParser()
                .parse(encoded, ChainRole.ANSWER, state, gapId).payload();
    }

    private ChainPersistenceRecords.RouteDecisionRecord
            requireCurrentDirectRoute(
                    ChainPersistenceRecords.TaskRecord task,
                    ChainPersistenceRecords.InstructionRecord instruction,
                    String routeDecisionId,
                    boolean allowCommittedEscalation) {
        required(routeDecisionId, "routeDecisionId");
        if (task.sessionId() != instruction.sessionId()) {
            throw new IllegalStateException(
                    "CHAIN_DIRECT_ROUTE_INSTRUCTION_IDENTITY_INVALID");
        }
        List<ChainPersistenceRecords.TaskInstructionBindingRecord> bindings =
                foundations.findTaskInstructions(
                        task.taskId(), Long.MAX_VALUE).stream()
                        .sorted(Comparator.comparingLong(
                                ChainPersistenceRecords
                                        .TaskInstructionBindingRecord
                                        ::taskInstructionSequence))
                        .toList();
        if (bindings.isEmpty()
                || !bindings.get(bindings.size() - 1).instructionId()
                .equals(instruction.instructionId())) {
            throw new IllegalStateException(
                    "CHAIN_DIRECT_ROUTE_INSTRUCTION_NOT_CURRENT");
        }
        List<ChainPersistenceRecords.RouteDecisionRecord> routes = workflow
                .findRouteDecisions(task.taskId()).stream()
                .filter(value -> value.instructionId().equals(
                        instruction.instructionId()))
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.RouteDecisionRecord
                                ::decisionOrdinal))
                .toList();
        List<ChainPersistenceRecords.RouteDecisionRecord> selected = routes
                .stream().filter(value -> value.routeDecisionId().equals(
                        routeDecisionId)).toList();
        boolean validEscalationSuffix = routes.size() == 2
                && routes.get(1).decisionKind()
                == ChainPersistenceRecords.RouteDecisionType.ESCALATION
                && routes.get(1).decisionOrdinal() == 1
                && routeDecisionId.equals(
                routes.get(1).parentRouteDecisionId());
        if (selected.size() != 1
                || (routes.size() != 1
                && (!allowCommittedEscalation
                || !validEscalationSuffix))
                || selected.get(0).decisionKind()
                != ChainPersistenceRecords.RouteDecisionType.INITIAL
                || selected.get(0).decisionOrdinal() != 0
                || selected.get(0).route() != ChainExecutionMode.DIRECT
                || selected.get(0).needsTool()
                || selected.get(0).needsNetwork()
                || selected.get(0).needsProject()
                || selected.get(0).needsPersistentProgress()
                || selected.get(0).directTaskSpecification() == null
                || selected.get(0).answerRequiredRefs() == null) {
            throw new IllegalStateException(
                    "CHAIN_DIRECT_ROUTE_AUTHORITY_INVALID");
        }
        return selected.get(0);
    }

    static void validateDirectInvocationIdentity(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.RouteDecisionRecord route,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        String expectedContextId = directContextId(
                task.taskId(), route.routeDecisionId());
        String expectedInvocationId = identity(
                "invocation", expectedContextId);
        boolean directKind = proposal.proposalKind()
                == ChainProposalKind.ANSWER_DIRECT_ANSWER;
        boolean escalationKind = proposal.proposalKind()
                == ChainProposalKind.ANSWER_ESCALATE_TO_PERSISTENT;
        if (!task.taskId().equals(route.taskId())
                || !instruction.instructionId().equals(
                route.instructionId())
                || route.route() != ChainExecutionMode.DIRECT
                || !task.taskId().equals(proposal.taskId())
                || proposal.role() != ChainRole.ANSWER
                || (!directKind && !escalationKind)
                || !expectedInvocationId.equals(proposal.invocationId())
                || !expectedInvocationId.equals(invocation.invocationId())
                || !task.taskId().equals(invocation.taskId())
                || !expectedContextId.equals(
                invocation.contextRevisionId())
                || invocation.role() != ChainRole.ANSWER
                || invocation.workState()
                != ChainWorkState.DIRECT_ANSWERING
                || !"DIRECT_ROUTE".equals(invocation.callReason())
                || !expectedContextId.equals(
                context.contextRevisionId())
                || !task.taskId().equals(context.taskId())
                || context.role() != ChainRole.ANSWER
                || context.workState()
                != ChainWorkState.DIRECT_ANSWERING
                || !"DIRECT_ROUTE".equals(context.callReason())
                || !instruction.instructionId().equals(
                context.instructionId())
                || context.status() != ChainContextRevisionStatus.COMPLETE
                || !Objects.equals(task.projectId(), context.projectId())
                || !Objects.equals(task.initialProjectVersion(),
                context.projectVersion())
                || context.taskFrameId() != null
                || context.planId() != null
                || context.planRevisionId() != null
                || context.stepId() != null
                || context.activationEventId() != null
                || (directKind && (proposal.bodyAuthorityRef() == null
                || !"ANSWER_BODY".equals(
                proposal.bodyAuthorityType())))
                || (escalationKind
                && (proposal.bodyAuthorityRef() != null
                || proposal.bodyAuthorityType() != null))) {
            throw new IllegalStateException(
                    "CHAIN_DIRECT_ANSWER_INVOCATION_IDENTITY_INVALID");
        }
    }

    static void validateAcceptedStatePrefix(
            ChainPersistenceRecords.ModelProposalRecord proposal,
            List<ChainPersistenceRecords.ProposalStateEventRecord> states) {
        Objects.requireNonNull(proposal, "proposal");
        states = Objects.requireNonNull(states, "states").stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence))
                .toList();
        if (states.isEmpty() || states.size() > 2) {
            throw new IllegalStateException(
                    "CHAIN_DIRECT_ANSWER_PROPOSAL_STATE_INVALID");
        }
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            ChainPersistenceRecords.ProposalStateEventRecord state =
                    states.get(index);
            if (!proposal.proposalId().equals(state.proposalId())
                    || !proposal.taskId().equals(state.taskId())
                    || state.stateSequence() != index + 1L) {
                throw new IllegalStateException(
                        "CHAIN_DIRECT_ANSWER_PROPOSAL_STATE_INVALID");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "CHAIN_DIRECT_ANSWER_PROPOSAL_STATE_INVALID",
                        invalid);
            }
            prefix.add(state.stateKind());
        }
        if (states.get(0).stateKind() != ChainProposalState.ACCEPTED
                || states.get(0).officialAuthorityType() != null
                || states.get(0).officialAuthorityRef() != null) {
            throw new IllegalStateException(
                    "CHAIN_DIRECT_ANSWER_PROPOSAL_NOT_ACCEPTED");
        }
        if (states.size() == 2) {
            String expectedType = proposal.proposalKind()
                    == ChainProposalKind.ANSWER_DIRECT_ANSWER
                    ? "DELIVERY" : "ROUTE_DECISION";
            if (!expectedType.equals(
                    states.get(1).officialAuthorityType())) {
                throw new IllegalStateException(
                        "CHAIN_DIRECT_ANSWER_PROPOSAL_BOUND_ELSEWHERE");
            }
        }
    }

    static AnswerPayload decodeDirectProposal(
            ChainModelProtocolOutcome.ProposalReady ready) {
        Objects.requireNonNull(ready, "ready");
        if (ready.proposal().proposalKind()
                == ChainProposalKind.ANSWER_DIRECT_ANSWER) {
            return decodeCanonicalAnswerPayload(ready);
        }
        if (ready.proposal().proposalKind()
                != ChainProposalKind.ANSWER_ESCALATE_TO_PERSISTENT
                || ready.bodyContent() != null
                || ready.proposal().bodyAuthorityRef() != null
                || ready.proposal().bodyAuthorityType() != null) {
            throw new IllegalStateException(
                    "CHAIN_DIRECT_ANSWER_PROPOSAL_KIND_INVALID");
        }
        String encoded = "{\"schemaVersion\":\"1\",\"kind\":\""
                + ready.proposal().proposalKind().wireName()
                + "\",\"payload\":"
                + ready.proposal().payload().json() + "}";
        return (AnswerPayload) new StrictChainProviderOutputParser()
                .parse(encoded, ChainRole.ANSWER,
                        ChainWorkState.DIRECT_ANSWERING, null)
                .payload();
    }

    static void validateDirectPayload(
            io.paperagent.v2.chain.ChainProposalPayload payload,
            String routeDecisionId) {
        required(routeDecisionId, "routeDecisionId");
        boolean valid = payload instanceof AnswerPayload.DirectAnswer answer
                && routeDecisionId.equals(answer.routeDecisionRef())
                || payload instanceof AnswerPayload.EscalateToPersistent escalation
                && routeDecisionId.equals(
                escalation.directRouteDecisionRef());
        if (!valid) {
            throw new ChainProviderProtocolException(
                    ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    "$.payload",
                    "Direct Answer payload must reference the selected formal route");
        }
    }

    static void validateDirectRoutePayload(
            io.paperagent.v2.chain.ChainProposalPayload payload,
            ChainPersistenceRecords.RouteDecisionRecord route) {
        Objects.requireNonNull(route, "route");
        validateDirectPayload(payload, route.routeDecisionId());
        if (!(payload instanceof AnswerPayload.DirectAnswer answer)) {
            return;
        }
        try {
            JsonNode specification = JSON.readTree(
                    route.directTaskSpecification().json())
                    .get("specification");
            boolean valid = specification != null
                    && specification.isTextual()
                    && specification.textValue().equals(
                    answer.directTaskSpecification())
                    && route.answerRequiredRefs().json().equals(
                    JSON.writeValueAsString(answer.factRefs()));
            if (!valid) {
                throw new ChainProviderProtocolException(
                        ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                        "$.payload",
                        "Direct Answer must copy the selected route specification and fact refs");
            }
        } catch (JsonProcessingException invalidRoute) {
            throw new IllegalStateException(
                    "CHAIN_DIRECT_ROUTE_CANONICAL_PAYLOAD_INVALID",
                    invalidRoute);
        }
    }

    static String directContextId(String taskId, String routeDecisionId) {
        return identity("context", required(taskId, "taskId")
                + "\0ANSWER_DIRECT\0"
                + required(routeDecisionId, "routeDecisionId"));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record DirectProposal(
            String proposalId,
            ChainProposalKind proposalKind,
            String invocationId,
            String routeDecisionId) {
        public DirectProposal {
            required(proposalId, "proposalId");
            Objects.requireNonNull(proposalKind, "proposalKind");
            if (proposalKind != ChainProposalKind.ANSWER_DIRECT_ANSWER
                    && proposalKind
                    != ChainProposalKind.ANSWER_ESCALATE_TO_PERSISTENT) {
                throw new IllegalArgumentException(
                        "proposalKind is not a Direct Answer result");
            }
            required(invocationId, "invocationId");
            required(routeDecisionId, "routeDecisionId");
        }
    }

    public sealed interface DirectConsumption permits
            DirectDelivered, DirectEscalated {
    }

    public record DirectDelivered(
            ChainDeliveryRuntime.Attempted delivery)
            implements DirectConsumption {
        public DirectDelivered {
            Objects.requireNonNull(delivery, "delivery");
        }
    }

    public record DirectEscalated(
            ChainPersistenceRecords.RouteDecisionRecord route)
            implements DirectConsumption {
        public DirectEscalated {
            Objects.requireNonNull(route, "route");
            if (route.decisionKind()
                    != ChainPersistenceRecords.RouteDecisionType.ESCALATION
                    || route.route()
                    != ChainExecutionMode.PERSISTENT_PLAN_EXECUTE) {
                throw new IllegalArgumentException(
                        "route is not a formal Direct escalation");
            }
        }
    }

    public record PersistentAnswerProposal(
            String proposalId,
            ChainProposalKind proposalKind,
            String invocationId,
            String outcomeId) {
        public PersistentAnswerProposal {
            required(proposalId, "proposalId");
            Objects.requireNonNull(proposalKind, "proposalKind");
            if (proposalKind != ChainProposalKind.ANSWER_FINAL_DELIVERY
                    && proposalKind
                    != ChainProposalKind.ANSWER_STATUS_OR_FAILURE) {
                throw new IllegalArgumentException(
                        "proposalKind is not a persistent Answer result");
            }
            required(invocationId, "invocationId");
            required(outcomeId, "outcomeId");
        }
    }

    public sealed interface PersistentConsumption permits
            PersistentDelivered, PersistentBound {
    }

    public record PersistentDelivered(
            ChainDeliveryRuntime.Attempted attempted)
            implements PersistentConsumption {
        public PersistentDelivered {
            Objects.requireNonNull(attempted, "attempted");
        }
    }

    public record PersistentBound(
            ChainPersistenceRecords.DeliveryRecord delivery)
            implements PersistentConsumption {
        public PersistentBound {
            Objects.requireNonNull(delivery, "delivery");
        }
    }

    public record PendingAnswerProposal(
            String proposalId,
            String invocationId,
            String gapId,
            ChainWorkState workState) {
        public PendingAnswerProposal {
            required(proposalId, "proposalId");
            required(invocationId, "invocationId");
            required(gapId, "gapId");
            if (workState != ChainWorkState.WAITING_USER
                    && workState != ChainWorkState.WAITING_PERMISSION) {
                throw new IllegalArgumentException(
                        "pending Answer workState is invalid");
            }
        }
    }

    public sealed interface PendingConsumption permits
            PendingDelivered, PendingBound {
    }

    public record PendingDelivered(
            ChainDeliveryRuntime.Attempted attempted)
            implements PendingConsumption {
        public PendingDelivered {
            Objects.requireNonNull(attempted, "attempted");
        }
    }

    public record PendingBound(
            ChainPersistenceRecords.DeliveryRecord delivery)
            implements PendingConsumption {
        public PendingBound {
            Objects.requireNonNull(delivery, "delivery");
        }
    }

    private static String identity(String prefix, String material) {
        return prefix + "." + sha256(material);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
