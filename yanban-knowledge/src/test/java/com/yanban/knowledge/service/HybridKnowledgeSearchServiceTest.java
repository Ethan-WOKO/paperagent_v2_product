package com.yanban.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.domain.KbChunkRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;

class HybridKnowledgeSearchServiceTest {

    @Test
    void searchUsesHybridIndexResultsWhenAvailable() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        SimpleKnowledgeSearchService fallback = new SimpleKnowledgeSearchService(chunks, documents);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(embeddingClient, indexClient, documents, fallback);

        when(embeddingClient.embed("alpha")).thenReturn(List.of(0.1d, 0.2d));
        KnowledgeSearchOptions alphaOptions = KnowledgeSearchOptions.activeOnly(1001L, 3);
        when(indexClient.searchLexical("alpha", alphaOptions, 12)).thenReturn(List.of(
                new KnowledgeSearchIndexHit(1L, 0, "alpha content", 4.2d)
        ));
        when(indexClient.searchVector(List.of(0.1d, 0.2d), alphaOptions, 12)).thenReturn(List.of(
                new KnowledgeSearchIndexHit(1L, 0, "alpha content", 1.5d)
        ));
        when(documents.findById(1L)).thenReturn(java.util.Optional.of(new KbDocument(1001L, "paper.md", "READY", false)));

