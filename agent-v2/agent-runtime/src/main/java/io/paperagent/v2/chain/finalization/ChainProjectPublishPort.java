package io.paperagent.v2.chain.finalization;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRuntimePolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Stable publish boundary; every failure result must already name its formal record. */
public interface ChainProjectPublishPort {
    PublishResult publish(PublishCommand command);

    record PublishCommand(
            String taskId,
            String readinessId,
            String finalizationCheckId,
            int attemptNo,
            String idempotencyKey,
            String baseProjectVersion,
            long artifactId,
            String candidateKey,
            String validationId,
            String runtimePolicyVersion,
            String validationRequestDigest,
            String validationReceiptDigest) {
        public PublishCommand {
            taskId = required(taskId, "taskId");
            readinessId = required(readinessId, "readinessId");
            finalizationCheckId = required(
                    finalizationCheckId, "finalizationCheckId");
            ChainRuntimePolicy runtimePolicy = ChainRuntimePolicy
                    .requireVersion(runtimePolicyVersion);
            if (attemptNo < 1 || attemptNo > runtimePolicy
                    .finalizationMechanicalAttemptsTotal()) {
                throw new IllegalArgumentException(
                        "attemptNo is outside finalization policy");
            }
            idempotencyKey = required(idempotencyKey, "idempotencyKey");
            baseProjectVersion = required(
                    baseProjectVersion, "baseProjectVersion");
            if (artifactId < 1) {
                throw new IllegalArgumentException("artifactId must be positive");
            }
            candidateKey = required(candidateKey, "candidateKey");
            validationId = required(validationId, "validationId");
            sha256(validationRequestDigest, "validationRequestDigest");
            sha256(validationReceiptDigest, "validationReceiptDigest");
            if (!idempotencyKey.equals(stableIdempotencyKey(
                    taskId, readinessId, finalizationCheckId, attemptNo,
                    baseProjectVersion, artifactId, candidateKey,
                    validationId, runtimePolicyVersion,
                    validationRequestDigest,
                    validationReceiptDigest))) {
                throw new IllegalArgumentException(
                        "idempotencyKey does not bind publish attempt identity");
            }
        }
    }

    sealed interface PublishResult permits Published, Failed {
    }

    record Published(
            String operationId,
            int attemptNo,
            String idempotencyKey,
            boolean replayed,
            String baseProjectVersion,
            String candidateKey,
            String validationId,
            String publishedProjectVersion,
            long publishedRevisionId,
            String publishReceiptId) implements PublishResult {
        public Published {
            operationId = required(operationId, "operationId");
            if (attemptNo < 1) {
                throw new IllegalArgumentException("attemptNo must be positive");
            }
            idempotencyKey = required(idempotencyKey, "idempotencyKey");
            baseProjectVersion = required(
                    baseProjectVersion, "baseProjectVersion");
            candidateKey = required(candidateKey, "candidateKey");
            validationId = required(validationId, "validationId");
            publishedProjectVersion = required(
                    publishedProjectVersion, "publishedProjectVersion");
            if (publishedRevisionId < 1) {
                throw new IllegalArgumentException(
                        "publishedRevisionId must be positive");
            }
            publishReceiptId = required(
                    publishReceiptId, "publishReceiptId");
        }
    }

    record Failed(
            ErrorCode errorCode,
            String formalFailureRef,
            int attemptNo,
            String idempotencyKey,
            boolean retryable,
            boolean replayed) implements PublishResult {
        public Failed {
            Objects.requireNonNull(errorCode, "errorCode");
            formalFailureRef = required(
                    formalFailureRef, "formalFailureRef");
            if (attemptNo < 1) {
                throw new IllegalArgumentException("attemptNo must be positive");
            }
            idempotencyKey = required(idempotencyKey, "idempotencyKey");
            if (retryable
                    && errorCode != ErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE) {
                throw new IllegalArgumentException(
                        "only temporary publish failure is retryable");
            }
        }
    }

    enum ErrorCode {
        CANDIDATE_BINDING_MISMATCH,
        VALIDATION_BINDING_MISMATCH,
        STALE_VERSION_FENCE,
        VERSION_CONFLICT,
        AUTHORITY_TEMPORARILY_UNAVAILABLE
    }

    record NotRequired(String readinessId) {
        public NotRequired {
            readinessId = required(readinessId, "readinessId");
        }
    }

    static String stableIdempotencyKey(
            String taskId, String readinessId, String finalizationCheckId,
            int attemptNo, String baseProjectVersion, long artifactId,
            String candidateKey, String validationId,
            String runtimePolicyVersion,
            String validationRequestDigest, String validationReceiptDigest) {
        String identity = ChainRuntimePolicy.requireVersion(
                runtimePolicyVersion).policyVersion() + "\0"
                + required(taskId, "taskId") + "\0"
                + required(readinessId, "readinessId") + "\0"
                + required(finalizationCheckId, "finalizationCheckId") + "\0"
                + attemptNo + "\0"
                + required(baseProjectVersion, "baseProjectVersion") + "\0"
                + artifactId + "\0" + required(candidateKey, "candidateKey")
                + "\0" + required(validationId, "validationId") + "\0"
                + required(validationRequestDigest,
                "validationRequestDigest") + "\0"
                + required(validationReceiptDigest,
                "validationReceiptDigest");
        try {
            return "chain-publish." + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void sha256(String value, String name) {
        required(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be lowercase SHA-256");
        }
    }
}
