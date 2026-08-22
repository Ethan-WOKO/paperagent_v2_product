package com.yanban.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.knowledge.config.KnowledgeElasticsearchProperties;
import com.yanban.knowledge.config.KnowledgeUploadProperties;
import com.yanban.knowledge.domain.KbChunk;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class VectorizationServiceTest {

    @Test
    void vectorizeDocumentIndexesChunkAndStoresEsDocId() {
        EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);
        KnowledgeIndexService indexService = Mockito.mock(KnowledgeIndexService.class);
        KbChunkRepository chunkRepository = Mockito.mock(KbChunkRepository.class);
        KnowledgeElasticsearchProperties properties = new KnowledgeElasticsearchProperties();
        properties.setVectorDimensions(3);
        KnowledgeUploadProperties upload = new KnowledgeUploadProperties();
        VectorizationService service = new VectorizationService(embeddingClient, indexService, properties,
                chunkRepository, upload, new KnowledgeResourceLimiter(upload));

        KbDocument document = new KbDocument(1L, "paper.md", "PROCESSING", false);
        document.setProjectId(99L);
        document.setSourceType("PAPER_POLISHED");
        document.setVersionStatus("ACTIVE");
        document.setLineageId("paper-1");
        document.setVersionNo(2);
        document.setCanonicalKey("paper-1-polished");
        KbChunk chunk = new KbChunk(10L, 0, "alpha content");
        when(embeddingClient.embedAll(any())).thenReturn(java.util.List.of(java.util.List.of(0.1d, 0.2d, 0.3d)));
        when(indexService.indexChunks(any())).thenReturn(java.util.List.of("es-123"));
        when(chunkRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.vectorizeDocument(document, Collections.singletonList(chunk));

        assertThat(chunk.getEsDocId()).isEqualTo("es-123");
        @SuppressWarnings("unchecked") ArgumentCaptor<java.util.List<IndexedChunkDocument>> indexedChunks =
                ArgumentCaptor.forClass(java.util.List.class);
        verify(indexService).indexChunks(indexedChunks.capture());
        IndexedChunkDocument indexedChunk = indexedChunks.getValue().get(0);
        assertThat(indexedChunk.projectId()).isEqualTo(99L);
        assertThat(indexedChunk.sourceType()).isEqualTo("PAPER_POLISHED");
        assertThat(indexedChunk.versionStatus()).isEqualTo("ACTIVE");
        assertThat(indexedChunk.lineageId()).isEqualTo("paper-1");
        assertThat(indexedChunk.versionNo()).isEqualTo(2);
        assertThat(indexedChunk.canonicalKey()).isEqualTo("paper-1-polished");
        verify(chunkRepository).saveAll(any());
    }
}
