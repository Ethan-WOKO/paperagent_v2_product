package com.yanban.api.agent.sandbox;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.V2SafeFailureDiagnostics;
import com.yanban.api.agent.v2.effect.NaturalLanguageEffectAuthoritySource;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimRepository;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimException;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimRequest;
import com.yanban.api.agent.v2.persistence.ProductEffectExecutionClaimResult;
import com.yanban.api.agent.v2.workspace
        .AuthenticatedAgentTurnWorkspacePortFactory;
import com.yanban.sandbox.contract.SandboxCanonicalDigest;
import com.yanban.sandbox.contract.SandboxDispatch;
import com.yanban.sandbox.contract.SandboxDispatchResponse;
import com.yanban.sandbox.contract.SandboxExecutionStatus;
import com.yanban.sandbox.contract.SandboxExecutionView;
import com.yanban.sandbox.contract.SandboxReceipt;
import io.paperagent.v2.contracts.ArtifactRef;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PlanExecutionContextRepository;
import io.paperagent.v2.runtime.execution.recovery.composition
        .RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition
        .StepRecoveryLeaseAttempt;
import io.paperagent.v2.runtime.execution.recovery.composition
        .StepRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition
        .StepRecoveryRequest;
import io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Product adapter from a durable {@code sandbox.execute} EffectIntent to the
 * existing shared broker. Dependencies are prepared by the broker profile;
 * user code still executes offline inside the E2B sandbox.
 */
@Service
@ConditionalOnProperty(
        prefix = "yanban.sandbox", name = "enabled", havingValue = "true")
public class V2SandboxEffectExecutionComposer {
    private static final Logger log = LoggerFactory.getLogger(
            V2SandboxEffectExecutionComposer.class);
    public static final String KIND = "sandbox.execute";
    private static final int MAX_FILES = 32;
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final int MAX_TOTAL_BYTES = 4 * 1024 * 1024;
    private static final Set<SandboxExecutionStatus> TERMINAL = Set.of(
            SandboxExecutionStatus.SUCCEEDED,
            SandboxExecutionStatus.FAILED,
            SandboxExecutionStatus.CANCELLED,
            SandboxExecutionStatus.TIMED_OUT,
            SandboxExecutionStatus.CLEANUP_FAILED);

    private final AgentTurnProductContextResolver contexts;
    private final ProductPlanIdDerivation planIds;
    private final StepRecoverer recoverer;
    private final EffectIntentRepository intents;
    private final ProductEffectExecutionClaimRepository claims;
    private final PlanExecutionContextRepository executionContexts;
    private final AuthenticatedAgentTurnWorkspacePortFactory workspaces;
    private final NaturalLanguageEffectAuthoritySource authorities;
    private final SandboxBrokerClient broker;
    private final SandboxExecutionProperties properties;
    private final SandboxCommandPolicy commands;
    private final V2SandboxPollWaiter waiter;

    public V2SandboxEffectExecutionComposer(
            AgentTurnProductContextResolver contexts,
            ProductPlanIdDerivation planIds,
            StepRecoverer recoverer,
            EffectIntentRepository intents,
            ProductEffectExecutionClaimRepository claims,
            PlanExecutionContextRepository executionContexts,
            AuthenticatedAgentTurnWorkspacePortFactory workspaces,
            NaturalLanguageEffectAuthoritySource authorities,
            SandboxBrokerClient broker,
            SandboxExecutionProperties properties,
            SandboxCommandPolicy commands,
            V2SandboxPollWaiter waiter) {
        this.contexts = contexts;
        this.planIds = planIds;
        this.recoverer = recoverer;
        this.intents = intents;
        this.claims = claims;
        this.executionContexts = executionContexts;
        this.workspaces = workspaces;
        this.authorities = authorities;
        this.broker = broker;
        this.properties = properties;
        this.commands = commands;
        this.waiter = waiter;
    }

