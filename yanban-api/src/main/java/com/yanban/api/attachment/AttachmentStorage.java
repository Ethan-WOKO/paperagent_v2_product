package com.yanban.api.attachment;

import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.service.KnowledgeBucketProvisioner;
import io.minio.*;
import java.io.*;
import org.springframework.stereotype.Component;

@Component
public class AttachmentStorage {
    private final MinioClient client;
    private final KnowledgeStorageProperties properties;
    private final KnowledgeBucketProvisioner buckets;
    public AttachmentStorage(MinioClient client, KnowledgeStorageProperties properties, KnowledgeBucketProvisioner buckets) {
        this.client=client;this.properties=properties;this.buckets=buckets;
    }
    public void put(String key, String mime, byte[] data) throws Exception {
        buckets.ensureBucketExists();
        client.putObject(PutObjectArgs.builder().bucket(properties.getBucket()).object(key)
                .contentType(mime).stream(new ByteArrayInputStream(data),data.length,-1).build());
    }
    public byte[] read(String key) throws Exception {
        try (InputStream input=client.getObject(GetObjectArgs.builder().bucket(properties.getBucket()).object(key).build())) {
            byte[] data=input.readNBytes(AttachmentParser.MAX_BYTES+1);
            if(data.length>AttachmentParser.MAX_BYTES) throw new IOException("Attachment exceeds size limit");
            return data;
        }
    }
}
