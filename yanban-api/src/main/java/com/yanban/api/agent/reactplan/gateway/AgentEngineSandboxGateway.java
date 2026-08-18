package com.yanban.api.agent.reactplan.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.BoundedOutput;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.Receipt;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.ReceiptInput;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.SandboxSubmit;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.SandboxCancelRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.SandboxView;
import com.yanban.api.agent.sandbox.SandboxBrokerClient;
import com.yanban.api.agent.sandbox.SandboxExecutionProperties;
import com.yanban.sandbox.contract.SandboxCanonicalDigest;
import com.yanban.sandbox.contract.SandboxCommandProfiles;
import com.yanban.sandbox.contract.SandboxDispatch;
import com.yanban.sandbox.contract.SandboxDispatchResponse;
import com.yanban.sandbox.contract.SandboxExecutionStatus;
import com.yanban.sandbox.contract.SandboxExecutionView;
import com.yanban.sandbox.contract.SandboxReceipt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
final class AgentEngineSandboxGateway {
    private static final Set<String> TERMINAL = Set.of(
            "SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "SYSTEM_ERROR");
    private final AgentEngineWorkspaceGateway workspaces;
    private final AgentEngineSandboxExecutionTransactions transactions;
    private final SandboxBrokerClient broker;
    private final SandboxExecutionProperties properties;
    private final ObjectMapper json;

    AgentEngineSandboxGateway(
            AgentEngineWorkspaceGateway workspaces,
            AgentEngineSandboxExecutionTransactions transactions,
            SandboxBrokerClient broker,
            SandboxExecutionProperties properties,
            ObjectMapper json) {
        this.workspaces = workspaces;
        this.transactions = transactions;
        this.broker = broker;
        this.properties = properties;
        this.json = json;
    }

    SandboxView submit(EngineTaskAuthority authority, SandboxSubmit request) {
        validate(request);
        AgentEngineWorkspaceGateway.ResolvedInputs inputs =
                workspaces.resolveInputs(authority, request.inputs());
        String semanticDigest = semanticDigest(request, inputs.inputs());
        String executionRef = "execution." + sha256(
                authority.taskId() + "\0" + request.clientRequestId());
        StoredRequest stored = new StoredRequest(
                authority.requestDigest(), authority.userId(), authority.turnId(),
                authority.sessionId(), authority.projectId(), authority.projectVersion(),
                request, inputs);
        AgentEngineSandboxExecutionEntity value;
        try {
            value = transactions.create(new AgentEngineSandboxExecutionEntity(
                    authority.taskId(), request.clientRequestId(), request.requestDigest(),
                    semanticDigest, executionRef, write(stored), LocalDateTime.now(ZoneOffset.UTC)));
        } catch (DataIntegrityViolationException race) {
            value = transactions.find(authority.taskId(), request.clientRequestId())
                    .orElseThrow(() -> race);
        }
        requireReplay(value, authority, request, semanticDigest);
        if (value.brokerExecutionRef() == null) {
            value = dispatch(value, stored);
        }
        return view(value);
    }

    SandboxView status(EngineTaskAuthority authority, String clientRequestId) {
        requireCallId(clientRequestId);
        AgentEngineSandboxExecutionEntity value = transactions.find(
                        authority.taskId(), clientRequestId)
                .orElseThrow(() -> EngineGatewayException.notFound("SANDBOX_EXECUTION_NOT_FOUND"));
        StoredRequest stored = read(value.requestJson(), StoredRequest.class);
        requireTaskAuthority(authority, stored);
        if (TERMINAL.contains(value.state()) || value.brokerExecutionRef() == null) {
            return view(value);
        }
        SandboxExecutionView brokerView;
        try {
            brokerView = broker.status(value.brokerExecutionRef());
        } catch (RuntimeException unavailable) {
            throw new EngineGatewayException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "SANDBOX_STATUS_UNAVAILABLE");
        }
        SandboxDispatch dispatch = dispatch(stored);
        if (brokerView == null
                || !value.brokerExecutionRef().equals(brokerView.executionId())
                || !dispatch.requestDigest().equals(brokerView.requestDigest())
                || dispatch.fence() != brokerView.fence()) {
            throw EngineGatewayException.conflict("SANDBOX_BROKER_IDENTITY_CONFLICT");
        }
        String state = state(brokerView.status());
        if (!TERMINAL.contains(state)) {
            return view(transactions.dispatched(
                    value.taskId(), value.clientRequestId(), brokerView.executionId(), state));
        }
        Receipt receipt = projectReceipt(value, stored, dispatch, brokerView, state);
        String receiptRef = "receipt." + sha256(
                value.taskId() + "\0" + value.clientRequestId());
        Receipt bound = new Receipt("1.0", receiptRef, value.executionRef(),
                receipt.status(), receipt.exitCode(), receipt.stdout(), receipt.stderr(),
                receipt.inputFingerprint(), receipt.inputs(), receipt.startedAt(), receipt.finishedAt());
        return view(transactions.terminal(value.taskId(), value.clientRequestId(),
                state, receiptRef, write(bound)));
    }

