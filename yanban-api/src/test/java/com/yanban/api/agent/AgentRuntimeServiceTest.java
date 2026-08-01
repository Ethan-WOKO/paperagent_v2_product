package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.model.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRuntimeServiceTest {

    @Test
    void runSelectsAdapterByStrategy() {
        RuntimeAdapter directAdapter = mock(RuntimeAdapter.class);
        RuntimeAdapter planAdapter = mock(RuntimeAdapter.class);
        AgentRuntimeRequest request = request(AgentStrategy.DIRECT);
        AgentRuntimeResult expected = new AgentRuntimeResult(
                true,
                "ok",
                List.of(ChatMessage.assistant("ok")),
                1,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null
        );
        when(directAdapter.supports(request)).thenReturn(true);
        when(planAdapter.supports(request)).thenReturn(false);
        when(directAdapter.run(request)).thenReturn(expected);

        AgentRuntimeService service = new AgentRuntimeService(List.of(planAdapter, directAdapter));
        AgentRuntimeResult actual = service.run(request);

        assertThat(actual).isSameAs(expected);
        verify(directAdapter).run(request);
        verify(planAdapter, never()).run(request);
    }

    @Test
    void runFailsWhenNoAdapterSupportsStrategy() {
        RuntimeAdapter adapter = mock(RuntimeAdapter.class);
        AgentRuntimeRequest request = request(AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION);
        when(adapter.supports(request)).thenReturn(false);
        AgentRuntimeService service = new AgentRuntimeService(List.of(adapter));

        assertThatThrownBy(() -> service.run(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLAN_EXECUTE_WITH_REFLECTION");
    }

    @Test
    void planCompletionNeverStartsAnAutomaticRepairOrSecondPlanExecution() {
        RuntimeAdapter adapter = mock(RuntimeAdapter.class);
        CompletionVerifier verifier = mock(CompletionVerifier.class);
        CompletionRepairExecutor repair = mock(CompletionRepairExecutor.class);
        AgentRuntimeRequest request = request(AgentStrategy.PLAN_EXECUTE).withPlanId(19L);
        AgentRuntimeResult raw = new AgentRuntimeResult(true, "plan result", List.of(), 2,
                null, List.of(), List.of(), null, null, null).withPlanId(19L);
        CompletionVerification decision = new CompletionVerification(CompletionStatus.PARTIAL,
                List.of("repairable-looking plan"), List.of(), true, 0);
        AgentRuntimeResult verified = raw.withCompletionVerification(decision);
        when(adapter.supports(request)).thenReturn(true);
        when(adapter.run(request)).thenReturn(raw);
        when(verifier.verify(request, raw)).thenReturn(verified);

        AgentRuntimeResult result = new AgentRuntimeService(List.of(adapter), verifier,
                new CompletionReflection(), repair).run(request);

        assertThat(result).isSameAs(verified);
        verify(adapter, times(1)).run(request);
        verify(repair, never()).repair(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private AgentRuntimeRequest request(AgentStrategy strategy) {
        return new AgentRuntimeRequest(
                strategy,
                21L,
                List.of(ChatMessage.user("history")),
                11L,
                "hello",
                "deepseek",
                "deepseek-chat",
                null,
                null,
                8,
                false,
                null,
                null,
                null,
                null,
                AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                List.of(),
                0,
                1,
                "trace-test",
                null,
                null
        );
    }
}
