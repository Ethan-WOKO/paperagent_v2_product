package com.yanban.agent.v2.adapter.taskframe;

import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.runtime.taskframe.DeterministicTaskFrameFreezer;
import io.paperagent.v2.runtime.taskframe.TaskFrameFreezeRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Pure product-to-V2 intake composition with no ambient state or persistence.
 */
public final class DeterministicProductTaskFrameAdapter {
    private static final String TASK_FRAME_ID_PREFIX = "product.";

    private final DeterministicTaskFrameFreezer freezer;

    public DeterministicProductTaskFrameAdapter() {
        this.freezer = new DeterministicTaskFrameFreezer();
    }

    public ProductTaskFrameBinding bind(ProductTaskFrameIntakeRequest request) {
        ProductTaskFramePreparation preparation = prepare(request);
        return new ProductTaskFrameBinding(
                preparation.identity(),
                freezer.freeze(preparation.freezeRequest()));
    }

    public ProductTaskFramePreparation prepare(ProductTaskFrameIntakeRequest request) {
        ProductTaskFrameIntakeRequest requiredRequest = required(
                request,
                "request");
        AgentRunIdentity identity = required(
                requiredRequest.identity(),
                "request.identity");
        requirePositive(identity.userId(), "request.identity.userId");
        requireOptionalPositive(identity.sessionId(), "request.identity.sessionId");

        Optional<ProjectVersionRef> sourceProjectVersion = projectVersion(
                identity.projectId(),
                required(
                        requiredRequest.projectVersionId(),
                        "request.projectVersionId"));

        TaskFrameFreezeRequest freezeRequest = new TaskFrameFreezeRequest(
                requiredRequest.routingDecision(),
                taskFrameId(identity),
                requiredRequest.draft(),
                sourceProjectVersion,
                requiredRequest.executionProfile(),
                requiredRequest.createdAt());
        return new ProductTaskFramePreparation(identity, freezeRequest);
    }

    private static Optional<ProjectVersionRef> projectVersion(
            Long projectId,
            Optional<String> projectVersionId) {
        if (projectId == null) {
            if (projectVersionId.isPresent()) {
                fail(
                        ProductTaskFrameValidationCode.PROJECT_VERSION_UNEXPECTED,
                        "request.projectVersionId",
                        "project version must be empty when projectId is absent");
            }
            return Optional.empty();
        }

        requirePositive(projectId, "request.identity.projectId");
        if (projectVersionId.isEmpty()) {
            fail(
                    ProductTaskFrameValidationCode.PROJECT_VERSION_MISSING,
                    "request.projectVersionId",
                    "project version is required when projectId is present");
        }
        String versionId = projectVersionId.orElseThrow();
        if (versionId.isBlank()) {
            fail(
                    ProductTaskFrameValidationCode.PROJECT_VERSION_BLANK,
                    "request.projectVersionId",
                    "project version must not be blank");
        }
        return Optional.of(new ProjectVersionRef(
                String.valueOf(projectId),
                versionId));
    }

    private static TaskFrameId taskFrameId(AgentRunIdentity identity) {
        return new TaskFrameId(TASK_FRAME_ID_PREFIX + sha256(identity.runId()));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK must provide SHA-256", exception);
        }
    }

    private static <T> T required(T value, String path) {
        if (value == null) {
            fail(
                    ProductTaskFrameValidationCode.REQUIRED_VALUE_MISSING,
                    path,
                    "value is required");
        }
        return value;
    }

    private static void requirePositive(Long value, String path) {
        if (value == null) {
            fail(
                    ProductTaskFrameValidationCode.REQUIRED_VALUE_MISSING,
                    path,
                    "value is required");
        }
        if (value <= 0) {
            fail(
                    ProductTaskFrameValidationCode.NON_POSITIVE_ID,
                    path,
                    "value must be positive");
        }
    }

    private static void requireOptionalPositive(Long value, String path) {
        if (value != null) {
            requirePositive(value, path);
        }
    }

    private static void fail(
            ProductTaskFrameValidationCode code,
            String path,
            String message) {
        throw new ProductTaskFrameValidationException(code, path, message);
    }
}
