package com.yanban.api.agent.v2.context;

public enum ContextSectionType {
    CORE_AUTHORITY(10),
    RECENT_CONVERSATION(20),
    CONVERSATION_SUMMARY(10),
    TOOL_RESULTS(20),
    STEP_STATE(15),
    LONG_TERM_MEMORY(10),
    RAG_EVIDENCE(5),
    OUTPUT_RESERVE(5),
    SAFETY_MARGIN(5);

    private final int percentage;

    ContextSectionType(int percentage) {
        this.percentage = percentage;
    }

    public int percentage() {
        return percentage;
    }
}
