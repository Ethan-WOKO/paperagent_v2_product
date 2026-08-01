package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRuntimePersistenceBoundaryTest {

    @Test
    void repairPersistsNoHistoryOrServerInstructionAndOnlyOneVisibleFinalAssistant() {
        ChatMessage oldAssistant = ChatMessage.assistant("old answer");
        ChatMessage oldTool = ChatMessage.tool("old-call", "old observation");
        ChatMessage repairInstruction = ChatMessage.system("server-owned repair instruction");
        ChatMessage toolRequest = new ChatMessage("assistant", "checking",
                List.of(new ToolCall("new-call", "function",
                        new ToolCall.FunctionCall("project_read_file",
                                "{\"projectId\":42,\"relativePath\":\"src/Main.java\"}"))), null);
        ChatMessage currentTool = ChatMessage.tool("new-call",
                "{\"projectId\":42,\"relativePath\":\"src/Main.java\",\"hash\":\"h1\"}");
        ChatMessage discardedIntermediate = ChatMessage.assistant("unverified intermediate answer");
        ChatMessage finalAssistant = ChatMessage.assistant("verified final answer");
        List<ChatMessage> transcript = List.of(oldAssistant, oldTool, repairInstruction,
                ChatMessage.user("same user task"), toolRequest, currentTool,
                discardedIntermediate, finalAssistant);

        List<ChatMessage> selected = AgentService.runtimeMessagesToPersist(transcript, 2);

        assertThat(selected).containsExactly(toolRequest, currentTool, finalAssistant);
        assertThat(selected).doesNotContain(oldAssistant, oldTool, repairInstruction, discardedIntermediate);
        assertThat(selected).filteredOn(message -> "assistant".equals(message.role())
                        && (message.toolCalls() == null || message.toolCalls().isEmpty()))
                .containsExactly(finalAssistant);
    }
}
