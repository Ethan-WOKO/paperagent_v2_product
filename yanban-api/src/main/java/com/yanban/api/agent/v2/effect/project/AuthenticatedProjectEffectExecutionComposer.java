package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.V2SafeFailureDiagnostics;
import com.yanban.api.agent.v2.compatibility.project.ProjectAnalysisAuthoritySource;
import com.yanban.api.agent.v2.compatibility.project.ProjectAnalysisEffectAuthority;
import com.yanban.api.agent.v2.compatibility.project.ProjectCandidateEffectGateway;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimRepository;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimRequest;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimResult;
import com.yanban.api.agent.v2.effect.NaturalLanguageEffectAuthoritySource;
import com.yanban.api.agent.v2.workspace
        .AuthenticatedAgentTurnWorkspaceConfigurationException;
import com.yanban.api.agent.v2.workspace.AuthenticatedAgentTurnWorkspacePortFactory;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NullValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PlanExecutionContextRepository;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryRequest;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryCompositionOutcome;
import io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization;
import io.paperagent.v2.workspace.WorkspaceException;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import io.paperagent.v2.providers.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedProjectEffectExecutionComposer {
    private static final Logger log = LoggerFactory.getLogger(
            AuthenticatedProjectEffectExecutionComposer.class);
    private static final Set<String> KINDS =
            Set.of("project.read", "project.search",
                    V2ProjectBibtexAuditTool.KIND);
    private static final int MAX_FILE_BYTES = 64 * 1024;
    private static final int MAX_CAPTURE_CHARS = 256 * 1024;

    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final StepRecoverer recoverer;
    private final EffectIntentRepository intents;
    private final ProductEffectExecutionClaimRepository claims;
    private final PlanExecutionContextRepository executionContexts;
    private final AuthenticatedAgentTurnWorkspacePortFactory workspaces;
    private final ProjectAnalysisAuthoritySource authorities;
    private final ProjectCandidateEffectGateway candidateAuthorities;
    private final ProjectCandidateCompositionEffect candidateComposition;
    private final ObjectMapper json;
    private final NaturalLanguageEffectAuthoritySource naturalAuthorities;
    private final NaturalLanguageCandidateAuthorityStore naturalCandidates;
    private final V2ProjectBibtexAuditTool bibtexAudit;

    public AuthenticatedProjectEffectExecutionComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepRecoverer recoverer,
            EffectIntentRepository intents,
            ProductEffectExecutionClaimRepository claims,
            PlanExecutionContextRepository executionContexts,
            AuthenticatedAgentTurnWorkspacePortFactory workspaces,
            ProjectAnalysisAuthoritySource authorities,
            ObjectMapper json) {
        this(contexts, planIds, recoverer, intents, claims, executionContexts,
                workspaces, authorities, null, null, json, null, null);
    }

    public AuthenticatedProjectEffectExecutionComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepRecoverer recoverer,
            EffectIntentRepository intents,
            ProductEffectExecutionClaimRepository claims,
            PlanExecutionContextRepository executionContexts,
            AuthenticatedAgentTurnWorkspacePortFactory workspaces,
            ProjectAnalysisAuthoritySource authorities,
            ProjectCandidateEffectGateway candidateAuthorities,
            ProjectCandidateCompositionEffect candidateComposition,
            ObjectMapper json) {
        this(contexts, planIds, recoverer, intents, claims,
                executionContexts, workspaces, authorities,
                candidateAuthorities, candidateComposition, json, null, null);
    }

    public AuthenticatedProjectEffectExecutionComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepRecoverer recoverer,
            EffectIntentRepository intents,
            ProductEffectExecutionClaimRepository claims,
            PlanExecutionContextRepository executionContexts,
            AuthenticatedAgentTurnWorkspacePortFactory workspaces,
            ProjectAnalysisAuthoritySource authorities,
            ProjectCandidateEffectGateway candidateAuthorities,
            ProjectCandidateCompositionEffect candidateComposition,
            ObjectMapper json,
            NaturalLanguageEffectAuthoritySource naturalAuthorities) {
        this(contexts, planIds, recoverer, intents, claims,
                executionContexts, workspaces, authorities,
                candidateAuthorities, candidateComposition, json,
                naturalAuthorities, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuthenticatedProjectEffectExecutionComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepRecoverer recoverer,
            EffectIntentRepository intents,
            ProductEffectExecutionClaimRepository claims,
            PlanExecutionContextRepository executionContexts,
            AuthenticatedAgentTurnWorkspacePortFactory workspaces,
            ProjectAnalysisAuthoritySource authorities,
            ProjectCandidateEffectGateway candidateAuthorities,
            ProjectCandidateCompositionEffect candidateComposition,
            ObjectMapper json,
            NaturalLanguageEffectAuthoritySource naturalAuthorities,
            NaturalLanguageCandidateAuthorityStore naturalCandidates) {
        this.contexts = contexts;
        this.planIds = planIds;
        this.recoverer = recoverer;
        this.intents = intents;
        this.claims = claims;
        this.executionContexts = executionContexts;
        this.workspaces = workspaces;
        this.authorities = authorities;
        this.candidateAuthorities = candidateAuthorities;
        this.candidateComposition = candidateComposition;
        this.json = json;
        this.naturalAuthorities = naturalAuthorities;
        this.naturalCandidates = naturalCandidates;
        this.bibtexAudit = new V2ProjectBibtexAuditTool(json);
    }

    public AuthenticatedProjectEffectExecutionOutcome execute(
            Long userId, Long turnId,
            AuthenticatedProjectEffectExecutionCommand command) {
        com.yanban.api.agent.v2.VerifiedAgentTurnProductContext context;
        try {
            context = contexts.resolve(userId, turnId);
        } catch (RuntimeException exception) {
            throw failed("context");
        }
        if (command == null || command.planId() == null
                || command.toolCallId() == null
                || command.recoveryAttempt() == null
                || context.identity().projectId() == null) {
            throw failed("command");
        }
        var planId = planIds.derive(context.identity());
        if (!planId.equals(command.planId())) throw failed("plan");
        StepRecoveryCompositionOutcome recovered;
        try {
            recovered = recoverer.recover(new StepRecoveryRequest(
                    planId, command.recoveryAttempt()));
        } catch (RuntimeException exception) {
            throw failed("recovery");
        }
        if (!(recovered instanceof RecoveredActiveStep active)
                || active.leaseDisposition()
                != StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY) {
            throw failed("recovery");
        }
        PersistedEffectIntent intent = intents.find(command.toolCallId())
                .value().orElseThrow(
                        () -> failed("intent"));
        if (!intent.intent().planId().equals(planId)
                || !intent.intent().stepId().equals(
                        active.recovery().activation().stepId())
                || !intent.activationEventId().equals(
                        active.recovery().activation()
                                .activationEvent().id())
                || !intent.leaseOwnerId().equals(active.lease().ownerId())
                || intent.fencingToken() != active.lease().fencingToken()
                || !KINDS.contains(intent.intent().kind())
                        && !ProjectCandidateCompositionEffect.KIND.equals(
                                intent.intent().kind())) {
            throw failed("intent_authority");
        }
        boolean naturalCandidate =
                ProjectCandidateCompositionEffect.KIND.equals(
                        intent.intent().kind())
                && naturalAuthorities != null
                && naturalCandidates != null
                && naturalAuthorities.authorizes(
                        userId, turnId, planId.value(),
                        intent.intent().stepId().value(),
                        intent.intent().kind());
        if (naturalCandidate
                && naturalCandidates.hasPreparedCandidate(
                        planId.value())) {
            return rejectDuplicateNaturalCandidate(
                    active, intent, command);
        }
        if (ProjectCandidateCompositionEffect.KIND.equals(intent.intent().kind())) {
            if (candidateAuthorities == null || candidateComposition == null) {
                throw failed("candidate_composition");
            }
            String arguments = canonical(intent.intent().arguments());
            var candidate = naturalCandidate
                    ? naturalCandidates.bind(
                            userId, context.identity().sessionId(), turnId,
                            planId.value(),
                            intent.intent().stepId().value(),
                            context.identity().projectId(),
                            context.projectVersionId().orElseThrow(
                                    AuthenticatedProjectEffectExecutionComposer::failed),
                            active.recovery().taskFrame().objective(),
                            arguments, strictCandidatePaths(arguments))
                    : candidateAuthorities.require(planId.value(),
                            intent.intent().stepId().value());
            if (naturalCandidate) {
                log.info(
                        "V2 natural Candidate authority bound planId={} "
                                + "stepId={} pathCount={}",
                        planId.value(),
                        intent.intent().stepId().value(),
                        candidate.paths().size());
            }
            if (!candidate.kind().equals(intent.intent().kind())
                    || !candidate.authorityJson().equals(arguments)
                    || !candidate.authoritySha256().equals(hash(arguments))
                    || !userId.equals(candidate.userId())
                    || !turnId.equals(candidate.turnId())
                    || !context.identity().projectId().equals(candidate.projectId())
                    || !context.identity().sessionId().equals(candidate.sessionId())
                    || context.projectVersionId()
                            .filter(candidate.projectVersion()::equals).isEmpty()) {
                throw failed();
            }
        } else {
            ProjectAnalysisEffectAuthority authority;
            try {
                authority = authorities.require(
                        planId.value(), intent.intent().stepId().value());
            } catch (RuntimeException missingAnalysis) {
                try {
                    if (candidateAuthorities == null) throw failed();
                    var candidate = candidateAuthorities.require(
                            planId.value(), intent.intent().stepId().value());
                    authority = new ProjectAnalysisEffectAuthority(
                            candidate.kind(), candidate.authorityJson(),
                            candidate.authoritySha256());
                } catch (RuntimeException missingCandidate) {
                    if (naturalAuthorities == null
                            || !naturalAuthorities.authorizes(
                                    userId, turnId, planId.value(),
                                    intent.intent().stepId().value(),
                                    intent.intent().kind())) {
                        throw failed("authority");
                    }
                    authority = null;
                }
            }
            String arguments = canonical(intent.intent().arguments());
            if (authority != null
                    && (!authority.kind().equals(intent.intent().kind())
                    || !authority.argumentJson().equals(arguments)
                    || !authority.argumentSha256().equals(hash(arguments)))) {
                throw failed("authority");
            }
        }
        var contextResult = executionContexts.inspect(planId);
        if (contextResult.outcome() != PersistenceOutcome.FOUND
                || !(contextResult.value().orElse(null)
                instanceof PersistedPlanExecutionContextConfirmed confirmed)) {
            throw failed("execution_context");
        }
        WorkspacePort workspace;
        VerifiedWorkspaceMaterialization verified;
        try {
            workspace = workspaces.create(userId, turnId);
            verified = workspace.inspectMaterialization(
                    confirmed.materializationSpec());
        } catch (WorkspaceException exception) {
            log.warn(
                    "V2 Project effect Workspace rejected "
                            + "planId={} stepId={} toolCallId={} kind={} "
                            + "code={} operation={} projectPathPresent={} "
                            + "origin={}",
                    planId.value(),
                    active.recovery().activation().stepId().value(),
                    intent.intent().toolCallId().value(),
                    intent.intent().kind(),
                    exception.code().name(),
                    exception.operation(),
                    exception.projectPath().isPresent(),
                    safeWorkspaceOrigin(exception));
            throw failed("workspace." + exception.code().name());
        } catch (AuthenticatedAgentTurnWorkspaceConfigurationException
                exception) {
            log.warn(
                    "V2 Project effect Workspace configuration rejected "
                            + "planId={} stepId={} toolCallId={} kind={} "
                            + "code={}",
                    planId.value(),
                    active.recovery().activation().stepId().value(),
                    intent.intent().toolCallId().value(),
                    intent.intent().kind(),
                    exception.code().name());
            throw failed(
                    "workspace.configuration." + exception.code().name());
        } catch (RuntimeException exception) {
            log.warn(
                    "V2 Project effect Workspace failed "
                            + "planId={} stepId={} toolCallId={} kind={} "
                            + "exceptionType={}",
                    planId.value(),
                    active.recovery().activation().stepId().value(),
                    intent.intent().toolCallId().value(),
                    intent.intent().kind(),
                    exception.getClass().getSimpleName());
            throw failed("workspace");
        }
        Instant started = Instant.now();
        ProductEffectExecutionClaimResult claimed;
        try {
            claimed = claims.execute(
                    new ProductEffectExecutionClaimRequest(
                            active.recovery(), active.lease(), intent,
                            command.recoveryAttempt().leaseToken(),
                            active.lease().fencingToken(), started,
                            () -> receipt(
                                    active, intent, workspace,
                                    verified.workspace(),
                                    context.identity().projectId(),
                                    userId, turnId, started,
                                    naturalCandidate,
                                    command.requestProvider())));
        } catch (RuntimeException exception) {
            log.warn(
                    "V2 Project effect claim failed planId={} stepId={} "
                            + "toolCallId={} kind={} exceptionType={} "
                            + "causeType={} origin={}",
                    planId.value(),
                    active.recovery().activation().stepId().value(),
                    intent.intent().toolCallId().value(),
                    intent.intent().kind(),
                    V2SafeFailureDiagnostics.exceptionType(exception),
                    V2SafeFailureDiagnostics.causeType(exception),
                    V2SafeFailureDiagnostics.origin(exception));
            throw failed("claim");
        }
        return new AuthenticatedProjectEffectExecutionOutcome(
                claimed.result(), claimed.replayed());
    }

    private AuthenticatedProjectEffectExecutionOutcome
            rejectDuplicateNaturalCandidate(
                    RecoveredActiveStep active,
                    PersistedEffectIntent intent,
                    AuthenticatedProjectEffectExecutionCommand command) {
        Instant started = Instant.now();
        ProductEffectExecutionClaimResult claimed;
        try {
            claimed = claims.execute(
                    new ProductEffectExecutionClaimRequest(
                            active.recovery(), active.lease(), intent,
                            command.recoveryAttempt().leaseToken(),
                            active.lease().fencingToken(), started,
                            () -> duplicateCandidateReceipt(
                                    intent, started)));
        } catch (RuntimeException exception) {
            log.warn(
                    "V2 duplicate Candidate recovery claim failed "
                            + "planId={} stepId={} toolCallId={} "
                            + "exceptionType={} causeType={} origin={}",
                    active.planId().value(),
                    active.recovery().activation().stepId().value(),
                    intent.intent().toolCallId().value(),
                    V2SafeFailureDiagnostics.exceptionType(exception),
                    V2SafeFailureDiagnostics.causeType(exception),
                    V2SafeFailureDiagnostics.origin(exception));
            throw failed("candidate_duplicate_claim");
        }
        log.info(
                "V2 duplicate Candidate request recorded as recoverable "
                        + "failure planId={} stepId={} toolCallId={} replayed={}",
                active.planId().value(),
                active.recovery().activation().stepId().value(),
                intent.intent().toolCallId().value(), claimed.replayed());
        return new AuthenticatedProjectEffectExecutionOutcome(
                claimed.result(), claimed.replayed());
    }

    private static ExecutionReceipt duplicateCandidateReceipt(
            PersistedEffectIntent intent, Instant started) {
        return new ExecutionReceipt(
                new ReceiptId("project-receipt." + hash(
                        intent.intent().toolCallId().value())),
                intent.intent().toolCallId(), ReceiptStatus.FAILURE,
                started, Instant.now(), java.util.Optional.of(1),
                java.util.Optional.of("CANDIDATE_ALREADY_EXISTS"),
                OutputCapture.empty(),
                OutputCapture.inline(
                        "A reviewable Candidate already exists for this "
                                + "Plan. Use the existing Candidate for "
                                + "validation or choose another tool.",
                        false),
                List.of(), java.util.Optional.empty(), List.of());
    }

    private ExecutionReceipt receipt(
            RecoveredActiveStep active,
            PersistedEffectIntent intent, WorkspacePort workspace,
            io.paperagent.v2.contracts.WorkspaceRef ref, Long projectId,
            Long userId, Long turnId, Instant started) {
        return receipt(active, intent, workspace, ref, projectId,
                userId, turnId, started, false, null);
    }

    private ExecutionReceipt receipt(
            RecoveredActiveStep active,
            PersistedEffectIntent intent, WorkspacePort workspace,
            io.paperagent.v2.contracts.WorkspaceRef ref, Long projectId,
            Long userId, Long turnId, Instant started,
            boolean naturalCandidate, ModelProvider requestProvider) {
        if (ProjectCandidateCompositionEffect.KIND.equals(intent.intent().kind())) {
            var authority =
                    new ProjectCandidateCompositionEffect.ModelAuthority(
                            active.recovery().taskFrame().id(),
                            active.planId(),
                            active.recovery().checkpoint().checkpoint()
                                    .revisionId(),
                            active.recovery().activation().stepId());
            var candidate = naturalCandidate
                    ? candidateComposition.executeNatural(
                            intent, authority, workspace, ref, userId,
                            turnId, projectId, started,
                            naturalCandidates, requestProvider)
                    : candidateComposition.execute(
                            intent, authority, workspace, ref, userId,
                            turnId, projectId, started);
            ObjectNode output = json.createObjectNode();
            output.put("diffFingerprint", candidate.diffFingerprint());
            Instant ended = Instant.now();
            return new ExecutionReceipt(
                    new ReceiptId("project-receipt." + hash(
                            intent.intent().toolCallId().value())),
                    intent.intent().toolCallId(), ReceiptStatus.SUCCESS,
                    started, ended, java.util.Optional.of(0),
                    java.util.Optional.empty(), capture(write(output)),
                    OutputCapture.empty(), List.of(), java.util.Optional.empty(), List.of());
        }
        boolean success = true;
        String failureCode = "PROJECT_EVIDENCE_FAILED";
        String failureMessage = "Project evidence operation failed";
        String output;
        try {
            ObjectNode arguments = (ObjectNode) json.readTree(
                    canonical(intent.intent().arguments()));
            output = switch (intent.intent().kind()) {
                case "project.read" -> read(workspace, ref, arguments);
                case "project.search" -> search(workspace, ref, arguments);
                case V2ProjectBibtexAuditTool.KIND ->
                        bibtexAudit.execute(workspace, ref, arguments);
                default -> throw failed("unsupported_project_effect");
            };
        } catch (RuntimeException | java.io.IOException exception) {
            log.warn(
                    "V2 Project evidence failed planId={} stepId={} "
                            + "toolCallId={} kind={} exceptionType={} "
                            + "causeType={} origin={}",
                    active.planId().value(),
                    active.recovery().activation().stepId().value(),
                    intent.intent().toolCallId().value(),
                    intent.intent().kind(),
                    V2SafeFailureDiagnostics.exceptionType(exception),
                    V2SafeFailureDiagnostics.causeType(exception),
                    V2SafeFailureDiagnostics.origin(exception));
            success = false;
            output = "";
            if (V2ProjectBibtexAuditTool.KIND.equals(
                    intent.intent().kind())) {
                failureCode = "PROJECT_BIBTEX_AUDIT_FAILED";
                failureMessage = "Project BibTeX audit failed";
            }
        }
        Instant ended = Instant.now();
        return new ExecutionReceipt(
                new ReceiptId("project-receipt." + hash(
                        intent.intent().toolCallId().value())),
                intent.intent().toolCallId(),
                success ? ReceiptStatus.SUCCESS : ReceiptStatus.FAILURE,
                started, ended, java.util.Optional.of(success ? 0 : 1),
                success ? java.util.Optional.empty()
                        : java.util.Optional.of(failureCode),
                success ? capture(output)
                        : OutputCapture.empty(),
                success ? OutputCapture.empty()
                        : OutputCapture.inline(
                                failureMessage, false),
                List.of(), java.util.Optional.empty(), List.of());
    }

    String read(
            WorkspacePort workspace,
            io.paperagent.v2.contracts.WorkspaceRef ref,
            ObjectNode arguments) {
        String path = exactText(arguments, "path");
        byte[] bytes = workspace.read(ref, new ProjectPath(path));
        if (bytes.length > MAX_FILE_BYTES) throw failed();
        return "path: " + path + "\ncontent:\n" + utf8(bytes);
    }

    String search(
            WorkspacePort workspace,
            io.paperagent.v2.contracts.WorkspaceRef ref,
            ObjectNode arguments) {
        String query = exactText(arguments, "query");
        int maximum = arguments.path("maxResults").asInt(-1);
        if (query.isBlank() || query.length() > 256
                || maximum < 1 || maximum > 20) throw failed();
        ArrayNode hits = json.createArrayNode();
        for (var stat : workspace.list(ref).stream()
                .sorted(Comparator.comparing(value ->
                        value.path().value())).toList()) {
            if (hits.size() >= maximum) break;
            byte[] bytes = workspace.read(ref, stat.path());
            if (bytes.length > MAX_FILE_BYTES) continue;
            String content;
            try {
                content = utf8(bytes);
            } catch (RuntimeException failure) {
                continue;
            }
            int from = content.indexOf(query);
            if (from < 0) continue;
            int start = Math.max(0, from - 120);
            int end = Math.min(content.length(),
                    from + query.length() + 120);
            ObjectNode hit = hits.addObject();
            hit.put("path", stat.path().value());
            hit.put("snippet", content.substring(start, end));
        }
        ObjectNode output = json.createObjectNode();
        output.put("query", query);
        output.set("hits", hits);
        return write(output);
    }

    private String canonical(ObjectValue value) {
        return write((ObjectNode) node(value));
    }

    private List<String> strictCandidatePaths(String canonicalArguments) {
        try {
            var root = json.readTree(canonicalArguments);
            if (!root.isObject() || root.size() != 2
                    || !"compose".equals(
                            root.path("operation").asText())
                    || !root.path("paths").isArray()
                    || root.path("paths").size() < 1
                    || root.path("paths").size() > 4) {
                throw failed();
            }
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            for (var item : root.path("paths")) {
                if (!item.isTextual()) throw failed();
                String path = new ProjectPath(
                        item.textValue()).value();
                if (!path.equals(item.textValue())
                        || !paths.add(path)) {
                    throw failed();
                }
            }
            return List.copyOf(paths);
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            throw failed();
        }
    }

    private com.fasterxml.jackson.databind.JsonNode node(ContractValue value) {
        if (value instanceof TextValue text) return json.getNodeFactory()
                .textNode(text.value());
        if (value instanceof NumberValue number) return json.getNodeFactory()
                .numberNode(number.value());
        if (value instanceof BooleanValue bool) return json.getNodeFactory()
                .booleanNode(bool.value());
        if (value instanceof NullValue) return json.getNodeFactory().nullNode();
        if (value instanceof ListValue list) {
            ArrayNode array = json.createArrayNode();
            list.values().forEach(item -> array.add(node(item)));
            return array;
        }
        ObjectNode object = json.createObjectNode();
        ((ObjectValue) value).values().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> object.set(
                        entry.getKey(), node(entry.getValue())));
        return object;
    }

    private String write(com.fasterxml.jackson.databind.JsonNode node) {
        try {
            String value = json.writeValueAsString(node);
            if (value.length() > MAX_CAPTURE_CHARS) throw failed();
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw failed();
        }
    }

    private static String exactText(ObjectNode node, String field) {
        if (!node.path(field).isTextual()) throw failed();
        return node.path(field).textValue();
    }

    private static String utf8(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (value.codePoints().anyMatch(character ->
                    character < 0x20
                            && character != '\t'
                            && character != '\n'
                            && character != '\r')) {
                throw failed();
            }
            return value;
        } catch (CharacterCodingException exception) {
            throw failed();
        }
    }

    static OutputCapture capture(String output) {
        if (output.length() <= OutputCapture.MAX_INLINE_CHARACTERS) {
            return OutputCapture.inline(output, false);
        }
        int end = OutputCapture.MAX_INLINE_CHARACTERS;
        if (Character.isHighSurrogate(output.charAt(end - 1))
                && Character.isLowSurrogate(output.charAt(end))) {
            end--;
        }
        return OutputCapture.inline(output.substring(0, end), true);
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static IllegalStateException failed() {
        return failed("operation");
    }

    private static ProjectEffectExecutionException failed(String stage) {
        return new ProjectEffectExecutionException(stage);
    }

    private static String safeWorkspaceOrigin(WorkspaceException exception) {
        for (StackTraceElement element : exception.getStackTrace()) {
            if (!element.getClassName().startsWith("io.paperagent.v2.workspace.")
                    || element.getClassName().endsWith(".WorkspaceException")
                    || element.getMethodName().equals("failure")
                    || element.getMethodName().equals("activeFailure")) {
                continue;
            }
            String className = element.getClassName();
            int separator = className.lastIndexOf('.');
            String simpleClass = separator < 0
                    ? className
                    : className.substring(separator + 1);
            return simpleClass + "#" + element.getMethodName()
                    + ":" + element.getLineNumber();
        }
        return "workspace-boundary";
    }
}
