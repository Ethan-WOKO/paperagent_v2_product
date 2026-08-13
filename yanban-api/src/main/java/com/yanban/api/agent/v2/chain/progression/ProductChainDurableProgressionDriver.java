package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.recovery.ProductChainReceivedCommandSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Pages durable work and grants one claimed progression action per item.
 * Role selection, recovery semantics and scheduling frequency belong to the
 * injected progression owners rather than this driver.
 */
public final class ProductChainDurableProgressionDriver {
    private final ReceivedScanSource received;
    private final ProductChainProgressionClaimStore claims;
    private final ReceivedProgression receivedProgression;
    private final TaskProgression taskProgression;
    private final ClaimTokenSource tokens;
    private final ClaimLeaseKeeper leaseKeeper;
    private final Clock clock;

    public ProductChainDurableProgressionDriver(
            ProductChainReceivedCommandSource received,
            ProductChainProgressionClaimStore claims,
            ReceivedProgression receivedProgression,
            TaskProgression taskProgression,
            ClaimTokenSource tokens,
            ClaimLeaseKeeper leaseKeeper,
            Clock clock) {
        this(received::scan, claims, receivedProgression, taskProgression,
                tokens, leaseKeeper, clock);
    }

    ProductChainDurableProgressionDriver(
            ReceivedScanSource received,
            ProductChainProgressionClaimStore claims,
            ReceivedProgression receivedProgression,
            TaskProgression taskProgression,
            ClaimTokenSource tokens,
            Clock clock) {
        this(received, claims, receivedProgression, taskProgression, tokens,
                (claim, lifetime, action) -> action.run(), clock);
    }

