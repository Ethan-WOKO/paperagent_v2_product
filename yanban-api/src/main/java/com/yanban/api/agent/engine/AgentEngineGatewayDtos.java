package com.yanban.api.agent.engine;

import java.time.Instant;
import java.util.List;

public final class AgentEngineGatewayDtos {
    private AgentEngineGatewayDtos() { }

    public record FileEntry(String path, long sizeBytes, String sha256, String mediaType) { }
    public record FileList(String contractVersion, String taskId, String projectVersion,
                           List<FileEntry> files) { }
    public record FileReadRequest(String contractVersion, String path, String expectedSha256) { }
    public record FileRead(String contractVersion, String path, long sizeBytes, String sha256,
                           String mediaType, String encoding, String content, boolean truncated) { }
    public record SandboxInput(String path, String sha256) { }
    public record SandboxSubmit(String contractVersion, String clientRequestId, String requestDigest,
                                List<String> argv, List<SandboxInput> inputs, long timeoutMillis) { }
    public record SandboxView(String contractVersion, String clientRequestId, String requestDigest,
                              String executionRef, String state, String receiptRef) { }
    public record BoundedOutput(String text, boolean truncated, long originalBytes) { }
    public record ReceiptInput(String path, String sha256, long sizeBytes) { }
    public record Receipt(String contractVersion, String receiptRef, String executionRef,
                          String status, Integer exitCode, BoundedOutput stdout, BoundedOutput stderr,
                          String inputFingerprint, List<ReceiptInput> inputs,
                          Instant startedAt, Instant finishedAt) { }
    public record Problem(String contractVersion, String code, String category,
                          String message, boolean retryable) { }
}
