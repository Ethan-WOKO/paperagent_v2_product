package com.yanban.paper.service;

import com.yanban.paper.config.PaperStorageProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PaperStorageService {

    private final ObjectProvider<MinioClient> minioClientProvider;
    private final PaperStorageProperties properties;

    public PaperStorageService(ObjectProvider<MinioClient> minioClientProvider,
                               PaperStorageProperties properties) {
        this.minioClientProvider = minioClientProvider;
        this.properties = properties;
    }

    public String storeOriginal(Long userId, MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            String safeFilename = Optional.ofNullable(file.getOriginalFilename())
                    .orElse("paper.docx")
                    .replaceAll("[^a-zA-Z0-9._-]", "_");
            String objectKey = buildObjectKey(userId, "original", safeFilename);
            storeBytes(objectKey, bytes, resolveDocxContentType(file.getContentType()));
            return objectKey;
        } catch (Exception ex) {
            throw new IllegalStateException("保存论文原始文件失败", ex);
        }
    }

    public String storeFinal(Long userId, String sourceFilename, byte[] bytes) {
        try {
            String safeFilename = Optional.ofNullable(sourceFilename)
                    .orElse("paper.docx")
                    .replaceAll("[^a-zA-Z0-9._-]", "_");
            String objectKey = buildObjectKey(userId, "final", safeFilename);
            storeBytes(objectKey, bytes, resolveDocxContentType(null));
            return objectKey;
        } catch (Exception ex) {
            throw new IllegalStateException("保存论文结果文件失败", ex);
        }
    }

    public String storeArtifact(Long userId, String type, String filename, byte[] bytes, String contentType) {
        try {
            String safeType = Optional.ofNullable(type).orElse("artifact").replaceAll("[^a-zA-Z0-9._-]", "_");
            String safeFilename = Optional.ofNullable(filename).orElse("artifact.txt").replaceAll("[^a-zA-Z0-9._-]", "_");
            String objectKey = buildObjectKey(userId, safeType, safeFilename);
            storeBytes(objectKey, bytes, contentType == null || contentType.isBlank() ? "text/plain; charset=UTF-8" : contentType);
            return objectKey;
        } catch (Exception ex) {
            throw new IllegalStateException("保存论文产物失败", ex);
        }
    }

    public byte[] read(String objectKey) {
        return read(objectKey, Integer.MAX_VALUE - 1);
    }

    public byte[] read(String objectKey, int maxBytes) {
        if (maxBytes < 1 || maxBytes == Integer.MAX_VALUE) throw new IllegalArgumentException("invalid read limit");
        try {
            if (properties.isPreferMinio()) {
                MinioClient minioClient = minioClientProvider.getIfAvailable();
                if (minioClient != null) {
                    try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectKey)
                            .build())) {
                        if (inputStream != null) {
                            return boundedRead(inputStream, maxBytes);
                        }
                    } catch (Exception ignored) {
                        // Fall back to local backup below. This keeps tests and local dev robust when MinIO is mocked or unavailable.
                    }
                }
            }
            Path root = properties.getLocalRoot().toAbsolutePath().normalize();
            Path source = root.resolve(objectKey).normalize();
            if (!source.startsWith(root) || !source.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalStateException("invalid paper storage reference");
            }
            try (InputStream input = Files.newInputStream(source)) {
                return boundedRead(input, maxBytes);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("读取论文文件失败", ex);
        }
    }

    private byte[] boundedRead(InputStream input, int maxBytes) throws java.io.IOException {
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) throw new java.io.IOException("paper source exceeds read limit");
        return bytes;
    }

    private void storeBytes(String objectKey, byte[] bytes, String contentType) throws Exception {
        if (properties.isPreferMinio()) {
            MinioClient minioClient = minioClientProvider.getIfAvailable();
            if (minioClient != null) {
                try {
                    minioClient.putObject(PutObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectKey)
                            .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                            .contentType(contentType)
                            .build());
                } catch (Exception ignored) {
                    // Keep a local copy below even when MinIO is temporarily unavailable.
                }
            }
        }
        Path target = properties.getLocalRoot().resolve(objectKey);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private String buildObjectKey(Long userId, String kind, String safeFilename) {
        return properties.getObjectPrefix() + "/" + userId + "/" + kind + "/" + UUID.randomUUID() + "-" + safeFilename;
    }

    private String resolveDocxContentType(String contentType) {
        return contentType == null || contentType.isBlank()
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                : contentType;
    }
}
