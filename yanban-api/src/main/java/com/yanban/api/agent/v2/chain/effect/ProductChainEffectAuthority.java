package com.yanban.api.agent.v2.chain.effect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.sandbox.V2SandboxEffectExecutionComposer;
import com.yanban.api.agent.v2.effect.AuthenticatedLiteratureSearchEffectExecutionCommand;
import com.yanban.api.agent.v2.effect.AuthenticatedLiteratureSearchEffectExecutionComposer;
import com.yanban.api.agent.v2.effect.project.AuthenticatedProjectEffectExecutionCommand;
import com.yanban.api.agent.v2.effect.project.AuthenticatedProjectEffectExecutionComposer;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.agent.v2.tool.V2ProductToolCatalog;
import com.yanban.core.research.ProjectRelativePath;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords.ActionBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelProposalRecord;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NullValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Bridges a formal chain ActionBinding to the retained durable EffectIntent
 * and project-effect execution authority.
 *
 * <p>An existing intent without a final receipt is deliberately UNKNOWN. It
 * is never interpreted as permission to dispatch the external effect again.
 */
@Component
public final class ProductChainEffectAuthority
        implements ChainEffectRuntime.EffectAuthority {
    private static final String INTENT_REF_PREFIX = "effect-intent.";

    private final ChainFoundationRepository foundations;
    private final ChainWorkflowRepository workflow;
    private final ChainModelRepository models;
    private final LeaseRepository leases;
    private final StepRecoveryRepository recovery;
    private final EffectIntentRepository intents;
    private final EffectOutcomeRepository outcomes;
    private final AuthenticatedLiteratureSearchEffectExecutionComposer
            literatureEffects;
    private final AuthenticatedProjectEffectExecutionComposer projectEffects;
    private final V2SandboxEffectExecutionComposer sandboxEffects;
    private final ProductChainActionWorkspaceAuthorityFactory workspaceAuthorities;
    private final ProductChainTaskMutationFence mutationFence;
    private final ProductPlanBootstrapRepositoryAdapter planBootstraps;
    private final ObjectMapper json;
    private final ConcurrentMap<String, DispatchPermit> dispatchPermits =
            new ConcurrentHashMap<>();

    public ProductChainEffectAuthority(
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            ChainModelRepository models,
            LeaseRepository leases,
            StepRecoveryRepository recovery,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            AuthenticatedLiteratureSearchEffectExecutionComposer
                    literatureEffects,
            AuthenticatedProjectEffectExecutionComposer projectEffects,
            V2SandboxEffectExecutionComposer sandboxEffects,
            ProductChainActionWorkspaceAuthorityFactory workspaceAuthorities,
            ProductChainTaskMutationFence mutationFence,
            ProductPlanBootstrapRepositoryAdapter planBootstraps,
            ObjectMapper json) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.models = Objects.requireNonNull(models, "models");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.literatureEffects = Objects.requireNonNull(
                literatureEffects, "literatureEffects");
        this.projectEffects = Objects.requireNonNull(projectEffects, "projectEffects");
        this.sandboxEffects = Objects.requireNonNull(
                sandboxEffects, "sandboxEffects");
        this.workspaceAuthorities = Objects.requireNonNull(
                workspaceAuthorities, "workspaceAuthorities");
        this.mutationFence = Objects.requireNonNull(mutationFence, "mutationFence");
        this.planBootstraps = Objects.requireNonNull(
                planBootstraps, "planBootstraps");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public ChainEffectRuntime.EffectReconciliation reconcile(
            ChainEffectRuntime.FrozenMutation action) {
        Objects.requireNonNull(action, "action");
        ToolCallId toolCallId = new ToolCallId(action.actionId());
        PersistenceResult<PersistedEffectResult> result =
                outcomes.findResult(toolCallId);
        if (result.successful()) {
            return reconciliation(action, requiredValue(result, "effectResult"));
        }
        requireNotFound(result, "effectResult");

        PersistenceResult<PersistedEffectIntent> intent = intents.find(toolCallId);
        if (intent.successful()) {
            validateIntent(action, requiredValue(intent, "effectIntent"));
            return new ChainEffectRuntime.EffectReconciliation(
                    action.actionId(), action.idempotencyKey(),
                    ChainEffectRuntime.EffectStatus.UNKNOWN,
                    null, null, intentReference(action.actionId()), null);
        }
        requireNotFound(intent, "effectIntent");
        return new ChainEffectRuntime.EffectReconciliation(
                action.actionId(), action.idempotencyKey(),
                ChainEffectRuntime.EffectStatus.NOT_DISPATCHED,
                null, null, null, null);
    }

    @Override
    public ChainEffectRuntime.PreparedEffect prepare(
            ChainEffectRuntime.FrozenMutation action) {
        Objects.requireNonNull(action, "action");
        return mutationFence.prepareCurrent(action, () -> prepareCurrent(action));
    }

    private ChainEffectRuntime.PreparedEffect prepareCurrent(
            ChainEffectRuntime.FrozenMutation action) {
        require(formalAction(action.taskId(), action.actionId()).equals(action),
                "Effect preparation does not match the formal ActionBinding");
        ChainEffectRuntime.EffectReconciliation before = reconcile(action);
        require(before.status()
                        == ChainEffectRuntime.EffectStatus.NOT_DISPATCHED,
                "only an action without a durable EffectIntent may prepare");
        ToolAction proposal = toolAction(action);
        ActiveAuthority authority = activeAuthority(action);
        ToolId toolId = new ToolId(proposal.toolId());
        V2ProductToolCatalog.Entry tool = V2ProductToolCatalog.entry(toolId)
                .orElseThrow(() -> failure("unsupported tool"));
        ObjectValue arguments = objectArguments(proposal.completeArguments());
        require(V2ProductToolCatalog.acceptsArguments(toolId, arguments),
                "tool arguments violate the product schema");
        FormalScopes scopes = validateProposalAuthority(
                action, proposal, tool);
        ChainActionWorkspaceAuthority workspaceAuthority;
        if (tool.executionTarget()
                == V2ProductToolCatalog.ExecutionTarget.LITERATURE) {
            workspaceAuthority = null;
        } else {
            var task = foundations.findTask(action.taskId())
                    .orElseThrow(() -> failure("chain task is unavailable"));
            workspaceAuthority = workspaceAuthorities.create(
                    task, action, scopes.readScopes(), scopes.writeScopes());
        }

        EffectIntent effectIntent = new EffectIntent(
                new ToolCallId(action.actionId()), new PlanId(action.planId()),
                new PlanStepId(action.stepId()), proposal.toolId(), arguments);
        PersistenceResult<PersistedEffectIntent> persisted = intents.persist(
                new EffectIntentRequest(
                        effectIntent, authority.lease().leaseToken(),
                        authority.lease().fencingToken(),
                        new EventId(action.activationEventId())));
        require(persisted.successful(), "effect intent persistence was rejected");
        validateIntent(action, requiredValue(persisted, "effectIntent"));

        DispatchPermit permit = dispatchPermits.computeIfAbsent(
                action.actionId(), ignored -> new DispatchPermit(
                        action.taskId(), UUID.randomUUID().toString(),
                        tool.executionTarget(), workspaceAuthority));
        require(permit.taskId().equals(action.taskId())
                        && permit.executionTarget()
                        == tool.executionTarget()
                        && Objects.equals(permit.workspaceAuthority(),
                        workspaceAuthority),
                "dispatch permit belongs to another task");
        return new ChainEffectRuntime.PreparedEffect(
                intentReference(action.actionId()), action.actionId(),
                action.idempotencyKey(), action.versionFenceSha256(),
                permit.value());
    }

    @Override
    public ChainEffectRuntime.EffectReconciliation dispatch(
            ChainEffectRuntime.PreparedEffect prepared) {
        Objects.requireNonNull(prepared, "prepared");
        require(prepared.effectIntentId().equals(
                        intentReference(prepared.actionId())),
                "prepared effect intent reference is invalid");
        DispatchPermit permit = dispatchPermits.get(prepared.actionId());
        require(permit != null
                        && permit.value().equals(prepared.dispatchPermit())
                        && dispatchPermits.remove(
                        prepared.actionId(), permit),
                "dispatch permit is absent or already consumed");
        String taskId = permit.taskId();
        ChainEffectRuntime.FrozenMutation action = formalAction(
                taskId, prepared.actionId());
        require(action.idempotencyKey().equals(prepared.idempotencyKey())
                        && action.versionFenceSha256().equals(
                        prepared.versionFenceSha256()),
                "prepared effect no longer matches the formal action");

        ChainEffectRuntime.EffectReconciliation before = reconcile(action);
        if (before.status() != ChainEffectRuntime.EffectStatus.UNKNOWN) {
            require(before.status() != ChainEffectRuntime.EffectStatus.NOT_DISPATCHED,
                    "dispatch requires a durable EffectIntent");
            return before;
        }

        PersistedEffectIntent intent = requiredFound(
                intents.find(new ToolCallId(action.actionId())), "effectIntent");
        validateIntent(action, intent);
        ActiveAuthority authority = activeAuthority(action);
        require(intent.leaseOwnerId().equals(authority.lease().ownerId())
                        && intent.fencingToken()
                        == authority.lease().fencingToken(),
                "effect intent lease generation is no longer current");

        V2ProductToolCatalog.Entry tool = V2ProductToolCatalog.entry(
                        new ToolId(intent.intent().kind()))
                .orElseThrow(() -> failure("unsupported tool"));
        require(tool.executionTarget() == permit.executionTarget(),
                "dispatch target changed after EffectIntent preparation");
        var task = foundations.findTask(taskId)
                .orElseThrow(() -> failure("chain task is unavailable"));
        StepRecoveryLeaseAttempt attempt = new StepRecoveryLeaseAttempt(
                authority.lease().ownerId(),
                authority.lease().leaseToken(),
                authority.lease().expiresAt());
        PersistedEffectResult executed = switch (tool.executionTarget()) {
            case LITERATURE -> {
                require(permit.workspaceAuthority() == null,
                        "literature effect cannot carry Workspace authority");
                yield literatureEffects.executeChain(
                        task.userId(), task.turnId(),
                        new AuthenticatedLiteratureSearchEffectExecutionCommand(
                                new PlanId(action.planId()),
                                new ToolCallId(action.actionId()), attempt))
                        .result();
            }
            case PROJECT -> {
                require(permit.workspaceAuthority() != null,
                        "project effect requires Workspace authority");
                yield projectEffects.executeChain(
                        task.userId(), task.turnId(),
                        new AuthenticatedProjectEffectExecutionCommand(
                                new PlanId(action.planId()),
                                new ToolCallId(action.actionId()), attempt,
                                null, permit.workspaceAuthority()))
                        .result();
            }
            case SANDBOX -> {
                require(permit.workspaceAuthority() != null,
                        "sandbox effect requires Workspace authority");
                yield sandboxEffects.executeChain(
                        task.userId(), task.turnId(),
                        new PlanId(action.planId()),
                        new ToolCallId(action.actionId()), attempt,
                        permit.workspaceAuthority()).result();
            }
        };
        validateIntent(action, intent);
        return reconciliation(action, executed);
    }

    private ActiveAuthority activeAuthority(
            ChainEffectRuntime.FrozenMutation action) {
        PlanId planId = new PlanId(action.planId());
        LeaseRecord lease = requiredFound(leases.find(planId), "lease");
        StepRecoverySnapshot snapshot = requiredFound(
                recovery.inspect(planId), "stepRecovery");
        require(snapshot instanceof PersistedStepRecoveryActive,
                "effect requires an active recoverable step");
        PersistedStepRecoveryActive active = (PersistedStepRecoveryActive) snapshot;
        // Plan-revision authority is checked by ProductChainTaskMutationFence
        // against the formal Step activation before preparation. Recovery owns
        // the current Plan projection, whose latest revision may advance after
        // an earlier Step completes; the frozen ActionBinding deliberately
        // keeps the revision that authorized the action. This layer therefore
        // fences the exact Plan, TaskFrame, Step activation and lease generation
        // without conflating those two revision roles.
        require(active.plan().id().value().equals(action.planId())
                        && active.plan().taskFrameId().value().equals(
                        action.taskFrameId())
                        && active.activation().stepId().value().equals(
                        action.stepId())
                        && active.activation().activationEvent().id().value()
                        .equals(action.activationEventId()),
                "step recovery does not match the frozen action");
        require(lease.planId().equals(planId)
                        && lease.ownerId().equals(
                        active.activation().leaseOwnerId())
                        && lease.fencingToken()
                        == active.activation().fencingToken(),
                "lease does not match the active step generation");
        return new ActiveAuthority(lease, active);
    }

    private ToolAction toolAction(ChainEffectRuntime.FrozenMutation action) {
        ModelProposalRecord proposal = models.findProposal(action.proposalId())
                .orElseThrow(() -> failure("action proposal is unavailable"));
        require(proposal.taskId().equals(action.taskId())
                        && proposal.proposalKind()
                        == ChainProposalKind.EXECUTOR_TOOL_ACTION,
                "action proposal identity mismatch");
        try {
            JsonNode payload = json.readTree(proposal.payload().json());
            JsonNode toolId = payload.get("toolId");
            JsonNode arguments = payload.get("completeArguments");
            JsonNode requiredPermission = payload.get("requiredPermission");
            JsonNode readScopes = payload.get("readScopes");
            JsonNode writeScopes = payload.get("writeScopes");
            require(payload.isObject() && toolId != null && toolId.isTextual()
                            && arguments != null && arguments.isTextual()
                            && requiredPermission != null
                            && requiredPermission.isTextual()
                            && readScopes != null && readScopes.isArray()
                            && writeScopes != null && writeScopes.isArray(),
                    "tool proposal payload is invalid");
            return new ToolAction(
                    toolId.textValue(), arguments.textValue(),
                    requiredPermission.textValue(), strings(readScopes),
                    strings(writeScopes));
        } catch (java.io.IOException invalid) {
            throw failure("tool proposal payload is invalid");
        }
    }

    private FormalScopes validateProposalAuthority(
            ChainEffectRuntime.FrozenMutation action,
            ToolAction proposal,
            V2ProductToolCatalog.Entry tool) {
        ModelProposalRecord stored = models.findProposal(action.proposalId())
                .orElseThrow(() -> failure("action proposal is unavailable"));
        try {
            JsonNode sourceRefs = json.readTree(stored.sourceRefs().json());
            String permissionRef = permissionReference(tool);
            require(sourceRefs.isArray()
                            && contains(sourceRefs, proposal.toolId())
                            && contains(sourceRefs, permissionRef)
                            && permissionRef.equals(
                            proposal.requiredPermission()),
                    "tool or permission authority was not frozen in the proposal");
            var bootstrap = planBootstraps.find(new PlanId(action.planId()))
                    .orElseThrow(() -> failure(
                            "formal TaskFrame permission authority is unavailable"));
            require(bootstrap.plan().id().value().equals(action.planId())
                            && bootstrap.plan().taskFrameId().value().equals(
                            action.taskFrameId())
                            && bootstrap.taskFrame().id().value().equals(
                            action.taskFrameId()),
                    "formal TaskFrame permission authority does not match the action");
            var profile = bootstrap.taskFrame().executionProfile();
            require(profile.capabilities().containsAll(
                            tool.descriptor().requiredCapabilities()),
                    "formal TaskFrame does not grant the tool capabilities");
            if (tool.descriptor().requiredCapabilities()
                    .contains(Capability.ACCESS_NETWORK)) {
                require(profile.networkPolicy()
                                == NetworkPolicy.ALLOWLIST_ONLY
                                && profile.networkAllowlist().contains(
                                "product-literature-search"),
                        "formal TaskFrame does not grant governed network access");
            }
            LinkedHashSet<String> reads = projectScopes(
                    proposal.readScopes());
            LinkedHashSet<String> writes = projectScopes(
                    proposal.writeScopes());
            JsonNode arguments = json.readTree(proposal.completeArguments());
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            collectProjectPaths(arguments, null, paths);
            switch (tool.executionTarget()) {
                case LITERATURE -> require(
                        reads.isEmpty() && writes.isEmpty(),
                        "literature tool cannot declare Project scopes");
                case PROJECT -> {
                    require(tool.descriptor().requiredCapabilities()
                                    .contains(Capability.READ_PROJECT)
                                    && !reads.isEmpty(),
                            "project tool requires a formal read scope");
                    require(reads.containsAll(paths),
                            "project tool path is outside the formal read scope");
                    if (tool.descriptor().requiredCapabilities()
                            .contains(Capability.WRITE_WORKSPACE)) {
                        require(!writes.isEmpty()
                                        && writes.containsAll(paths)
                                        && reads.containsAll(writes),
                                "project tool path is outside the formal write scope");
                    } else {
                        require(writes.isEmpty(),
                                "read-only project tool cannot declare a write scope");
                    }
                }
                case SANDBOX -> {
                    require(!reads.isEmpty() && reads.containsAll(paths),
                            "sandbox path is outside the formal read scope");
                    require(writes.isEmpty(),
                            "sandbox tool cannot declare a Workspace write scope");
                }
            }
            return new FormalScopes(List.copyOf(reads), List.copyOf(writes));
        } catch (java.io.IOException invalid) {
            throw failure("tool proposal authority is invalid");
        }
    }

    private static boolean contains(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (value.isTextual() && value.textValue().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static String permissionReference(V2ProductToolCatalog.Entry tool) {
        Set<Capability> capabilities = tool.descriptor().requiredCapabilities();
        if (capabilities.equals(Set.of(Capability.READ_PROJECT))) {
            return "permission.project-read";
        }
        if (capabilities.equals(Set.of(
                Capability.READ_PROJECT, Capability.WRITE_WORKSPACE))) {
            return "permission.project-write";
        }
        if (capabilities.equals(Set.of(
                Capability.EXECUTE_COMMAND, Capability.INSTALL_DEPENDENCY))) {
            return "permission.sandbox-execute-install";
        }
        if (capabilities.equals(Set.of(
                Capability.ACCESS_NETWORK,
                Capability.INVOKE_EXTERNAL_TOOL))) {
            return "permission.literature-network-external";
        }
        throw failure("tool capability set has no formal permission mapping");
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            require(value.isTextual() && !value.textValue().isBlank(),
                    "tool proposal scope is invalid");
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private static LinkedHashSet<String> projectScopes(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        try {
            for (String value : values) {
                String normalized = new ProjectRelativePath(value).value();
                require(normalized.equals(value)
                                && result.add(normalized),
                        "tool proposal scope is duplicated or noncanonical");
            }
        } catch (IllegalArgumentException invalid) {
            throw failure("tool proposal scope is noncanonical");
        }
        return result;
    }

    private static void collectProjectPaths(
            JsonNode node, String fieldName, LinkedHashSet<String> paths) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectProjectPaths(
                    entry.getValue(), entry.getKey(), paths));
            return;
        }
        if (node.isArray()) {
            node.forEach(value -> collectProjectPaths(value, fieldName, paths));
            return;
        }
        if (node.isTextual() && fieldName != null
                && fieldName.toLowerCase(java.util.Locale.ROOT)
                .contains("path")) {
            paths.add(new ProjectRelativePath(node.textValue()).value());
        }
    }

    private ChainEffectRuntime.FrozenMutation formalAction(
            String taskId, String actionId) {
        List<ActionBindingRecord> matches = workflow.findActionBindings(taskId)
                .stream().filter(value -> value.actionId().equals(actionId))
                .toList();
        require(matches.size() == 1,
                "dispatch requires exactly one formal action binding");
        ActionBindingRecord action = matches.get(0);
        return new ChainEffectRuntime.FrozenMutation(
                ChainEffectRuntime.SourceKind.TOOL_ACTION,
                action.taskId(), action.actionId(), action.idempotencyKey(),
                action.proposalId(), action.instructionId(), action.taskFrameId(),
                action.planId(), action.planRevisionId(), action.stepId(),
                action.activationEventId(), action.workspaceId(),
                action.baseCandidateKey(), action.actionSignatureSha256(),
                action.versionFenceSha256());
    }

    private ChainEffectRuntime.EffectReconciliation reconciliation(
            ChainEffectRuntime.FrozenMutation action,
            PersistedEffectResult result) {
        ExecutionReceipt receipt = result.receipt();
        require(receipt.toolCallId().value().equals(action.actionId()),
                "effect receipt belongs to another action");
        String receiptRef = receipt.id().value();
        if (receipt.status() != ReceiptStatus.SUCCESS) {
            return new ChainEffectRuntime.EffectReconciliation(
                    action.actionId(), action.idempotencyKey(),
                    ChainEffectRuntime.EffectStatus.FAILED,
                    receiptRef, receiptRef, null, null);
        }
        ToolAction proposal = toolAction(action);
        V2ProductToolCatalog.Entry tool = V2ProductToolCatalog.entry(
                        new ToolId(proposal.toolId()))
                .orElseThrow(() -> failure("unsupported tool"));
        boolean workspaceMutation = tool.descriptor().requiredCapabilities()
                .contains(Capability.WRITE_WORKSPACE);
        return new ChainEffectRuntime.EffectReconciliation(
                action.actionId(), action.idempotencyKey(),
                ChainEffectRuntime.EffectStatus.SUCCEEDED,
                receiptRef, null, null,
                workspaceMutation
                        ? new ChainEffectRuntime.WorkspaceMutation(receiptRef)
                        : null);
    }

    private void validateIntent(
            ChainEffectRuntime.FrozenMutation action,
            PersistedEffectIntent persisted) {
        EffectIntent intent = persisted.intent();
        require(intent.toolCallId().value().equals(action.actionId())
                        && intent.planId().value().equals(action.planId())
                        && intent.stepId().value().equals(action.stepId())
                        && persisted.activationEventId().value().equals(
                        action.activationEventId()),
                "EffectIntent does not bind the frozen action");
        ToolAction proposal = toolAction(action);
        require(intent.kind().equals(proposal.toolId())
                        && intent.arguments().equals(
                        objectArguments(proposal.completeArguments())),
                "EffectIntent changed the accepted proposal");
    }

    private ObjectValue objectArguments(String document) {
        try {
            JsonNode root = json.readTree(document);
            require(root != null && root.isObject(),
                    "tool arguments must be one JSON object");
            return (ObjectValue) contractValue(root);
        } catch (java.io.IOException invalid) {
            throw failure("tool arguments are invalid JSON");
        }
    }

    private ContractValue contractValue(JsonNode node) {
        if (node.isTextual()) return new TextValue(node.textValue());
        if (node.isNumber()) return new NumberValue(node.decimalValue());
        if (node.isBoolean()) return new BooleanValue(node.booleanValue());
        if (node.isNull()) return NullValue.INSTANCE;
        if (node.isArray()) {
            List<ContractValue> values = new ArrayList<>();
            node.forEach(item -> values.add(contractValue(item)));
            return new ListValue(values);
        }
        require(node.isObject(), "unsupported tool argument value");
        Map<String, ContractValue> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry ->
                values.put(entry.getKey(), contractValue(entry.getValue())));
        return new ObjectValue(values);
    }

    private static String intentReference(String actionId) {
        return INTENT_REF_PREFIX + actionId;
    }

    private static <T> T requiredFound(
            PersistenceResult<T> result, String authority) {
        require(result.successful(), authority + " is unavailable");
        return requiredValue(result, authority);
    }

    private static <T> T requiredValue(
            PersistenceResult<T> result, String authority) {
        return result.value().orElseThrow(
                () -> failure(authority + " has no value"));
    }

    private static void requireNotFound(
            PersistenceResult<?> result, String authority) {
        require(result.failure().filter(value ->
                        value.code() == PersistenceErrorCode.NOT_FOUND)
                        .isPresent(),
                authority + " lookup was rejected");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw failure(message);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw failure(field + " must not be blank");
        }
        return value;
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }

    private record ToolAction(
            String toolId,
            String completeArguments,
            String requiredPermission,
            List<String> readScopes,
            List<String> writeScopes) {
        private ToolAction {
            required(toolId, "toolId");
            required(completeArguments, "completeArguments");
            required(requiredPermission, "requiredPermission");
            readScopes = List.copyOf(readScopes);
            writeScopes = List.copyOf(writeScopes);
        }
    }

    private record ActiveAuthority(
            LeaseRecord lease, PersistedStepRecoveryActive recovery) {
    }

    private record FormalScopes(
            List<String> readScopes, List<String> writeScopes) {
    }

    private record DispatchPermit(
            String taskId,
            String value,
            V2ProductToolCatalog.ExecutionTarget executionTarget,
            ChainActionWorkspaceAuthority workspaceAuthority) {
    }
}