    ProductChainDurableProgressionDriver(
            ReceivedScanSource received,
            ProductChainProgressionClaimStore claims,
            ReceivedProgression receivedProgression,
            TaskProgression taskProgression,
            ClaimTokenSource tokens,
            ClaimLeaseKeeper leaseKeeper,
            Clock clock) {
        this.received = Objects.requireNonNull(received, "received");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.receivedProgression = Objects.requireNonNull(
                receivedProgression, "receivedProgression");
        this.taskProgression = Objects.requireNonNull(
                taskProgression, "taskProgression");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.leaseKeeper = Objects.requireNonNull(
                leaseKeeper, "leaseKeeper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TickResult advance(TickRequest request) {
        Objects.requireNonNull(request, "request");
        MutableResult result = new MutableResult();
        Set<String> attemptedTasks = new HashSet<>();
        advanceReceived(request, result, attemptedTasks);
        advanceCommittedTasks(request, result, attemptedTasks);
        return result.freeze();
    }

    private void advanceReceived(
            TickRequest request, MutableResult result,
            Set<String> attemptedTasks) {
        ProductChainReceivedCommandSource.ScanCursor cursor = null;
        boolean hasMore;
        do {
            ProductChainReceivedCommandSource.ScanPage page = received.scan(
                    cursor, request.receivedPageSize());
            Objects.requireNonNull(page, "received scan page");
            for (ProductChainReceivedCommandSource.ScanResult entry
                    : page.entries()) {
                result.scanned++;
                if (entry instanceof ProductChainReceivedCommandSource.Ready ready) {
                    progress(ready.command().taskId(), request, result,
                            attemptedTasks,
                            claim -> receivedProgression.advance(
                                    ready.command(), claim));
                } else {
                    result.skipped++;
                }
            }
            hasMore = page.hasMore();
            if (hasMore && (page.entries().isEmpty()
                    || page.nextCursor() == null
                    || page.nextCursor().equals(cursor))) {
                throw new IllegalStateException(
                        "received scan did not advance its cursor");
            }
            cursor = page.nextCursor();
        } while (hasMore);
    }

    private void advanceCommittedTasks(
            TickRequest request, MutableResult result,
            Set<String> attemptedTasks) {
        ProductChainProgressionClaimStore.CommittedTaskCursor cursor = null;
        while (true) {
            ProductChainProgressionClaimStore.CommittedTaskPage page = claims
                    .scanCommittedRootTasks(cursor,
                            request.committedTaskPageSize());
            Objects.requireNonNull(page, "committed task page");
            if (page.taskIds().isEmpty()) return;
            for (String taskId : page.taskIds()) {
                result.scanned++;
                progress(taskId, request, result, attemptedTasks,
                        claim -> taskProgression.advance(taskId, claim));
            }
            if (page.nextCursor() == null
                    || page.nextCursor().equals(cursor)) {
                throw new IllegalStateException(
                        "committed task scan did not advance its cursor");
            }
            cursor = page.nextCursor();
        }
    }

    private void progress(
            String taskId,
            TickRequest request,
            MutableResult result,
            Set<String> attemptedTasks,
            ClaimedAction action) {
        if (!attemptedTasks.add(taskId)) {
            result.skipped++;
            return;
        }
        ProductChainProgressionClaim acquired = null;
        boolean releaseOnExit = true;
        try {
            String token = required(tokens.newToken(taskId), "claimToken");
            Instant expiresAt = clock.instant().plus(request.claimLifetime());
            ProductChainProgressionClaimStore.AcquireResult acquisition =
                    claims.acquire(taskId, request.ownerId(), token, expiresAt);
            if (acquisition.status()
                    != ProductChainProgressionClaimStore.AcquireStatus.ACQUIRED) {
                result.skipped++;
                return;
            }
            acquired = acquisition.claim();
            if (current(acquired)
                    != ProductChainProgressionClaimStore.CurrentResult.CURRENT) {
                result.skipped++;
                return;
            }
            Instant renewedExpiry = clock.instant()
                    .plus(request.claimLifetime());
            if (renewedExpiry.isBefore(acquired.expiresAt())) {
                renewedExpiry = acquired.expiresAt();
            }
            ProductChainProgressionClaimStore.RenewResult renewal =
                    claims.renew(taskId, acquired.ownerId(),
                            acquired.claimToken(), acquired.fence(),
                            renewedExpiry);
            if (renewal.status()
                    != ProductChainProgressionClaimStore.RenewStatus.RENEWED
                    && renewal.status()
                    != ProductChainProgressionClaimStore.RenewStatus.REPLAYED) {
                result.skipped++;
                return;
            }
            ProductChainProgressionClaim currentClaim = renewal.claim();
            leaseKeeper.runProtected(
                    currentClaim, request.claimLifetime(),
                    () -> action.advance(currentClaim));
            if (current(currentClaim)
                    != ProductChainProgressionClaimStore.CurrentResult.CURRENT) {
                releaseOnExit = false;
                result.fail(taskId,
                        "progression claim changed during action",
                        FailureKind.RECOVERABLE_STATE);
                return;
            }
            ProductChainProgressionClaimStore.ProgressDisposition progress =
                    claims.recordProgress(taskId,
                            currentClaim.authorityEventCut());
            if (progress == ProductChainProgressionClaimStore
                    .ProgressDisposition.BLOCKED) {
                result.fail(taskId,
                        "task blocked after repeated no-progress replan",
                        FailureKind.TERMINAL_STATE);
            }
            result.advanced++;
        } catch (RuntimeException failure) {
            // Keep a failed claim active until expiry. Releasing it without a
            // newer authority cut would make the durable scan treat the task
            // as a stable wait and suppress the required retry.
            String reason = Objects.toString(
                    failure.getMessage(), failure.getClass().getSimpleName());
            FailureKind kind = classify(failure, reason);
            if (acquired == null) {
                releaseOnExit = false;
            } else {
                var disposition = claims.recordFailure(
                        taskId, acquired.authorityEventCut(), sha256(reason),
                        reason, kind == FailureKind.TERMINAL_STATE
                                || kind == FailureKind.BUG);
                releaseOnExit = disposition == ProductChainProgressionClaimStore
                        .FailureDisposition.BLOCKED;
            }
            result.fail(taskId, reason, kind);
        } finally {
            if (acquired != null && releaseOnExit) {
                try {
                    ProductChainProgressionClaimStore.ReleaseResult released =
                            claims.release(acquired.taskId(), acquired.ownerId(),
                                    acquired.claimToken(), acquired.fence());
                    if (released
                            != ProductChainProgressionClaimStore.ReleaseResult.RELEASED) {
                        result.fail(taskId,
                                "claim release was not authoritative: "
                                        + released.name(),
                                FailureKind.RECOVERABLE_STATE);
                    }
                } catch (RuntimeException releaseFailure) {
                    result.fail(taskId, "claim release failed: "
                            + Objects.toString(releaseFailure.getMessage(),
                            releaseFailure.getClass().getSimpleName()),
                            FailureKind.TRANSIENT);
                }
            }
        }
    }

    static FailureKind classify(RuntimeException failure, String reason) {
        String value = Objects.toString(reason, "")
                .toUpperCase(java.util.Locale.ROOT);
        String type = failure.getClass().getSimpleName()
                .toUpperCase(java.util.Locale.ROOT);
        if (value.contains("TIMEOUT") || value.contains("TIMED OUT")
                || value.contains("TEMPORARILY UNAVAILABLE")
                || value.contains("CONNECTION")
                || value.contains("DATABASE UNAVAILABLE")
                || type.contains("TRANSIENT")
                || type.contains("TIMEOUT")) {
            return FailureKind.TRANSIENT;
        }
        if (value.contains("CONTEXT_INPUT_BLOCKED")
                || value.contains("PROJECTION BLOCKED:")
                || value.contains("CONTEXT INPUT IS BLOCKED")
                || value.matches(".*CHAIN_[A-Z0-9_]*CONTEXT_BLOCKED.*")
                || value.startsWith("CHAIN_ANSWER_")
                || value.startsWith("CHAIN_TERMINAL_")
                || value.contains("MISSING_OR_AMBIGUOUS")) {
            return FailureKind.TERMINAL_STATE;
        }
        if (failure instanceof IllegalArgumentException
                || failure instanceof NullPointerException) {
            return FailureKind.BUG;
        }
        return FailureKind.RECOVERABLE_STATE;
    }

    public enum FailureKind {
        TRANSIENT,
        RECOVERABLE_STATE,
        TERMINAL_STATE,
        BUG
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private ProductChainProgressionClaimStore.CurrentResult current(
            ProductChainProgressionClaim claim) {
        return claims.assertCurrent(claim.taskId(), claim.ownerId(),
                claim.claimToken(), claim.fence());
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @FunctionalInterface
    interface ReceivedScanSource {
        ProductChainReceivedCommandSource.ScanPage scan(
                ProductChainReceivedCommandSource.ScanCursor after,
                int limit);
    }

    @FunctionalInterface
    public interface ReceivedProgression {
        void advance(
                ProductChainReceivedCommandSource.ReceivedCommand command,
                ProductChainProgressionClaim claim);
    }

    @FunctionalInterface
    public interface TaskProgression {
        void advance(String taskId, ProductChainProgressionClaim claim);
    }

    @FunctionalInterface
    public interface ClaimTokenSource {
        String newToken(String taskId);
    }

    @FunctionalInterface
    public interface ClaimLeaseKeeper {
        /** Keeps the exact claim current for the full duration of one action. */
        void runProtected(
                ProductChainProgressionClaim claim,
                Duration claimLifetime,
                Runnable action);
    }

    @FunctionalInterface
    private interface ClaimedAction {
        void advance(ProductChainProgressionClaim claim);
    }

    public record TickRequest(
            String ownerId,
            Duration claimLifetime,
            int receivedPageSize,
            int committedTaskPageSize) {
        public TickRequest {
            required(ownerId, "ownerId");
            Objects.requireNonNull(claimLifetime, "claimLifetime");
            if (claimLifetime.isZero() || claimLifetime.isNegative()) {
                throw new IllegalArgumentException(
                        "claimLifetime must be positive");
            }
            if (receivedPageSize < 1 || committedTaskPageSize < 1) {
                throw new IllegalArgumentException(
                        "page sizes must be positive");
            }
        }
    }

    public record TickFailure(
            String taskId, String reason, FailureKind kind) {
        public TickFailure {
            required(taskId, "taskId");
            required(reason, "reason");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record TickResult(
            int scanned,
            int advanced,
            int skipped,
            List<TickFailure> failures) {
        public TickResult {
            if (scanned < 0 || advanced < 0 || skipped < 0) {
                throw new IllegalArgumentException(
                        "tick counts must not be negative");
            }
            failures = List.copyOf(Objects.requireNonNull(
                    failures, "failures"));
        }
    }

    private static final class MutableResult {
        private int scanned;
        private int advanced;
        private int skipped;
        private final List<TickFailure> failures = new ArrayList<>();

        private void fail(
                String taskId, String reason, FailureKind kind) {
            failures.add(new TickFailure(taskId, reason, kind));
        }

        private TickResult freeze() {
            return new TickResult(scanned, advanced, skipped, failures);
        }
    }
}
