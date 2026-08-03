package com.yanban.api.agent.v2.context.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.adaptive.ReflectionModelCallGuard;
import com.yanban.api.agent.v2.adaptive.ReflectionModelCallGuardException;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.Utf8ByteTokenCounter;
import com.yanban.api.agent.v2.context.V2ContextRevisionDraft;
import com.yanban.api.agent.v2.context.V2ContextRevisionOutcome;
import com.yanban.api.agent.v2.context.V2ContextRevisionService;
import com.yanban.api.agent.v2.context.V2ContextRevisionSnapshot;
import com.yanban.api.agent.v2.context.V2ContextRevisionStatus;
import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import com.yanban.api.agent.v2.context.V2ContextSectionStatus;
import com.yanban.api.agent.v2.context.V2ContextStage;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.providers.CorrelationId;
import io.paperagent.v2.providers.GenerationOptions;
import io.paperagent.v2.providers.MessageRole;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelRequestId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class V2ReflectionModelCallContextGuardTest {
    @Test
    void readyRevisionMatchesReturnedRequestAndExactReplay() {
        ObjectMapper json = new ObjectMapper();
        AgentTurnProductContextResolver contexts =
                mock(AgentTurnProductContextResolver.class);
        when(contexts.resolve(1L, 3L)).thenReturn(
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity("turn", "3", 1L, 2L, null),
                        Optional.empty()));
        V2ContextRevisionService revisions = mock(V2ContextRevisionService.class);
        V2ContextRevisionOrchestrator orchestrator =
                mock(V2ContextRevisionOrchestrator.class);
        V2ContextRevisionSnapshot parent = snapshot(10L, baseline());
        when(revisions.find(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(revisions.findLatest(1L, 2L, 3L)).thenReturn(Optional.of(parent));
        when(revisions.findById(1L, 2L, 3L, 10L))
                .thenReturn(Optional.of(parent));
        when(orchestrator.prepare(any())).thenReturn(
                new V2ContextBoundaryPrepared(parent, List.of(parent)));
        V2ReflectionModelCallContextGuard guard =
                new V2ReflectionModelCallContextGuard(
                        contexts, revisions, orchestrator,
                        new V2ContextStageKeyFactory(), json);
        ModelRequest source = request();

        ModelRequest returned = guard.requireReady(
                new ReflectionModelCallGuard.Call(
                        1L, 3L, "audit-cut", source));

        ArgumentCaptor<V2ContextBoundaryRequest> captured =
                ArgumentCaptor.forClass(V2ContextBoundaryRequest.class);
        verify(orchestrator).prepare(captured.capture());
        V2ContextBoundaryRequest boundary = captured.getValue();
        V2ContextRevisionDraft readyDraft = new V2ContextRevisionDraft(
                boundary.userId(), boundary.sessionId(), boundary.turnId(),
                boundary.phaseRevisions().get(
                        boundary.phaseRevisions().size() - 1).revisionNumber(),
                boundary.parentSnapshotId(), boundary.parentDigest(),
                boundary.stage(), "reflection-ready",
                V2ContextRevisionStatus.READY, boundary.modelProvider(),
                boundary.model(), boundary.contextWindowTokens(),
                boundary.maxOutputTokens(), boundary.tokenCounterVersion(),
                boundary.profileVersion(), boundary.sections().stream()
                        .mapToLong(V2ContextSectionDraft::tokensAfter).sum(),
                boundary.outputReserveTokens(), boundary.sections());
        when(revisions.find(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(Optional.of(snapshot(11L, readyDraft)));
        ModelRequest replayed = guard.requireReady(
                new ReflectionModelCallGuard.Call(
                        1L, 3L, "audit-cut", source));

        verify(orchestrator, times(1)).prepare(any());
        assertThat(replayed).isEqualTo(returned);
        assertThat(boundary.stage()).isEqualTo(V2ContextStage.REFLECTION);
        assertThat(boundary.subCall()).isEqualTo("audit-cut");
        assertThat(boundary.sections()).hasSize(9);
        String digest = com.yanban.api.agent.v2.loop
                .V2StepModelCallMaterial.requestDigest(returned);
        assertThat(boundary.sections().stream()
                .filter(value -> value.type() == ContextSectionType.STEP_STATE)
                .findFirst().orElseThrow().sourceRefsJson())
                .contains(digest, "audit-cut", "step-1");
        assertThat(boundary.sections().stream()
                .filter(value -> value.type() != ContextSectionType.STEP_STATE)
                .toList()).containsExactlyElementsOf(parent.revision().sections()
                        .stream().filter(value -> value.type()
                                != ContextSectionType.STEP_STATE).toList());
    }

    @Test
    void infrastructureFailureIsWrappedAndProviderBoundaryCannotProceed() {
        AgentTurnProductContextResolver contexts =
                mock(AgentTurnProductContextResolver.class);
        when(contexts.resolve(1L, 3L)).thenThrow(
                new IllegalStateException("db unavailable"));
        V2ReflectionModelCallContextGuard guard =
                new V2ReflectionModelCallContextGuard(
                        contexts, mock(V2ContextRevisionService.class),
                        mock(V2ContextRevisionOrchestrator.class),
                        new V2ContextStageKeyFactory(), new ObjectMapper());

        assertThatThrownBy(() -> guard.requireReady(
                new ReflectionModelCallGuard.Call(
                        1L, 3L, "main", request())))
                .isInstanceOf(ReflectionModelCallGuardException.class)
                .extracting("code")
                .isEqualTo("REFLECTION_CONTEXT_INFRASTRUCTURE_FAILED");
    }

    private static ModelRequest request() {
        return new ModelRequest(
                new ModelRequestId("reflection-request"),
                new CorrelationId("reflection-correlation"),
                List.of(new ModelMessage(MessageRole.SYSTEM, "system"),
                        new ModelMessage(MessageRole.USER, "safe facts")),
                List.of(), new GenerationOptions(
                        256, 0, 0.1, OptionalLong.empty(), Map.of()),
                Optional.of(new TaskFrameId("frame-1")),
                Optional.of(new PlanId("plan-1")),
                Optional.of(new PlanRevisionId("revision-1")),
                Optional.of(new PlanStepId("step-1")), false);
    }

    private static V2ContextRevisionDraft baseline() {
        List<V2ContextSectionDraft> sections = new ArrayList<>();
        for (ContextSectionType type : ContextSectionType.values()) {
            sections.add(new V2ContextSectionDraft(
                    type, type.percentage(), 100_000, 0, 0,
                    V2ContextSectionStatus.READY, "[]", "{}", null));
        }
        return new V2ContextRevisionDraft(
                1L, 2L, 3L, 1, null, null, V2ContextStage.PLANNER,
                "ctx-v1/planner/parent/initial",
                V2ContextRevisionStatus.READY, "deepseek",
                "deepseek-v4-flash", 1_000_000, 384_000,
                Utf8ByteTokenCounter.VERSION, "layered-v1",
                0, 50_000, sections);
    }

    private static V2ContextRevisionSnapshot snapshot(
            Long id, V2ContextRevisionDraft draft) {
        return new V2ContextRevisionSnapshot(
                id, V2ContextRevisionOutcome.APPLIED, draft, "{}",
                "0".repeat(64), List.of());
    }
}
