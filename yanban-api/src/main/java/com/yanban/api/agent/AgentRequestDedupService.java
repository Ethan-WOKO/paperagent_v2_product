package com.yanban.api.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentRequestDedupService {

    private static final Duration COMPLETED_TTL = Duration.ofMinutes(5);

    private final ConcurrentHashMap<RequestKey, CompletableFuture<SendMessageResponse>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RequestKey, CompletedResponse> completed = new ConcurrentHashMap<>();

    public SendMessageResponse execute(Long userId,
                                       Long sessionId,
                                       String clientRequestId,
                                       Supplier<SendMessageResponse> action) {
        if (!StringUtils.hasText(clientRequestId)) {
            return action.get();
        }

        RequestKey key = new RequestKey(userId, sessionId, clientRequestId.trim());
        CompletedResponse cached = completed.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.response();
        }
        if (cached != null) {
            completed.remove(key, cached);
        }

        CompletableFuture<SendMessageResponse> created = new CompletableFuture<>();
        CompletableFuture<SendMessageResponse> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            return await(existing);
        }

        try {
            SendMessageResponse response = action.get();
            completed.put(key, new CompletedResponse(response, System.currentTimeMillis() + COMPLETED_TTL.toMillis()));
            created.complete(response);
            return response;
        } catch (Throwable ex) {
            created.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(key, created);
        }
    }

    private SendMessageResponse await(CompletableFuture<SendMessageResponse> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw ex;
        }
    }

    private record RequestKey(Long userId, Long sessionId, String clientRequestId) {
        private RequestKey {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(clientRequestId, "clientRequestId");
        }
    }

    private record CompletedResponse(SendMessageResponse response, long expiresAtEpochMs) {
        private boolean isExpired() {
            return System.currentTimeMillis() > expiresAtEpochMs;
        }
    }
}