        List<KnowledgeSearchResult> results = service.search("alpha", alphaOptions);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).filename()).isEqualTo("paper.md");
        assertThat(results.get(0).score()).isEqualTo(2.2d);
        assertThat(results.get(0).rerankScore()).isNotNull();
        assertThat(results.get(0).rerankReason()).contains("exact_phrase");
    }

    @Test
    void searchFallsBackToDatabaseWhenHybridFails() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        SimpleKnowledgeSearchService fallback = new SimpleKnowledgeSearchService(chunks, documents);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(embeddingClient, indexClient, documents, fallback);

        when(embeddingClient.embed("beta")).thenThrow(new IllegalStateException("embedding down"));
        com.yanban.knowledge.domain.KbChunk chunk = new com.yanban.knowledge.domain.KbChunk(1L, 0, "beta keyword");
        when(chunks.searchAccessibleVersionedChunks("beta", 2002L, null, false, PageRequest.of(0, 8))).thenReturn(List.of(chunk));
        when(documents.findById(1L)).thenReturn(java.util.Optional.of(new KbDocument(2002L, "notes.md", "READY", false)));

        List<KnowledgeSearchResult> results = service.search("beta", 2002L, 2);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).filename()).isEqualTo("notes.md");
    }

    @Test
    void searchUsesLookupVariantWhenNaturalQueryWrapsKey() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        SimpleKnowledgeSearchService fallback = new SimpleKnowledgeSearchService(chunks, documents);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(embeddingClient, indexClient, documents, fallback);

        String query = "find the exact answer for mentor_lookup_deepseek-20260701.";
        List<Double> vector = List.of(0.1d, 0.2d);
        KnowledgeSearchOptions options = KnowledgeSearchOptions.activeOnly(1001L, 3);
        when(embeddingClient.embed(query)).thenReturn(vector);
        when(indexClient.searchLexical(query.substring(0, query.length() - 1), options, 12)).thenReturn(List.of());
        when(indexClient.searchLexical("mentor_lookup_deepseek", options, 12)).thenReturn(List.of(
                new KnowledgeSearchIndexHit(1L, 0, "mentor_lookup_deepseek-20260701 key: Zhang Mingyuan.", 1.1d)
        ));
        when(indexClient.searchVector(vector, options, 12)).thenReturn(List.of());
        when(documents.findById(1L)).thenReturn(java.util.Optional.of(new KbDocument(1001L, "lab-notes.md", "READY", false)));

        List<KnowledgeSearchResult> results = service.search(query, options);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chunkText()).contains("Zhang Mingyuan");
        assertThat(results.get(0).rerankReason()).contains("mentor_lookup_deepseek");
    }

    @Test
    void fallbackSearchUsesLookupVariantWhenEmbeddingFails() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        SimpleKnowledgeSearchService fallback = new SimpleKnowledgeSearchService(chunks, documents);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(embeddingClient, indexClient, documents, fallback);

        String query = "find the exact answer for beta_lookup_deepseek-20260701.";
        when(embeddingClient.embed(query)).thenThrow(new IllegalStateException("embedding down"));
        com.yanban.knowledge.domain.KbChunk chunk = new com.yanban.knowledge.domain.KbChunk(1L, 0, "beta_lookup_deepseek-20260701 key: beta keyword");
        when(chunks.searchAccessibleVersionedChunks("beta_lookup_deepseek", 2002L, null, false, PageRequest.of(0, 8))).thenReturn(List.of(chunk));
        when(documents.findById(1L)).thenReturn(java.util.Optional.of(new KbDocument(2002L, "notes.md", "READY", false)));

        List<KnowledgeSearchResult> results = service.search(query, 2002L, 2);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).filename()).isEqualTo("notes.md");
        assertThat(results.get(0).rerankScore()).isNotNull();
    }

    @Test
    void fallbackSearchUsesChineseVariantsWhenEmbeddingFails() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        SimpleKnowledgeSearchService fallback = new SimpleKnowledgeSearchService(chunks, documents);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(embeddingClient, indexClient, documents, fallback);

        String query = "项目名称";
        when(embeddingClient.embed(query)).thenThrow(new IllegalStateException("embedding down"));
        com.yanban.knowledge.domain.KbChunk chunk = new com.yanban.knowledge.domain.KbChunk(5L, 0, "项目正式名称是青岚知识助手");
        when(chunks.searchAccessibleVersionedChunks("项目名称", 2002L, null, false, PageRequest.of(0, 8))).thenReturn(List.of());
        when(chunks.searchAccessibleVersionedChunks("项目", 2002L, null, false, PageRequest.of(0, 8))).thenReturn(List.of(chunk));
        when(chunks.searchAccessibleVersionedChunks("目名", 2002L, null, false, PageRequest.of(0, 8))).thenReturn(List.of());
        when(chunks.searchAccessibleVersionedChunks("名称", 2002L, null, false, PageRequest.of(0, 8))).thenReturn(List.of(chunk));
        when(documents.findById(5L)).thenReturn(java.util.Optional.of(new KbDocument(2002L, "notes.md", "READY", false)));

        List<KnowledgeSearchResult> results = service.search(query, 2002L, 2);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chunkText()).contains("青岚知识助手");
        assertThat(results.get(0).rerankReason()).contains("项目");
    }

    @Test
    void hybridHydrationFiltersDeletedDocumentsAndFallsBack() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        SimpleKnowledgeSearchService fallback = new SimpleKnowledgeSearchService(chunks, documents);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(embeddingClient, indexClient, documents, fallback);

        when(embeddingClient.embed("gamma")).thenReturn(List.of(0.1d, 0.2d));
        KnowledgeSearchOptions options = KnowledgeSearchOptions.activeOnly(3003L, 2);
        when(indexClient.searchLexical("gamma", options, 8)).thenReturn(List.of(
                new KnowledgeSearchIndexHit(1L, 0, "gamma deleted", 3.0d)
        ));
        when(indexClient.searchVector(List.of(0.1d, 0.2d), options, 8)).thenReturn(List.of(
                new KnowledgeSearchIndexHit(1L, 0, "gamma deleted", 2.0d)
        ));
        KbDocument deleted = new KbDocument(3003L, "deleted.md", "READY", false);
        deleted.setVersionStatus("DELETED");
        when(documents.findById(1L)).thenReturn(java.util.Optional.of(deleted));

        com.yanban.knowledge.domain.KbChunk activeChunk = new com.yanban.knowledge.domain.KbChunk(2L, 0, "gamma active");
        when(chunks.searchAccessibleVersionedChunks("gamma", 3003L, null, false, PageRequest.of(0, 8))).thenReturn(List.of(activeChunk));
        when(documents.findById(2L)).thenReturn(java.util.Optional.of(new KbDocument(3003L, "active.md", "READY", false)));

        List<KnowledgeSearchResult> results = service.search("gamma", options);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).filename()).isEqualTo("active.md");
        assertThat(results.get(0).versionStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void commonHitFromBothRoutesRanksAheadOfSingleRouteHits() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(
                embeddingClient, indexClient, documents, new SimpleKnowledgeSearchService(chunks, documents));
        KnowledgeSearchOptions options = KnowledgeSearchOptions.activeOnly(7L, 3);
        List<Double> vector = List.of(0.2d, 0.8d);

        when(embeddingClient.embed("hybrid")).thenReturn(vector);
        when(indexClient.searchLexical("hybrid", options, 12)).thenReturn(List.of(
                new KnowledgeSearchIndexHit(1L, 0, "lexical only", 8.0d),
                new KnowledgeSearchIndexHit(2L, 0, "shared hybrid", 7.0d)
        ));
        when(indexClient.searchVector(vector, options, 12)).thenReturn(List.of(
                new KnowledgeSearchIndexHit(2L, 0, "shared hybrid", 0.95d),
                new KnowledgeSearchIndexHit(3L, 0, "vector only", 0.90d)
        ));
        when(documents.findById(1L)).thenReturn(java.util.Optional.of(document(1L, 7L, "lexical.md")));
        when(documents.findById(2L)).thenReturn(java.util.Optional.of(document(2L, 7L, "shared.md")));
        when(documents.findById(3L)).thenReturn(java.util.Optional.of(document(3L, 7L, "vector.md")));

        List<KnowledgeSearchResult> results = service.search("hybrid", options);

        assertThat(results.get(0).filename()).isEqualTo("shared.md");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void lexicalRouteStillWorksWhenEmbeddingIsUnavailable() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(
                embeddingClient, indexClient, documents, new SimpleKnowledgeSearchService(chunks, documents));
        KnowledgeSearchOptions options = KnowledgeSearchOptions.activeOnly(8L, 2);

        when(embeddingClient.embed("bm25 only")).thenThrow(new IllegalStateException("embedding down"));
        when(indexClient.searchLexical("bm25 only", options, 8)).thenReturn(List.of(
                new KnowledgeSearchIndexHit(4L, 0, "bm25 only answer", 5.0d)
        ));
        when(documents.findById(4L)).thenReturn(java.util.Optional.of(new KbDocument(8L, "bm25.md", "READY", false)));

        assertThat(service.search("bm25 only", options))
                .extracting(KnowledgeSearchResult::filename)
                .containsExactly("bm25.md");
    }

    @Test
    void vectorRouteStillWorksWhenLexicalSearchIsUnavailable() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeSearchIndexClient indexClient = Mockito.mock(KnowledgeSearchIndexClient.class);
        KbDocumentRepository documents = Mockito.mock(KbDocumentRepository.class);
        KbChunkRepository chunks = Mockito.mock(KbChunkRepository.class);
        HybridKnowledgeSearchService service = new HybridKnowledgeSearchService(
                embeddingClient, indexClient, documents, new SimpleKnowledgeSearchService(chunks, documents));
        KnowledgeSearchOptions options = KnowledgeSearchOptions.activeOnly(9L, 2);
        List<Double> vector = List.of(0.4d, 0.6d);

        when(indexClient.searchLexical("semantic only", options, 8)).thenThrow(new IllegalStateException("bm25 down"));
        when(embeddingClient.embed("semantic only")).thenReturn(vector);
        when(indexClient.searchVector(vector, options, 8)).thenReturn(List.of(
                new KnowledgeSearchIndexHit(5L, 0, "semantic result", 0.9d)
        ));
        when(documents.findById(5L)).thenReturn(java.util.Optional.of(new KbDocument(9L, "vector.md", "READY", false)));

        assertThat(service.search("semantic only", options))
                .extracting(KnowledgeSearchResult::filename)
                .containsExactly("vector.md");
    }

    private KbDocument document(Long id, Long userId, String filename) {
        KbDocument document = new KbDocument(userId, filename, "READY", false);
        org.springframework.test.util.ReflectionTestUtils.setField(document, "id", id);
        return document;
    }
}
