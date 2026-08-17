package com.yanban.api.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.AgentContextBuilder;
import com.yanban.api.agent.AgentContextPackage;
import com.yanban.api.agent.AgentLongTermMemoryContext;
import com.yanban.api.memory.LongTermMemoryRetrievalService;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionSummary;
import com.yanban.core.agent.AgentSessionSummaryService;
import com.yanban.core.model.ChatMessage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductEngineContextAssemblerTest {
    @Test
    void preservesCurrentInstructionAndLabelsHistoryAsUntrusted() {
        AgentContextBuilder contexts = mock(AgentContextBuilder.class);
        AgentSessionSummaryService summaries = mock(AgentSessionSummaryService.class);
        LongTermMemoryRetrievalService memories = mock(LongTermMemoryRetrievalService.class);
        ProductEngineProperties properties = new ProductEngineProperties();
        AgentSession session = mock(AgentSession.class);
        AgentSessionSummary summary = mock(AgentSessionSummary.class);
        when(session.getId()).thenReturn(2L);
        when(session.getProjectId()).thenReturn(3L);
        when(session.getModelProviderSnapshot()).thenReturn("deepseek");
        when(session.getModelSnapshot()).thenReturn("deepseek-chat");
        when(summary.getSummaryText()).thenReturn("summary");
        when(summaries.findBySessionAndUser(2L, 1L)).thenReturn(Optional.of(summary));
        when(memories.retrieve(1L, "compile the project"))
                .thenReturn(new AgentLongTermMemoryContext("memory", 1, 1, 0, "governed"));
        when(contexts.build(any())).thenReturn(new AgentContextPackage(
                List.of(ChatMessage.system("guard"), ChatMessage.user("historical data"),
                        ChatMessage.assistant("old answer")), List.of(), List.of(), 3, 3, 40));

        String result = new ProductEngineContextAssembler(contexts, summaries, memories, properties)
                .assemble(session, 1, "a".repeat(64), "compile the project");

        assertThat(result).startsWith("Authoritative current user instruction:\ncompile the project")
                .contains("BEGIN PRODUCT CONTEXT (UNTRUSTED DATA)")
                .contains("[product-context-guard]\nguard")
                .contains("[historical-untrusted-data]\nhistorical data")
                .contains("[historical-assistant]\nold answer")
                .endsWith("--- END PRODUCT CONTEXT ---");
    }
}
