package io.paperagent.v2.chain.state;

import io.paperagent.v2.chain.ChainPermissionDecision;
import io.paperagent.v2.chain.ChainPermissionDecisionWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** The sole runtime authority that turns a verified product decision into a permission fact. */
public final class ChainPermissionDecisionRuntime {
    private final ChainPermissionDecisionWriter decisions;
    private final ProductAuthorityVerifier authorityVerifier;

    public ChainPermissionDecisionRuntime(
            ChainPermissionDecisionWriter decisions,
            ProductAuthorityVerifier authorityVerifier) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.authorityVerifier = Objects.requireNonNull(
                authorityVerifier, "authorityVerifier");
    }

    public CommitResult commit(ProductDecisionRequest request) {
        Objects.requireNonNull(request, "request");
        authorityVerifier.verify(request);
        String decisionId = "permission." + sha256(
                request.taskId() + "\0" + request.gapId() + "\0"
                        + request.permissionScope() + "\0"
                        + request.authority().authorityType() + "\0"
                        + request.authority().authorityRef());
        ChainPersistenceRecords.PermissionDecisionRecord requested =
                new ChainPersistenceRecords.PermissionDecisionRecord(
                        decisionId, request.taskId(), request.eventId(), request.gapId(),
                        request.permissionScope(), request.authority().authorityType(),
                        request.authority().authorityRef(), request.decision(),
                        request.reason(), request.createdAt());
        String sourceIdentity = sha256(
                request.authority().authorityType() + "\0"
                        + request.authority().authorityRef());
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        request.eventId(), request.taskId(), "PERMISSION_DECISION", null,
                        sourceIdentity, request.createdAt());
        ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.PermissionDecisionRecord> appended =
                decisions.appendPermissionDecision(
                        new ChainPersistenceRecords.AuthoritativeFact<>(event, requested));
        require(samePermissionDecision(appended.fact(), requested),
                "PermissionDecision append/replay changed immutable contents");
        requireEvent(event, appended.event());
        return new CommitResult(appended.fact(), appended.replayed());
    }

    private static void requireEvent(
            ChainPersistenceRecords.AuthorityEventRequest requested,
            ChainPersistenceRecords.AuthorityEventRecord stored) {
        require(stored.eventId().equals(requested.eventId())
                        && stored.taskId().equals(requested.taskId())
                        && stored.eventType().equals(requested.eventType())
                        && Objects.equals(stored.transitionId(), requested.transitionId())
                        && stored.sourceIdentitySha256().equals(requested.sourceIdentitySha256()),
                "PermissionDecision authority event changed immutable contents");
    }

    private static boolean samePermissionDecision(
            ChainPersistenceRecords.PermissionDecisionRecord stored,
            ChainPersistenceRecords.PermissionDecisionRecord requested) {
        return stored.permissionDecisionId().equals(requested.permissionDecisionId())
                && stored.taskId().equals(requested.taskId())
                && stored.eventId().equals(requested.eventId())
                && stored.gapId().equals(requested.gapId())
                && stored.permissionScope().equals(requested.permissionScope())
                && stored.productAuthorityType().equals(requested.productAuthorityType())
                && stored.productAuthorityRef().equals(requested.productAuthorityRef())
                && stored.decision() == requested.decision()
                && stored.reason().equals(requested.reason());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record ProductAuthority(String authorityType, String authorityRef) {
        public ProductAuthority {
            required(authorityType, "authorityType");
            required(authorityRef, "authorityRef");
        }
    }

    public record ProductDecisionRequest(
            String taskId,
            String gapId,
            String eventId,
            String permissionScope,
            ProductAuthority authority,
            ChainPermissionDecision decision,
            String reason,
            Instant createdAt) {
        public ProductDecisionRequest {
            required(taskId, "taskId");
            required(gapId, "gapId");
            required(eventId, "eventId");
            required(permissionScope, "permissionScope");
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(decision, "decision");
            required(reason, "reason");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record CommitResult(
            ChainPersistenceRecords.PermissionDecisionRecord decision,
            boolean replayed) {
        public CommitResult {
            Objects.requireNonNull(decision, "decision");
        }
    }

    /** Verifies the product-owned ACL/consent fact; model proposals are not valid authorities. */
    public interface ProductAuthorityVerifier {
        void verify(ProductDecisionRequest request);
    }
}
