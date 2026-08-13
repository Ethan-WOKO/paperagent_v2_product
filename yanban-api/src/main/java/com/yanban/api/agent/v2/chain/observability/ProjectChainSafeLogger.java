package com.yanban.api.agent.v2.chain.observability;

import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Ordinary chain logs expose metadata only; body-bearing values are opaque. */
@Component
public final class ProjectChainSafeLogger {
    private static final Set<String> FINISH_REASONS = Set.of(
            "COMMITTED", "STOP", "LENGTH", "TOOL_CALLS",
            "CONTENT_FILTER", "ERROR", "NONE");
    private static final Set<String> ERROR_CATEGORIES = Set.of(
            "COMMAND_REJECTED", "COMMAND_FAILED", "RECOVERY_FAILED",
            "MODEL_FAILED", "DELIVERY_FAILED", "VALIDATION_FAILED",
            "INTERNAL_ERROR", "NONE");
    private final Logger log;

    public ProjectChainSafeLogger() {
        this(LoggerFactory.getLogger(ProjectChainSafeLogger.class));
    }

    ProjectChainSafeLogger(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    public void info(SafeEvent event, SensitiveBodies bodies) {
        Objects.requireNonNull(bodies, "bodies");
        log.info("chain event completed requestId={} correlationId={} taskId={} planId={} stepId={} invocationId={} provider={} model={} messageCount={} toolCount={} referenceCount={} contextRevisionId={} inputCharacters={} outputCharacters={} finishReason={} durationMs={}",
                event.requestId(), event.correlationId(), event.taskId(),
                event.planId(), event.stepId(), event.invocationId(),
                event.provider(), event.model(), event.messageCount(),
                event.toolCount(), event.referenceCount(),
                event.contextRevisionId(), bodies.inputCharacters(),
                bodies.outputCharacters(), safeFinishReason(
                        event.finishReason()),
                event.durationMs());
    }

    public void warn(
            SafeEvent event, String errorCategory,
            SensitiveBodies bodies, Throwable unsafeCause) {
        Objects.requireNonNull(bodies, "bodies");
        log.warn("chain event warning requestId={} correlationId={} taskId={} planId={} stepId={} invocationId={} errorCategory={} failureType={} workspaceVersion={} candidateVersion={} validationVersion={}",
                event.requestId(), event.correlationId(), event.taskId(),
                event.planId(), event.stepId(), event.invocationId(),
                safeErrorCategory(errorCategory), failureType(unsafeCause),
                event.workspaceVersion(), event.candidateVersion(),
                event.validationVersion());
    }

    public void error(
            SafeEvent event, String errorCategory,
            SensitiveBodies bodies, Throwable unsafeCause) {
        Objects.requireNonNull(bodies, "bodies");
        log.error("chain event failed requestId={} correlationId={} taskId={} planId={} stepId={} invocationId={} errorCategory={} failureType={} durationMs={}",
                event.requestId(), event.correlationId(), event.taskId(),
                event.planId(), event.stepId(), event.invocationId(),
                safeErrorCategory(errorCategory), failureType(unsafeCause),
                event.durationMs());
    }

    private static String failureType(Throwable value) {
        return value == null ? "NONE" : value.getClass().getSimpleName();
    }

    private static String safeFinishReason(String value) {
        String normalized = token(value);
        return FINISH_REASONS.contains(normalized) ? normalized : "OTHER";
    }

    private static String safeErrorCategory(String value) {
        String normalized = token(value);
        return ERROR_CATEGORIES.contains(normalized) ? normalized : "OTHER";
    }

    private static String token(String value) {
        return value == null || value.isBlank()
                ? "NONE" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public record SafeEvent(
            String requestId,
            String correlationId,
            String taskId,
            String planId,
            String stepId,
            String invocationId,
            String provider,
            String model,
            int messageCount,
            int toolCount,
            int referenceCount,
            String contextRevisionId,
            String finishReason,
            long durationMs,
            String workspaceVersion,
            String candidateVersion,
            String validationVersion) {
    }

    /**
     * Values in this record may contain user or provider bodies. Only counts
     * are observable; no logger call may format the record or any field.
     */
    public record SensitiveBodies(
            String modelInput,
            String rawResponse,
            String acceptedBody,
            String candidate,
            String toolArguments,
            String stdout,
            String stderr,
            String memoryAndRag) {
        int inputCharacters() {
            return length(modelInput) + length(toolArguments)
                    + length(memoryAndRag);
        }

        int outputCharacters() {
            return length(rawResponse) + length(acceptedBody)
                    + length(candidate) + length(stdout) + length(stderr);
        }

        private static int length(String value) {
            return value == null ? 0 : value.length();
        }
    }
}
