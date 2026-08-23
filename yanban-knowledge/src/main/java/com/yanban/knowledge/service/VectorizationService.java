package com.yanban.knowledge.service;

import com.yanban.knowledge.config.KnowledgeElasticsearchProperties;
import com.yanban.knowledge.domain.KbChunk;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import java.util.List;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import com.yanban.knowledge.config.KnowledgeUploadProperties;

@Service
public class VectorizationService {

    private final EmbeddingClient embeddingClient;
    private final KnowledgeIndexService knowledgeIndexService;
    private final KnowledgeElasticsearchProperties elasticsearchProperties;
    private final KbChunkRepository chunks;
    private final KnowledgeUploadProperties uploadProperties;
    private final KnowledgeResourceLimiter resourceLimiter;

    public VectorizationService(EmbeddingClient embeddingClient,
                                KnowledgeIndexService knowledgeIndexService,
                                KnowledgeElasticsearchProperties elasticsearchProperties,
                                KbChunkRepository chunks,
                                KnowledgeUploadProperties uploadProperties,
                                KnowledgeResourceLimiter resourceLimiter) {
        this.embeddingClient = embeddingClient;
        this.knowledgeIndexService = knowledgeIndexService;
        this.elasticsearchProperties = elasticsearchProperties;
        this.chunks = chunks;
        this.uploadProperties = uploadProperties;
        this.resourceLimiter = resourceLimiter;
    }

    public void vectorizeDocument(KbDocument document, List<KbChunk> documentChunks) {
        int batchSize = uploadProperties.getEmbeddingBatchSize();
        for (int start = 0; start < documentChunks.size(); start += batchSize) {
            List<KbChunk> batch = documentChunks.subList(start, Math.min(documentChunks.size(), start + batchSize));
            java.time.Instant startedAt = java.time.Instant.now();
            try (KnowledgeResourceLimiter.Permit ignored = resourceLimiter.embedding(document.getUserId())) {
                List<List<Double>> vectors = embeddingClient.embedAll(
                        batch.stream().map(KbChunk::getChunkText).toList());
                if (vectors.size() != batch.size()) throw new IllegalStateException("Embedding 批量响应数量不一致");
                vectors.forEach(this::validateDimensions);
                List<IndexedChunkDocument> indexed = new ArrayList<>();
                for (int i = 0; i < batch.size(); i++) indexed.add(indexed(document, batch.get(i), vectors.get(i)));
                List<String> ids = knowledgeIndexService.indexChunks(indexed);
                if (ids.size() != batch.size()) throw new IllegalStateException("Elasticsearch Bulk 响应数量不一致");
                for (int i = 0; i < batch.size(); i++) batch.get(i).setEsDocId(ids.get(i));
                chunks.saveAll(batch);
                KnowledgeMetrics.embeddingBatch("succeeded", batch.size(),
                        java.time.Duration.between(startedAt, java.time.Instant.now()));
            } catch (RuntimeException failure) {
                KnowledgeMetrics.embeddingBatch("failed", batch.size(),
                        java.time.Duration.between(startedAt, java.time.Instant.now()));
                throw failure;
            }
        }
    }

    public void deleteDocumentIndex(Long documentId) {
        knowledgeIndexService.deleteByDocumentId(documentId);
    }

    private IndexedChunkDocument indexed(KbDocument document, KbChunk chunk, List<Double> vector) {
        return new IndexedChunkDocument(chunk.getId(), document.getId(), document.getUserId(),
                document.getProjectId(), Boolean.TRUE.equals(document.getIsPublic()), document.getSourceType(),
                document.getVersionStatus(), document.getLineageId(), document.getVersionNo(),
                document.getCanonicalKey(), chunk.getChunkIndex(), chunk.getChunkText(), vector);
    }

    private void validateDimensions(List<Double> vector) {
        if (vector == null || vector.size() != elasticsearchProperties.getVectorDimensions()) {
            throw new IllegalStateException("Embedding 维度与 Elasticsearch 配置不一致");
        }
    }
}
