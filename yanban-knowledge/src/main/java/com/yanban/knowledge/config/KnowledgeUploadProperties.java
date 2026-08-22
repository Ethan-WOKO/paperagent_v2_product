package com.yanban.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.knowledge.upload")
public class KnowledgeUploadProperties {

    private String tempPrefix = "kb/temp";
    private String objectPrefix = "kb/documents";
    private String processingTopic = "file-processing";
    private int processingPartitions = 8;
    private int consumerConcurrency = 4;
    private long retryBackoffMillis = 2000;
    private int maxProcessingAttempts = 4;
    private int outboxBatchSize = 50;
    private int outboxMaxAttempts = 10;
    private int processingBatchSize = 32;
    private int embeddingBatchSize = 10;
    private int maxConcurrentUploads = 12;
    private int maxConcurrentUploadsPerUser = 2;
    private int maxConcurrentProcessing = 8;
    private int maxConcurrentProcessingPerUser = 2;
    private int maxConcurrentEmbeddingBatches = 4;
    private int maxConcurrentEmbeddingBatchesPerUser = 1;
    private long ocrMaxBytes = 25L * 1024L * 1024L;
    private long reconciliationDelayMillis = 60000L;

    public String getTempPrefix() {
        return tempPrefix;
    }

    public void setTempPrefix(String tempPrefix) {
        this.tempPrefix = tempPrefix;
    }

    public String getObjectPrefix() {
        return objectPrefix;
    }

    public void setObjectPrefix(String objectPrefix) {
        this.objectPrefix = objectPrefix;
    }

    public String getProcessingTopic() {
        return processingTopic;
    }

    public void setProcessingTopic(String processingTopic) {
        this.processingTopic = processingTopic;
    }

    public int getProcessingPartitions() { return processingPartitions; }
    public void setProcessingPartitions(int value) { processingPartitions = positive(value, "processingPartitions"); }
    public int getConsumerConcurrency() { return consumerConcurrency; }
    public void setConsumerConcurrency(int value) { consumerConcurrency = positive(value, "consumerConcurrency"); }
    public long getRetryBackoffMillis() { return retryBackoffMillis; }
    public void setRetryBackoffMillis(long value) { retryBackoffMillis = positive(value, "retryBackoffMillis"); }
    public int getMaxProcessingAttempts() { return maxProcessingAttempts; }
    public void setMaxProcessingAttempts(int value) { maxProcessingAttempts = positive(value, "maxProcessingAttempts"); }
    public int getOutboxBatchSize() { return outboxBatchSize; }
    public void setOutboxBatchSize(int value) { outboxBatchSize = positive(value, "outboxBatchSize"); }
    public int getOutboxMaxAttempts() { return outboxMaxAttempts; }
    public void setOutboxMaxAttempts(int value) { outboxMaxAttempts = positive(value, "outboxMaxAttempts"); }
    public int getProcessingBatchSize() { return processingBatchSize; }
    public void setProcessingBatchSize(int value) { processingBatchSize = positive(value, "processingBatchSize"); }
    public int getEmbeddingBatchSize() { return embeddingBatchSize; }
    public void setEmbeddingBatchSize(int value) {
        if (value < 1 || value > 10) {
            throw new IllegalArgumentException("embeddingBatchSize must be between 1 and 10");
        }
        embeddingBatchSize = value;
    }
    public int getMaxConcurrentUploads() { return maxConcurrentUploads; }
    public void setMaxConcurrentUploads(int value) { maxConcurrentUploads = positive(value, "maxConcurrentUploads"); }
    public int getMaxConcurrentUploadsPerUser() { return maxConcurrentUploadsPerUser; }
    public void setMaxConcurrentUploadsPerUser(int value) { maxConcurrentUploadsPerUser = positive(value, "maxConcurrentUploadsPerUser"); }
    public int getMaxConcurrentProcessing() { return maxConcurrentProcessing; }
    public void setMaxConcurrentProcessing(int value) { maxConcurrentProcessing = positive(value, "maxConcurrentProcessing"); }
    public int getMaxConcurrentProcessingPerUser() { return maxConcurrentProcessingPerUser; }
    public void setMaxConcurrentProcessingPerUser(int value) { maxConcurrentProcessingPerUser = positive(value, "maxConcurrentProcessingPerUser"); }
    public int getMaxConcurrentEmbeddingBatches() { return maxConcurrentEmbeddingBatches; }
    public void setMaxConcurrentEmbeddingBatches(int value) { maxConcurrentEmbeddingBatches = positive(value, "maxConcurrentEmbeddingBatches"); }
    public int getMaxConcurrentEmbeddingBatchesPerUser() { return maxConcurrentEmbeddingBatchesPerUser; }
    public void setMaxConcurrentEmbeddingBatchesPerUser(int value) { maxConcurrentEmbeddingBatchesPerUser = positive(value, "maxConcurrentEmbeddingBatchesPerUser"); }
    public long getOcrMaxBytes() { return ocrMaxBytes; }
    public void setOcrMaxBytes(long value) { ocrMaxBytes = positive(value, "ocrMaxBytes"); }
    public long getReconciliationDelayMillis() { return reconciliationDelayMillis; }
    public void setReconciliationDelayMillis(long value) { reconciliationDelayMillis = positive(value, "reconciliationDelayMillis"); }

    private int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private long positive(long value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
