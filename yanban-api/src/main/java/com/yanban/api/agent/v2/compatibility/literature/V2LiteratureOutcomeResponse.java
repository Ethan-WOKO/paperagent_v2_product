package com.yanban.api.agent.v2.compatibility.literature;

import java.util.List;

public record V2LiteratureOutcomeResponse(
        Long sessionId,
        Long turnId,
        String clientRequestId,
        Long literatureTaskId,
        String status,
        String stage,
        boolean terminal,
        boolean cancellable,
        int requestedTopK,
        boolean includeBibtex,
        Long resultMessageId,
        int resultCount,
        int totalCount,
        List<String> sourceFailures,
        List<V2LiteraturePaperItem> items) {
    public V2LiteratureOutcomeResponse {
        sourceFailures = sourceFailures == null
                ? List.of() : List.copyOf(sourceFailures);
        items = items == null ? List.of() : List.copyOf(items);
    }
}