    public V2SandboxEffectExecutionOutcome execute(
            Long userId, Long turnId, io.paperagent.v2.contracts.PlanId planId,
            ToolCallId toolCallId, StepRecoveryLeaseAttempt attempt) {
        var context = contexts.resolve(userId, turnId);
        if (context.identity().projectId() == null
                || !planIds.derive(context.identity()).equals(planId)) {
            throw failed("context");
        }
        var recovered = recoverer.recover(
                new StepRecoveryRequest(planId, attempt));
        if (!(recovered instanceof RecoveredActiveStep active)
                || active.leaseDisposition()
                        != StepRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY) {
            throw failed("recovery");
        }
        PersistedEffectIntent intent = intents.find(toolCallId)
                .value().orElseThrow(() -> failed("intent"));
        if (!intent.intent().planId().equals(planId)
                || !intent.intent().stepId().equals(
                        active.recovery().activation().stepId())
                || !intent.activationEventId().equals(
                        active.recovery().activation()
                                .activationEvent().id())
                || !intent.leaseOwnerId().equals(active.lease().ownerId())
                || intent.fencingToken() != active.lease().fencingToken()
                || !KIND.equals(intent.intent().kind())
                || !authorities.authorizes(
                        userId, turnId, planId.value(),
                        intent.intent().stepId().value(), KIND)) {
            throw failed("intent_authority");
        }
        var executionContext = executionContexts.inspect(planId);
        if (executionContext.outcome() != PersistenceOutcome.FOUND
                || !(executionContext.value().orElse(null)
                        instanceof PersistedPlanExecutionContextConfirmed
                                confirmed)) {
            throw failed("execution_context");
        }
        WorkspacePort workspace = workspaces.create(userId, turnId);
        VerifiedWorkspaceMaterialization verified =
                workspace.inspectMaterialization(
                        confirmed.materializationSpec());
        Arguments arguments = arguments(intent);
        Map<String, String> files = files(
                workspace, verified.workspace(), arguments.paths());
        SandboxExecutionException commandFailure = null;
        try {
            commands.validate(arguments.argv(), Map.of());
        } catch (SandboxExecutionException rejected) {
            commandFailure = rejected;
            log.warn(
                    "V2 Sandbox command rejected planId={} stepId={} "
                            + "toolCallId={} sandboxCode={}",
                    planId.value(),
                    active.recovery().activation().stepId().value(),
                    toolCallId.value(), rejected.code());
        }
        SandboxExecutionException rejectedCommand = commandFailure;
        Instant observed = Instant.now();
        ProductEffectExecutionClaimResult claimed;
        try {
            claimed = claims.execute(
                    new ProductEffectExecutionClaimRequest(
                            active.recovery(), active.lease(), intent,
                            attempt.leaseToken(),
                            active.lease().fencingToken(), observed,
                            () -> rejectedCommand == null
                                    ? executeBroker(
                                            context.identity().userId(),
                                            context.identity().projectId(),
                                            context.identity().sessionId(),
                                            context.projectVersionId()
                                                    .orElseThrow(),
                                            active, intent, files,
                                            arguments.argv())
                                    : commandPolicyReceipt(
                                            toolCallId, rejectedCommand,
                                            observed)));
        } catch (V2SandboxEffectPendingException pending) {
            throw pending;
        } catch (V2SandboxEffectExecutionException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            V2SandboxEffectPendingException pending = cause(
                    failure, V2SandboxEffectPendingException.class);
            if (pending != null) {
                throw pending;
            }
            V2SandboxEffectExecutionException rejected = cause(
                    failure, V2SandboxEffectExecutionException.class);
            if (rejected != null) {
                throw rejected;
            }
            ProductEffectExecutionClaimException claimFailure = cause(
                    failure, ProductEffectExecutionClaimException.class);
            if (claimFailure != null) {
                log.warn(
                        "V2 Sandbox durable claim rejected planId={} "
                                + "stepId={} toolCallId={} claimPath={} "
                                + "timingDeltaMillis={}",
                        planId.value(),
                        active.recovery().activation().stepId().value(),
                        toolCallId.value(), claimFailure.path(),
                        claimFailure.timingDeltaMillis());
            }
            log.warn(
                    "V2 Sandbox claim failed planId={} stepId={} "
                            + "toolCallId={} exceptionType={} causeType={} "
                            + "origin={}",
                    planId.value(),
                    active.recovery().activation().stepId().value(),
                    toolCallId.value(),
                    V2SafeFailureDiagnostics.exceptionType(failure),
                    V2SafeFailureDiagnostics.causeType(failure),
                    V2SafeFailureDiagnostics.origin(failure));
            throw failed("claim");
        }
        return new V2SandboxEffectExecutionOutcome(
                claimed.result(), claimed.replayed());
    }

    private static ExecutionReceipt commandPolicyReceipt(
            ToolCallId toolCallId,
            SandboxExecutionException failure,
            Instant observed) {
        return new ExecutionReceipt(
                new ReceiptId("sandbox-receipt." + hash(
                        toolCallId.value())),
                toolCallId,
                ReceiptStatus.FAILURE,
                observed,
                observed,
                Optional.empty(),
                Optional.of(failure.code().name()),
                OutputCapture.empty(),
                OutputCapture.inline(
                        "Server sandbox policy rejected the command. "
                                + "Use an exact argv shape from the "
                                + "sandbox.execute tool description.",
                        false),
                List.of(), Optional.empty(), List.of());
    }

