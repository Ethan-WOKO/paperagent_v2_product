package com.yanban.api.agent.v2.context.assembly;

import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import java.util.List;

public record V2ContextAssemblyResult(
        List<V2ContextSectionDraft> sections,
        List<Long> recentTurnIds,
        List<Long> evictedTurnIds
) {
    public V2ContextAssemblyResult {
        sections = sections == null ? List.of() : List.copyOf(sections);
        recentTurnIds = recentTurnIds == null
                ? List.of() : List.copyOf(recentTurnIds);
        evictedTurnIds = evictedTurnIds == null
                ? List.of() : List.copyOf(evictedTurnIds);
    }
}
