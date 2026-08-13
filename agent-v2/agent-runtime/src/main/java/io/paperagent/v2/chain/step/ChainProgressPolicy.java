package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.ChainRuntimePolicy;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Versioned no-progress detector over formal progress identities. */
public final class ChainProgressPolicy {
    private final ChainRuntimePolicy policy;

    public ChainProgressPolicy(ChainRuntimePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public Assessment assess(List<ProgressMarker> markers) {
        List<ProgressMarker> ordered = List.copyOf(
                        Objects.requireNonNull(markers, "markers"))
                .stream().sorted(Comparator.comparingLong(
                        ProgressMarker::authorityEventSequence)).toList();
        long previous = 0;
        for (ProgressMarker marker : ordered) {
            if (marker.authorityEventSequence() <= previous) {
                throw new IllegalArgumentException(
                        "progress markers must have unique increasing authority order");
            }
            previous = marker.authorityEventSequence();
        }
        if (ordered.isEmpty()) {
            return new Assessment(ProgressDecision.CONTINUE_EXECUTOR, 0);
        }
        String latest = ordered.get(ordered.size() - 1)
                .progressIdentitySha256();
        int unchanged = 0;
        for (int index = ordered.size() - 1; index >= 0; index--) {
            if (!ordered.get(index).progressIdentitySha256().equals(latest)) {
                break;
            }
            unchanged++;
        }
        return new Assessment(
                unchanged >= policy.noProgressThreshold()
                        ? ProgressDecision.BLOCK_FOR_REFLECTOR
                        : ProgressDecision.CONTINUE_EXECUTOR,
                unchanged);
    }

    public record ProgressMarker(
            long authorityEventSequence,
            String progressIdentitySha256) {
        public ProgressMarker {
            if (authorityEventSequence < 1) {
                throw new IllegalArgumentException(
                        "authorityEventSequence must be positive");
            }
            if (progressIdentitySha256 == null
                    || !progressIdentitySha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "progressIdentitySha256 must be lowercase SHA-256");
            }
        }
    }

    public enum ProgressDecision {
        CONTINUE_EXECUTOR,
        BLOCK_FOR_REFLECTOR
    }

    public record Assessment(
            ProgressDecision decision,
            int unchangedOccurrences) {
        public Assessment {
            Objects.requireNonNull(decision, "decision");
            if (unchangedOccurrences < 0) {
                throw new IllegalArgumentException(
                        "unchangedOccurrences must not be negative");
            }
        }
    }
}
