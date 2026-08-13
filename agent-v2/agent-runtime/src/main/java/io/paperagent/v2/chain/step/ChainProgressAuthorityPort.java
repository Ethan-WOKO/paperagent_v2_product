package io.paperagent.v2.chain.step;

import java.util.List;

/** Supplies a complete, authority-cut progress history for one Step activation. */
@FunctionalInterface
public interface ChainProgressAuthorityPort {
    ProgressSnapshot readProgress(
            String taskId, String stepId, String activationEventId);

    record ProgressSnapshot(
            long authorityEventCut,
            List<ChainProgressPolicy.ProgressMarker> markers) {
        public ProgressSnapshot {
            if (authorityEventCut < 0) {
                throw new IllegalArgumentException(
                        "authorityEventCut must not be negative");
            }
            markers = List.copyOf(markers);
            if (markers.stream().anyMatch(marker ->
                    marker.authorityEventSequence() > authorityEventCut)) {
                throw new IllegalArgumentException(
                        "progress marker exceeds the formal authority cut");
            }
        }
    }
}
