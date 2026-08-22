package com.yanban.knowledge.service;

import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.config.KnowledgeChunkingProperties;
import com.yanban.knowledge.config.KnowledgeUploadProperties;
import com.yanban.knowledge.domain.KbDocument;
import io.minio.MinioClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FileProcessingServiceTest {

    private static final String TITLE = "\u0052\u0041\u0047\u0020\u7efc\u5408\u8bc4\u6d4b\u6837\u672c\u6587\u6863";
    private static final String FORMAL_NAME_LINE = "\u9879\u76ee\u6b63\u5f0f\u540d\u79f0\u662f\u201c\u9752\u5c9a\u77e5\u8bc6\u52a9\u624b\u201d\u3002";
    private static final String SHORT_NAME = "\u9752\u5c9a\u77e5\u8bc6\u52a9\u624b";

    @Test
    void extractTextPreservesUtf8MarkdownContent() throws Exception {
        FileProcessingService service = new FileProcessingService(
                mock(com.yanban.knowledge.domain.KbDocumentRepository.class),
                mock(com.yanban.knowledge.domain.KbChunkRepository.class),
                mock(MinioClient.class),
                new KnowledgeStorageProperties(),
                mock(VectorizationService.class),
                emptyOcrProvider(),
                new KnowledgeTextChunker(new KnowledgeChunkingProperties()),
                uploadProperties(), new KnowledgeResourceLimiter(uploadProperties())
        );
        KbDocument document = new KbDocument(2L, "rag-eval-comprehensive-sample.md", "PROCESSING", false);
        document.setMimeType("text/markdown");

        String markdown = String.join("\n",
                "# " + TITLE,
                "",
                FORMAL_NAME_LINE,
                "\u9879\u76ee\u82f1\u6587\u540d\u79f0\u662f `BlueMist Assistant`\u3002",
                "\u65e7\u4ee3\u53f7\u662f `Project Cedar`\uff0c\u4f46\u5f53\u524d\u4e0d\u518d\u4f5c\u4e3a\u6b63\u5f0f\u540d\u79f0\u4f7f\u7528\u3002"
        );
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);

        String extracted = service.extractText(document, bytes);

        assertThat(extracted)
                .contains(TITLE)
                .contains(FORMAL_NAME_LINE)
                .contains("BlueMist Assistant")
                .contains("Project Cedar");
    }

    @Test
    void extractTextFallsBackToFilenameForUtf8MarkdownWhenMimeTypeMissing() throws Exception {
        FileProcessingService service = new FileProcessingService(
                mock(com.yanban.knowledge.domain.KbDocumentRepository.class),
                mock(com.yanban.knowledge.domain.KbChunkRepository.class),
                mock(MinioClient.class),
                new KnowledgeStorageProperties(),
                mock(VectorizationService.class),
                emptyOcrProvider(),
                new KnowledgeTextChunker(new KnowledgeChunkingProperties()),
                uploadProperties(), new KnowledgeResourceLimiter(uploadProperties())
        );
        KbDocument document = new KbDocument(2L, "sample.md", "PROCESSING", false);

        byte[] bytes = FORMAL_NAME_LINE.getBytes(StandardCharsets.UTF_8);

        assertThat(service.extractText(document, bytes)).contains(SHORT_NAME);
    }

    private ObjectProvider<OcrProvider> emptyOcrProvider() {
        return new ObjectProvider<>() {
            @Override
            public OcrProvider getObject(Object... args) {
                return null;
            }

            @Override
            public OcrProvider getIfAvailable() {
                return null;
            }

            @Override
            public OcrProvider getIfUnique() {
                return null;
            }

            @Override
            public OcrProvider getObject() {
                return null;
            }
        };
    }

    private KnowledgeUploadProperties uploadProperties() { return new KnowledgeUploadProperties(); }
}