    private ExecutionReceipt executeBroker(
            long userId, long projectId, long sessionId,
            String projectVersion, RecoveredActiveStep active,
            PersistedEffectIntent intent, Map<String, String> files,
            List<String> argv) {
        String key = intent.intent().toolCallId().value();
        String policy = hash("v2-e2b-offline\u0000"
                + String.join("\u0000", argv));
        SandboxDispatch unsigned = new SandboxDispatch(
                key, "", userId, projectId, sessionId,
                stableLong(active.planId().value()),
                stableLong(intent.intent().stepId().value()),
                active.lease().fencingToken(), projectVersion, policy,
                files, argv, properties.getCpus(),
                properties.getMemoryLimit().toBytes(),
                properties.getExecutionTimeout().toMillis(),
                properties.getMaxOutputSize().toBytes(), false);
        SandboxDispatch dispatch = new SandboxDispatch(
                key, SandboxCanonicalDigest.compute(unsigned),
                unsigned.userId(), unsigned.projectId(),
                unsigned.sessionId(), unsigned.planId(), unsigned.stepId(),
                unsigned.fence(), unsigned.projectVersion(),
                unsigned.policyDigest(), unsigned.files(), unsigned.argv(),
                unsigned.cpus(), unsigned.memoryBytes(),
                unsigned.timeoutMillis(), unsigned.maxOutputBytes(), false);
        SandboxDispatchResponse accepted;
        try {
            broker.requireHealthy();
            accepted = broker.dispatch(dispatch);
        } catch (RuntimeException temporarilyUnknown) {
            log.warn(
                    "V2 Sandbox broker dispatch unavailable planId={} "
                            + "stepId={} toolCallId={} exceptionType={} "
                            + "causeType={} sandboxCode={} origin={}",
                    active.planId().value(),
                    intent.intent().stepId().value(),
                    intent.intent().toolCallId().value(),
                    V2SafeFailureDiagnostics.exceptionType(
                            temporarilyUnknown),
                    V2SafeFailureDiagnostics.causeType(
                            temporarilyUnknown),
                    sandboxCode(temporarilyUnknown),
                    V2SafeFailureDiagnostics.origin(
                            temporarilyUnknown));
            throw new V2SandboxEffectPendingException();
        }
        if (accepted == null
                || !key.equals(accepted.idempotencyKey())
                || !dispatch.requestDigest().equals(
                        accepted.requestDigest())
                || accepted.fence() != dispatch.fence()) {
            throw failed("dispatch_authority");
        }
        SandboxExecutionView view = null;
        for (int attempt = 0;
                attempt < waiter.maximumPolls(); attempt++) {
            try {
                view = broker.status(accepted.executionId());
            } catch (RuntimeException unavailable) {
                view = null;
            }
            if (view != null && TERMINAL.contains(view.status())) {
                break;
            }
            if (attempt + 1 < waiter.maximumPolls()) {
                waiter.pause();
            }
        }
        if (view == null || !TERMINAL.contains(view.status())) {
            throw new V2SandboxEffectPendingException();
        }
        if (!accepted.executionId().equals(view.executionId())
                || !key.equals(view.idempotencyKey())
                || !dispatch.requestDigest().equals(view.requestDigest())
                || dispatch.fence() != view.fence()) {
            throw failed("status_authority");
        }
        return receipt(dispatch, view, intent.intent().toolCallId());
    }

