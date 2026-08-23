package com.yanban.knowledge.service;

import com.yanban.core.user.UserAccountPolicy;
import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.config.KnowledgeUploadProperties;
import com.yanban.knowledge.domain.KbChunkUpload;
import com.yanban.knowledge.domain.KbChunkUploadRepository;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.web.ChunkUploadRequest;
import com.yanban.knowledge.web.ChunkUploadResponse;
import com.yanban.knowledge.web.KbDocumentResponse;
import com.yanban.knowledge.web.MergeUploadRequest;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

@Service
public class KnowledgeUploadService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeUploadService.class);

    private final KbChunkUploadRepository chunkUploads;
    private final KbDocumentRepository documents;
    private final MinioClient minioClient;
    private final KnowledgeBucketProvisioner bucketProvisioner;
    private final KnowledgeStorageProperties storageProperties;
    private final KnowledgeUploadProperties uploadProperties;
    private final ObjectProvider<UserAccountPolicy> accountPolicy;
    private final KnowledgeOutboxService outboxService;
    private final KnowledgeResourceLimiter resourceLimiter;

    public KnowledgeUploadService(KbChunkUploadRepository chunkUploads,
                                  KbDocumentRepository documents,
                                  MinioClient minioClient,
                                  KnowledgeBucketProvisioner bucketProvisioner,
                                  KnowledgeStorageProperties storageProperties,
                                  KnowledgeUploadProperties uploadProperties,
                                  ObjectProvider<UserAccountPolicy> accountPolicy,
                                  KnowledgeOutboxService outboxService,
                                  KnowledgeResourceLimiter resourceLimiter) {
        this.chunkUploads = chunkUploads;
        this.documents = documents;
        this.minioClient = minioClient;
        this.bucketProvisioner = bucketProvisioner;
        this.storageProperties = storageProperties;
        this.uploadProperties = uploadProperties;
        this.accountPolicy = accountPolicy;
        this.outboxService = outboxService;
        this.resourceLimiter = resourceLimiter;
    }

    @Transactional
    public ChunkUploadResponse uploadChunk(Long userId, ChunkUploadRequest request) {
        validateChunkRequest(request);
        try (KnowledgeResourceLimiter.Permit ignored = resourceLimiter.upload(userId)) {
            byte[] bytes = request.file().getBytes();
            String digest = sha256(bytes);
            var existing = chunkUploads.findByUserIdAndUploadIdAndChunkNumber(
                    userId, request.uploadId(), request.chunkNumber());
            if (existing.isPresent()) {
                KbChunkUpload value = existing.get();
                if (digest.equals(value.getChunkDigest())
                        && request.totalChunks().equals(value.getTotalChunks())
                        && request.filename().equals(value.getFilename())) {
                    KnowledgeMetrics.upload("idempotent_replay");
                    return new ChunkUploadResponse(request.uploadId(), request.chunkNumber(),
                            request.totalChunks(), value.getStatus(), value.getTempObjectKey());
                }
                throw new ResponseStatusException(CONFLICT, "相同上传分片编号的内容不一致");
            }
            bucketProvisioner.ensureBucketExists();
            UserAccountPolicy policy = accountPolicy.getIfAvailable();
            if (policy != null) {
                policy.assertCanUploadKnowledge(userId, bytes.length);
            }
            validateMd5(request.chunkMd5(), bytes);

            String tempObjectKey = buildTempObjectKey(userId, request.uploadId(), request.chunkNumber());
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(storageProperties.getBucket())
                    .object(tempObjectKey)
                    .stream(new java.io.ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(resolveContentType(request.file().getContentType()))
                    .build());

            chunkUploads.save(new KbChunkUpload(
                    userId,
                    request.uploadId(),
                    request.filename(),
                    request.chunkNumber(),
                    request.totalChunks(),
                    (long) bytes.length,
                    normalizeMd5(request.chunkMd5()),
                    digest,
                    tempObjectKey,
                    "UPLOADED"
            ));
            KnowledgeMetrics.upload("chunk_accepted");
            return new ChunkUploadResponse(
                    request.uploadId(),
                    request.chunkNumber(),
                    request.totalChunks(),
                    "UPLOADED",
                    tempObjectKey
            );
        } catch (KnowledgeCapacityException ex) {
            KnowledgeMetrics.upload("rate_limited");
            throw new ResponseStatusException(TOO_MANY_REQUESTS, ex.getMessage(), ex);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            KnowledgeMetrics.upload("failed");
            log.error("知识库上传分片失败: userId={}, uploadId={}, filename={}, chunkNumber={}, totalChunks={}",
                    userId, request.uploadId(), request.filename(), request.chunkNumber(), request.totalChunks(), ex);
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "上传分片失败", ex);
        }
    }

    @Transactional
    public KbDocumentResponse mergeChunks(Long userId, MergeUploadRequest request) {
        var alreadyMerged = documents.findByUserIdAndUploadId(userId, request.uploadId());
        if (alreadyMerged.isPresent()) {
            KnowledgeMetrics.upload("merge_idempotent_replay");
            return KbDocumentResponse.from(alreadyMerged.get());
        }
        List<KbChunkUpload> uploadedChunks = chunkUploads.findByUserIdAndUploadIdOrderByChunkNumberAsc(
                userId, request.uploadId());
        if (uploadedChunks.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "未找到上传分片");
        }
        if (!uploadedChunks.stream().allMatch(chunk -> chunk.getUserId().equals(userId))) {
            throw new ResponseStatusException(BAD_REQUEST, "上传分片不属于当前用户");
        }
        if (uploadedChunks.size() != request.totalChunks()) {
            throw new ResponseStatusException(BAD_REQUEST, "分片数量不完整");
        }
        validateChunkSequence(uploadedChunks, request.totalChunks());
        UserAccountPolicy policy = accountPolicy.getIfAvailable();
        if (policy != null) {
            long totalBytes = uploadedChunks.stream().mapToLong(KbChunkUpload::getChunkSize).sum();
            policy.assertCanUploadKnowledge(userId, totalBytes);
        }

        Path mergedFile = null;
        try (KnowledgeResourceLimiter.Permit ignored = resourceLimiter.upload(userId)) {
            bucketProvisioner.ensureBucketExists();
            mergedFile = Files.createTempFile("yanban-kb-merge-", ".bin");
            MessageDigest mergedDigest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(mergedFile), mergedDigest)) {
                for (KbChunkUpload upload : uploadedChunks) {
                    try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                            .bucket(storageProperties.getBucket())
                            .object(upload.getTempObjectKey())
                            .build())) {
                        in.transferTo(out);
                    }
                }
            }

            String fileDigest = HexFormat.of().formatHex(mergedDigest.digest());
            String objectKey = buildDocumentObjectKey(userId, request.uploadId(), request.filename());
            long fileSize = Files.size(mergedFile);
            try (InputStream mergedInputStream = Files.newInputStream(mergedFile)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(storageProperties.getBucket())
                        .object(objectKey)
                        .stream(mergedInputStream, fileSize, -1)
                        .contentType(resolveContentType(request.mimeType()))
                        .build());
            }

            KbDocument document = new KbDocument(userId, request.filename(), "PROCESSING", request.isPublic());
            document.setObjectKey(objectKey);
            document.setMimeType(resolveContentType(request.mimeType()));
            document.setFileSize(fileSize);
            document.setUploadId(request.uploadId());
            document.setFileDigest(fileDigest);
            document = documents.save(document);
            outboxService.enqueue(document);
            document = documents.save(document);

            for (KbChunkUpload upload : uploadedChunks) {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(storageProperties.getBucket())
                        .object(upload.getTempObjectKey())
                        .build());
            }
            uploadedChunks.forEach(KbChunkUpload::markMerged);
            chunkUploads.saveAll(uploadedChunks);
            KnowledgeMetrics.upload("merged");
            return KbDocumentResponse.from(document);
        } catch (KnowledgeCapacityException ex) {
            KnowledgeMetrics.upload("rate_limited");
            throw new ResponseStatusException(TOO_MANY_REQUESTS, ex.getMessage(), ex);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            KnowledgeMetrics.upload("failed");
            log.error("知识库合并分片失败: userId={}, uploadId={}, filename={}, totalChunks={}",
                    userId, request.uploadId(), request.filename(), request.totalChunks(), ex);
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "合并分片失败", ex);
        } finally {
            if (mergedFile != null) {
                try {
                    Files.deleteIfExists(mergedFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void validateChunkRequest(ChunkUploadRequest request) {
        if (request.file() == null || request.file().isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "分片文件不能为空");
        }
        if (request.chunkNumber() == null || request.totalChunks() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "分片参数不完整");
        }
        if (request.chunkNumber() >= request.totalChunks()) {
            throw new ResponseStatusException(BAD_REQUEST, "chunkNumber 必须小于 totalChunks");
        }
    }

    private void validateChunkSequence(List<KbChunkUpload> uploads, int totalChunks) {
        int start = uploads.get(0).getChunkNumber() == 0 ? 0 : 1;
        for (int i = 0; i < uploads.size(); i++) {
            int expected = start + i;
            if (!uploads.get(i).getChunkNumber().equals(expected)) {
                throw new ResponseStatusException(BAD_REQUEST, "分片序号不连续");
            }
            if (!uploads.get(i).getTotalChunks().equals(totalChunks)) {
                throw new ResponseStatusException(BAD_REQUEST, "totalChunks 不一致");
            }
        }
    }

    private void validateMd5(String expectedMd5, byte[] bytes) {
        String normalized = normalizeMd5(expectedMd5);
        if (normalized == null) {
            return;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            String actual = HexFormat.of().formatHex(digest.digest(bytes));
            if (!normalized.equals(actual)) {
                throw new ResponseStatusException(BAD_REQUEST, "分片 MD5 校验失败");
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "分片 MD5 校验失败", ex);
        }
    }

    private String normalizeMd5(String md5) {
        if (md5 == null || md5.isBlank()) {
            return null;
        }
        return md5.trim().toLowerCase(Locale.ROOT);
    }

    private String resolveContentType(String contentType) {
        return (contentType == null || contentType.isBlank())
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : contentType;
    }

    private String buildTempObjectKey(Long userId, String uploadId, Integer chunkNumber) {
        return uploadProperties.getTempPrefix() + "/" + userId + "/" + uploadId + "/chunk-" + chunkNumber;
    }

    private String buildDocumentObjectKey(Long userId, String uploadId, String filename) {
        String safeFilename = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        String safeUploadId = uploadId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return uploadProperties.getObjectPrefix() + "/" + userId + "/" + safeUploadId + "-" + safeFilename;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
