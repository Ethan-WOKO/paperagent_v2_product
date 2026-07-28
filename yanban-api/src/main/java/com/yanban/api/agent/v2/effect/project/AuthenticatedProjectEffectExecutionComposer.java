package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.compatibility.project.ProjectAnalysisAuthoritySource;
import com.yanban.api.agent.v2.compatibility.project.ProjectAnalysisEffectAuthority;
import com.yanban.api.agent.v2.compatibility.project.ProjectCandidateEffectGateway;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimRepository;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimRequest;
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
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedProjectEffectExecutionComposer {
    private static final Set<String> KINDS =
            Set.of("project.read", "project.search");
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
                workspaces, authorities, null, null, json);
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
            ObjectMapper json) {
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
    }

    public AuthenticatedProjectEffectExecutionOutcome execute(
            Long userId, Long turnId,
            AuthenticatedProjectEffectExecutionCommand command) {
        var context = contexts.resolve(userId, turnId);
        if (command == null || command.planId() == null
                || command.toolCallId() == null
                || command.recoveryAttempt() == null
                || context.identity().projectId() == null) {
            throw failed();
        }
        var planId = planIds.derive(context.identity());
        if (!planId.equals(command.planId())) throw failed();
        var recovered = recoverer.recover(new StepRecoveryRequest(
                planId, command.recoveryAttempt()));
        if (!(recovered instanceof RecoveredActiveStep active)
                || active.leaseDisposition()
                != StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY) {
            throw failed();
        }
        PersistedEffectIntent intent = intents.find(command.toolCallId())
                .value().orElseThrow(
                        AuthenticatedProjectEffectExecutionComposer::failed);
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
            throw failed();
        }
        if (ProjectCandidateCompositionEffect.KIND.equals(intent.intent().kind())) {
            if (candidateAuthorities == null || candidateComposition == null) {
                throw failed();
            }
            var candidate = candidateAuthorities.require(planId.value(),
                    intent.intent().stepId().value());
            String arguments = canonical(intent.intent().arguments());
            if (!candidate.kind().equals(intent.intent().kind())
                    || !candidate.authorityJson().equals(arguments)
                    || !candidate.authoritySha256().equals(hash(arguments))) throw failed();
        } else {
            ProjectAnalysisEffectAuthority authority;
            try {
                authority = authorities.require(
                        planId.value(), intent.intent().stepId().value());
            } catch (RuntimeException missingAnalysis) {
                if (candidateAuthorities == null) throw failed();
                var candidate = candidateAuthorities.require(
                        planId.value(), intent.intent().stepId().value());
                authority = new ProjectAnalysisEffectAuthority(
                        candidate.kind(), candidate.authorityJson(),
                        candidate.authoritySha256());
            }
            String arguments = canonical(intent.intent().arguments());
            if (!authority.kind().equals(intent.intent().kind())
                    || !authority.argumentJson().equals(arguments)
                    || !authority.argumentSha256().equals(hash(arguments))) {
                throw failed();
            }
        }
        var contextResult = executionContexts.inspect(planId);
        if (contextResult.outcome() != PersistenceOutcome.FOUND
                || !(contextResult.value().orElse(null)
                instanceof PersistedPlanExecutionContextConfirmed confirmed)) {
            throw failed();
        }
        WorkspacePort workspace = workspaces.create(userId, turnId);
        var verified = workspace.inspectMaterialization(
                confirmed.materializationSpec());
        Instant started = Instant.now();
        var claimed = claims.execute(new ProductEffectExecutionClaimRequest(
                active.recovery(), active.lease(), intent,
                command.recoveryAttempt().leaseToken(),
                active.lease().fencingToken(), started,
                () -> receipt(intent, workspace, verified.workspace(),
                        context.identity().projectId(), userId, turnId, started)));
        return new AuthenticatedProjectEffectExecutionOutcome(
                claimed.result(), claimed.replayed());
    }

    private ExecutionReceipt receipt(
            PersistedEffectIntent intent, WorkspacePort workspace,
            io.paperagent.v2.contracts.WorkspaceRef ref, Long projectId,
            Long userId, Long turnId, Instant started) {
        if (ProjectCandidateCompositionEffect.KIND.equals(intent.intent().kind())) {
            var candidate = candidateComposition.execute(intent, workspace, ref,
                    userId, turnId, projectId, started);
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
        String output;
        try {
            ObjectNode arguments = (ObjectNode) json.readTree(
                    canonical(intent.intent().arguments()));
            output = "project.read".equals(intent.intent().kind())
                    ? read(workspace, ref, arguments)
                    : search(workspace, ref, arguments);
        } catch (RuntimeException | java.io.IOException exception) {
            success = false;
            output = "";
        }
        Instant ended = Instant.now();
        return new ExecutionReceipt(
                new ReceiptId("project-receipt." + hash(
                        intent.intent().toolCallId().value())),
                intent.intent().toolCallId(),
                success ? ReceiptStatus.SUCCESS : ReceiptStatus.FAILURE,
                started, ended, java.util.Optional.of(success ? 0 : 1),
                success ? java.util.Optional.empty()
                        : java.util.Optional.of("PROJECT_EVIDENCE_FAILED"),
                success ? capture(output)
                        : OutputCapture.empty(),
                success ? OutputCapture.empty()
                        : OutputCapture.inline(
                                "Project evidence operation failed", false),
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
        return new IllegalStateException(
                "V2 Project evidence execution failed");
    }
}
