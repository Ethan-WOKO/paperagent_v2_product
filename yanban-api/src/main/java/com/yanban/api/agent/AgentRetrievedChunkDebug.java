package com.yanban.api.agent;

public record AgentRetrievedChunkDebug(
        String source,
        Long documentId,
        String filename,
        Integer chunkIndex,
        String citationId,
        Double score,
        String content,
        Integer versionNo,
        String versionStatus
) {
    public AgentRetrievedChunkDebug(
            String source,
            Long documentId,
            String filename,
            Integer chunkIndex,
            String citationId,
            Double score,
            String content) {
        this(source, documentId, filename, chunkIndex, citationId,
                score, content, null, null);
    }
}
