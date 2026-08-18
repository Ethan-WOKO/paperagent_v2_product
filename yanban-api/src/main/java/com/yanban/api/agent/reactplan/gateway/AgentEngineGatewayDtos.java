package com.yanban.api.agent.reactplan.gateway;

import com.fasterxml.jackson.databind.JsonNode;
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
    public record WorkspaceWriteRequest(String contractVersion, String clientRequestId,
                                        String requestDigest, String operation, String path,
                                        String baseSha256, String content) { }
    public record WorkspaceWriteResult(String contractVersion, String clientRequestId,
                                       String requestDigest, boolean replayed, String operation,
                                       String path, String beforeSha256, String afterSha256,
                                       long sizeBytes) { }
    public record WorkspaceDiffEntry(String operation, String path,
                                     String beforeSha256, String afterSha256) { }
    public record WorkspaceDiff(String contractVersion, String taskId, String projectVersion,
                                boolean changed, List<WorkspaceDiffEntry> entries) { }
    public record WorkspacePublishRequest(String contractVersion, String clientRequestId,
                                          String requestDigest, String receiptRef,
                                          List<WorkspaceDiffEntry> entries) { }
    public record WorkspacePublishResult(String contractVersion, String clientRequestId,
                                         String requestDigest, long operationId,
                                         String baseProjectVersion,
                                         String publishedProjectVersion,
                                         long publishedRevisionId, String receiptRef) { }
    public record SandboxInput(String path, String sha256) { }
    public record SandboxSubmit(String contractVersion, String clientRequestId, String requestDigest,
                                List<String> argv, List<SandboxInput> inputs, long timeoutMillis) { }
    public record SandboxCancelRequest(String contractVersion) { }
    public record SandboxView(String contractVersion, String clientRequestId, String requestDigest,
                              String executionRef, String state, String receiptRef) { }
    public record BoundedOutput(String text, boolean truncated, long originalBytes) { }
    public record ReceiptInput(String path, String sha256, long sizeBytes) { }
    public record Receipt(String contractVersion, String receiptRef, String executionRef,
                          String status, Integer exitCode, BoundedOutput stdout, BoundedOutput stderr,
                          String inputFingerprint, List<ReceiptInput> inputs,
                          Instant startedAt, Instant finishedAt) { }
    public record RegisteredToolFunction(String name, String description, JsonNode parameters) { }
    public record RegisteredToolSpec(String type, RegisteredToolFunction function) { }
    public record RegisteredToolCatalog(String contractVersion, String taskId,
                                        String projectVersion, String catalogDigest,
                                        List<RegisteredToolSpec> tools) { }
    public record RegisteredToolCall(String contractVersion, String callId,
                                     String toolName, JsonNode arguments,
                                     String requestDigest) { }
    public record RegisteredToolResult(String contractVersion, String callId,
                                       String toolName, String requestDigest,
                                       boolean success, JsonNode output,
                                       String errorCode, String errorMessage,
                                       boolean retryable, List<String> evidenceRefs,
                                       String version) { }
    public record ModelToolCall(String id, String name, String arguments) { }
    public record ModelMessage(String role, String content, String toolCallId,
                               List<ModelToolCall> toolCalls) { }
    public record ModelToolFunction(String name, String description, JsonNode parameters) { }
    public record ModelToolSpec(String type, ModelToolFunction function) { }
    public record ModelCompletionRequest(String contractVersion, String clientRequestId,
                                         String requestDigest, String provider, String model,
                                         List<ModelMessage> messages, List<ModelToolSpec> tools,
                                         int maxOutputTokens) { }
    public record ModelUsage(int promptTokens, int completionTokens) { }
    public record ModelCompletionResult(String contractVersion, String clientRequestId,
                                        String requestDigest, String content,
                                        List<ModelToolCall> toolCalls, String finishReason,
                                        ModelUsage usage, boolean replayed) { }
    public record Problem(String contractVersion, String code, String category,
                          String message, boolean retryable) { }
}
