package com.yanban.api.agent.v2.intake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.agent.v2.adapter.provider.ProductChatModelProviderAdapter;
import com.yanban.agent.v2.adapter.provider.ProductModelEndpoint;
import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapCommand;
import com.yanban.api.agent.AgentContextBuildRequest;
import com.yanban.api.agent.AgentContextBuilder;
import com.yanban.api.agent.AgentContextDebugView;
import com.yanban.api.agent.AgentContextPackage;
import com.yanban.api.agent.AgentContextProjectState;
import com.yanban.api.agent.AgentExperimentContext;
import com.yanban.api.agent.AgentExperimentRequest;
import com.yanban.api.agent.AgentExperimentService;
import com.yanban.api.agent.AgentLongTermMemoryContext;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.V2SafeFailureDiagnostics;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnPlanBootstrapComposer;
import com.yanban.api.agent.v2.adaptive.V2AdaptiveExecutionService;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.KnownModelContextProfileRegistry;
import com.yanban.api.agent.v2.context.ShadowContextAccountant;
import com.yanban.api.agent.v2.context.ShadowContextMeasurement;
import com.yanban.api.agent.v2.context.Utf8ByteTokenCounter;
import com.yanban.api.agent.v2.context.runtime.V2PlannerContextBoundaryFactory;
import com.yanban.api.agent.v2.context.runtime.V2PlannerContextBoundaryException;
import com.yanban.api.agent.v2.persistence
        .ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.memory.LongTermMemoryRetrievalService;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.skills.ResolvedSkill;