    SandboxView cancel(EngineTaskAuthority authority, String clientRequestId,
                       SandboxCancelRequest request) {
        if (request == null || !"1.0".equals(request.contractVersion())) {
            throw EngineGatewayException.badRequest("SANDBOX_CANCEL_REQUEST_INVALID");
        }
        requireCallId(clientRequestId);
        AgentEngineSandboxExecutionEntity current = transactions.find(
                        authority.taskId(), clientRequestId)
                .orElseThrow(() -> EngineGatewayException.notFound("SANDBOX_EXECUTION_NOT_FOUND"));
        StoredRequest stored = read(current.requestJson(), StoredRequest.class);
        requireTaskAuthority(authority, stored);
        if (TERMINAL.contains(current.state())) return view(current);

        AgentEngineSandboxExecutionEntity requested = transactions.requestCancel(
                authority.taskId(), clientRequestId);
        if (requested.brokerExecutionRef() != null) {
            try {
                broker.cancel(requested.brokerExecutionRef(), dispatch(stored).fence());
            } catch (RuntimeException unavailable) {
                throw new EngineGatewayException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "SANDBOX_CANCEL_UNAVAILABLE");
            }
        }
        return view(requested);
    }

    Receipt receipt(EngineTaskAuthority authority, String receiptRef) {
        if (receiptRef == null || receiptRef.isBlank() || receiptRef.length() > 256) {
            throw EngineGatewayException.badRequest("SANDBOX_RECEIPT_REF_INVALID");
        }
        AgentEngineSandboxExecutionEntity value = transactions.findReceipt(
                        authority.taskId(), receiptRef)
                .orElseThrow(() -> EngineGatewayException.notFound("SANDBOX_RECEIPT_NOT_FOUND"));
        requireTaskAuthority(authority, read(value.requestJson(), StoredRequest.class));
        if (!TERMINAL.contains(value.state()) || value.receiptJson() == null) {
            throw EngineGatewayException.notFound("SANDBOX_RECEIPT_NOT_FOUND");
        }
        return read(value.receiptJson(), Receipt.class);
    }

    Receipt requireSuccessfulReceipt(
            EngineTaskAuthority authority, String receiptRef) {
        Receipt receipt = receipt(authority, receiptRef);
        if (!"SUCCEEDED".equals(receipt.status())
                || receipt.exitCode() == null || receipt.exitCode() != 0) {
            throw EngineGatewayException.conflict("WORKSPACE_PUBLICATION_RECEIPT_FAILED");
        }
        return receipt;
    }

    private AgentEngineSandboxExecutionEntity dispatch(
            AgentEngineSandboxExecutionEntity value, StoredRequest stored) {
        SandboxDispatch request = dispatch(stored);
        SandboxDispatchResponse response;
        try {
            response = broker.dispatch(request);
        } catch (RuntimeException unavailable) {
            throw new EngineGatewayException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "SANDBOX_SUBMIT_UNAVAILABLE");
        }
        if (response == null || response.executionId() == null
                || !request.requestDigest().equals(response.requestDigest())
                || request.fence() != response.fence()) {
            throw EngineGatewayException.conflict("SANDBOX_BROKER_IDENTITY_CONFLICT");
        }
        String state = state(response.status());
        AgentEngineSandboxExecutionEntity dispatched = transactions.dispatched(
                value.taskId(), value.clientRequestId(),
                response.executionId(), TERMINAL.contains(state) ? "RUNNING" : state);
        if ("CANCEL_REQUESTED".equals(dispatched.state())) {
            try {
                broker.cancel(response.executionId(), request.fence());
            } catch (RuntimeException unavailable) {
                throw new EngineGatewayException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "SANDBOX_CANCEL_UNAVAILABLE");
            }
        }
        return dispatched;
    }

    private SandboxDispatch dispatch(StoredRequest stored) {
        SandboxSubmit submit = stored.submit();
        String idempotency = "engine." + sha256(
                stored.authorityRequestDigest() + "\0" + submit.clientRequestId());
        String policyDigest = sha256("agent-engine-p2\0isolated-workspace\0execute-sandbox");
        SandboxDispatch unsigned = new SandboxDispatch(
                idempotency, "", stored.userId(), stored.projectId(), stored.sessionId(),
                stableLong("plan\0" + stored.authorityRequestDigest()),
                stableLong("step\0" + submit.clientRequestId()), 1,
                stored.projectVersion(), policyDigest, stored.inputs().files(),
                submit.argv(), properties.getCpus(), properties.getMemoryLimit().toBytes(),
                submit.timeoutMillis(), properties.getMaxOutputSize().toBytes(), false);
        return new SandboxDispatch(
                unsigned.idempotencyKey(), SandboxCanonicalDigest.compute(unsigned),
                unsigned.userId(), unsigned.projectId(), unsigned.sessionId(),
                unsigned.planId(), unsigned.stepId(), unsigned.fence(),
                unsigned.projectVersion(), unsigned.policyDigest(), unsigned.files(),
                unsigned.argv(), unsigned.cpus(), unsigned.memoryBytes(),
                unsigned.timeoutMillis(), unsigned.maxOutputBytes(), false);
    }

    private Receipt projectReceipt(
            AgentEngineSandboxExecutionEntity value,
            StoredRequest stored,
            SandboxDispatch dispatch,
            SandboxExecutionView brokerView,
            String state) {
        SandboxReceipt source = brokerView.receipt();
        if (source == null || !valid(source, brokerView, dispatch)) {
            if (!"SYSTEM_ERROR".equals(state)) {
                throw EngineGatewayException.conflict("SANDBOX_RECEIPT_CONFLICT");
            }
            Instant now = Instant.now();
            return new Receipt("1.0", "pending", value.executionRef(), state, null,
                    new BoundedOutput("", false, 0), new BoundedOutput("", false, 0),
                    stored.inputs().inputFingerprint(), stored.inputs().inputs(), now, now);
        }
        Integer exitCode = switch (state) {
            case "SUCCEEDED", "FAILED" -> source.exitCode();
            default -> null;
        };
        return new Receipt("1.0", "pending", value.executionRef(), state, exitCode,
                output(source.stdout(), source.outputTruncated()),
                output(source.stderr(), source.outputTruncated()),
                stored.inputs().inputFingerprint(), stored.inputs().inputs(),
                source.startedAt(), source.finishedAt());
    }

    private boolean valid(SandboxReceipt receipt, SandboxExecutionView view,
                          SandboxDispatch dispatch) {
        return receipt.executionId().equals(view.executionId())
                && receipt.idempotencyKey().equals(dispatch.idempotencyKey())
                && receipt.requestDigest().equals(dispatch.requestDigest())
                && receipt.userId() == dispatch.userId()
                && receipt.projectId() == dispatch.projectId()
                && receipt.sessionId() == dispatch.sessionId()
                && receipt.planId() == dispatch.planId()
                && receipt.stepId() == dispatch.stepId()
                && receipt.fence() == dispatch.fence()
                && receipt.projectVersion().equals(dispatch.projectVersion())
                && receipt.policyDigest().equals(dispatch.policyDigest())
                && properties.getProvider().equals(receipt.provider())
                && receipt.status() == view.status()
                && receipt.startedAt() != null && receipt.finishedAt() != null
                && !receipt.finishedAt().isBefore(receipt.startedAt());
    }

    private static BoundedOutput output(String source, boolean sourceTruncated) {
        String value = source == null ? "" : source;
        long original = value.getBytes(StandardCharsets.UTF_8).length;
        int end = Math.min(value.length(), 65_536);
        while (end > 0 && value.substring(0, end).getBytes(StandardCharsets.UTF_8).length > 65_536) end--;
        return new BoundedOutput(value.substring(0, end),
                sourceTruncated || end < value.length(), original);
    }

    private static void validate(SandboxSubmit request) {
        if (request == null || !"1.0".equals(request.contractVersion())
                || request.requestDigest() == null || !request.requestDigest().matches("[a-f0-9]{64}")
                || request.timeoutMillis() < 1000 || request.timeoutMillis() > 300_000) {
            throw EngineGatewayException.badRequest("SANDBOX_REQUEST_INVALID");
        }
        requireCallId(request.clientRequestId());
        try {
            SandboxCommandProfiles.requireAllowed(request.argv());
        } catch (RuntimeException denied) {
            throw EngineGatewayException.badRequest("SANDBOX_COMMAND_DENIED");
        }
    }

    private static void requireCallId(String value) {
        if (value == null || !value.matches("call\\.[A-Za-z0-9_-]{16,120}")) {
            throw EngineGatewayException.badRequest("SANDBOX_CALL_ID_INVALID");
        }
    }

    private void requireReplay(
            AgentEngineSandboxExecutionEntity value,
            EngineTaskAuthority authority, SandboxSubmit request,
            String semanticDigest) {
        if (!value.requestDigest().equals(request.requestDigest())
                || !value.semanticDigest().equals(semanticDigest)) {
            throw EngineGatewayException.conflict("SANDBOX_REQUEST_CONFLICT");
        }
        requireTaskAuthority(authority, value.taskId(),
                read(value.requestJson(), StoredRequest.class));
    }

    private static void requireTaskAuthority(
            EngineTaskAuthority authority, StoredRequest stored) {
        requireTaskAuthority(authority, authority.taskId(), stored);
    }

    private static void requireTaskAuthority(
            EngineTaskAuthority authority, String taskId, StoredRequest stored) {
        if (!authority.taskId().equals(taskId)
                || !authority.requestDigest().equals(stored.authorityRequestDigest())
                || authority.userId() != stored.userId()
                || authority.turnId() != stored.turnId()
                || authority.sessionId() != stored.sessionId()
                || authority.projectId() != stored.projectId()
                || !authority.projectVersion().equals(stored.projectVersion())) {
            throw EngineGatewayException.forbidden("TASK_AUTHORITY_CONFLICT");
        }
    }

    private static String state(SandboxExecutionStatus status) {
        if (status == null) throw EngineGatewayException.conflict("SANDBOX_STATUS_INVALID");
        return switch (status) {
            case ACCEPTED, CLAIMED, MATERIALIZING, CREATED, POLICY_APPLIED -> "QUEUED";
            case RUNNING, SUCCEEDED_PENDING_CLEANUP, FAILED_PENDING_CLEANUP,
                    CANCEL_REQUESTED, TIMED_OUT_PENDING_CLEANUP, CLEANING -> "RUNNING";
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            case TIMED_OUT -> "TIMED_OUT";
            case CLEANUP_FAILED -> "SYSTEM_ERROR";
        };
    }

    private static SandboxView view(AgentEngineSandboxExecutionEntity value) {
        return new SandboxView("1.0", value.clientRequestId(), value.requestDigest(),
                value.executionRef(), "CANCEL_REQUESTED".equals(value.state())
                        ? "RUNNING" : value.state(), value.receiptRef());
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("engine gateway serialization failed", failure); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception failure) { throw EngineGatewayException.conflict("SANDBOX_STORED_STATE_INVALID"); }
    }

    private static String semanticDigest(SandboxSubmit request, List<ReceiptInput> inputs) {
        StringBuilder value = new StringBuilder("agent-engine-sandbox-v1")
                .append('\0').append(request.timeoutMillis());
        request.argv().forEach(argument -> value.append('\0').append(argument));
        inputs.stream().sorted(Comparator.comparing(ReceiptInput::path)).forEach(input ->
                value.append('\0').append(input.path()).append('\0').append(input.sha256()));
        return sha256(value.toString());
    }

    private static long stableLong(String value) {
        byte[] digest = digest(value);
        long result = java.nio.ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
        return result == 0 ? 1 : result;
    }

    private static String sha256(String value) {
        return HexFormat.of().formatHex(digest(value));
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private record StoredRequest(
            String authorityRequestDigest, long userId, long turnId,
            long sessionId, long projectId, String projectVersion,
            SandboxSubmit submit,
            AgentEngineWorkspaceGateway.ResolvedInputs inputs) { }
}
