package com.yanban.knowledge.service;

import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.config.KnowledgeUploadProperties;
import com.yanban.knowledge.domain.KbChunk;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToTextContentHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FileProcessingService {
    private static final Logger log = LoggerFactory.getLogger(FileProcessingService.class);

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final MinioClient minioClient;
    private final KnowledgeStorageProperties storageProperties;
    private final VectorizationService vectorizationService;
    private final OcrProvider ocrProvider;
    private final KnowledgeTextChunker textChunker;
    private final KnowledgeUploadProperties uploadProperties;
    private final KnowledgeResourceLimiter resourceLimiter;
    private final Tika tika = new Tika();

    public FileProcessingService(KbDocumentRepository documents,
                                 KbChunkRepository chunks,
                                 MinioClient minioClient,
                                 KnowledgeStorageProperties storageProperties,
                                 VectorizationService vectorizationService,
                                 ObjectProvider<OcrProvider> ocrProvider,
                                 KnowledgeTextChunker textChunker,
                                 KnowledgeUploadProperties uploadProperties,
                                 KnowledgeResourceLimiter resourceLimiter) {
        this.documents = documents;
        this.chunks = chunks;
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
        this.vectorizationService = vectorizationService;
        this.ocrProvider = ocrProvider.getIfAvailable();
        this.textChunker = textChunker;
        this.uploadProperties = uploadProperties;
        this.resourceLimiter = resourceLimiter;
    }

    public void process(FileProcessingMessage message) {
        Instant startedAt = Instant.now();
        KbDocument document = documents.findById(message.documentId())
                .orElseThrow(() -> new IllegalStateException("知识库文档不存在: " + message.documentId()));
        validateAuthority(message, document);
        if ("READY".equals(document.getStatus()) && sameDigest(message.fileDigest(), document.getFileDigest())) return;
        try (KnowledgeResourceLimiter.Permit ignored = resourceLimiter.processing(document.getUserId())) {
            document.startProcessing(message.eventId());
            documents.save(document);
            processFromStorage(message, document);
            document.setStatus("READY");
            document.setErrorMessage(null);
            document.setProcessedAt(Instant.now());
            documents.save(document);
            KnowledgeMetrics.processing("succeeded", java.time.Duration.between(startedAt, Instant.now()));
        } catch (KnowledgePermanentProcessingException ex) {
            markRetrying(document, ex);
            log.error("知识库文件处理发生永久错误: documentId={}, eventId={}",
                    document.getId(), message.eventId(), ex);
            KnowledgeMetrics.processing("permanent_failure", java.time.Duration.between(startedAt, Instant.now()));
            throw ex;
        } catch (Exception ex) {
            markRetrying(document, ex);
            log.warn("知识库文件处理失败，等待 Kafka 重试: documentId={}, eventId={}",
                    document.getId(), message.eventId(), ex);
            KnowledgeMetrics.processing("retryable_failure", java.time.Duration.between(startedAt, Instant.now()));
            throw ex instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("知识库文件处理失败", ex);
        }
    }

    private void processFromStorage(FileProcessingMessage message, KbDocument document) throws Exception {
        Path source = Files.createTempFile("yanban-kb-source-", ".bin");
        Path extracted = Files.createTempFile("yanban-kb-text-", ".txt");
        try {
            try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(storageProperties.getBucket()).object(message.objectKey()).build())) {
                Files.copy(in, source, StandardCopyOption.REPLACE_EXISTING);
            }
            String actualDigest = sha256(source);
            if (message.fileDigest() != null && !message.fileDigest().equals(actualDigest)) {
                throw new KnowledgePermanentProcessingException("文件摘要与处理消息不一致");
            }
            if (document.getFileDigest() != null && !document.getFileDigest().equals(actualDigest)) {
                throw new KnowledgePermanentProcessingException("文件摘要与文档事实不一致");
            }
            document.setFileDigest(actualDigest);
            extractToFile(document, source, extracted);
            knowledgeIndexServiceDelete(document.getId());
            chunks.deleteByDocumentId(document.getId());
            streamChunks(document, extracted, actualDigest);
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(extracted);
        }
    }

    private void streamChunks(KbDocument document, Path extracted, String fileDigest) throws Exception {
        AtomicInteger index = new AtomicInteger();
        List<KbChunk> batch = new ArrayList<>(uploadProperties.getProcessingBatchSize());
        try (Reader reader = Files.newBufferedReader(extracted, StandardCharsets.UTF_8)) {
            textChunker.forEachChunk(reader, value -> {
                KbChunk chunk = new KbChunk(document.getId(), index.getAndIncrement(), value);
                chunk.setContentDigest(digestText(value));
                batch.add(chunk);
                if (batch.size() >= uploadProperties.getProcessingBatchSize()) persistBatch(document, batch);
            });
        }
        if (!batch.isEmpty()) persistBatch(document, batch);
        if (index.get() == 0) {
            KbChunk empty = new KbChunk(document.getId(), 0, "");
            empty.setContentDigest(digestText(""));
            persistBatch(document, new ArrayList<>(List.of(empty)));
        }
    }

    private void persistBatch(KbDocument document, List<KbChunk> batch) {
        List<KbChunk> persisted = chunks.saveAllAndFlush(List.copyOf(batch));
        vectorizationService.vectorizeDocument(document, persisted);
        batch.clear();
    }

    private void extractToFile(KbDocument document, Path source, Path target) throws Exception {
        String mimeType = resolveMimeType(document, source);
        if (mimeType != null && mimeType.toLowerCase().startsWith("image/")) {
            if (ocrProvider == null) throw new KnowledgePermanentProcessingException("OCR 未配置");
            long size = Files.size(source);
            if (size > uploadProperties.getOcrMaxBytes()) {
                throw new KnowledgePermanentProcessingException("OCR 文件超过允许大小");
            }
            String text = ocrProvider.extractText(Files.readAllBytes(source), mimeType, document.getFilename());
            Files.writeString(target, text == null ? "" : text, StandardCharsets.UTF_8);
            return;
        }
        if (shouldDecodeAsUtf8Text(mimeType, document.getFilename())) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Metadata metadata = new Metadata();
        if (document.getFilename() != null) metadata.set("resourceName", document.getFilename());
        try (InputStream in = Files.newInputStream(source);
             Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            new AutoDetectParser().parse(in, new ToTextContentHandler(writer), metadata, new ParseContext());
        }
    }

    private void knowledgeIndexServiceDelete(Long documentId) {
        try { vectorizationService.deleteDocumentIndex(documentId); }
        catch (Exception ignored) { /* deterministic bulk upserts repair partial prior attempts */ }
    }

    private String resolveMimeType(KbDocument document, Path source) throws Exception {
        if (StringUtils.hasText(document.getMimeType())) return document.getMimeType();
        try (InputStream in = Files.newInputStream(source)) {
            String detected = tika.detect(in, document.getFilename());
            return StringUtils.hasText(detected) ? detected : null;
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String digestText(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private void validateAuthority(FileProcessingMessage message, KbDocument document) {
        if (message.documentId() == null || message.userId() == null || message.objectKey() == null
                || !message.userId().equals(document.getUserId())
                || !message.objectKey().equals(document.getObjectKey())) {
            throw new KnowledgePermanentProcessingException("处理消息与文档事实不一致");
        }
    }

    private boolean sameDigest(String left, String right) {
        return left == null || right == null || left.equals(right);
    }

    private void markRetrying(KbDocument document, Exception ex) {
        document.setStatus("RETRYING");
        document.setErrorMessage(limitError(ex.getMessage()));
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