import com.yanban.api.skills.SkillsService;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionSummary;
import com.yanban.core.agent.AgentSessionSummaryService;
import com.yanban.core.model.ChatModelProvider;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.routing.RoutingDecisionReason;
import io.paperagent.v2.runtime.routing.RoutingRequestId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class V2NaturalLanguageTurnService {
    private static final int INTAKE_MAX_RECENT_MESSAGES = 12;
    private static final int INTAKE_MAX_CONTEXT_CHARACTERS = 12_000;
    private static final Logger log = LoggerFactory.getLogger(
            V2NaturalLanguageTurnService.class);
    private static final KnownModelContextProfileRegistry CONTEXT_PROFILES =
            new KnownModelContextProfileRegistry();
    private static final ShadowContextAccountant SHADOW_CONTEXT_ACCOUNTANT =
            new ShadowContextAccountant(new Utf8ByteTokenCounter());
    private static final String FAILURE_MESSAGE =
            "V2 无法根据本次请求创建有效任务";

    private final AgentSessionRepository sessions;
    private final V2TurnIntakeTransactions transactions;
    private final AgentTurnProductContextResolver contexts;
    private final AgentContextBuilder contextBuilder;
    private final AgentSessionSummaryService summaries;
    private final LongTermMemoryRetrievalService memories;
    private final AgentExperimentService experiments;
    private final SkillsService skills;
    private final UserSettingsService settings;
    private final AuthenticatedAgentTurnPlanBootstrapComposer bootstraps;
    private final ObjectMapper json;
    private final V2TurnPlanner planner;
    private final V2AdaptiveExecutionService adaptive;
    private final ChatModelProvider modelProvider;
    private final ProductPlanBootstrapRepositoryAdapter resumeBootstraps;
    private final V2PlannerContextBoundaryFactory plannerContexts;

    public V2NaturalLanguageTurnService(
            AgentSessionRepository sessions,
            V2TurnIntakeTransactions transactions,
            AgentTurnProductContextResolver contexts,
            AgentContextBuilder contextBuilder,
            AgentSessionSummaryService summaries,
            LongTermMemoryRetrievalService memories,
            AgentExperimentService experiments,
            SkillsService skills,
            UserSettingsService settings,
            AuthenticatedAgentTurnPlanBootstrapComposer bootstraps,
            ObjectMapper json,
            @Qualifier("chatModelProvider") ChatModelProvider modelProvider) {
        this(sessions, transactions, contexts, contextBuilder, summaries,
                memories, experiments, skills, settings, bootstraps, json,
                modelProvider, null, null, null);
    }

    public V2NaturalLanguageTurnService(
            AgentSessionRepository sessions,
            V2TurnIntakeTransactions transactions,
            AgentTurnProductContextResolver contexts,
            AgentContextBuilder contextBuilder,
            AgentSessionSummaryService summaries,
            LongTermMemoryRetrievalService memories,
            AgentExperimentService experiments,
            SkillsService skills,
            UserSettingsService settings,
            AuthenticatedAgentTurnPlanBootstrapComposer bootstraps,
            ObjectMapper json,
            @Qualifier("chatModelProvider") ChatModelProvider modelProvider,
            V2AdaptiveExecutionService adaptive,
            ProductPlanBootstrapRepositoryAdapter resumeBootstraps) {
        this(sessions, transactions, contexts, contextBuilder, summaries,
                memories, experiments, skills, settings, bootstraps, json,
                modelProvider, adaptive, resumeBootstraps, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public V2NaturalLanguageTurnService(
            AgentSessionRepository sessions,
            V2TurnIntakeTransactions transactions,
            AgentTurnProductContextResolver contexts,
            AgentContextBuilder contextBuilder,
            AgentSessionSummaryService summaries,
            LongTermMemoryRetrievalService memories,
            AgentExperimentService experiments,
            SkillsService skills,
            UserSettingsService settings,
            AuthenticatedAgentTurnPlanBootstrapComposer bootstraps,
            ObjectMapper json,
            @Qualifier("chatModelProvider") ChatModelProvider modelProvider,
            V2AdaptiveExecutionService adaptive,
            ProductPlanBootstrapRepositoryAdapter resumeBootstraps,
            V2PlannerContextBoundaryFactory plannerContexts) {
        this.sessions = sessions;
        this.transactions = transactions;
        this.contexts = contexts;
        this.contextBuilder = contextBuilder;
        this.summaries = summaries;
        this.memories = memories;
        this.experiments = experiments;
        this.skills = skills;
        this.settings = settings;
        this.bootstraps = bootstraps;
        this.json = json;
        this.planner = new V2TurnPlanner(modelProvider, json);
        this.adaptive = adaptive;
        this.modelProvider = modelProvider;
        this.resumeBootstraps = resumeBootstraps;
        this.plannerContexts = plannerContexts;
    }

    public V2NaturalLanguageTurnResponse execute(
            Long userId,
            Long sessionId,
            V2NaturalLanguageTurnRequest input) {
        AgentSession session = requireRequest(userId, sessionId, input);
        NormalizedRequest request = normalize(session, input);
        UserSettingsService.ModelEndpoint currentEndpoint =
                settings.resolveModelEndpoint(userId, null, null);
        V2TurnIntakeEntity intake = transactions.open(
                userId,
                sessionId,
                request.clientRequestId(),
                request.fingerprint(),
                request.content(),
                request.ragDisabled(),
                request.skillId(),
                request.experimentJson(),
                currentEndpoint.providerKey(),
                currentEndpoint.modelName());
        ProcessResult result = transactions.locked(
                intake, locked -> processLocked(session, locked, request));
        if (result.failed()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, FAILURE_MESSAGE);
        }
        V2AdaptiveExecutionService.Command pending = result.pending();
        if (pending == null
                && result.replayed()
                && V2TurnIntakeEntity.PERSISTENT.equals(
                        result.intake().status())
                && adaptive != null
                && adaptive.canResume(
                        userId, sessionId, request.clientRequestId())) {
            pending = resumeCommand(
                    session, result.intake(), request);
        }
        if (pending != null) {
            if (adaptive == null) {
                return response(result.intake(), result.replayed());
            }
            var execution = adaptive.execute(pending);
            if ("SUCCEEDED".equals(execution.status())
                    && execution.finalText() != null) {
                transactions.savePersistentAssistant(
                        userId, sessionId, request.clientRequestId(),
                        execution.finalText());
            }
        }
        return response(result.intake(), result.replayed());
    }

    private V2AdaptiveExecutionService.Command resumeCommand(
            AgentSession session,
            V2TurnIntakeEntity intake,
            NormalizedRequest request) {
        if (resumeBootstraps == null
                || !StringUtils.hasText(intake.planId())
                || !StringUtils.hasText(
                        intake.capabilityBindingsJson())) {
            throw new IllegalStateException(
                    "V2 adaptive resume authority is unavailable");
        }
        VerifiedAgentTurnProductContext verified =
                contexts.resolve(intake.userId(), intake.turnId());
        PersistedPlanBootstrap bootstrap = resumeBootstraps.find(
                        new io.paperagent.v2.contracts.PlanId(
                                intake.planId()))
                .orElseThrow(() -> new IllegalStateException(
                        "V2 persistent bootstrap disappeared"));
        UserSettingsService.ModelEndpoint endpoint =
                resolveTurnEndpoint(intake, session);
        ResolvedSkill skill = StringUtils.hasText(request.skillId())
                ? skills.resolveEnabledSkill(
                        intake.userId(), request.skillId())
                : null;
        AgentExperimentContext experiment = request.ragDisabled()
                ? experiments.prepare(
                        intake.userId(), request.content(), null)
                : experiments.prepare(
                        intake.userId(), request.content(),
                        request.experiment());
        AgentContextPackage context = contextBuilder.build(
                new AgentContextBuildRequest(
                        intake.sessionId(), intake.userId(),
                        endpoint.providerKey(), endpoint.modelName(),
                        summary(intake), memory(intake),
                        experiment.ragResult() == null
                                ? null
                                : experiment.ragResult().ragContext(),
                        null, null, null, null, List.of(),
                        intake.content(), projectState(verified)));
        recordShadowContext(
                endpoint, context,
                verified.projectVersionId().isPresent());
        Map<String, String> bindings = readBindings(
                intake.capabilityBindingsJson());
        List<String> conversation = context.messages().stream()
                .map(message -> message.role() + ": "
                        + boundedContext(message.content()))
                .limit(32)
                .toList();
        return new V2AdaptiveExecutionService.Command(
                intake.id(), intake.userId(), intake.sessionId(),
                intake.turnId(), intake.clientRequestId(),
                verified.projectVersionId().orElse(null),
                bootstrap, bindings, conversation,
                bootstrap.taskFrame().createdAt(),
                requestScopedProvider(endpoint));
    }

    private Map<String, String> readBindings(String value) {
        try {
            List<CapabilityBinding> bindings = json.readValue(
                    value,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
            return bindings.stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                            CapabilityBinding::stepId,
                            CapabilityBinding::internalToolId));
        } catch (Exception invalid) {
            throw new IllegalStateException(
                    "V2 persisted capability hints are invalid");
        }
    }

    private ProcessResult processLocked(
            AgentSession session,
            V2TurnIntakeEntity intake,
            NormalizedRequest request) {
        if (V2TurnIntakeEntity.DIRECT.equals(intake.status())
                || V2TurnIntakeEntity.PERSISTENT.equals(intake.status())) {
            return new ProcessResult(intake, true, false, null);
        }
        if (V2TurnIntakeEntity.FAILED.equals(intake.status())) {
            return new ProcessResult(intake, true, true, null);
        }
        try {
            VerifiedAgentTurnProductContext verified =
                    contexts.resolve(intake.userId(), intake.turnId());
            boolean projectSession = verified.projectVersionId().isPresent();
            ResolvedSkill skill = StringUtils.hasText(request.skillId())
                    ? skills.resolveEnabledSkill(
                            intake.userId(), request.skillId())
                    : null;
            AgentExperimentContext experiment = request.ragDisabled()
                    ? experiments.prepare(
                            intake.userId(), request.content(), null)
                    : experiments.prepare(
                            intake.userId(),
                            request.content(),
                            request.experiment());
            UserSettingsService.ModelEndpoint endpoint =
                    resolveTurnEndpoint(intake, session);
            String summaryContent = summary(intake);
            AgentLongTermMemoryContext memoryContext = memory(intake);
            com.yanban.api.agent.AgentRagExperimentResult ragResult =
                    experiment.ragResult();
            AgentContextPackage context = contextBuilder.build(
                    new AgentContextBuildRequest(
                            intake.sessionId(),
                            intake.userId(),
                            endpoint.providerKey(),
                            endpoint.modelName(),
                            summaryContent,
                            memoryContext,
                            ragResult == null
                                    ? null
                                    : ragResult.ragContext(),
                            null,
                            INTAKE_MAX_RECENT_MESSAGES,
                            INTAKE_MAX_CONTEXT_CHARACTERS,
                            null,
                            List.of(),
                            intake.content(),
                            projectState(verified)));
            recordShadowContext(endpoint, context, projectSession);
            logPlannerContext(
                    session, intake, endpoint, context, projectSession);
            V2PlannerContextBoundaryFactory.Session plannerContext =
                    plannerContexts == null ? null : plannerContexts.open(
                            new V2PlannerContextBoundaryFactory.Input(
                                    intake.userId(), intake.sessionId(),
                                    intake.turnId(), intake.id(),
                                    intake.clientRequestId(),
                                    intake.requestSha256(),
                                    endpoint.providerKey(), endpoint.modelName(),
                                    intake.content(), verified.projectVersionId()
                                            .map(Object::toString).orElse(null),
                                    context, summaryContent, memoryContext,
                                    ragResult));
            V2TurnPlanner.PlannedTurn planned = planner.plan(
                    context,
                    endpoint,
                    skill,
                    projectSession,
                    "v2-intake-" + shortHash(
                            intake.userId() + "\0" + intake.turnId()),
                    plannerContext);
            log.info(
                    "V2 intake planner decision intakeId={} turnId={} "
                            + "sessionId={} route={} requirementsAny={} "
                            + "capabilityCount={}",
                    intake.id(), intake.turnId(), intake.sessionId(),
                    planned.route(), planned.requirements().any(),
                    planned.capabilities().size());
            if (planned.route() == Route.DIRECT) {
                AgentMessage assistant = transactions.saveAssistant(
                        intake, planned.answer(), planned.rawOutput());
                if (!assistant.getId().equals(intake.assistantMessageId())) {
                    throw new IllegalStateException(
                            "V2 direct message authority mismatch");
                }
                return new ProcessResult(intake, false, false, null);
            }
            Instant now = Instant.now();
            var persisted = bootstraps.bootstrap(
                    intake.userId(),
                    intake.turnId(),
                    bootstrap(planned, verified, intake.turnId(), now));
            if (!persisted.successful()
                    || persisted.value().isEmpty()
                    || persisted.outcome()
                    != io.paperagent.v2.persistence.PersistenceOutcome.APPLIED
                    && persisted.outcome()
                    != io.paperagent.v2.persistence.PersistenceOutcome.REPLAYED) {
                throw new V2TurnPlanningException(
                        "persistent bootstrap was rejected");
            }
            PersistedPlanBootstrap value = persisted.value().orElseThrow();
            String capabilityJson = bindings(planned.capabilities());
            transactions.savePersistent(
                    intake,
                    value.plan().id().value(),
                    planned.rawOutput(),
                    capabilityJson);
            Map<String, String> toolBindings =
                    planned.capabilities().stream().collect(
                            java.util.stream.Collectors.toUnmodifiableMap(
                                    capability -> capability.stepId().value(),
                                    capability -> capability.internalToolId()
                                            .value()));
            List<String> conversation = context.messages().stream()
                    .map(message -> message.role() + ": "
                            + boundedContext(message.content()))
                    .limit(32)
                    .toList();
            return new ProcessResult(
                    intake, false, false,
                    new V2AdaptiveExecutionService.Command(
                            intake.id(), intake.userId(), intake.sessionId(),
                            intake.turnId(), intake.clientRequestId(),
                            verified.projectVersionId().orElse(null),
                            value, toolBindings, conversation, now,
                            requestScopedProvider(endpoint)));
        } catch (RuntimeException failure) {
            String code = failureCode(failure);
            log.warn(
                    "V2 intake processing failed intakeId={} turnId={} "
                            + "sessionId={} failureCode={} exceptionType={} "
                            + "causeType={} origin={}",
                    intake.id(), intake.turnId(), intake.sessionId(), code,
                    V2SafeFailureDiagnostics.exceptionType(failure),
                    V2SafeFailureDiagnostics.causeType(failure),
                    V2SafeFailureDiagnostics.origin(failure));
            transactions.saveFailure(intake, code);
            return new ProcessResult(intake, false, true, null);
        }
    }

    private static void logPlannerContext(
            AgentSession session,
            V2TurnIntakeEntity intake,
            UserSettingsService.ModelEndpoint endpoint,
            AgentContextPackage context,
            boolean projectSession) {
        int messageCharacters = context.messages().stream()
                .map(message -> message.content())
                .filter(java.util.Objects::nonNull)
                .mapToInt(String::length)
                .sum();
        int currentMessageCharacters = context.currentUserMessage() == null
                || context.currentUserMessage().content() == null
                ? 0 : context.currentUserMessage().content().length();
        log.info(
                "V2 intake planner context intakeId={} turnId={} "
                        + "sessionId={} projectSession={} "
                        + "sessionProvider={} sessionModel={} "
                        + "turnProvider={} turnModel={} "
                        + "resolvedProvider={} resolvedModel={} "
                        + "messageCount={} messageCharacters={} "
                        + "sectionCount={} currentMessageCharacters={} "
                        + "recentMessageLimit={} contextCharacterLimit={}",
                intake.id(), intake.turnId(), intake.sessionId(),
                projectSession,
                session.getModelProviderSnapshot(),
                session.getModelSnapshot(),
                intake.modelProviderSnapshot(),
                intake.modelSnapshot(),
                endpoint.providerKey(), endpoint.modelName(),
                context.messages().size(), messageCharacters,
                context.sections().size(), currentMessageCharacters,
                INTAKE_MAX_RECENT_MESSAGES,
                INTAKE_MAX_CONTEXT_CHARACTERS);
    }

    private static void recordShadowContext(
            UserSettingsService.ModelEndpoint endpoint,
            AgentContextPackage context,
            boolean projectSession) {
        Optional<ShadowContextMeasurement> measured =
                shadowContextMeasurement(endpoint, context, projectSession);
        if (measured.isEmpty()) {
            log.info("V2 context shadow profileKnown=0");
            return;
        }
        ShadowContextMeasurement measurement = measured.orElseThrow();
        log.info(
                "V2 context shadow profileVersion={} counterVersion={} "
                        + "contextWindowTokens={} maxOutputTokens={} "
                        + "estimatedInputTokens={} coreTokens={} "
                        + "recentTokens={} summaryTokens={} toolTokens={} "
                        + "stepTokens={} memoryTokens={} ragTokens={} "
                        + "overLimitSections={}",
                measurement.profileVersion(),
                measurement.tokenCounterVersion(),
                measurement.contextWindowTokens(),
                measurement.maxOutputTokens(),
                measurement.estimatedInputTokens(),
                measurement.section(ContextSectionType.CORE_AUTHORITY)
                        .estimatedTokens(),
                measurement.section(ContextSectionType.RECENT_CONVERSATION)
                        .estimatedTokens(),
                measurement.section(ContextSectionType.CONVERSATION_SUMMARY)
                        .estimatedTokens(),
                measurement.section(ContextSectionType.TOOL_RESULTS)
                        .estimatedTokens(),
                measurement.section(ContextSectionType.STEP_STATE)
                        .estimatedTokens(),
                measurement.section(ContextSectionType.LONG_TERM_MEMORY)
                        .estimatedTokens(),
                measurement.section(ContextSectionType.RAG_EVIDENCE)
                        .estimatedTokens(),
                measurement.overLimitSectionCount());
    }

    static Optional<ShadowContextMeasurement> shadowContextMeasurement(
            UserSettingsService.ModelEndpoint endpoint,
            AgentContextPackage context,
            boolean projectSession) {
        var profile = CONTEXT_PROFILES.find(
                endpoint.providerKey(), endpoint.modelName());
        if (profile.isEmpty()) {
            return Optional.empty();
        }
        EnumMap<ContextSectionType, List<String>> content =
                new EnumMap<>(ContextSectionType.class);
        List<String> core = new ArrayList<>();
        List<String> recent = new ArrayList<>();
        if (!context.messages().isEmpty()) {
            core.add(context.messages().get(0).content());
        }
        AgentContextDebugView debug = context.debugView();
        if (debug != null && debug.currentMessage() != null) {
            core.add(debug.currentMessage().content());
        } else if (context.currentUserMessage() != null) {
            core.add(context.currentUserMessage().content());
        }
        if (debug != null) {
            if (debug.sessionSummary() != null) {
                content.put(
                        ContextSectionType.CONVERSATION_SUMMARY,
                        java.util.Collections.singletonList(
                                debug.sessionSummary().content()));
            }
            if (debug.longTermMemory() != null) {
                content.put(
                        ContextSectionType.LONG_TERM_MEMORY,
                        java.util.Collections.singletonList(
                                debug.longTermMemory().content()));
            }
            if (!debug.recentTurns().isEmpty()) {
                for (AgentContextDebugView.DebugTurn turn
                        : debug.recentTurns()) {
                    recent.add(turn.user());
                    recent.add(turn.assistant());
                }
            }
        }
        if (recent.isEmpty() && !projectSession) {
            context.messages().stream()
                    .skip(2)
                    .map(message -> message.content())
                    .forEach(recent::add);
        }
        content.put(ContextSectionType.CORE_AUTHORITY, core);
        content.put(ContextSectionType.RECENT_CONVERSATION, recent);
        // The current safe projection exposes evidence references, not RAG or
        // tool bodies. Those sections remain zero until the dedicated context
        // assembler supplies bounded projections in a later checkpoint.
        return Optional.of(SHADOW_CONTEXT_ACCOUNTANT.measure(
                profile.orElseThrow(), content));
    }

    private UserSettingsService.ModelEndpoint resolveTurnEndpoint(
            V2TurnIntakeEntity intake,
            AgentSession session) {
        boolean hasProvider = StringUtils.hasText(
                intake.modelProviderSnapshot());
        boolean hasModel = StringUtils.hasText(intake.modelSnapshot());
        if (hasProvider && hasModel) {
            return settings.resolveModelEndpoint(
                    intake.userId(), intake.modelProviderSnapshot(),
                    intake.modelSnapshot());
        }
        if (!hasProvider && !hasModel) {
            return settings.resolveModelEndpoint(
                    intake.userId(), session.getModelProviderSnapshot(),
                    session.getModelSnapshot());
        }
        throw new IllegalStateException(
                "V2 turn model snapshot is incomplete");
    }

    private ProductPersistentPlanBootstrapCommand bootstrap(
            V2TurnPlanner.PlannedTurn planned,
            VerifiedAgentTurnProductContext verified,
            Long turnId,
            Instant now) {
        Set<RoutingRequirement> requirements =
                routingRequirements(verified);
        RoutingDecisionReason reason = requirements.isEmpty()
                ? RoutingDecisionReason.INCOMPLETE_ASSESSMENT
                : RoutingDecisionReason.DECLARED_REQUIREMENT;
        return new ProductPersistentPlanBootstrapCommand(
                new RoutingDecision(
                        new RoutingRequestId("natural-language-route-" + turnId),
                        Route.PERSISTENT_PLAN_EXECUTE,
                        reason,
                        requirements),
                planned.taskFrame(),
                executionProfile(),
                planned.plan(),
                now,
                now.plusMillis(1),
                now.plusMillis(2));
    }

    private Set<RoutingRequirement> routingRequirements(
            VerifiedAgentTurnProductContext verified) {
        Set<RoutingRequirement> values = new LinkedHashSet<>();
        if (verified.projectVersionId().isPresent()) {
            values.add(RoutingRequirement.PROJECT_FILE_ACCESS);
        }
        for (var entry : com.yanban.api.agent.v2.tool
                .V2ProductToolCatalog.entries()) {
            values.addAll(entry.routingRequirements());
        }
        return Set.copyOf(values);
    }

    private ExecutionProfile executionProfile() {
        Set<Capability> capabilities = new LinkedHashSet<>();
        for (var entry : com.yanban.api.agent.v2.tool
                .V2ProductToolCatalog.entries()) {
            capabilities.addAll(
                    entry.descriptor().requiredCapabilities());
        }
        boolean network = capabilities.contains(Capability.ACCESS_NETWORK);
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.copyOf(capabilities),
                network
                        ? NetworkPolicy.ALLOWLIST_ONLY
                        : NetworkPolicy.DENY_ALL,
                network
                        ? List.of("product-literature-search")
                        : List.of(),
                new ResourceLimits(
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(5),
                        512L * 1024 * 1024,
                        4L * 1024 * 1024,
                        8),
                Set.of());
    }

    private AgentContextProjectState projectState(
            VerifiedAgentTurnProductContext verified) {
        return verified.projectVersionId()
                .map(version -> new AgentContextProjectState(
                        verified.identity().projectId(), version))
                .orElse(null);
    }

    private String summary(V2TurnIntakeEntity intake) {
        try {
            return summaries.findBySessionAndUser(
                            intake.sessionId(), intake.userId())
                    .map(AgentSessionSummary::getSummaryText)
                    .filter(StringUtils::hasText)
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private AgentLongTermMemoryContext memory(V2TurnIntakeEntity intake) {
        try {
            return memories.retrieve(intake.userId(), intake.content());
        } catch (RuntimeException unavailable) {
            return AgentLongTermMemoryContext.empty();
        }
    }

    private V2NaturalLanguageTurnResponse response(
            V2TurnIntakeEntity intake, boolean replayed) {
        if (V2TurnIntakeEntity.DIRECT.equals(intake.status())) {
            AgentMessage assistant =
                    transactions.message(intake.assistantMessageId());
            return new V2NaturalLanguageTurnResponse(
                    intake.sessionId(),
                    intake.turnId(),
                    intake.userMessageId(),
                    assistant.getId(),
                    intake.clientRequestId(),
                    Route.DIRECT.name(),
                    assistant.getContent(),
                    null,
                    replayed);
        }
        if (V2TurnIntakeEntity.PERSISTENT.equals(intake.status())) {
            return new V2NaturalLanguageTurnResponse(
                    intake.sessionId(),
                    intake.turnId(),
                    intake.userMessageId(),
                    null,
                    intake.clientRequestId(),
                    Route.PERSISTENT_PLAN_EXECUTE.name(),
                    null,
                    intake.planId(),
                    replayed);
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, FAILURE_MESSAGE);
    }

    private NormalizedRequest normalize(
            AgentSession session, V2NaturalLanguageTurnRequest input) {
        String content = required(input.content(), 20_000, "content");
        String clientRequestId = required(
                input.clientRequestId(), 128, "clientRequestId");
        String skillId = StringUtils.hasText(input.skillId())
                ? required(input.skillId(), 128, "skillId")
                : null;
        boolean ragDisabled = input.ragDisabled() == null
                ? Boolean.TRUE.equals(session.getRagDisabled())
                : input.ragDisabled();
        String experimentJson = input.experiment() == null
                ? null : write(input.experiment());
        String canonical = write(List.of(
                content,
                ragDisabled,
                skillId == null ? "" : skillId,
                experimentJson == null ? "" : experimentJson));
        return new NormalizedRequest(
                content,
                ragDisabled,
                skillId,
                input.experiment(),
                experimentJson,
                clientRequestId,
                hash(canonical));
    }

    private AgentSession requireRequest(
            Long userId,
            Long sessionId,
            V2NaturalLanguageTurnRequest input) {
        if (userId == null || sessionId == null || input == null) {
            throw new IllegalArgumentException("V2 turn request is invalid");
        }
        return sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "agent session was not found"));
    }

    private String bindings(
            List<V2TurnPlanner.PlannedCapability> capabilities) {
        List<CapabilityBinding> values = capabilities.stream()
                .map(value -> new CapabilityBinding(
                        value.stepId().value(),
                        value.publicAlias(),
                        value.internalToolId().value()))
                .toList();
        return write(values);
    }

    private ModelProvider requestScopedProvider(
            UserSettingsService.ModelEndpoint endpoint) {
        ProductModelEndpoint transientEndpoint = new ProductModelEndpoint(
                endpoint.providerKey(), endpoint.modelName(),
                endpoint.apiKey(), endpoint.apiUrl());
        return new ProductChatModelProviderAdapter(
                modelProvider, json, ignored -> transientEndpoint);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("V2 request encoding failed");
        }
    }

    private static String required(
            String value, int maximum, String field) {
        if (!StringUtils.hasText(value) || value.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }

    private static String boundedContext(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    private static String failureCode(RuntimeException failure) {
        if (failure instanceof V2PlannerContextBoundaryException context) {
            return context.code();
        }
        return failure instanceof V2TurnPlanningException planning
                ? planning.failureCode() : "INTAKE_FAILED";
    }

    private static String shortHash(String value) {
        return hash(value).substring(0, 32);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private record NormalizedRequest(
            String content,
            boolean ragDisabled,
            String skillId,
            AgentExperimentRequest experiment,
            String experimentJson,
            String clientRequestId,
            String fingerprint) {
    }

    private record CapabilityBinding(
            String stepId, String publicAlias, String internalToolId) {
    }

    private record ProcessResult(
            V2TurnIntakeEntity intake, boolean replayed, boolean failed,
            V2AdaptiveExecutionService.Command pending) {
    }
}
