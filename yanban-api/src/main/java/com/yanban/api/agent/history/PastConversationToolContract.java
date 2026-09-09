package com.yanban.api.agent.history;

import com.yanban.core.tool.ToolDescriptor;
import java.util.List;
import java.util.Set;

public final class PastConversationToolContract {
    public static final String SEARCH = "search_past_conversations";
    public static final String GET = "get_past_conversation";
    public static final String VERSION = "past-conversation-v1";
    public static final Set<String> TOOL_NAMES = Set.of(SEARCH, GET);

    private PastConversationToolContract() { }

    public static ToolDescriptor descriptor(String name) {
        if (!TOOL_NAMES.contains(name)) throw new IllegalArgumentException("Unknown history tool");
        return new ToolDescriptor(name, VERSION, "conversation-history",
                List.of(ToolDescriptor.CapabilityProfile.CHAT, ToolDescriptor.CapabilityProfile.PROJECT),
                List.of("history:read"), List.of(ToolDescriptor.ResourceScope.SESSION),
                ToolDescriptor.SideEffectType.NONE, ToolDescriptor.ConfirmationPolicy.NEVER,
                ToolDescriptor.AsyncMode.SYNC, ToolDescriptor.IdempotencyPolicy.NONE,
                ToolDescriptor.RepeatPolicy.ALLOW_LIMITED, true);
    }
}