    private ExecutionReceipt receipt(
            SandboxDispatch dispatch, SandboxExecutionView view,
            ToolCallId toolCallId) {
        SandboxReceipt source = view.receipt();
        if (source == null
                || !view.executionId().equals(source.executionId())
                || !dispatch.idempotencyKey().equals(source.idempotencyKey())
                || !dispatch.requestDigest().equals(source.requestDigest())
                || dispatch.fence() != source.fence()
                || dispatch.userId() != source.userId()
                || dispatch.projectId() != source.projectId()
                || dispatch.sessionId() != source.sessionId()
                || dispatch.planId() != source.planId()
                || dispatch.stepId() != source.stepId()
                || !dispatch.projectVersion().equals(
                        source.projectVersion())
                || !dispatch.policyDigest().equals(source.policyDigest())
                || !properties.getProvider().equals(source.provider())
                || source.status() != view.status()
                || source.startedAt() == null
                || source.finishedAt() == null
                || source.finishedAt().isBefore(source.startedAt())) {
            throw failed("receipt_authority");
        }
        boolean succeeded =
                source.status() == SandboxExecutionStatus.SUCCEEDED
                        && source.exitCode() != null
                        && source.exitCode() == 0
                        && source.errorCode() == null;
        ReceiptStatus receiptStatus = switch (source.status()) {
            case SUCCEEDED -> ReceiptStatus.SUCCESS;
            case TIMED_OUT -> ReceiptStatus.TIMEOUT;
            case CANCELLED -> ReceiptStatus.CANCELLED;
            default -> ReceiptStatus.FAILURE;
        };
        if ((receiptStatus == ReceiptStatus.SUCCESS && !succeeded)
                || (receiptStatus == ReceiptStatus.FAILURE
                        && source.exitCode() != null
                        && source.exitCode() == 0)) {
            throw failed("receipt_authority");
        }
        Optional<Integer> exitCode =
                receiptStatus == ReceiptStatus.CANCELLED
                                || receiptStatus == ReceiptStatus.TIMEOUT
                        ? Optional.empty()
                        : Optional.ofNullable(source.exitCode());
        List<ArtifactRef> artifacts = new ArrayList<>();
        artifacts.add(V2SandboxInputFingerprint.artifactReference(
                dispatch.files()));
        source.artifacts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ArtifactRef(
                        "sandbox:" + source.executionId() + ":"
                                + entry.getKey() + ":"
                                + entry.getValue().sha256()))
                .forEach(artifacts::add);
        return new ExecutionReceipt(
                new ReceiptId("sandbox-receipt." + hash(
                        toolCallId.value())),
                toolCallId,
                receiptStatus,
                source.startedAt(), source.finishedAt(),
                exitCode,
                succeeded ? Optional.empty()
                        : Optional.of(resultCode(source)),
                capture(source.stdout(), source.outputTruncated()),
                capture(source.stderr(), source.outputTruncated()),
                artifacts, Optional.empty(), List.of());
    }

    private static String resultCode(SandboxReceipt source) {
        String code = source.errorCode() == null
                ? source.status().name()
                : source.errorCode().name();
        if (source.providerDiagnostic() == null) {
            return code;
        }
        String phase = source.providerDiagnostic().failurePhase();
        if (phase == null) {
            return code;
        }
        String normalized = phase.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")) {
            return code;
        }
        return code + "." + normalized;
    }

    private static Arguments arguments(PersistedEffectIntent intent) {
        Map<String, ContractValue> values =
                intent.intent().arguments().values();
        if (!values.keySet().equals(Set.of("paths", "argv"))
                || !(values.get("paths") instanceof ListValue paths)
                || !(values.get("argv") instanceof ListValue argv)
                || paths.values().isEmpty()
                || paths.values().size() > MAX_FILES
                || argv.values().isEmpty()) {
            throw failed("arguments");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (ContractValue value : paths.values()) {
            if (!(value instanceof TextValue text)
                    || !normalized.add(
                            new ProjectPath(text.value()).value())) {
                throw failed("arguments");
            }
        }
        List<String> command = new ArrayList<>();
        for (ContractValue value : argv.values()) {
            if (!(value instanceof TextValue text)) {
                throw failed("arguments");
            }
            command.add(text.value());
        }
        return new Arguments(List.copyOf(normalized),
                List.copyOf(command));
    }

    private static Map<String, String> files(
            WorkspacePort workspace,
            io.paperagent.v2.contracts.WorkspaceRef ref,
            List<String> paths) {
        Map<String, String> result = new LinkedHashMap<>();
        int total = 0;
        for (String path : paths) {
            byte[] bytes = workspace.read(ref, new ProjectPath(path));
            total += bytes.length;
            if (bytes.length > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) {
                throw failed("workspace_files");
            }
            try {
                result.put(path, StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString());
            } catch (Exception invalid) {
                throw failed("workspace_files");
            }
        }
        return Map.copyOf(result);
    }

    private static OutputCapture capture(
            String value, boolean sourceTruncated) {
        String required = value == null ? "" : value;
        if (required.isEmpty()) {
            return OutputCapture.empty();
        }
        int end = Math.min(
                required.length(),
                OutputCapture.MAX_INLINE_CHARACTERS);
        if (end < required.length()
                && Character.isHighSurrogate(required.charAt(end - 1))
                && Character.isLowSurrogate(required.charAt(end))) {
            end--;
        }
        return OutputCapture.inline(
                required.substring(0, end),
                sourceTruncated || end < required.length());
    }

    private static long stableLong(String value) {
        byte[] digest = digest(value);
        long number = ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
        return number == 0 ? 1 : number;
    }

    private static String hash(String value) {
        return HexFormat.of().formatHex(digest(value));
    }

    private static String sandboxCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SandboxExecutionException sandbox
                    && sandbox.code() != null) {
                return sandbox.code().name();
            }
            current = current.getCause();
        }
        return "UNAVAILABLE";
    }

    private static <T extends Throwable> T cause(
            Throwable failure, Class<T> expected) {
        Throwable current = failure;
        while (current != null) {
            if (expected.isInstance(current)) {
                return expected.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static V2SandboxEffectExecutionException failed(String stage) {
        return new V2SandboxEffectExecutionException(stage);
    }

    private record Arguments(List<String> paths, List<String> argv) {
    }
}
