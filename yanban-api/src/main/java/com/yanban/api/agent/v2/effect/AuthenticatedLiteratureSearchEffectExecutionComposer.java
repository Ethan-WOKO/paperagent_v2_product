package com.yanban.api.agent.v2.effect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.LiteratureSearchStartToolExecutor;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.compatibility.literature.LiteratureSearchRequestAuthority;
import com.yanban.api.agent.v2.compatibility.literature.LiteratureSearchRequestAuthoritySource;
import com.yanban.api.agent.v2.compatibility.literature.LiteratureDeliveryTaskBindingService;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimRepository;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimRequest;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolResult;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryCompositionOutcome;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryRequest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class AuthenticatedLiteratureSearchEffectExecutionComposer {
    static final String V2_TOOL = "literature.search";
    static final String PRODUCT_TOOL = "literature_search_start";
    private static final int MAX_QUERY = 2_000;
    private static final int MAX_CAPTURE = 2_048;

    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final StepRecoverer recoverer;
    private final EffectIntentRepository intents;
    private final ProductEffectExecutionClaimRepository claims;
    private final LiteratureSearchStartToolExecutor executor;
    private final LiteratureSearchEffectExecutionTimeSource timeSource;
    private final ObjectMapper json;
    private final LiteratureSearchRequestAuthoritySource authorities;
    private final LiteratureDeliveryTaskBindingService taskBindings;
    private final NaturalLanguageEffectAuthoritySource naturalAuthorities;

    public AuthenticatedLiteratureSearchEffectExecutionComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepRecoverer recoverer,
            EffectIntentRepository intents,
            ProductEffectExecutionClaimRepository claims,
            LiteratureSearchStartToolExecutor executor,
            LiteratureSearchEffectExecutionTimeSource timeSource,
            ObjectMapper json,
            LiteratureSearchRequestAuthoritySource authorities,
            LiteratureDeliveryTaskBindingService taskBindings) {
        this(contexts, planIds, recoverer, intents, claims, executor,
                timeSource, json, authorities, taskBindings, null);
    }

    @Autowired
    public AuthenticatedLiteratureSearchEffectExecutionComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepRecoverer recoverer,
            EffectIntentRepository intents,
            ProductEffectExecutionClaimRepository claims,
            LiteratureSearchStartToolExecutor executor,
            LiteratureSearchEffectExecutionTimeSource timeSource,
            ObjectMapper json,
            LiteratureSearchRequestAuthoritySource authorities,
            LiteratureDeliveryTaskBindingService taskBindings,
            NaturalLanguageEffectAuthoritySource naturalAuthorities) {
        this.contexts = contexts;
        this.planIds = planIds;
        this.recoverer = recoverer;
        this.intents = intents;
        this.claims = claims;
        this.executor = executor;
        this.timeSource = timeSource;
        this.json = json;
        this.authorities = authorities;
        this.taskBindings = taskBindings;
        this.naturalAuthorities = naturalAuthorities;
    }

    public AuthenticatedLiteratureSearchEffectExecutionOutcome execute(
            Long userId, Long turnId,
            AuthenticatedLiteratureSearchEffectExecutionCommand command) {
        try {
            VerifiedAgentTurnProductContext context =
                    contexts.resolve(userId, turnId);
            requireCommand(command);
            PlanId authoritativePlan = planIds.derive(context.identity());
            if (!authoritativePlan.equals(command.planId())) {
                throw failed("command.planId");
            }
            StepRecoveryCompositionOutcome recovered = recoverer.recover(
                    new StepRecoveryRequest(
                            authoritativePlan, command.recoveryAttempt()));
            if (!(recovered instanceof RecoveredActiveStep active)
                    || active.leaseDisposition()
                    != StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY
                    || !authoritativePlan.equals(active.planId())) {
                throw failed("recovery.activeStep");
            }
            PersistedEffectIntent intent = load(command);
            validateIntent(active, intent, command);
            ObjectNode arguments = arguments(intent.intent().arguments(),
                    context, active, command);
            Optional<LiteratureSearchRequestAuthority> explicit =
                    authorities.find(userId, turnId);
            boolean natural = explicit.isEmpty()
                    && naturalAuthorities != null
                    && naturalAuthorities.authorizes(
                            userId, turnId, authoritativePlan.value(),
                            intent.intent().stepId().value(), V2_TOOL);
            if (explicit.isPresent()) {
                requireAuthority(explicit.orElseThrow(), arguments);
            } else if (!natural) {
                throw failed("request.authority");
            }
            Instant observedAt = requiredTime(timeSource.now(), "time.start");
            var result = claims.execute(
                    new ProductEffectExecutionClaimRequest(
                            active.recovery(), active.lease(), intent,
                            command.recoveryAttempt().leaseToken(),
                            active.lease().fencingToken(), observedAt,
                            () -> invoke(context, turnId, command, arguments,
                                    observedAt, natural)));
            return new AuthenticatedLiteratureSearchEffectExecutionOutcome(
                    result.result(), result.replayed());
        } finally {
            ToolExecutionContext.clear();
        }
    }

    private static void requireAuthority(
            LiteratureSearchRequestAuthority authority,
            ObjectNode arguments) {
        boolean yearMatches = authority.yearFrom() == null
                ? !arguments.has("yearFrom")
                : arguments.path("yearFrom").canConvertToInt()
                && arguments.path("yearFrom").intValue()
                == authority.yearFrom();
        if (!authority.query().equals(arguments.path("query").asText())
                || authority.topK() != arguments.path("topK").asInt(10)
                || authority.includeBibtex()
                != arguments.path("includeBibtex").asBoolean(false)
                || !yearMatches) {
            throw failed("request.authority");
        }
    }

    private PersistedEffectIntent load(
            AuthenticatedLiteratureSearchEffectExecutionCommand command) {
        var found = intents.find(command.toolCallId());
        if (found == null || found.outcome() != PersistenceOutcome.FOUND
                || found.failure().isPresent() || found.value().isEmpty()) {
            throw failed("intent");
        }
        return found.value().orElseThrow();
    }

    private static void validateIntent(
            RecoveredActiveStep active,
            PersistedEffectIntent intent,
            AuthenticatedLiteratureSearchEffectExecutionCommand command) {
        if (!intent.intent().toolCallId().equals(command.toolCallId())
                || !intent.intent().planId().equals(command.planId())
                || !intent.intent().planId().equals(active.planId())
                || !intent.intent().stepId().equals(
                        active.recovery().activation().stepId())
                || !intent.activationEventId().equals(
                        active.recovery().activation()
                                .activationEvent().id())
                || !intent.leaseOwnerId().equals(active.lease().ownerId())
                || intent.fencingToken() != active.lease().fencingToken()
                || !V2_TOOL.equals(intent.intent().kind())) {
            throw failed("intent.authority");
        }
    }

    private ObjectNode arguments(
            ObjectValue source,
            VerifiedAgentTurnProductContext context,
            RecoveredActiveStep active,
            AuthenticatedLiteratureSearchEffectExecutionCommand command) {
        Map<String, ContractValue> values = source.values();
        if (!Set.of("query", "topK", "yearFrom", "includeBibtex")
                .containsAll(values.keySet())) {
            throw failed("intent.arguments.fields");
        }
        ContractValue rawQuery = values.get("query");
        if (!(rawQuery instanceof TextValue text)) {
            throw failed("intent.arguments.query");
        }
        String query = text.value().replaceAll("\\s+", " ").trim();
        if (query.isEmpty() || query.length() > MAX_QUERY) {
            throw failed("intent.arguments.query");
        }
        ObjectNode target = json.createObjectNode();
        target.put("query", query);
        optionalInteger(values, "topK", 1, 20)
                .ifPresent(value -> target.put("topK", value));
        optionalInteger(values, "yearFrom", 1000, 3000)
                .ifPresent(value -> target.put("yearFrom", value));
        ContractValue bibtex = values.get("includeBibtex");
        if (bibtex != null) {
            if (!(bibtex instanceof BooleanValue bool)) {
                throw failed("intent.arguments.includeBibtex");
            }
            target.put("includeBibtex", bool.value());
        }
        target.put("clientRequestId",
                deterministic("v2-literature-request",
                        command.toolCallId().value()));
        Long projectId = context.identity().projectId();
        Optional<String> frozenProject = active.recovery().taskFrame()
                .sourceProjectVersion()
                .map(version -> version.projectId());
        if ((projectId == null) != frozenProject.isEmpty()
                || projectId != null
                && !String.valueOf(projectId)
                        .equals(frozenProject.orElseThrow())) {
            throw failed("authority.project");
        }
        if (projectId != null) {
            target.put("projectId", projectId);
        }
        return target;
    }

    private static Optional<Integer> optionalInteger(
            Map<String, ContractValue> values,
            String name, int minimum, int maximum) {
        ContractValue raw = values.get(name);
        if (raw == null) {
            return Optional.empty();
        }
        if (!(raw instanceof NumberValue number)) {
            throw failed("intent.arguments." + name);
        }
        BigDecimal value = number.value();
        final int exact;
        try {
            exact = value.intValueExact();
        } catch (ArithmeticException exception) {
            throw failed("intent.arguments." + name);
        }
        if (exact < minimum || exact > maximum) {
            throw failed("intent.arguments." + name);
        }
        return Optional.of(exact);
    }

    private ExecutionReceipt invoke(
            VerifiedAgentTurnProductContext context,
            Long turnId,
            AuthenticatedLiteratureSearchEffectExecutionCommand command,
            ObjectNode arguments,
            Instant startedAt, boolean natural) {
        ToolExecutionContext.clear();
        ToolExecutionContext.setCurrentUserId(context.identity().userId());
        if (context.identity().projectId() != null) {
            ToolExecutionContext.setCurrentProjectId(
                    context.identity().projectId());
        }
        ToolExecutionContext.setResolvedAllowedTools(Set.of(PRODUCT_TOOL));
        ToolResult toolResult;
        try {
            toolResult = executor.execute(new ToolCall(
                    command.toolCallId().value(), PRODUCT_TOOL, arguments));
        } catch (RuntimeException exception) {
            toolResult = null;
        } finally {
            ToolExecutionContext.clear();
        }
        Instant endedAt = requiredTime(timeSource.now(), "time.end");
        if (endedAt.isBefore(startedAt)) {
            throw failed("time.end");
        }
        boolean success = validSuccess(toolResult, command);
        OutputCapture stdout = success
                ? capture(successOutput(toolResult.output(), arguments))
                : OutputCapture.empty();
        OutputCapture stderr = success
                ? OutputCapture.empty()
                : OutputCapture.inline(
                        "literature_search_start failed", false);
        ExecutionReceipt receipt = new ExecutionReceipt(
                new ReceiptId(deterministic(
                        "v2-literature-receipt",
                        command.toolCallId().value())),
                command.toolCallId(),
                success ? ReceiptStatus.SUCCESS : ReceiptStatus.FAILURE,
                startedAt, endedAt,
                Optional.of(success ? 0 : 1),
                success ? Optional.empty()
                        : Optional.of("LITERATURE_START_FAILED"),
                stdout, stderr, List.of(), Optional.empty(), List.of());
        if (receipt.status() == ReceiptStatus.SUCCESS && !natural) {
            taskBindings.bindSuccessfulReceipt(
                    context.identity().userId(), turnId, receipt);
        }
        return receipt;
    }

    private static boolean validSuccess(
            ToolResult result,
            AuthenticatedLiteratureSearchEffectExecutionCommand command) {
        return result != null && result.success()
                && command.toolCallId().value().equals(result.toolCallId())
                && PRODUCT_TOOL.equals(result.toolName())
                && result.output() != null && result.output().isObject()
                && result.output().path("taskId").canConvertToLong()
                && result.output().path("taskId").longValue() > 0;
    }

    private ObjectNode successOutput(
            JsonNode source, ObjectNode authoritativeArguments) {
        ObjectNode output = json.createObjectNode();
        output.put("taskId", source.path("taskId").longValue());
        copyText(source, output, "status");
        copyText(source, output, "currentStage");
        output.put("clientRequestId",
                authoritativeArguments.path("clientRequestId").asText());
        if (authoritativeArguments.path("projectId").canConvertToLong()) {
            output.put("projectId",
                    authoritativeArguments.path("projectId").longValue());
        }
        return output;
    }

    private static void copyText(
            JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isTextual()) {
            target.put(field, value.textValue());
        }
    }

    private OutputCapture capture(JsonNode output) {
        try {
            String value = json.writeValueAsString(output);
            boolean truncated = value.length() > MAX_CAPTURE;
            return OutputCapture.inline(
                    truncated ? value.substring(0, MAX_CAPTURE) : value,
                    truncated);
        } catch (Exception exception) {
            throw failed("execution.output");
        }
    }

    private static void requireCommand(
            AuthenticatedLiteratureSearchEffectExecutionCommand command) {
        if (command == null || command.planId() == null
                || command.toolCallId() == null
                || command.recoveryAttempt() == null) {
            throw failed("command");
        }
    }

    private static Instant requiredTime(Instant value, String path) {
        if (value == null) {
            throw failed(path);
        }
        return value;
    }

    private static String deterministic(String domain, String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (domain + "\0" + source)
                            .getBytes(StandardCharsets.UTF_8));
            return domain + "."
                    + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static AuthenticatedLiteratureSearchEffectExecutionException
            failed(String path) {
        return new AuthenticatedLiteratureSearchEffectExecutionException(path);
    }
}
