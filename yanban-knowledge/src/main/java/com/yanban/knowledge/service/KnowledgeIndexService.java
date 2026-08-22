package com.yanban.knowledge.service;

import java.util.List;

public interface KnowledgeIndexService {
    String indexChunk(IndexedChunkDocument chunkDocument);

    default List<String> indexChunks(List<IndexedChunkDocument> chunkDocuments) {
        return chunkDocuments.stream().map(this::indexChunk).toList();
    }

    void deleteByDocumentId(Long documentId);

    default long countByDocumentId(Long documentId) { return -1L; }
}
