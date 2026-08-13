package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.V2SafeFailureDiagnostics;
import com.yanban.api.agent.v2.chain.effect.ChainActionWorkspaceAuthority;
import com.yanban.api.agent.v2.chain.effect.ProjectCandidateEffectAuthority;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimRepository;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimRequest;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimResult;
import com.yanban.api.agent.v2.tool.V2ProductToolCatalog;
import com.yanban.api.project.ProjectStorageProperties;
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
import io.paperagent.v2.contracts.ToolId;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedProjectEffectExecutionComposer {
    private static final Logger log = LoggerFactory.getLogger(
            AuthenticatedProjectEffectExecutionComposer.class);
    private static final int MAX_CAPTURE_CHARS = 256 * 1024;

    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final StepRecoverer recoverer;
    private final EffectIntentRepository intents;
    private final ProductEffectExecutionClaimRepository claims;
    private final PlanExecutionContextRepository executionContexts;
    private final AuthenticatedAgentTurnWorkspacePortFactory workspaces;
    private final ProjectCandidateCompositionEffect candidateComposition;
    private final ObjectMapper json;
    private final NaturalLanguageCandidateAuthorityStore naturalCandidates;
    private final V2ProjectBibtexAuditTool bibtexAudit;
    private final V2ProjectLatexOutlineTool latexOutline;
    private final V2ProjectLatexCrossrefAuditTool latexCrossrefAudit;
    private final V2ProjectLatexFloatAuditTool latexFloatAudit;
    private final V2ProjectLatexProtectedInventoryTool
            latexProtectedInventory;
    private final V2ProjectPaperAcronymAuditTool paperAcronymAudit;
    private final V2ProjectPaperLanguageStatsTool paperLanguageStats;
    private final V2ProjectCodeSymbolsTool codeSymbols;
    private final V2ProjectExperimentSummaryTool experimentSummary;
    private final V2ProjectCrossMaterialSearchTool crossMaterialSearch;
    private final V2ProjectDocumentExtractTool documentExtract;
    private final V2ProjectSpreadsheetInspectTool spreadsheetInspect;
    private final long maxFileBytes;

    public AuthenticatedProjectEffectExecutionComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepRecoverer recoverer,
            EffectIntentRepository intents,
            ProductEffectExecutionClaimRepository claims,
            PlanExecutionContextRepository executionContexts,
            AuthenticatedAgentTurnWorkspacePortFactory workspaces,
            ObjectMapper json) {
        this(contexts, planIds, recoverer, intents, claims, executionContexts,
                workspaces, null, json, null,
                new ProjectStorageProperties());
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
            ProjectCandidateCompositionEffect candidateComposition,
            ObjectMapper json,
            NaturalLanguageCandidateAuthorityStore naturalCandidates,
            ProjectStorageProperties storage) {
        this.contexts = contexts;
        this.planIds = planIds;
        this.recoverer = recoverer;
        this.intents = intents;
        this.claims = claims;
        this.executionContexts = executionContexts;
        this.workspaces = workspaces;
        this.candidateComposition = candidateComposition;
        this.json = json;
        this.naturalCandidates = naturalCandidates;
        this.maxFileBytes = java.util.Objects.requireNonNull(
                storage, "storage").getMaxFileBytes();
        if (maxFileBytes < 1) {
            throw new IllegalArgumentException(
                    "project maxFileBytes must be positive");
        }
        this.bibtexAudit = new V2ProjectBibtexAuditTool(json);
        this.latexOutline = new V2ProjectLatexOutlineTool(json);
        this.latexCrossrefAudit =
                new V2ProjectLatexCrossrefAuditTool(json);
        this.latexFloatAudit = new V2ProjectLatexFloatAuditTool(json);
        this.latexProtectedInventory =
                new V2ProjectLatexProtectedInventoryTool(json);
        this.paperAcronymAudit =
                new V2ProjectPaperAcronymAuditTool(json);
        this.paperLanguageStats =
                new V2ProjectPaperLanguageStatsTool(json);
        this.codeSymbols = new V2ProjectCodeSymbolsTool(json);
        this.experimentSummary =
                new V2ProjectExperimentSummaryTool(json);
        this.crossMaterialSearch =
                new V2ProjectCrossMaterialSearchTool(json);
        this.documentExtract = new V2ProjectDocumentExtractTool(json);
        this.spreadsheetInspect =
                new V2ProjectSpreadsheetInspectTool(json);
    }

    public AuthenticatedProjectEffectExecutionOutcome execute(
            Long userId, Long turnId,
            AuthenticatedProjectEffectExecutionCommand command) {
        FormalExecution formal = formalExecution(userId, turnId, command);
        ProjectCandidateEffectAuthority candidate = legacyCandidateAuthority(
                userId, turnId, formal);
        WorkspaceExecution workspace = legacyWorkspace(
                userId, turnId, formal);
        Instant started = Instant.now();
        return claim(formal, command, started, () -> receiptLegacy(
                formal.active(), formal.intent(), workspace.port(),
                workspace.ref(), formal.context().identity().projectId(),
                userId, turnId, started, candidate));
    }

    /** Executes a formally bound chain action without legacy Candidate state. */
    public AuthenticatedProjectEffectExecutionOutcome executeChain(
            Long userId, Long turnId,
            AuthenticatedProjectEffectExecutionCommand command) {
        FormalExecution formal = formalExecution(userId, turnId, command);
        ChainActionWorkspaceAuthority chain =
                requireChainAuthority(command, formal);
        ProjectCandidateEffectAuthority candidate = chainCandidateAuthority(
                userId, turnId, formal, chain);
        WorkspaceExecution workspace = chainWorkspace(formal, chain);
        Instant started = Instant.now();
        try {
            return claim(formal, command, started, () -> receiptChain(
                    formal.active(), formal.intent(), workspace.port(),
                    workspace.ref(), formal.context().identity().projectId(),
                    userId, turnId, started, candidate));
        } finally {
            cleanupChainWorkspace(formal, workspace);
        }
    }

    private FormalExecution formalExecution(
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
                || !isProjectKind(intent.intent().kind())) {
            throw failed("intent_authority");
        }
        return new FormalExecution(context, planId, active, intent);
    }

    private ProjectCandidateEffectAuthority legacyCandidateAuthority(
            Long userId, Long turnId, FormalExecution formal) {
        PersistedEffectIntent intent = formal.intent();
        ProjectCandidateEffectAuthority candidate = null;
        if (ProjectCandidateCompositionEffect.KIND.equals(intent.intent().kind())) {
            if (candidateComposition == null || naturalCandidates == null) {
                throw failed("candidate_composition");
            }
            String arguments = canonical(intent.intent().arguments());
            List<String> paths = strictCandidatePaths(arguments);
            candidate = naturalCandidates.bind(
                    userId, formal.context().identity().sessionId(), turnId,
                    formal.planId().value(),
                    intent.intent().stepId().value(),
                    formal.context().identity().projectId(),
                    formal.context().projectVersionId().orElseThrow(
                            AuthenticatedProjectEffectExecutionComposer::failed),
                    formal.active().recovery().taskFrame().objective(),
                    arguments, paths);
            validateCandidateAuthority(
                    userId, turnId, formal, arguments, candidate, false);
        } else {
            canonical(intent.intent().arguments());
        }
        return candidate;
    }

    private ProjectCandidateEffectAuthority chainCandidateAuthority(
            Long userId, Long turnId, FormalExecution formal,
            ChainActionWorkspaceAuthority chain) {
        PersistedEffectIntent intent = formal.intent();
        if (!ProjectCandidateCompositionEffect.KIND.equals(
                intent.intent().kind())) {
            canonical(intent.intent().arguments());
            return null;
        }
        if (candidateComposition == null) {
            throw failed("candidate_composition");
        }
        String arguments = canonical(intent.intent().arguments());
        List<String> paths = strictCandidatePaths(arguments);
        ProjectCandidateEffectAuthority candidate =
                new ProjectCandidateEffectAuthority(
                        ProjectCandidateCompositionEffect.KIND,
                        arguments, hash(arguments), userId,
                        formal.context().identity().projectId(),
                        formal.context().identity().sessionId(), turnId,
                        formal.context().projectVersionId().orElseThrow(
                                AuthenticatedProjectEffectExecutionComposer
                                        ::failed),
                        formal.active().recovery().taskFrame().objective(),
                        paths, null, chain);
        validateCandidateAuthority(
                userId, turnId, formal, arguments, candidate, true);
        return candidate;
    }

    private void validateCandidateAuthority(
            Long userId, Long turnId, FormalExecution formal,
            String arguments, ProjectCandidateEffectAuthority candidate,
            boolean chain) {
        log.info(
                "V2 Candidate authority bound planId={} stepId={} "
                        + "pathCount={}",
                formal.planId().value(),
                formal.intent().intent().stepId().value(),
                candidate.paths().size());
        if (!candidate.kind().equals(formal.intent().intent().kind())
                || !candidate.authorityJson().equals(arguments)
                || !candidate.authoritySha256().equals(hash(arguments))
                || !userId.equals(candidate.userId())
                || !turnId.equals(candidate.turnId())
                || !formal.context().identity().projectId().equals(
                candidate.projectId())
                || !formal.context().identity().sessionId().equals(
                candidate.sessionId())
                || formal.context().projectVersionId()
                .filter(candidate.projectVersion()::equals).isEmpty()
                || chain != (candidate.chainAction() != null)) {
            throw failed();
        }
    }

    private ChainActionWorkspaceAuthority
            requireChainAuthority(
            AuthenticatedProjectEffectExecutionCommand command,
            FormalExecution formal) {
        ChainActionWorkspaceAuthority chain =
                command.chainAuthority();
        if (chain == null
                || !chain.actionId().equals(command.toolCallId().value())
                || !formal.intent().intent().toolCallId().equals(
                command.toolCallId())
                || !formal.active().planId().equals(formal.planId())
                || formal.context().projectVersionId().isEmpty()
                || !formal.context().projectVersionId().orElseThrow().equals(
                chain.baseCandidate().baseProjectVersion())) {
            throw failed("chain_authority");
        }
        return chain;
    }

    private WorkspaceExecution legacyWorkspace(
            Long userId, Long turnId, FormalExecution formal) {
        PersistedPlanExecutionContextConfirmed confirmed =
                executionContext(formal.planId());
        return workspace(formal,
                () -> workspaces.create(userId, turnId),
                port -> port.inspectMaterialization(
                        confirmed.materializationSpec()));
    }

    private WorkspaceExecution chainWorkspace(
            FormalExecution formal,
            ChainActionWorkspaceAuthority chain) {
        PersistedPlanExecutionContextConfirmed confirmed =
                executionContext(formal.planId());
        if (!confirmed.materializationSpec().workspaceId().value().equals(
                chain.workspaceId())
                || !confirmed.materializationSpec().sourceProjectVersion()
                .versionId().equals(
                        chain.baseCandidate().baseProjectVersion())) {
            throw failed("chain_authority");
        }
        return workspace(formal,
                () -> workspaces.createChain(formal.context(), chain),
                port -> port.materialize(
                        confirmed.materializationSpec()));
    }

    private PersistedPlanExecutionContextConfirmed executionContext(
            io.paperagent.v2.contracts.PlanId planId) {
        var contextResult = executionContexts.inspect(planId);
        if (contextResult.outcome() != PersistenceOutcome.FOUND
                || !(contextResult.value().orElse(null)
                instanceof PersistedPlanExecutionContextConfirmed confirmed)) {
            throw failed("execution_context");
        }
        return confirmed;
    }

    private WorkspaceExecution workspace(
            FormalExecution formal, Supplier<WorkspacePort> create,
            Function<WorkspacePort, VerifiedWorkspaceMaterialization>
                    materialize) {
        try {
            WorkspacePort workspace = create.get();
            VerifiedWorkspaceMaterialization verified =
                    materialize.apply(workspace);
            return new WorkspaceExecution(workspace, verified.workspace());
        } catch (WorkspaceException exception) {
            log.warn(
                    "V2 Project effect Workspace rejected "
                            + "planId={} stepId={} toolCallId={} kind={} "
                            + "code={} operation={} projectPathPresent={} "
                            + "origin={}",
                    formal.planId().value(),
                    formal.active().recovery().activation().stepId().value(),
                    formal.intent().intent().toolCallId().value(),
                    formal.intent().intent().kind(),
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
                    formal.planId().value(),
                    formal.active().recovery().activation().stepId().value(),
                    formal.intent().intent().toolCallId().value(),
                    formal.intent().intent().kind(),
                    exception.code().name());
            throw failed(
                    "workspace.configuration." + exception.code().name());
        } catch (RuntimeException exception) {
            log.warn(
                    "V2 Project effect Workspace failed "
                            + "planId={} stepId={} toolCallId={} kind={} "
                            + "exceptionType={}",
                    formal.planId().value(),
                    formal.active().recovery().activation().stepId().value(),
                    formal.intent().intent().toolCallId().value(),
                    formal.intent().intent().kind(),
                    exception.getClass().getSimpleName());
            throw failed("workspace");
        }
    }

    private void cleanupChainWorkspace(
            FormalExecution formal, WorkspaceExecution workspace) {
        try {
            workspace.port().cleanup(workspace.ref());
        } catch (RuntimeException exception) {
            log.warn(
                    "V2 Project chain Workspace cleanup failed "
                            + "planId={} stepId={} toolCallId={} "
                            + "exceptionType={}",
                    formal.planId().value(),
                    formal.active().recovery().activation().stepId().value(),
                    formal.intent().intent().toolCallId().value(),
                    exception.getClass().getSimpleName());
        }
    }

    private AuthenticatedProjectEffectExecutionOutcome claim(
            FormalExecution formal,
            AuthenticatedProjectEffectExecutionCommand command,
            Instant started, Supplier<ExecutionReceipt> execution) {
        ProductEffectExecutionClaimResult claimed;
        try {
            claimed = claims.execute(
                    new ProductEffectExecutionClaimRequest(
                            formal.active().recovery(), formal.active().lease(),
                            formal.intent(),
                            command.recoveryAttempt().leaseToken(),
                            formal.active().lease().fencingToken(), started,
                            execution));
        } catch (RuntimeException exception) {
            log.warn(
                    "V2 Project effect claim failed planId={} stepId={} "
                            + "toolCallId={} kind={} exceptionType={} "
                            + "causeType={} origin={}",
                    formal.planId().value(),
                    formal.active().recovery().activation().stepId().value(),
                    formal.intent().intent().toolCallId().value(),
                    formal.intent().intent().kind(),
                    V2SafeFailureDiagnostics.exceptionType(exception),
                    V2SafeFailureDiagnostics.causeType(exception),
                    V2SafeFailureDiagnostics.origin(exception));
            throw failed("claim");
        }
        return new AuthenticatedProjectEffectExecutionOutcome(
                claimed.result(), claimed.replayed());
    }

    private ExecutionReceipt receiptLegacy(
            RecoveredActiveStep active,
            PersistedEffectIntent intent, WorkspacePort workspace,
            io.paperagent.v2.contracts.WorkspaceRef ref, Long projectId,
            Long userId, Long turnId, Instant started,
            ProjectCandidateEffectAuthority candidateAuthority) {
        if (ProjectCandidateCompositionEffect.KIND.equals(intent.intent().kind())) {
            var candidate = candidateComposition.executeNatural(
                    intent, modelAuthority(active), workspace, ref, userId,
                    turnId, projectId, started, naturalCandidates);
            return candidateReceipt(intent, candidate, started);
        }
        return evidenceReceipt(active, intent, workspace, ref, started);
    }

    private ExecutionReceipt receiptChain(
            RecoveredActiveStep active,
            PersistedEffectIntent intent, WorkspacePort workspace,
            io.paperagent.v2.contracts.WorkspaceRef ref, Long projectId,
            Long userId, Long turnId, Instant started,
            ProjectCandidateEffectAuthority candidateAuthority) {
        if (ProjectCandidateCompositionEffect.KIND.equals(intent.intent().kind())) {
            try {
                var candidate = candidateComposition.executeChain(
                        intent, modelAuthority(active), candidateAuthority,
                        workspace, ref, userId, turnId, projectId, started);
                return candidateReceipt(intent, candidate, started);
            } catch (ProjectCandidateCompositionEffect
                    .CandidateCompositionException rejected) {
                return candidateFailureReceipt(
                        intent, started, rejected.code());
            }
        }
        return evidenceReceipt(active, intent, workspace, ref, started);
    }

    private ProjectCandidateCompositionEffect.ModelAuthority modelAuthority(
            RecoveredActiveStep active) {
        return new ProjectCandidateCompositionEffect.ModelAuthority(
                active.recovery().taskFrame().id(), active.planId(),
                active.recovery().checkpoint().checkpoint().revisionId(),
                active.recovery().activation().stepId());
    }

    private ExecutionReceipt candidateReceipt(
            PersistedEffectIntent intent,
            ProjectCandidateCompositionEffect.CandidateResult candidate,
            Instant started) {
        ObjectNode output = json.createObjectNode();
        output.put("diffFingerprint", candidate.diffFingerprint());
        Instant ended = Instant.now();
        return new ExecutionReceipt(
                new ReceiptId("project-receipt." + hash(
                        intent.intent().toolCallId().value())),
                intent.intent().toolCallId(), ReceiptStatus.SUCCESS,
                started, ended, java.util.Optional.of(0),
                java.util.Optional.empty(), capture(write(output)),
                OutputCapture.empty(), List.of(), java.util.Optional.empty(),
                List.of());
    }

    private ExecutionReceipt candidateFailureReceipt(
            PersistedEffectIntent intent, Instant started, String code) {
        Instant ended = Instant.now();
        return new ExecutionReceipt(
                new ReceiptId("project-receipt." + hash(
                        intent.intent().toolCallId().value())),
                intent.intent().toolCallId(), ReceiptStatus.FAILURE,
                started, ended, java.util.Optional.of(1),
                java.util.Optional.of(code), OutputCapture.empty(),
                OutputCapture.inline(
                        "Project Candidate operation made no progress", false),
                List.of(), java.util.Optional.empty(), List.of());
    }

    private ExecutionReceipt evidenceReceipt(
            RecoveredActiveStep active,
            PersistedEffectIntent intent, WorkspacePort workspace,
            io.paperagent.v2.contracts.WorkspaceRef ref, Instant started) {
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
                case V2ProjectLatexOutlineTool.KIND ->
                        latexOutline.execute(workspace, ref, arguments);
                case V2ProjectLatexCrossrefAuditTool.KIND ->
                        latexCrossrefAudit.execute(
                                workspace, ref, arguments);
                case V2ProjectLatexFloatAuditTool.KIND ->
                        latexFloatAudit.execute(workspace, ref, arguments);
                case V2ProjectLatexProtectedInventoryTool.KIND ->
                        latexProtectedInventory.execute(
                                workspace, ref, arguments);
                case V2ProjectPaperAcronymAuditTool.KIND ->
                        paperAcronymAudit.execute(
                                workspace, ref, arguments);
                case V2ProjectPaperLanguageStatsTool.KIND ->
                        paperLanguageStats.execute(
                                workspace, ref, arguments);
                case V2ProjectCodeSymbolsTool.KIND ->
                        codeSymbols.execute(workspace, ref, arguments);
                case V2ProjectExperimentSummaryTool.KIND ->
                        experimentSummary.execute(workspace, ref, arguments);
                case V2ProjectCrossMaterialSearchTool.KIND ->
                        crossMaterialSearch.execute(
                                workspace, ref, arguments);
                case V2ProjectDocumentExtractTool.KIND ->
                        documentExtract.execute(workspace, ref, arguments);
                case V2ProjectSpreadsheetInspectTool.KIND ->
                        spreadsheetInspect.execute(
                                workspace, ref, arguments);
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
            failureCode = failureCode(intent.intent().kind());
            failureMessage = failureMessage(intent.intent().kind());
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
        if (bytes.length > maxFileBytes) throw failed();
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
            if (bytes.length > maxFileBytes) continue;
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
            if (!root.isObject() || root.size() != 3
                    || !"compose".equals(
                            root.path("operation").asText())
                    || !root.path("paths").isArray()
                    || root.path("paths").size() < 1
                    || root.path("paths").size() > 4
                    || !root.path("replacements").isArray()
                    || root.path("replacements").size()
                            != root.path("paths").size()) {
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
            LinkedHashSet<String> replacementPaths =
                    new LinkedHashSet<>();
            for (var item : root.path("replacements")) {
                if (!item.isObject() || item.size() != 2
                        || !item.path("path").isTextual()
                        || !item.path("text").isTextual()
                        || item.path("text").textValue()
                                .getBytes(StandardCharsets.UTF_8).length
                                > maxFileBytes) {
                    throw failed();
                }
                String path = new ProjectPath(
                        item.path("path").textValue()).value();
                if (!path.equals(item.path("path").textValue())
                        || !replacementPaths.add(path)) {
                    throw failed();
                }
            }
            if (!replacementPaths.equals(paths)) {
                throw failed();
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

    private static boolean isProjectKind(String kind) {
        try {
            return V2ProductToolCatalog.entry(new ToolId(kind))
                    .filter(entry -> entry.executionTarget()
                            == V2ProductToolCatalog.ExecutionTarget.PROJECT)
                    .isPresent();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String failureCode(String kind) {
        return switch (kind) {
            case V2ProjectBibtexAuditTool.KIND ->
                    "PROJECT_BIBTEX_AUDIT_FAILED";
            case V2ProjectLatexOutlineTool.KIND ->
                    "PROJECT_LATEX_OUTLINE_FAILED";
            case V2ProjectLatexCrossrefAuditTool.KIND ->
                    "PROJECT_LATEX_CROSSREF_AUDIT_FAILED";
            case V2ProjectLatexFloatAuditTool.KIND ->
                    "PROJECT_LATEX_FLOAT_AUDIT_FAILED";
            case V2ProjectLatexProtectedInventoryTool.KIND ->
                    "PROJECT_LATEX_PROTECTED_INVENTORY_FAILED";
            case V2ProjectPaperAcronymAuditTool.KIND ->
                    "PROJECT_PAPER_ACRONYM_AUDIT_FAILED";
            case V2ProjectPaperLanguageStatsTool.KIND ->
                    "PROJECT_PAPER_LANGUAGE_STATS_FAILED";
            case V2ProjectCodeSymbolsTool.KIND ->
                    "PROJECT_CODE_SYMBOLS_FAILED";
            case V2ProjectExperimentSummaryTool.KIND ->
                    "PROJECT_EXPERIMENT_SUMMARY_FAILED";
            case V2ProjectCrossMaterialSearchTool.KIND ->
                    "PROJECT_CROSS_MATERIAL_SEARCH_FAILED";
            case V2ProjectDocumentExtractTool.KIND ->
                    "PROJECT_DOCUMENT_EXTRACT_FAILED";
            case V2ProjectSpreadsheetInspectTool.KIND ->
                    "PROJECT_SPREADSHEET_INSPECT_FAILED";
            default -> "PROJECT_EVIDENCE_FAILED";
        };
    }

    private static String failureMessage(String kind) {
        return switch (kind) {
            case V2ProjectBibtexAuditTool.KIND ->
                    "Project BibTeX audit failed";
            case V2ProjectLatexOutlineTool.KIND ->
                    "Project LaTeX outline failed";
            case V2ProjectLatexCrossrefAuditTool.KIND ->
                    "Project LaTeX cross-reference audit failed";
            case V2ProjectLatexFloatAuditTool.KIND ->
                    "Project LaTeX float audit failed";
            case V2ProjectLatexProtectedInventoryTool.KIND ->
                    "Project LaTeX protected inventory failed";
            case V2ProjectPaperAcronymAuditTool.KIND ->
                    "Project paper acronym audit failed";
            case V2ProjectPaperLanguageStatsTool.KIND ->
                    "Project paper language statistics failed";
            case V2ProjectCodeSymbolsTool.KIND ->
                    "Project code symbol extraction failed";
            case V2ProjectExperimentSummaryTool.KIND ->
                    "Project experiment summary failed";
            case V2ProjectCrossMaterialSearchTool.KIND ->
                    "Project cross-material search failed";
            case V2ProjectDocumentExtractTool.KIND ->
                    "Project document extraction failed";
            case V2ProjectSpreadsheetInspectTool.KIND ->
                    "Project spreadsheet inspection failed";
            default -> "Project evidence operation failed";
        };
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

    private record FormalExecution(
            com.yanban.api.agent.v2.VerifiedAgentTurnProductContext context,
            io.paperagent.v2.contracts.PlanId planId,
            RecoveredActiveStep active,
            PersistedEffectIntent intent) {
    }

    private record WorkspaceExecution(
            WorkspacePort port,
            io.paperagent.v2.contracts.WorkspaceRef ref) {
    }
}
