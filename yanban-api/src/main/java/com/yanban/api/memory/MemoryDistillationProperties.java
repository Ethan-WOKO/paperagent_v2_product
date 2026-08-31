package com.yanban.api.memory;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.memory.distillation")
public class MemoryDistillationProperties {
    private boolean enabled = true;
    private Duration interval = Duration.ofHours(24);
    private Duration claimLease = Duration.ofMinutes(3);
    private Duration modelTimeout = Duration.ofSeconds(90);
    private long scanMillis = 2_000L;
    private int messageBatchSize = 80;
    private int maxInputCharacters = 24_000;
    private int maxCandidates = 12;
    private int maxAttempts = 3;
    private BigDecimal minScopeConfidence = new BigDecimal("0.75");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getInterval() { return interval; }
    public void setInterval(Duration interval) { this.interval = positive(interval, "interval"); }
    public Duration getClaimLease() { return claimLease; }
    public void setClaimLease(Duration claimLease) { this.claimLease = positive(claimLease, "claimLease"); }
    public Duration getModelTimeout() { return modelTimeout; }
    public void setModelTimeout(Duration modelTimeout) { this.modelTimeout = positive(modelTimeout, "modelTimeout"); }
    public long getScanMillis() { return scanMillis; }
    public void setScanMillis(long scanMillis) {
        if (scanMillis < 250L) throw new IllegalArgumentException("scanMillis must be at least 250");
        this.scanMillis = scanMillis;
    }
    public int getMessageBatchSize() { return messageBatchSize; }
    public void setMessageBatchSize(int messageBatchSize) {
        if (messageBatchSize < 1 || messageBatchSize > 200) {
            throw new IllegalArgumentException("messageBatchSize must be between 1 and 200");
        }
        this.messageBatchSize = messageBatchSize;
    }
    public int getMaxInputCharacters() { return maxInputCharacters; }
    public void setMaxInputCharacters(int maxInputCharacters) {
        if (maxInputCharacters < 1_000 || maxInputCharacters > 100_000) {
            throw new IllegalArgumentException("maxInputCharacters must be between 1000 and 100000");
        }
        this.maxInputCharacters = maxInputCharacters;
    }
    public int getMaxCandidates() { return maxCandidates; }
    public void setMaxCandidates(int maxCandidates) {
        if (maxCandidates < 1 || maxCandidates > 30) {
            throw new IllegalArgumentException("maxCandidates must be between 1 and 30");
        }
        this.maxCandidates = maxCandidates;
    }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
        }
        this.maxAttempts = maxAttempts;
    }
    public BigDecimal getMinScopeConfidence() { return minScopeConfidence; }
    public void setMinScopeConfidence(BigDecimal minScopeConfidence) {
        if (minScopeConfidence == null
                || minScopeConfidence.compareTo(BigDecimal.ZERO) < 0
                || minScopeConfidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("minScopeConfidence must be between 0 and 1");
        }
        this.minScopeConfidence = minScopeConfidence;
    }

    private Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
