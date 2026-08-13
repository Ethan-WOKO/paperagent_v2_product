package com.yanban.api.agent.v2.chain.progression;

import java.time.Instant;
import java.util.List;

/** Product persistence boundary used by a future progression driver. */
public interface ProductChainProgressionClaimStore {
    AcquireResult acquire(
            String taskId, String ownerId, String claimToken,
            Instant expiresAt);

    RenewResult renew(
            String taskId, String ownerId, String claimToken, long fence,
            Instant expiresAt);

    CurrentResult assertCurrent(
            String taskId, String ownerId, String claimToken, long fence);

    ReleaseResult release(
            String taskId, String ownerId, String claimToken, long fence);

    /**
     * Pages public root Tasks whose latest claim permits another attempt.
     * A Task is eligible only before its first claim, after a released claim
     * when a newer authority event exists, or after an unreleased claim has
     * expired.
     */
    CommittedTaskPage scanCommittedRootTasks(
            CommittedTaskCursor afterExclusive, int limit);

    /** Records one failed scheduler attempt at its exact authority cut. */
    default FailureDisposition recordFailure(
            String taskId, long authorityEventCut, String failureSha256,
            String reason, boolean deterministic) {
        return FailureDisposition.RETRY;
    }

    /** Clears transient failure history or blocks a repeated post-replan loop. */
    default ProgressDisposition recordProgress(
            String taskId, long previousAuthorityEventCut) {
        return ProgressDisposition.RUNNABLE;
    }

    enum AcquireStatus {
        ACQUIRED,
        ACTIVE_CLAIM,
        STOPPED,
        TASK_NOT_FOUND
    }

    enum FailureDisposition { RETRY, BLOCKED }

    enum ProgressDisposition { RUNNABLE, BLOCKED }

    record AcquireResult(
            AcquireStatus status,
            ProductChainProgressionClaim claim) {
        public AcquireResult {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            if ((status == AcquireStatus.ACQUIRED) != (claim != null)) {
                throw new IllegalArgumentException(
                        "only ACQUIRED may expose the acquired claim");
            }
        }

        public static AcquireResult acquired(
                ProductChainProgressionClaim claim) {
            return new AcquireResult(AcquireStatus.ACQUIRED, claim);
        }

        public static AcquireResult active() {
            return new AcquireResult(AcquireStatus.ACTIVE_CLAIM, null);
        }

        public static AcquireResult taskNotFound() {
            return new AcquireResult(AcquireStatus.TASK_NOT_FOUND, null);
        }
    }

    enum ReleaseResult {
        RELEASED,
        STALE_CLAIM,
        TASK_NOT_FOUND
    }

    enum RenewStatus {
        RENEWED,
        REPLAYED,
        STALE_CLAIM,
        EXPIRED_CLAIM,
        TASK_NOT_FOUND
    }

    record RenewResult(
            RenewStatus status,
            ProductChainProgressionClaim claim) {
        public RenewResult {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            boolean successful = status == RenewStatus.RENEWED
                    || status == RenewStatus.REPLAYED;
            if (successful != (claim != null)) {
                throw new IllegalArgumentException(
                        "only successful renewal results contain a claim");
            }
        }

        public static RenewResult renewed(
                ProductChainProgressionClaim claim) {
            return new RenewResult(RenewStatus.RENEWED, claim);
        }

        public static RenewResult stale() {
            return new RenewResult(RenewStatus.STALE_CLAIM, null);
        }

        public static RenewResult replayed(
                ProductChainProgressionClaim claim) {
            return new RenewResult(RenewStatus.REPLAYED, claim);
        }

        public static RenewResult expired() {
            return new RenewResult(RenewStatus.EXPIRED_CLAIM, null);
        }

        public static RenewResult taskNotFound() {
            return new RenewResult(RenewStatus.TASK_NOT_FOUND, null);
        }
    }

    enum CurrentResult {
        CURRENT,
        STALE_CLAIM,
        EXPIRED_CLAIM,
        TASK_NOT_FOUND
    }

    record CommittedTaskCursor(Instant committedAt, String taskId) {
        public CommittedTaskCursor {
            if (committedAt == null) {
                throw new IllegalArgumentException(
                        "committedAt must not be null");
            }
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException(
                        "taskId must not be blank");
            }
        }
    }

    record CommittedTaskPage(
            List<String> taskIds,
            CommittedTaskCursor nextCursor) {
        public CommittedTaskPage {
            taskIds = List.copyOf(taskIds);
            if (taskIds.isEmpty() != (nextCursor == null)) {
                throw new IllegalArgumentException(
                        "only a nonempty page has a next cursor");
            }
        }
    }
}
