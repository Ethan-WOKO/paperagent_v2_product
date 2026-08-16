package com.yanban.api.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.engine.AgentEngineGatewayDtos.ReceiptInput;
import com.yanban.api.agent.engine.AgentEngineGatewayDtos.SandboxInput;
import com.yanban.api.agent.engine.AgentEngineGatewayDtos.SandboxSubmit;
import com.yanban.api.agent.sandbox.SandboxBrokerClient;
import com.yanban.api.agent.sandbox.SandboxExecutionProperties;
import com.yanban.sandbox.contract.SandboxCanonicalDigest;
import com.yanban.sandbox.contract.SandboxDispatch;
import com.yanban.sandbox.contract.SandboxDispatchResponse;
import com.yanban.sandbox.contract.SandboxExecutionStatus;
import com.yanban.sandbox.contract.SandboxExecutionView;
import com.yanban.sandbox.contract.SandboxReceipt;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class AgentEngineSandboxGatewayTest {
    private static final String TASK = "task." + "1".repeat(64);
    private static final String AUTHORITY_DIGEST = "2".repeat(64);
    private static final String VERSION = "3".repeat(64);
    private static final String FILE_HASH = "4".repeat(64);
    private static final String CALL = "call.abcdefghijklmnop";

    @Test
    void computesBrokerDigestAndExactReplayDoesNotDuplicateDispatch() {
        AgentEngineWorkspaceGateway workspaces = mock(AgentEngineWorkspaceGateway.class);
        AgentEngineSandboxExecutionTransactions transactions =
                mock(AgentEngineSandboxExecutionTransactions.class);
        SandboxBrokerClient broker = mock(SandboxBrokerClient.class);
        AtomicReference<AgentEngineSandboxExecutionEntity> stored = new AtomicReference<>();
        when(workspaces.resolveInputs(any(), any())).thenReturn(inputs());
        when(transactions.create(any())).thenAnswer(invocation -> {
            AgentEngineSandboxExecutionEntity value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });
        when(transactions.dispatched(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    AgentEngineSandboxExecutionEntity value = stored.get();
                    value.dispatched(invocation.getArgument(2), invocation.getArgument(3),
                            java.time.LocalDateTime.now());
                    return value;
                });
        when(broker.dispatch(any())).thenAnswer(invocation -> {
            SandboxDispatch request = invocation.getArgument(0);
            return new SandboxDispatchResponse("broker-1", request.idempotencyKey(),
                    request.requestDigest(), request.fence(), SandboxExecutionStatus.ACCEPTED);
        });
        AgentEngineSandboxGateway gateway = gateway(workspaces, transactions, broker);
        SandboxSubmit request = request("5".repeat(64), List.of("yanban-runner", "java", "Sort.java"));

        var first = gateway.submit(authority(), request);
        assertThat(first.state()).isEqualTo("QUEUED");
        ArgumentCaptor<SandboxDispatch> dispatch = ArgumentCaptor.forClass(SandboxDispatch.class);
        verify(broker).dispatch(dispatch.capture());
        assertThat(dispatch.getValue().requestDigest())
                .isEqualTo(SandboxCanonicalDigest.compute(dispatch.getValue().withoutDigest()));

        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(transactions).create(any());
        when(transactions.find(TASK, CALL)).thenReturn(Optional.of(stored.get()));
        var replay = gateway.submit(authority(), request);
        assertThat(replay.executionRef()).isEqualTo(first.executionRef());
        verify(broker).dispatch(any());

        SandboxSubmit conflicting = request("5".repeat(64),
                List.of("yanban-runner", "java", "Other.java"));
        assertThatThrownBy(() -> gateway.submit(authority(), conflicting))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("SANDBOX_REQUEST_CONFLICT"));
    }

    @Test
    void rejectsForbiddenCommandBeforeBrokerDispatch() {
        AgentEngineWorkspaceGateway workspaces = mock(AgentEngineWorkspaceGateway.class);
        AgentEngineSandboxExecutionTransactions transactions = mock(AgentEngineSandboxExecutionTransactions.class);
        SandboxBrokerClient broker = mock(SandboxBrokerClient.class);
        AgentEngineSandboxGateway gateway = gateway(workspaces, transactions, broker);

        assertThatThrownBy(() -> gateway.submit(authority(),
                request("5".repeat(64), List.of("sh", "-c", "cat /etc/passwd"))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo("SANDBOX_COMMAND_DENIED"));
        verify(broker, never()).dispatch(any());
    }

    @Test
    void rejectsBrokerSubmissionWithDifferentIdempotencyIdentity() {
        AgentEngineWorkspaceGateway workspaces = mock(AgentEngineWorkspaceGateway.class);
        AgentEngineSandboxExecutionTransactions transactions =
                mock(AgentEngineSandboxExecutionTransactions.class);
        SandboxBrokerClient broker = mock(SandboxBrokerClient.class);
        when(workspaces.resolveInputs(any(), any())).thenReturn(inputs());
        when(transactions.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(broker.dispatch(any())).thenAnswer(invocation -> {
            SandboxDispatch request = invocation.getArgument(0);
            return new SandboxDispatchResponse("broker-1", "wrong-idempotency-key",
                    request.requestDigest(), request.fence(), SandboxExecutionStatus.ACCEPTED);
        });
        AgentEngineSandboxGateway gateway = gateway(workspaces, transactions, broker);

        assertThatThrownBy(() -> gateway.submit(authority(),
                request("5".repeat(64), List.of("yanban-runner", "java", "Sort.java"))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("SANDBOX_BROKER_IDENTITY_CONFLICT"));
    }

    @Test
    void rejectsBrokerStatusWithDifferentIdempotencyIdentity() {
        AgentEngineWorkspaceGateway workspaces = mock(AgentEngineWorkspaceGateway.class);
        AgentEngineSandboxExecutionTransactions transactions =
                mock(AgentEngineSandboxExecutionTransactions.class);
        SandboxBrokerClient broker = mock(SandboxBrokerClient.class);
        AtomicReference<AgentEngineSandboxExecutionEntity> stored = new AtomicReference<>();
        AtomicReference<SandboxDispatch> dispatched = new AtomicReference<>();
        when(workspaces.resolveInputs(any(), any())).thenReturn(inputs());
        when(transactions.create(any())).thenAnswer(invocation -> {
            AgentEngineSandboxExecutionEntity value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });
        when(transactions.dispatched(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    AgentEngineSandboxExecutionEntity value = stored.get();
                    value.dispatched(invocation.getArgument(2), invocation.getArgument(3),
                            java.time.LocalDateTime.now());
                    return value;
                });
        when(broker.dispatch(any())).thenAnswer(invocation -> {
            SandboxDispatch request = invocation.getArgument(0);
            dispatched.set(request);
            return new SandboxDispatchResponse("broker-1", request.idempotencyKey(),
                    request.requestDigest(), request.fence(), SandboxExecutionStatus.ACCEPTED);
        });
        AgentEngineSandboxGateway gateway = gateway(workspaces, transactions, broker);
        gateway.submit(authority(),
                request("5".repeat(64), List.of("yanban-runner", "java", "Sort.java")));
        when(transactions.find(TASK, CALL)).thenReturn(Optional.of(stored.get()));
        when(broker.status("broker-1")).thenAnswer(ignored -> {
            SandboxDispatch request = dispatched.get();
            return new SandboxExecutionView("broker-1", "wrong-idempotency-key",
                    request.requestDigest(), request.fence(), SandboxExecutionStatus.RUNNING,
                    null, null);
        });

        assertThatThrownBy(() -> gateway.status(authority(), CALL))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("SANDBOX_BROKER_IDENTITY_CONFLICT"));
    }

    @Test
    void persistsBoundedFormalReceiptFromTerminalBrokerProjection() {
        AgentEngineWorkspaceGateway workspaces = mock(AgentEngineWorkspaceGateway.class);
        AgentEngineSandboxExecutionTransactions transactions =
                mock(AgentEngineSandboxExecutionTransactions.class);
        SandboxBrokerClient broker = mock(SandboxBrokerClient.class);
        AtomicReference<AgentEngineSandboxExecutionEntity> stored = new AtomicReference<>();
        AtomicReference<SandboxDispatch> dispatched = new AtomicReference<>();
        when(workspaces.resolveInputs(any(), any())).thenReturn(inputs());
        when(transactions.create(any())).thenAnswer(invocation -> {
            AgentEngineSandboxExecutionEntity value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });
        when(transactions.dispatched(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    AgentEngineSandboxExecutionEntity value = stored.get();
                    value.dispatched(invocation.getArgument(2), invocation.getArgument(3),
                            java.time.LocalDateTime.now());
                    return value;
                });
        when(transactions.terminal(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    AgentEngineSandboxExecutionEntity value = stored.get();
                    value.terminal(invocation.getArgument(2), invocation.getArgument(3),
                            invocation.getArgument(4), java.time.LocalDateTime.now());
                    return value;
                });
        when(broker.dispatch(any())).thenAnswer(invocation -> {
            SandboxDispatch request = invocation.getArgument(0);
            dispatched.set(request);
            return new SandboxDispatchResponse("broker-1", request.idempotencyKey(),
                    request.requestDigest(), request.fence(), SandboxExecutionStatus.ACCEPTED);
        });
        AgentEngineSandboxGateway gateway = gateway(workspaces, transactions, broker);
        gateway.submit(authority(), request("5".repeat(64),
                List.of("yanban-runner", "java", "Sort.java",
                        "--dependency=org.apache.commons:commons-lang3:3.14.0")));
        when(transactions.find(TASK, CALL)).thenReturn(Optional.of(stored.get()));
        SandboxDispatch dispatch = dispatched.get();
        Instant started = Instant.parse("2026-08-16T10:00:00Z");
        SandboxReceipt source = new SandboxReceipt(
                "broker-1", dispatch.idempotencyKey(), dispatch.requestDigest(),
                dispatch.userId(), dispatch.projectId(), dispatch.sessionId(),
                dispatch.planId(), dispatch.stepId(), dispatch.fence(),
                dispatch.projectVersion(), dispatch.policyDigest(), "e2b",
                SandboxExecutionStatus.SUCCEEDED, 0, "x".repeat(70_000), "", false,
                Map.of(), started, started.plusSeconds(2), null);
        when(broker.status("broker-1")).thenReturn(new SandboxExecutionView(
                "broker-1", dispatch.idempotencyKey(), dispatch.requestDigest(),
                dispatch.fence(), SandboxExecutionStatus.SUCCEEDED, source, null));

        var terminal = gateway.status(authority(), CALL);
        assertThat(terminal.state()).isEqualTo("SUCCEEDED");
        assertThat(terminal.receiptRef()).startsWith("receipt.");
        when(transactions.findReceipt(TASK, terminal.receiptRef()))
                .thenReturn(Optional.of(stored.get()));
        var receipt = gateway.receipt(authority(), terminal.receiptRef());
        assertThat(receipt.status()).isEqualTo("SUCCEEDED");
        assertThat(receipt.exitCode()).isZero();
        assertThat(receipt.stdout().text()).hasSize(65_536);
        assertThat(receipt.stdout().truncated()).isTrue();
        assertThat(receipt.stdout().originalBytes()).isEqualTo(70_000);
        assertThat(receipt.inputs()).singleElement().satisfies(input -> {
            assertThat(input.path()).isEqualTo("Sort.java");
            assertThat(input.sha256()).isEqualTo(FILE_HASH);
        });
    }

    private static AgentEngineSandboxGateway gateway(
            AgentEngineWorkspaceGateway workspaces,
            AgentEngineSandboxExecutionTransactions transactions,
            SandboxBrokerClient broker) {
        return new AgentEngineSandboxGateway(workspaces, transactions, broker,
                new SandboxExecutionProperties(),
                new ObjectMapper().findAndRegisterModules());
    }

    private static AgentEngineWorkspaceGateway.ResolvedInputs inputs() {
        return new AgentEngineWorkspaceGateway.ResolvedInputs(
                Map.of("Sort.java", "class Sort {}"),
                List.of(new ReceiptInput("Sort.java", FILE_HASH, 13)),
                "6".repeat(64));
    }

    private static SandboxSubmit request(String digest, List<String> argv) {
        return new SandboxSubmit("1.0", CALL, digest, argv,
                List.of(new SandboxInput("Sort.java", FILE_HASH)), 30_000);
    }

    private static EngineTaskAuthority authority() {
        return new EngineTaskAuthority(TASK, AUTHORITY_DIGEST,
                11, 12, 13, 14, VERSION, true, true,
                Instant.parse("2026-08-16T11:00:00Z"));
    }
}
