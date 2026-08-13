package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.context.ChainContextVersionMatrix;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Pure constructors shared by the small product authority projectors. */
public final class ProductChainContextProjectionSupport {
    private ProductChainContextProjectionSupport() {
    }

    public static ProductChainContextAuthorityProjection present(
            ChainContextModule module,
            Map<String, ChainContextValue> sourceVersion,
            Map<String, ChainContextValue> readBoundary,
            String projectionVersion,
            String paginationVersion,
            Map<String, ChainContextValue> projectionParameters,
            Map<String, ChainContextValue> projectionFields,
            String... requiredFields) {
        requireFields(module, projectionFields, requiredFields);
        return new ProductChainContextAuthorityProjection(
                ChainContextModuleStatus.PRESENT,
                sourceVersion,
                readBoundary,
                projectionVersion,
                paginationVersion,
                projectionParameters,
                projectionFields,
                null);
    }

    public static ProductChainContextAuthorityProjection empty(
            ChainContextModule module,
            Map<String, ChainContextValue> sourceVersion,
            Map<String, ChainContextValue> readBoundary,
            String projectionVersion,
            String paginationVersion,
            Map<String, ChainContextValue> projectionParameters,
            String emptyWatermark) {
        Objects.requireNonNull(module, "module");
        ChainContextVersionMatrix.VersionRequirement requirement =
                ChainContextVersionMatrix.requirement(module);
        if (!requirement.emptyAllowed()) {
            throw blocked(module, "EMPTY authority is not permitted");
        }
        if (!requirement.emptyWatermark().equals(emptyWatermark)) {
            throw blocked(module, "EMPTY authority watermark is invalid");
        }
        return new ProductChainContextAuthorityProjection(
                ChainContextModuleStatus.EMPTY,
                sourceVersion,
                readBoundary,
                projectionVersion,
                paginationVersion,
                projectionParameters,
                Map.of(),
                emptyWatermark);
    }

    public static Map<String, ChainContextValue> requireFields(
            ChainContextModule module,
            Map<String, ChainContextValue> projectionFields,
            String... requiredFields) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(projectionFields, "projectionFields");
        Objects.requireNonNull(requiredFields, "requiredFields");
        Arrays.stream(requiredFields).forEach(field -> {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException(
                        "required field names must not be blank");
            }
            if (!projectionFields.containsKey(field)
                    || projectionFields.get(field) == null) {
                throw blocked(module,
                        "required authority field is unavailable: " + field);
            }
        });
        return projectionFields;
    }

    public static ChainContextException blocked(
            ChainContextModule module, String safeReason) {
        Objects.requireNonNull(module, "module");
        if (safeReason == null || safeReason.isBlank()) {
            throw new IllegalArgumentException("safeReason must not be blank");
        }
        return new ChainContextException(
                ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                module,
                module.wireName() + " projection blocked: " + safeReason);
    }

    /**
     * Marks an expected missing or ambiguous business input as a durable
     * Context build failure. Authority corruption and version drift must keep
     * using {@link #blocked(ChainContextModule, String)} so they propagate
     * instead of being converted into a workflow failure.
     */
    public static ChainContextException formalBuildBlocked(
            ChainContextModule module, String safeReason) {
        Objects.requireNonNull(module, "module");
        if (safeReason == null || safeReason.isBlank()) {
            throw new IllegalArgumentException("safeReason must not be blank");
        }
        return new ChainContextException(
                ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                module,
                ChainContextException.FailureDisposition.FORMAL_BUILD_BLOCK,
                module.wireName() + " projection blocked: " + safeReason);
    }
}
