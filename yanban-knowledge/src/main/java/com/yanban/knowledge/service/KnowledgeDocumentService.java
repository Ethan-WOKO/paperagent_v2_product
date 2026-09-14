package com.yanban.knowledge.service;

import com.yanban.core.user.UserAccountPolicy;
import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.domain.KbChunkRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.web.KbDocumentListItemResponse;
import com.yanban.knowledge.web.KbDocumentPreviewResponse;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.RemoveObjectArgs;
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeDocumentService {

    private final KbDocumentRepository documents;
    private final KbChunkRepository chunks;
    private final KnowledgeIndexService indexService;
    private final MinioClient minioClient;
    private final KnowledgeStorageProperties storageProperties;
    private final ObjectProvider<UserAccountPolicy> accountPolicy;
    private final ObjectProvider<KnowledgeDocumentPublisherPolicy> publisherPolicy;

    public KnowledgeDocumentService(KbDocumentRepository documents,
                                    KbChunkRepository chunks,
                                    KnowledgeIndexService indexService,
                                    MinioClient minioClient,
                                    KnowledgeStorageProperties storageProperties,
                                    ObjectProvider<UserAccountPolicy> accountPolicy,
                                    ObjectProvider<KnowledgeDocumentPublisherPolicy> publisherPolicy) {
        this.documents = documents;
        this.chunks = chunks;
        this.indexService = indexService;
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
        this.accountPolicy = accountPolicy;
        this.publisherPolicy = publisherPolicy;
    }

    @Transactional(readOnly = true)
    public List<KbDocumentListItemResponse> listOwnedDocuments(Long userId) {
        return documents.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(KbDocumentListItemResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<KbDocumentListItemResponse> listVisibleDocuments(Long userId) {
        requireIdentity(userId);
        KnowledgeDocumentPublisherPolicy policy = publisherPolicy.getIfAvailable();
        Set<Long> administrators = policy == null ? Set.of() : policy.administratorIds();
        var visible = new LinkedHashMap<Long, KbDocument>();
        documents.findByUserIdOrderByCreatedAtDesc(userId).forEach(doc -> visible.put(doc.getId(), doc));
        if (!administrators.isEmpty()) {
            documents.findPublicDocumentsByPublisherIds(administrators).stream()
                    .filter(doc -> administrators.contains(doc.getUserId()) && isShareable(doc))
                    .forEach(doc -> visible.putIfAbsent(doc.getId(), doc));
        }
        return visible.values().stream()
                .sorted(Comparator.comparing(KbDocument::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(KbDocument::getId, Comparator.reverseOrder()))
                .map(doc -> KbDocumentListItemResponse.from(doc, userId.equals(doc.getUserId()),
                        administrators.contains(doc.getUserId()) && Boolean.TRUE.equals(doc.getIsPublic()),
                        !isRetired(doc) && hasOriginal(doc)))
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentDownload downloadDocument(Long userId, Long documentId) {
        requireIdentity(userId);
        KbDocument doc = documents.findById(documentId).orElseThrow(KnowledgeDocumentService::notAvailable);
        if (isRetired(doc)) throw notAvailable();
        if (!userId.equals(doc.getUserId())) {
            KnowledgeDocumentPublisherPolicy policy = publisherPolicy.getIfAvailable();
            if (!isShareable(doc) || policy == null || !policy.isAdministrator(doc.getUserId())) {
                throw notAvailable();
            }
        }
        if (!hasOriginal(doc)) throw notAvailable();
        String filename = safeFilename(doc);
        try {
            return new KnowledgeDocumentDownload(filename, minioClient.getObject(GetObjectArgs.builder()
                    .bucket(storageProperties.getBucket()).object(doc.getObjectKey()).build()));
        } catch (ErrorResponseException ex) {
            if ("NoSuchKey".equals(ex.errorResponse().code())) throw notAvailable();
            throw storageUnavailable();
        } catch (Exception ex) {
            throw storageUnavailable();
        }
    }

    private static void requireIdentity(Long userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请登录后重试");
    }

    private static boolean isShareable(KbDocument doc) {
        return !isRetired(doc) && Boolean.TRUE.equals(doc.getIsPublic())
                && "READY".equalsIgnoreCase(doc.getStatus())
                && (doc.getVersionStatus() == null || "ACTIVE".equalsIgnoreCase(doc.getVersionStatus()));
    }

    private static boolean isRetired(KbDocument doc) {
        return doc.getDeletedAt() != null || "DELETED".equalsIgnoreCase(doc.getVersionStatus())
                || "ARCHIVED".equalsIgnoreCase(doc.getVersionStatus());
    }

    private static boolean hasOriginal(KbDocument doc) {
        return doc.getObjectKey() != null && !doc.getObjectKey().isBlank();
    }

    private static String safeFilename(KbDocument doc) {
        String name = doc.getFilename() == null ? "" : doc.getFilename().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\\p{Cf}]", "")
                .replaceAll("[<>:\"|?*]", "_").strip().replaceAll("[. ]+$", "");
        return name.isBlank() ? "document-" + doc.getId() : name;
    }

    private static ResponseStatusException notAvailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "文档或原文件不可用");
    }

    private static ResponseStatusException storageUnavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件暂时无法下载，请稍后重试");
    }

    @Transactional(readOnly = true)
    public KbDocumentPreviewResponse previewOwnedDocument(Long userId, Long documentId, Integer requestedMaxChars) {
        KbDocument document = documents.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库文档不存在"));
        int maxChars = normalizeMaxChars(requestedMaxChars);
        int totalChunks = chunks.countByDocumentId(documentId);
        List<com.yanban.knowledge.domain.KbChunk> previewChunks = chunks.findByDocumentIdOrderByChunkIndexAsc(
                documentId,
                PageRequest.of(0, 24)
        );
        StringBuilder content = new StringBuilder(Math.min(maxChars, 16_384));
        int usedChunks = 0;
        boolean truncated = totalChunks > previewChunks.size();
        for (com.yanban.knowledge.domain.KbChunk chunk : previewChunks) {
            if (content.length() >= maxChars) {
                truncated = true;
                break;
            }
            if (usedChunks > 0 && content.length() + 2 <= maxChars) {
                content.append("\n\n");
            }
            String text = chunk.getChunkText() == null ? "" : chunk.getChunkText();
            int remaining = maxChars - content.length();
            if (text.length() > remaining) {
                content.append(text, 0, Math.max(0, remaining));
                truncated = true;
                usedChunks++;
                break;
            }
            content.append(text);
            usedChunks++;
        }
        return KbDocumentPreviewResponse.of(document, totalChunks, usedChunks, maxChars, truncated, content.toString());
    }

    @Transactional
    public void deleteOwnedDocument(Long userId, Long documentId) {
        KbDocument document = documents.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库文档不存在"));
        UserAccountPolicy policy = accountPolicy.getIfAvailable();
        if (policy != null) {
            policy.assertCanDeleteKnowledgeDocument(userId, document.getSourceType());
        }
        chunks.deleteByDocumentId(documentId);
        indexService.deleteByDocumentId(documentId);
        removeObjectQuietly(document.getObjectKey());
        documents.delete(document);
    }

    private void removeObjectQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(storageProperties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("删除 MinIO 文档失败", ex);
        }
    }

    private int normalizeMaxChars(Integer requestedMaxChars) {
        if (requestedMaxChars == null) {
            return 12_000;
        }
        return Math.max(1_000, Math.min(50_000, requestedMaxChars));
    }
}
