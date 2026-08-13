package com.yanban.api.agent.v2.persistence;

import com.yanban.api.agent.v2.chain.persistence.ProductPlanReplanMarkerReader;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.PersistedPlanReplan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductPlanRevisionAuthoritySourceTest {
    private ProductActiveStepReplanJpaRepository activeRows;
    private ProductActiveStepReplanMarkerReader activeMarkers;
    private ProductPlanReplanMarkerReader ordinaryMarkers;
    private ProductPlanRevisionAuthoritySource source;

    @BeforeEach
    void setUp() {
        activeRows = mock(ProductActiveStepReplanJpaRepository.class);
        activeMarkers = mock(ProductActiveStepReplanMarkerReader.class);
        ordinaryMarkers = mock(ProductPlanReplanMarkerReader.class);
        source = new ProductPlanRevisionAuthoritySource(
                activeRows, activeMarkers, ordinaryMarkers);
        when(activeRows.findAllByPlanIdOrderBySourceEventSequenceAsc("plan-1"))
                .thenReturn(List.of());
        when(ordinaryMarkers.findAllByPlanId("plan-1"))
                .thenReturn(List.of());
    }

    @Test
    void readsAnExactValidatedActiveStepReplanAuthority() {
        PlanRevision revision = revision("revision-2");
        ProductActiveStepReplanEntity row = activeRow("revision-2", "a".repeat(64));
        PersistedActiveStepReplan persisted = mock(
                PersistedActiveStepReplan.class);
        when(persisted.replannedRevision()).thenReturn(revision);
        when(activeRows.findAllByPlanIdOrderBySourceEventSequenceAsc("plan-1"))
                .thenReturn(List.of(row));
        when(activeMarkers.read(row)).thenReturn(
                new ProductActiveStepReplanMarkerReader.Marker(
                        mock(io.paperagent.v2.persistence
                                .ActiveStepReplanRequest.class), persisted));

        var found = source.find("plan-1", "revision-2").orElseThrow();

        assertEquals(revision, found.revision());
        assertEquals("a".repeat(64), found.authoritySha256());
    }

    @Test
    void readsAnExactValidatedOrdinaryReplanAuthority() {
        PlanRevision revision = revision("revision-2");
        PersistedPlanReplan persisted = mock(PersistedPlanReplan.class);
        when(persisted.replannedRevision()).thenReturn(revision);
        ProductPlanReplanMarkerReader.Marker marker = mock(
                ProductPlanReplanMarkerReader.Marker.class);
        when(marker.result()).thenReturn(persisted);
        when(ordinaryMarkers.findAllByPlanId("plan-1"))
                .thenReturn(List.of(marker));
        when(ordinaryMarkers.authoritySha256(marker))
                .thenReturn("b".repeat(64));

        var found = source.find("plan-1", "revision-2").orElseThrow();

        assertEquals(revision, found.revision());
        assertEquals("b".repeat(64), found.authoritySha256());
    }

    @Test
    void rejectsAmbiguousRevisionAuthoritiesAcrossFormalSources() {
        PlanRevision revision = revision("revision-2");
        ProductActiveStepReplanEntity row = activeRow("revision-2", "a".repeat(64));
        PersistedActiveStepReplan active = mock(PersistedActiveStepReplan.class);
        when(active.replannedRevision()).thenReturn(revision);
        when(activeRows.findAllByPlanIdOrderBySourceEventSequenceAsc("plan-1"))
                .thenReturn(List.of(row));
        when(activeMarkers.read(row)).thenReturn(
                new ProductActiveStepReplanMarkerReader.Marker(
                        mock(io.paperagent.v2.persistence
                                .ActiveStepReplanRequest.class), active));
        PersistedPlanReplan ordinary = mock(PersistedPlanReplan.class);
        when(ordinary.replannedRevision()).thenReturn(revision);
        ProductPlanReplanMarkerReader.Marker marker = mock(
                ProductPlanReplanMarkerReader.Marker.class);
        when(marker.result()).thenReturn(ordinary);
        when(ordinaryMarkers.findAllByPlanId("plan-1"))
                .thenReturn(List.of(marker));
        when(ordinaryMarkers.authoritySha256(marker))
                .thenReturn("b".repeat(64));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> source.find("plan-1", "revision-2"));

        assertEquals("CHAIN_STEP_PLAN_REVISION_AMBIGUOUS",
                failure.getMessage());
    }

    @Test
    void ignoresAnActiveRowThatFailsItsExistingMarkerValidation() {
        ProductActiveStepReplanEntity row = activeRow(
                "revision-2", "a".repeat(64));
        when(activeRows.findAllByPlanIdOrderBySourceEventSequenceAsc("plan-1"))
                .thenReturn(List.of(row));
        when(activeMarkers.read(row)).thenReturn(null);

        assertTrue(source.find("plan-1", "revision-2").isEmpty());
    }

    private static ProductActiveStepReplanEntity activeRow(
            String revisionId, String sha256) {
        ProductActiveStepReplanEntity row = mock(
                ProductActiveStepReplanEntity.class);
        when(row.resultRevisionId()).thenReturn(revisionId);
        when(row.resultSha256()).thenReturn(sha256);
        return row;
    }

    private static PlanRevision revision(String id) {
        PlanRevision revision = mock(PlanRevision.class);
        when(revision.id()).thenReturn(new PlanRevisionId(id));
        return revision;
    }
}
