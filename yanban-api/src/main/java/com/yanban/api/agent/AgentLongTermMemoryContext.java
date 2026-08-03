package com.yanban.api.agent;

import java.util.List;
import org.springframework.util.StringUtils;

public record AgentLongTermMemoryContext(
        String content,
        int hitCount,
        int candidateCount,
        int omittedCount,
        String note,
        List<AgentMemorySelectionRef> selectedRefs
) {
    public AgentLongTermMemoryContext {
        selectedRefs = selectedRefs == null
                ? List.of() : List.copyOf(selectedRefs);
    }

    public AgentLongTermMemoryContext(
            String content,
            int hitCount,
            int candidateCount,
            int omittedCount,
            String note) {
        this(content, hitCount, candidateCount, omittedCount, note, List.of());
    }

    public static AgentLongTermMemoryContext empty() {
        return new AgentLongTermMemoryContext(null, 0, 0, 0,
                "No relevant long-term memory was injected.", List.of());
    }

    public boolean hasContent() {
        return StringUtils.hasText(content);
    }
}
