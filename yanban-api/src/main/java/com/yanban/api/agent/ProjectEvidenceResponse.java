package com.yanban.api.agent;

/** Read-only, server-revalidated projection of a persisted Plan Project observation. */
public record ProjectEvidenceResponse(
        String id,
        String relativePath,
        String hash,
        String version,
        String chunk,
        boolean trusted,
        boolean current
) {
}
