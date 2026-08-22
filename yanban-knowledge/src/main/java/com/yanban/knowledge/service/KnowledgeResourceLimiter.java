package com.yanban.knowledge.service;

import com.yanban.knowledge.config.KnowledgeUploadProperties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeResourceLimiter {
    private final Semaphore uploads;
    private final Semaphore processing;
    private final Semaphore embeddings;
    private final int uploadsPerUser;
    private final int processingPerUser;
    private final int embeddingsPerUser;
    private final ConcurrentHashMap<Long, Semaphore> userUploads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Semaphore> userProcessing = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Semaphore> userEmbeddings = new ConcurrentHashMap<>();

    public KnowledgeResourceLimiter(KnowledgeUploadProperties properties) {
        uploads = new Semaphore(properties.getMaxConcurrentUploads(), true);
        processing = new Semaphore(properties.getMaxConcurrentProcessing(), true);
        embeddings = new Semaphore(properties.getMaxConcurrentEmbeddingBatches(), true);
        uploadsPerUser = properties.getMaxConcurrentUploadsPerUser();
        processingPerUser = properties.getMaxConcurrentProcessingPerUser();
        embeddingsPerUser = properties.getMaxConcurrentEmbeddingBatchesPerUser();
    }

    public Permit upload(long userId) {
        return acquireWaiting(uploads, userUploads, uploadsPerUser, userId,
                "KNOWLEDGE_UPLOAD_INTERRUPTED");
    }
    public Permit processing(long userId) {
        return acquireWaiting(processing, userProcessing, processingPerUser, userId,
                "KNOWLEDGE_PROCESSING_INTERRUPTED");
    }
    public Permit embedding(long userId) {
        return acquireWaiting(embeddings, userEmbeddings, embeddingsPerUser, userId,
                "KNOWLEDGE_EMBEDDING_INTERRUPTED");
    }

    private Permit acquireWaiting(Semaphore global, ConcurrentHashMap<Long, Semaphore> users,
                                  int perUser, long userId, String interruptedCode) {
        Semaphore user = users.computeIfAbsent(userId, ignored -> new Semaphore(perUser, true));
        boolean userAcquired = false;
        try {
            user.acquire();
            userAcquired = true;
            global.acquire();
            return new Permit(global, user);
        } catch (InterruptedException ex) {
            if (userAcquired) user.release();
            Thread.currentThread().interrupt();
            throw new KnowledgeCapacityException(interruptedCode + ": interrupted while waiting for capacity");
        }
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore global;
        private final Semaphore user;
        private boolean closed;
        Permit(Semaphore global, Semaphore user) { this.global = global; this.user = user; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            user.release();
            global.release();
        }
    }
}
