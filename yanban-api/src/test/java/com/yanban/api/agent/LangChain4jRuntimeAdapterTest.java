package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.core.model.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class LangChain4jRuntimeAdapterTest {

    @Test
    void delegatesEveryDirectRequestToLangChainToolStrategy() {
        LangChain4jToolCallingStrategy strategy = mock(LangChain4jToolCallingStrategy.class);
        LangChain4jRuntimeAdapter adapter = new LangChain4jRuntimeAdapter(strategy);
        AgentRuntimeRequest request = request();
        AgentRuntimeResult expected = new AgentRuntimeResult(
                true,
                "ok",
                List.of(ChatMessage.assistant("ok")),
                1,
                null,
                List.of("step=1 tool=search_web"),
                List.of(),
                12,
                34,
                46
        );
        when(strategy.run(request)).thenReturn(expected);

        AgentRuntimeResult actual = adapter.run(request);

        assertThat(actual).isSameAs(expected);
        assertThat(adapter.supports(request)).isTrue();
        verify(strategy).run(request);
    }

    @Test
    void projectsOnlyStrictPolicyBoundRuntimeToolFacts() {
        LangChain4jToolCallingStrategy strategy = mock(LangChain4jToolCallingStrategy.class);
        LangChain4jRuntimeAdapter adapter = new LangChain4jRuntimeAdapter(strategy);
        AgentRuntimeRequest request = request();
        AgentRuntimeResult raw = new AgentRuntimeResult(true, "ok", List.of(ChatMessage.assistant("ok")), 1,
                null, List.of(
                "step=1 tool=search_web executed=true budgetConsumed=true success=true reused=false skipped=false args={q:' success=false '}",
                "step=2 tool=forbidden executed=true budgetConsumed=true success=true reused=false skipped=false args={}"),
                List.of(), null, null, null);
        when(strategy.run(request)).thenReturn(raw);

        AgentRuntimeResult result = adapter.run(request);

        assertThat(result.domainRuntimeFacts().toolOutcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.toolName()).isEqualTo("search_web");
            assertThat(outcome.success()).isTrue();
            assertThat(outcome.budgetConsumed()).isTrue();
        });
    }

    private AgentRuntimeRequest request() {
        return new AgentRuntimeRequest(
                AgentStrategy.DIRECT,
                9L,
                List.of(ChatMessage.user("history")),
                3L,
                "hello",
                "deepseek",
                "deepseek-v4-flash",
                null,
                null,
                4,
                false,
                null,
                null,
                null,
                null,
                AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                List.of("search_web"),
                2,
                1,
                "trace-lc4j",
                null,
                null
        );
    }
}
