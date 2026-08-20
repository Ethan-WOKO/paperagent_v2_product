package com.yanban.knowledge.service;

public record KnowledgeSearchIndexHit(
        Long documentId,
        Integer chunkIndex,
        String chunkText,
        double retrievalScore
) {
}
