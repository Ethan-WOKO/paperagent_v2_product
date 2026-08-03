package com.yanban.api.agent.v2.context.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.adaptive.FinalSynthesisModelCallGuard;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.V2ContextRevisionDraft;
import com.yanban.api.agent.v2.context.V2ContextRevisionOutcome;
import com.yanban.api.agent.v2.context.V2ContextRevisionService;
import com.yanban.api.agent.v2.context.V2ContextRevisionSnapshot;
import com.yanban.api.agent.v2.context.V2ContextRevisionStatus;
import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import com.yanban.api.agent.v2.context.V2ContextSectionStatus;
import com.yanban.api.agent.v2.context.V2ContextStage;
import com.yanban.core.agent.AgentRunIdentity;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V2FinalSynthesisModelCallContextGuardTest {
    @Test
    void secondCallReplaysTheExactReadyRequestAndChecksDirectParent() {
        ObjectMapper json = new ObjectMapper();
        AgentTurnProductContextResolver contexts =
                mock(AgentTurnProductContextResolver.class);
        when(contexts.resolve(1L, 3L)).thenReturn(
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity("turn", "3", 1L, 2L, null),
                        Optional.empty()));
        V2ContextRevisionService revisions =
                mock(V2ContextRevisionService.class);
        V2ContextRevisionOrchestrator orchestrator =
                mock(V2ContextRevisionOrchestrator.class);
        List<V2ContextSectionDraft> baseSections = sections();
        V2ContextRevisionSnapshot base = snapshot(1L, "a".repeat(64),
                draft(1, null, null, V2ContextStage.PLANNER,
                        "base", V2ContextRevisionStatus.READY,
                        baseSections));
        when(revisions.findLatest(1L, 2L, 3L)).thenReturn(Optional.of(base));
        AtomicReference<V2ContextRevisionSnapshot> replay =
                new AtomicReference<>();
        AtomicReference<V2ContextRevisionSnapshot> direct =
                new AtomicReference<>();
        when(revisions.find(any(), any(), any(), any())).thenAnswer(
                ignored -> Optional.ofNullable(replay.get()));
        when(orchestrator.prepare(any())).thenAnswer(invocation -> {
            V2ContextBoundaryRequest request = invocation.getArgument(0);
            V2ContextRevisionSnapshot assembling = snapshot(
                    2L, "b".repeat(64),
                    draft(2, 1L, "a".repeat(64),
                            V2ContextStage.FINAL_SYNTHESIS,
                            "assembling", V2ContextRevisionStatus.ASSEMBLING,
                            request.sections()));
            V2ContextRevisionSnapshot ready = snapshot(
                    3L, "c".repeat(64),
                    draft(3, 2L, "b".repeat(64),
                            V2ContextStage.FINAL_SYNTHESIS,
                            "ready", V2ContextRevisionStatus.READY,
                            request.sections()));
            direct.set(assembling);
            replay.set(ready);
            return new V2ContextBoundaryPrepared(ready,
                    List.of(assembling, ready));
        });
        when(revisions.findById(1L, 2L, 3L, 2L)).thenAnswer(
                ignored -> Optional.ofNullable(direct.get()));
        var guard = new V2FinalSynthesisModelCallContextGuard(
                contexts, revisions, orchestrator,
                new V2ContextStageKeyFactory(), json);
        ModelRequest request = request();

        ModelRequest first = guard.requireReady(
                new FinalSynthesisModelCallGuard.Call(1L, 3L, request));
        ModelRequest second = guard.requireReady(
                new FinalSynthesisModelCallGuard.Call(1L, 3L, request));

        assertThat(second).isEqualTo(first);
        verify(orchestrator, times(1)).prepare(any());
    }

    private static List<V2ContextSectionDraft> sections() {
        List<V2ContextSectionDraft> values = new ArrayList<>();
        for (ContextSectionType type : ContextSectionType.values()) {
            values.add(new V2ContextSectionDraft(
                    type, type.percentage(), 100_000, 2, 2,
                    V2ContextSectionStatus.READY, "[]", "{}", null));
        }
        return List.copyOf(values);
    }

    private static V2ContextRevisionDraft draft(
            int number, Long parentId, String parentDigest,
            V2ContextStage stage, String key,
            V2ContextRevisionStatus status,
            List<V2ContextSectionDraft> sections) {
        return new V2ContextRevisionDraft(
                1L, 2L, 3L, number, parentId, parentDigest, stage,
                "ctx-v1/final/" + key, status, "deepseek",
                "deepseek-v4-flash", 1_000_000, 384_000,
                "utf8-byte-v1", "layered-v1", 18, 50_000, sections);
    }

    private static V2ContextRevisionSnapshot snapshot(
            Long id, String digest, V2ContextRevisionDraft draft) {
        return new V2ContextRevisionSnapshot(
                id, V2ContextRevisionOutcome.APPLIED, draft,
                draft.toString(), digest, List.of());
    }

    private static ModelRequest request() {
        return new ModelRequest(
                new ModelRequestId("final-request"),
                new CorrelationId("final-correlation"),
                List.of(new ModelMessage(MessageRole.SYSTEM, "system"),
                        new ModelMessage(MessageRole.USER, "safe facts")),
                List.of(), new GenerationOptions(
                        256, 0, 0.1, OptionalLong.empty(), Map.of()),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), false);
    }
}
