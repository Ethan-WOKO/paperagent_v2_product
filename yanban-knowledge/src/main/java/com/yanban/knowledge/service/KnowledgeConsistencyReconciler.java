package com.yanban.knowledge.service;

import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.domain.KbProcessingOutboxRepository;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class KnowledgeConsistencyReconciler {
    private static final List<String> ACTIVE_OUTBOX = List.of("PENDING", "RETRY", "DISPATCHING");
    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final KbProcessingOutboxRepository outbox;
    private final MinioClient minio;
    private final KnowledgeStorageProperties storage;
    private final KnowledgeIndexService index;
    private final KnowledgeOutboxService events;

    public KnowledgeConsistencyReconciler(KbDocumentRepository documents, KbChunkRepository chunks,
                                          KbProcessingOutboxRepository outbox, MinioClient minio,
                                          KnowledgeStorageProperties storage, KnowledgeIndexService index,
                                          KnowledgeOutboxService events) {
        this.documents = documents; this.chunks = chunks; this.outbox = outbox; this.minio = minio;
        this.storage = storage; this.index = index; this.events = events;
    }

    @Scheduled(initialDelayString = "${yanban.knowledge.upload.reconciliation-delay-millis:60000}",
            fixedDelayString = "${yanban.knowledge.upload.reconciliation-delay-millis:60000}")
    @Transactional
    public void reconcile() {
        List<KbDocument> values = documents.findByStatusIn(
                List.of("PROCESSING", "RETRYING", "READY"), PageRequest.of(0, 100));
        for (KbDocument document : values) reconcile(document);
    }

    private void reconcile(KbDocument document) {
        if (document.getObjectKey() == null) return;
        try {
            minio.statObject(StatObjectArgs.builder().bucket(storage.getBucket())
                    .object(document.getObjectKey()).build());
        } catch (Exception missing) {
            document.setStatus("FAILED");
            document.setErrorMessage("MinIO object missing during reconciliation");
            documents.save(document);
            KnowledgeMetrics.reconciliation("minio_missing");
            return;
        }
        if (!"READY".equals(document.getStatus())) {
            if (!outbox.existsByDocumentIdAndStatusIn(document.getId(), ACTIVE_OUTBOX)) {
                events.enqueue(document);
                documents.save(document);
                KnowledgeMetrics.reconciliation("requeued");
            }
            return;
        }
        try {
            int mysqlChunks = chunks.countByDocumentId(document.getId());
            long elasticChunks = index.countByDocumentId(document.getId());
            if (elasticChunks >= 0 && mysqlChunks != elasticChunks) {
                document.setStatus("PROCESSING");
                document.setErrorMessage("MySQL/Elasticsearch chunk count mismatch");
                events.enqueue(document);
                documents.save(document);
                KnowledgeMetrics.reconciliation("index_mismatch");
            } else {
                KnowledgeMetrics.reconciliation("consistent");
            }
        } catch (RuntimeException unavailable) {
            KnowledgeMetrics.reconciliation("check_failed");
        }
    }
}
