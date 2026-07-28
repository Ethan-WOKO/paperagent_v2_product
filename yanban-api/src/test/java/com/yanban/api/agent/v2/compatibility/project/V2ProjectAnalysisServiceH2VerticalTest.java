package com.yanban.api.agent.v2.compatibility.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.WorkspaceDiff;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.providers.FinishReason;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.UsageMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import({
        ProjectAnalysisDeliveryTransactions.class,
        V2ProjectAnalysisServiceH2VerticalTest.Config.class
})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class V2ProjectAnalysisServiceH2VerticalTest {
    @Autowired
    ProjectAnalysisDeliveryTransactions deliveries;
    @Autowired
    AgentSessionRepository sessions;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void readRequestCompletesOneMessageAndExactReplay() {
        AgentSession session = sessions.saveAndFlush(new AgentSession(
                7L, "project", "test", "test", 8, true,
                AgentSessionScope.PROJECT, 8L));
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 8L)).thenReturn(
                new ProjectManifestResponse(
                        8L, "version", List.of(new ProjectFileEntry(
                                "paper.md", 8L, Instant.EPOCH,
                                "a".repeat(64)))));
        var contexts = mock(com.yanban.api.agent.v2
                .AgentTurnProductContextResolver.class);
        when(contexts.resolve(any(), any())).thenAnswer(call ->
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity(
                                "AGENT_TURN",
                                "turn-" + call.getArgument(1, Long.class),
                                7L, session.getId(), 8L),
                        Optional.of("version")));
        PlanId planId = new PlanId("plan-project-vertical");
        var starts = mock(com.yanban.api.agent.v2.bootstrap
                .AuthenticatedAgentTurnFreshExecutionStartComposer.class);
        var started = mock(io.paperagent.v2.runtime.execution.start
                .FreshExecutionStarted.class);
        var persistedStart = mock(io.paperagent.v2.persistence
                .PersistedExecutionStart.class);
        when(persistedStart.planId()).thenReturn(planId);
        when(started.persistedStart()).thenReturn(persistedStart);
        when(starts.start(any(), any(), any())).thenReturn(started);

        ProjectVersionRef source =
                new ProjectVersionRef("8", "version");
        WorkspaceRef workspaceRef =
                new WorkspaceRef(new WorkspaceId("workspace"), source);
        var executionContexts = mock(com.yanban.api.agent.v2.workspace
                .AuthenticatedAgentTurnPlanExecutionContextComposer.class);
        var ready = mock(io.paperagent.v2.runtime.execution.context.composition
                .PlanExecutionContextReady.class);
        var persistedContext = mock(io.paperagent.v2.persistence
                .PersistedPlanExecutionContextConfirmed.class);
        var spec = mock(io.paperagent.v2.contracts
                .WorkspaceMaterializationSpec.class);
        var verified = mock(io.paperagent.v2.workspace
                .VerifiedWorkspaceMaterialization.class);
        when(ready.planId()).thenReturn(planId);
        when(ready.persistedContext()).thenReturn(persistedContext);
        when(ready.verifiedWorkspace()).thenReturn(verified);
        when(persistedContext.materializationSpec()).thenReturn(spec);
        when(verified.workspace()).thenReturn(workspaceRef);
        when(executionContexts.compose(any(), any(), any()))
                .thenReturn(ready);

        var loop = mock(com.yanban.api.agent.v2.loop
                .AuthenticatedPersistentPlanAgentLoopComposer.class);
        var loopOutcome = mock(com.yanban.api.agent.v2.loop
                .PersistentPlanAgentLoopOutcome.class);
        when(loopOutcome.state()).thenReturn(
                com.yanban.api.agent.v2.loop
                        .PersistentPlanAgentLoopState.PLAN_SUCCEEDED);
        when(loop.execute(any(), any(), any())).thenReturn(loopOutcome);

        ReceiptId receiptId = new ReceiptId("receipt-project-vertical");
        ToolCallId toolCallId = new ToolCallId("tool-project-vertical");
        PlanStepId stepId = new PlanStepId("project-read-01");
        TaskFrameId taskFrameId = new TaskFrameId("task-project-vertical");
        PlanRevisionId revisionId =
                new PlanRevisionId("revision-project-vertical");
        PlanStep step = new PlanStep(
                stepId, "read", "evidence", java.util.Set.of(),
                List.of("receipt"), new io.paperagent.v2.contracts
                        .BoundedExecutionHints(1, Duration.ofMinutes(1)));
        CompletionFact fact = new CompletionFact(
                stepId, "outcome", Instant.EPOCH, List.of(receiptId));
        PlanRevision revision = new PlanRevision(
                revisionId, taskFrameId, 1L, Optional.empty(),
                "initial", Instant.EPOCH, List.of(step),
                Map.of(stepId, fact));
        Plan plan = new Plan(planId, taskFrameId, List.of(revision));
        TaskFrame frame = mock(TaskFrame.class);
        when(frame.id()).thenReturn(taskFrameId);
        when(frame.sourceProjectVersion()).thenReturn(Optional.of(source));
        Checkpoint checkpoint = new Checkpoint(
                taskFrameId, planId, revisionId, 1L, 1L,
                PlanExecutionState.SUCCEEDED,
                Map.of(stepId, StepExecutionState.SUCCEEDED),
                List.of(receiptId), Instant.EPOCH);
        PersistedStepRecoverySucceeded terminal =
                new PersistedStepRecoverySucceeded(
                        frame, plan, new VersionedCheckpoint(4L, checkpoint),
                        Optional.of(persistedContext));
        var recovery = mock(io.paperagent.v2.persistence
                .StepRecoveryRepository.class);
        when(recovery.inspect(planId))
                .thenReturn(PersistenceResult.found(terminal));

        var workspace = mock(io.paperagent.v2.workspace.WorkspacePort.class);
        when(workspace.inspectMaterialization(spec)).thenReturn(verified);
        WorkspaceDiff diff = new WorkspaceDiff(
                new io.paperagent.v2.contracts.DiffId("diff"),
                workspaceRef, List.of(), Instant.EPOCH);
        when(workspace.diff(any(), any(), any())).thenReturn(diff);
        var workspaces = mock(com.yanban.api.agent.v2.workspace
                .AuthenticatedAgentTurnWorkspacePortFactory.class);
        when(workspaces.create(any(), any())).thenReturn(workspace);

        ExecutionReceipt receipt = new ExecutionReceipt(
                receiptId, toolCallId, ReceiptStatus.SUCCESS,
                Instant.EPOCH, Instant.EPOCH.plusMillis(1), Optional.of(0),
                Optional.empty(),
                OutputCapture.inline(
                        "{\"path\":\"paper.md\",\"content\":\"evidence\"}",
                        false),
                OutputCapture.empty(), List.of(), Optional.empty(), List.of());
        var receipts = mock(io.paperagent.v2.persistence
                .ReceiptRepository.class);
        when(receipts.find(receiptId))
                .thenReturn(PersistenceResult.found(receipt));
        var intents = mock(io.paperagent.v2.persistence
                .EffectIntentRepository.class);
        var intent = new io.paperagent.v2.contracts.EffectIntent(
                toolCallId, planId, stepId, "project.read",
                new io.paperagent.v2.contracts.ObjectValue(Map.of()));
        when(intents.find(toolCallId)).thenReturn(
                PersistenceResult.found(new PersistedEffectIntent(
                        intent, "owner", 1L, new EventId("activation"))));
        var provider = mock(io.paperagent.v2.providers.ModelProvider.class);
        when(provider.complete(any())).thenReturn(new ModelResponse(
                Optional.of("Evidence from paper.md."),
                List.of(), FinishReason.STOP,
                new UsageMetadata(1, 1, 0, Map.of()), Map.of()));

        AtomicReference<FinalSynthesis> stored = new AtomicReference<>();
        var syntheses = mock(io.paperagent.v2.persistence
                .FinalSynthesisRepository.class);
        when(syntheses.find(planId)).thenAnswer(ignored ->
                stored.get() == null
                        ? PersistenceResult.rejected(
                                io.paperagent.v2.persistence
                                        .PersistenceErrorCode.NOT_FOUND,
                                "planId")
                        : PersistenceResult.found(stored.get()));
        when(syntheses.append(any())).thenAnswer(call -> {
            FinalSynthesis value = call.getArgument(0);
            stored.compareAndSet(null, value);
            return PersistenceResult.applied(stored.get());
        });
        V2ProjectAnalysisService service = new V2ProjectAnalysisService(
                sessions, projects, deliveries, starts, executionContexts,
                loop, recovery,
                mock(io.paperagent.v2.persistence.LeaseRepository.class),
                workspaces, contexts, syntheses, receipts, intents,
                provider, new ObjectMapper());
        var request = new V2ProjectAnalysisRequest(
                "Analyze evidence", List.of("paper.md"),
                null, 10, "request-vertical");

        var first = service.execute(
                7L, 8L, session.getId(), request);
        when(projects.manifest(7L, 8L)).thenThrow(
                new IllegalStateException("Project changed after delivery"));
        var replay = service.execute(
                7L, 8L, session.getId(), request);
        assertThrows(IllegalArgumentException.class, () -> service.execute(
                7L, 8L, session.getId(),
                new V2ProjectAnalysisRequest(
                        "Changed objective", List.of("paper.md"),
                        null, 10, "request-vertical")));

        assertEquals("SUCCEEDED", first.status());
        assertEquals("Evidence from paper.md.", first.finalText());
        assertEquals(first.assistantMessageId(),
                replay.assistantMessageId());
        assertEquals(true, replay.replayed());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from agent_messages "
                        + "where session_id = ? and role = 'assistant'",
                Long.class, session.getId()));
    }

    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
