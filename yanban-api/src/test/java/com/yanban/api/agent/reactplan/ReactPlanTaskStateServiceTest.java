package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.reactplan.gateway.AgentEngineTaskGrantService;
import com.yanban.api.agent.reactplan.gateway.AgentEngineObservationReader;
import com.yanban.api.agent.reactplan.gateway.EngineTaskGrant;
import com.yanban.api.agent.reactplan.gateway.EngineModelRouteCandidate;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.core.agent.AgentRunIdentity;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.InOrder;

class ReactPlanTaskStateServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ReactPlanTaskCheckpointRepository checkpoints = mock(ReactPlanTaskCheckpointRepository.class);
    private final ReactPlanTaskEventRepository events = mock(ReactPlanTaskEventRepository.class);
    private final ReactPlanTurnIntakeRepository intakes = mock(ReactPlanTurnIntakeRepository.class);
    private final AgentTurnProductContextResolver contexts = mock(AgentTurnProductContextResolver.class);
    private final AgentEngineTaskGrantService grants = mock(AgentEngineTaskGrantService.class);
    private final AgentEngineObservationReader observations = mock(AgentEngineObservationReader.class);
    private final ReactPlanUsageSettlementRepository usageSettlements =
            mock(ReactPlanUsageSettlementRepository.class);
    private final ReactPlanTaskSchedulerService scheduler = mock(ReactPlanTaskSchedulerService.class);
    private final org.springframework.context.ApplicationEventPublisher applicationEvents =
            mock(org.springframework.context.ApplicationEventPublisher.class);
    private final ReactPlanTaskStateService service = new ReactPlanTaskStateService(
            json, checkpoints, events, intakes, contexts, grants, observations,
            usageSettlements,
            scheduler, applicationEvents);
    private final String taskId = ReactPlanRuntimeService.taskId(7L, 42L);

    @BeforeEach
    void authority() {
        ReactPlanTurnIntakeEntity intake = new ReactPlanTurnIntakeEntity(
                7L, 11L, "client.request", "a".repeat(64), 42L, 99L,
                taskId, LocalDateTime.parse("2026-08-18T00:00:00"));
        org.springframework.test.util.ReflectionTestUtils.setField(intake, "id", 1L);
        when(intakes.findByTaskId(taskId)).thenReturn(Optional.of(intake));
        when(contexts.resolve(7L, 42L)).thenReturn(new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "42", 7L, 11L, 88L),
                Optional.of("b".repeat(64))));
        when(checkpoints.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(events.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void supportsTheClassBasedProxyRequiredByTransactionalMethods() {
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);

        assertThat(AopUtils.isCglibProxy(factory.getProxy())).isTrue();
    }

    @Test
    void createsBoundedCredentialFreeCheckpointAndOrderedEvent() {
        ObjectNode checkpoint = checkpoint("running", 0);
        String digest = checkpoint.path("view").path("requestDigest").asText();
        when(checkpoints.findLockedByTaskId(taskId)).thenReturn(Optional.empty());

        assertThat(service.save(taskId, digest, null, checkpoint, null)).isEqualTo(1);

        InOrder admissionLocks = inOrder(scheduler, checkpoints);
        admissionLocks.verify(scheduler).lockScheduler();
        admissionLocks.verify(checkpoints).findLockedByTaskId(taskId);

        ReactPlanTaskCheckpointEntity persisted = new ReactPlanTaskCheckpointEntity(
                taskId, digest, 7L, 11L, 42L, "running", 0,
                checkpoint.toString(), LocalDateTime.now());
        when(checkpoints.findLockedByTaskId(taskId)).thenReturn(Optional.of(persisted));
        when(events.findByTaskIdAndSequenceNumber(taskId, 1L)).thenReturn(Optional.empty());
        when(events.findTopByTaskIdOrderBySequenceNumberDesc(taskId)).thenReturn(Optional.empty());
        ObjectNode event = json.createObjectNode();
        event.put("contractVersion", "1.0");
        event.put("taskId", taskId);
        event.put("sequence", 1);
        event.put("occurredAt", "2026-08-18T00:00:00Z");
        event.put("type", "status");
        event.put("state", "running");
        event.putNull("error");

        service.appendEvent(event, null);

        verify(events).saveAndFlush(any(ReactPlanTaskEventEntity.class));
    }

    @Test
    void rejectsSecretFieldsAndStaleCheckpointRevision() {
        ObjectNode checkpoint = checkpoint("running", 0);
        String digest = checkpoint.path("view").path("requestDigest").asText();
        checkpoint.put("taskGrant", "must-not-be-persisted");
        assertThatThrownBy(() -> service.save(taskId, digest, null, checkpoint, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CHECKPOINT_SECRET_FIELD_FORBIDDEN");

        ObjectNode clean = checkpoint("running", 0);
        ReactPlanTaskCheckpointEntity persisted = new ReactPlanTaskCheckpointEntity(
                taskId, digest, 7L, 11L, 42L, "running", 0,
                clean.toString(), LocalDateTime.now());
        when(checkpoints.findLockedByTaskId(taskId)).thenReturn(Optional.of(persisted));
        assertThatThrownBy(() -> service.save(taskId, digest, 2L, clean, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CHECKPOINT_REVISION_CONFLICT");
    }

    @Test
    void reauthorizesOnlyTheExactPersistedRecoverableTask() {
        ObjectNode checkpoint = checkpoint("running", 3);
        String digest = checkpoint.path("view").path("requestDigest").asText();
        ReactPlanTaskCheckpointEntity persisted = new ReactPlanTaskCheckpointEntity(
                taskId, digest, 7L, 11L, 42L, "running", 3,
                checkpoint.toString(), LocalDateTime.now());
        when(checkpoints.findById(taskId)).thenReturn(Optional.of(persisted));
        List<EngineModelRouteCandidate> fallbacks = List.of(
                new EngineModelRouteCandidate("backup", "backup-model"));
        when(grants.issue(taskId, digest, 7L, 42L, "test", "test-model", fallbacks)).thenReturn(
                new EngineTaskGrant("g".repeat(40), Instant.parse("2026-08-18T00:05:00Z")));

        EngineTaskGrant recovered = service.authorizeRecovery(taskId, digest);

        assertThat(recovered.value()).hasSize(40);
        verify(grants).issue(taskId, digest, 7L, 42L, "test", "test-model", fallbacks);
        assertThatThrownBy(() -> service.authorizeRecovery(taskId, "f".repeat(64)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("TASK_DIGEST_CONFLICT");
    }

    @Test
    void settlesAllSuccessfulModelCallsOnceWhenTaskBecomesTerminal() {
        ObjectNode running = checkpoint("running", 0);
        String digest = running.path("view").path("requestDigest").asText();
        ReactPlanTaskCheckpointEntity persisted = new ReactPlanTaskCheckpointEntity(
                taskId, digest, 7L, 11L, 42L, "running", 0,
                running.toString(), LocalDateTime.now());
        ObjectNode succeeded = checkpoint("succeeded", 0);
        when(checkpoints.findLockedByTaskId(taskId)).thenReturn(Optional.of(persisted));
        when(events.findTopByTaskIdOrderBySequenceNumberDesc(taskId)).thenReturn(Optional.empty());
        when(observations.models(taskId)).thenReturn(List.of(
                new AgentEngineObservationReader.ModelFact("model.1", "test", "model", "SUCCEEDED",
                        "2026-08-18T00:00:00Z", "2026-08-18T00:00:01Z", 10, 20, 12, 3, 0, null),
                new AgentEngineObservationReader.ModelFact("model.2", "test", "model", "SUCCEEDED",
                        "2026-08-18T00:00:01Z", "2026-08-18T00:00:02Z", 10, 20, 7, 2, 0, null),
                new AgentEngineObservationReader.ModelFact("model.3", "test", "model", "FAILED",
                        "2026-08-18T00:00:02Z", "2026-08-18T00:00:03Z", 10, 0, 99, 99, 0, "FAILED")));

        assertThat(service.save(taskId, digest, 1L, succeeded, null)).isEqualTo(2L);
        assertThat(service.save(taskId, digest, 2L, succeeded, null)).isEqualTo(3L);

        verify(usageSettlements, times(1)).save(
                any(ReactPlanUsageSettlementEntity.class));
        verify(applicationEvents, times(1)).publishEvent(
                any(ReactPlanUsageSettlementRequested.class));
        verify(applicationEvents, times(1)).publishEvent(
                any(ReactPlanConversationSummaryRequested.class));
        assertThat(persisted.usageSettled()).isTrue();
        assertThat(persisted.settledPromptTokens()).isEqualTo(19L);
        assertThat(persisted.settledCompletionTokens()).isEqualTo(5L);
    }

    private ObjectNode checkpoint(String state, long sequence) {
        ObjectNode authority = json.createObjectNode();
        authority.put("runMode", "PERSISTENT_PLAN_EXECUTE");
        authority.put("sessionRef", "session.11");
        authority.putObject("project").put("projectId", "88").put("projectVersion", "b".repeat(64));
        authority.put("instruction", "inspect Sort.java");
        authority.putObject("permissions")
                .put("readProject", true).put("writeWorkspace", true).put("executeSandbox", true);
        ObjectNode model = authority.putObject("model");
        model.put("provider", "test").put("model", "test-model");
        model.putArray("fallbacks").addObject()
                .put("provider", "backup").put("model", "backup-model");
        String digest = ReactPlanCanonicalJson.digest(json, authority);
        ObjectNode checkpoint = json.createObjectNode();
        checkpoint.set("authority", authority);
        checkpoint.putObject("view")
                .put("contractVersion", "1.0").put("taskId", taskId)
                .put("requestDigest", digest).put("state", state)
                .put("lastSequence", sequence);
        return checkpoint;
    }
}
