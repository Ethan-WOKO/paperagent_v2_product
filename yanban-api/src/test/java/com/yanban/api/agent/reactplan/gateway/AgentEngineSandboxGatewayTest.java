package com.yanban.api.agent.reactplan.gateway;

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
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.ReceiptInput;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.SandboxInput;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.SandboxCancelRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.SandboxSubmit;
import com.yanban.api.agent.sandbox.SandboxBrokerClient;
import com.yanban.api.agent.sandbox.SandboxExecutionProperties;
import com.yanban.sandbox.contract.SandboxCanonicalDigest;
import com.yanban.sandbox.contract.SandboxDispatch;
import com.yanban.sandbox.contract.SandboxDispatchResponse;
import com.yanban.sandbox.contract.SandboxExecutionStatus;
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
        when(workspaces.resolveInputs(any(), any(), any())).thenReturn(inputs());
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
    void rejectsIncompleteMavenContextBeforeBrokerDispatch() {
        AgentEngineWorkspaceGateway workspaces = mock(AgentEngineWorkspaceGateway.class);
        AgentEngineSandboxExecutionTransactions transactions =
                mock(AgentEngineSandboxExecutionTransactions.class);
        SandboxBrokerClient broker = mock(SandboxBrokerClient.class);
        when(workspaces.resolveInputs(any(), any(), any()))
                .thenThrow(EngineGatewayException.badRequest(
                        "MAVEN_ROOT_POM_REQUIRED"));
        AgentEngineSandboxGateway gateway = gateway(workspaces, transactions, broker);

        assertThatThrownBy(() -> gateway.submit(authority(),
                request("5".repeat(64), List.of("mvn", "-o", "test"))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MAVEN_ROOT_POM_REQUIRED"));
        verify(broker, never()).dispatch(any());
        verify(transactions, never()).create(any());
    }

    @Test
    void cancelsOnlyTheStoredTaskBoundBrokerExecutionWithServerFence() {
        AgentEngineWorkspaceGateway workspaces = mock(AgentEngineWorkspaceGateway.class);
        AgentEngineSandboxExecutionTransactions transactions =
                mock(AgentEngineSandboxExecutionTransactions.class);
        SandboxBrokerClient broker = mock(SandboxBrokerClient.class);
        AgentEngineSandboxExecutionEntity stored = new AgentEngineSandboxExecutionEntity(
                TASK, CALL, "5".repeat(64), "6".repeat(64),
                "execution.local", new ObjectMapper().findAndRegisterModules()
                        .valueToTree(new StoredRequestFixture()).toString(),
                java.time.LocalDateTime.now());
        stored.dispatched("broker-exact", "RUNNING", java.time.LocalDateTime.now());
        when(transactions.find(TASK, CALL)).thenReturn(Optional.of(stored));
        when(transactions.requestCancel(TASK, CALL)).thenAnswer(invocation -> {
            stored.requestCancel(java.time.LocalDateTime.now());
            return stored;
        });
        AgentEngineSandboxGateway gateway = gateway(workspaces, transactions, broker);

        var result = gateway.cancel(authority(), CALL, new SandboxCancelRequest("1.0"));

        assertThat(result.state()).isEqualTo("RUNNING");
        verify(broker).cancel("broker-exact", 1L);
    }

    @Test
    void submitCancelRaceCancelsImmediatelyAfterBrokerIdentityIsBound() {
        AgentEngineWorkspaceGateway workspaces = mock(AgentEngineWorkspaceGateway.class);
        AgentEngineSandboxExecutionTransactions transactions =
                mock(AgentEngineSandboxExecutionTransactions.class);
        SandboxBrokerClient broker = mock(SandboxBrokerClient.class);
        AtomicReference<AgentEngineSandboxExecutionEntity> stored = new AtomicReference<>();
        when(workspaces.resolveInputs(any(), any(), any())).thenReturn(inputs());
        when(transactions.create(any())).thenAnswer(invocation -> {
            AgentEngineSandboxExecutionEntity value = invocation.getArgument(0);
            value.requestCancel(java.time.LocalDateTime.now());
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
            return new SandboxDispatchResponse("broker-race", request.idempotencyKey(),
                    request.requestDigest(), request.fence(), SandboxExecutionStatus.ACCEPTED);
        });
        AgentEngineSandboxGateway gateway = gateway(workspaces, transactions, broker);

        var result = gateway.submit(authority(),
                request("5".repeat(64), List.of("yanban-runner", "java", "Sort.java")));

        assertThat(result.state()).isEqualTo("RUNNING");
        verify(broker).cancel("broker-race", 1L);
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

    private record StoredRequestFixture(
            String authorityRequestDigest, long userId, long turnId,
            long sessionId, long projectId, String projectVersion,
            SandboxSubmit submit,
            AgentEngineWorkspaceGateway.ResolvedInputs inputs) {
        private StoredRequestFixture() {
            this(AUTHORITY_DIGEST, 11, 12, 13, 14, VERSION,
                    request("5".repeat(64), List.of("yanban-runner", "java", "Sort.java")),
                    AgentEngineSandboxGatewayTest.inputs());
        }
    }
}
