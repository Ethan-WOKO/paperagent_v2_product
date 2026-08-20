package com.yanban.knowledge.service;

import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.domain.KbChunk;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.tika.Tika;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FileProcessingService {

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final MinioClient minioClient;
    private final KnowledgeStorageProperties storageProperties;
    private final VectorizationService vectorizationService;
    private final OcrProvider ocrProvider;
    private final KnowledgeTextChunker textChunker;
    private final Tika tika = new Tika();

    public FileProcessingService(KbDocumentRepository documents,
                                 KbChunkRepository chunks,
                                 MinioClient minioClient,
                                 KnowledgeStorageProperties storageProperties,
                                 VectorizationService vectorizationService,
                                 ObjectProvider<OcrProvider> ocrProvider,
                                 KnowledgeTextChunker textChunker) {
        this.documents = documents;
        this.chunks = chunks;
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
        this.vectorizationService = vectorizationService;
        this.ocrProvider = ocrProvider.getIfAvailable();
        this.textChunker = textChunker;
    }

    @Transactional
    public void process(FileProcessingMessage message) {
        KbDocument document = documents.findById(message.documentId())
                .orElseThrow(() -> new IllegalStateException("知识库文档不存在: " + message.documentId()));
        document.setStatus("PROCESSING");
        document.setErrorMessage(null);
        documents.save(document);

        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(storageProperties.getBucket())
                .object(message.objectKey())
                .build())) {
            byte[] bytes = in.readAllBytes();
            String text = extractText(document, bytes);
            chunks.deleteByDocumentId(document.getId());
            List<KbChunk> persistedChunks = chunks.saveAll(splitText(document.getId(), text));
            vectorizationService.vectorizeDocument(document, persistedChunks);
            document.setStatus("READY");
            document.setErrorMessage(null);
        } catch (Exception ex) {
            document.setStatus("FAILED");
            document.setErrorMessage(limitError(ex.getMessage()));
        }

        documents.save(document);
    }

    String extractText(KbDocument document, byte[] bytes) throws Exception {
        String mimeType = resolveMimeType(document, bytes);
        if (mimeType != null && mimeType.toLowerCase().startsWith("image/")) {
            if (ocrProvider == null) {
                throw new IllegalStateException("OCR 未配置");
            }
            return ocrProvider.extractText(bytes, mimeType, document.getFilename());
        }
        if (shouldDecodeAsUtf8Text(mimeType, document == null ? null : document.getFilename())) {
            return decodeUtf8Text(bytes);
        }
        return tika.parseToString(new ByteArrayInputStream(bytes));
    }

    List<KbChunk> splitText(Long documentId, String text) {
        List<KbChunk> result = new ArrayList<>();
        int index = 0;
        for (String chunkText : textChunker.split(text)) {
            result.add(new KbChunk(documentId, index++, chunkText));
        }
        return result;
    }

    private String resolveMimeType(KbDocument document, byte[] bytes) {
        if (document == null) {
            return null;
        }
        if (StringUtils.hasText(document.getMimeType())) {
            return document.getMimeType();
        }
        String detected = tika.detect(bytes, document.getFilename());
        return StringUtils.hasText(detected) ? detected : null;
    }

    private boolean shouldDecodeAsUtf8Text(String mimeType, String filename) {
        String normalizedMimeType = mimeType == null ? "" : mimeType.toLowerCase();
        if (normalizedMimeType.startsWith("text/")) {
            return true;
        }
        if ("application/json".equals(normalizedMimeType)
                || "application/xml".equals(normalizedMimeType)
                || "application/yaml".equals(normalizedMimeType)
                || "application/x-yaml".equals(normalizedMimeType)
                || "application/csv".equals(normalizedMimeType)
                || "application/markdown".equals(normalizedMimeType)) {
            return true;
        }
        if (!StringUtils.hasText(filename) || !filename.contains(".")) {
            return false;
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (extension) {
            case "md", "markdown", "txt", "csv", "json", "xml", "yml", "yaml", "log" -> true;
            default -> false;
        };
    }

    private String decodeUtf8Text(byte[] bytes) {
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        return decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
    }

    private String limitError(String message) {
        if (message == null || message.isBlank()) {
            return "文件解析失败";
        }
        return message.length() > 255 ? message.substring(0, 255) : message;
    }
}
