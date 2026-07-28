package com.yanban.api.agent.v2.compatibility.literature;

public record LiteratureSearchRequestAuthority(
        String query,
        int topK,
        Integer yearFrom,
        boolean includeBibtex) {
}
