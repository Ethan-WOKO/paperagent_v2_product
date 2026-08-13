package com.yanban.api.agent.v2.persistence;

import com.yanban.api.agent.v2.chain.persistence.ProductPlanReplanMarkerReader;
import io.paperagent.v2.contracts.PlanRevision;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

/** Exact persisted authority for a Plan revision committed after bootstrap. */
@Component
public final class ProductPlanRevisionAuthoritySource {
    private final ProductActiveStepReplanJpaRepository activeReplans;
    private final ProductActiveStepReplanMarkerReader activeMarkers;
    private final ProductPlanReplanMarkerReader ordinaryReplans;

    public ProductPlanRevisionAuthoritySource(
            ProductActiveStepReplanJpaRepository activeReplans,
            ProductActiveStepReplanMarkerReader activeMarkers,
            ProductPlanReplanMarkerReader ordinaryReplans) {
        this.activeReplans = Objects.requireNonNull(
                activeReplans, "activeReplans");
        this.activeMarkers = Objects.requireNonNull(
                activeMarkers, "activeMarkers");
        this.ordinaryReplans = Objects.requireNonNull(
                ordinaryReplans, "ordinaryReplans");
    }

    public Optional<RevisionAuthority> find(
            String planId, String revisionId) {
        ArrayList<RevisionAuthority> matches = new ArrayList<>();
        activeReplans.findAllByPlanIdOrderBySourceEventSequenceAsc(planId)
                .stream()
                .filter(row -> row.resultRevisionId().equals(revisionId))
                .map(row -> new ActiveAuthority(
                        row, activeMarkers.read(row)))
                .filter(value -> value.marker() != null)
                .filter(value -> value.marker().result().replannedRevision().id()
                        .value().equals(revisionId))
                .map(value -> new RevisionAuthority(
                        value.marker().result().replannedRevision(),
                        value.row().resultSha256()))
                .forEach(matches::add);
        ordinaryReplans.findAllByPlanId(planId).stream()
                .filter(marker -> marker.result().replannedRevision().id()
                        .value().equals(revisionId))
                .map(marker -> new RevisionAuthority(
                        marker.result().replannedRevision(),
                        ordinaryReplans.authoritySha256(marker)))
                .forEach(matches::add);
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "CHAIN_STEP_PLAN_REVISION_AMBIGUOUS");
        }
        return matches.stream().findFirst();
    }

    private record ActiveAuthority(
            ProductActiveStepReplanEntity row,
            ProductActiveStepReplanMarkerReader.Marker marker) {
    }

    public record RevisionAuthority(
            PlanRevision revision, String authoritySha256) {
        public RevisionAuthority {
            Objects.requireNonNull(revision, "revision");
            if (authoritySha256 == null || authoritySha256.isBlank()) {
                throw new IllegalArgumentException(
                        "authoritySha256 must not be blank");
            }
        }
    }
}
