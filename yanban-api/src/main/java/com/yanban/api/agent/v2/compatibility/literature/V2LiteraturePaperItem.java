package com.yanban.api.agent.v2.compatibility.literature;

import java.util.List;

public record V2LiteraturePaperItem(
        Long cardId,
        String title,
        List<String> authors,
        Integer year,
        String venue,
        String doi,
        String arxivId,
        String openAlexId,
        String url,
        String source,
        Double score,
        String bibtex) {
    public V2LiteraturePaperItem {
        authors = authors == null ? List.of() : List.copyOf(authors);
    }
}
