package com.yanban.api.agent.v2.compatibility.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetiredProjectReadTest {
    @Test
    void pendingCandidateReadReturnsStoredStatusWithoutResumingOrWriting() {
        var deliveries = mock(ProjectCandidateDeliveryTransactions.class);
        var row = mock(ProjectCandidateDeliveryEntity.class);
        var key = new ProjectCandidateDeliveryKey(7L, 8L, 9L, "pending");
        when(row.id()).thenReturn(key);
        when(row.status()).thenReturn("RUNNING");
        when(deliveries.find(key)).thenReturn(row);
        // All execution collaborators deliberately absent: a read must not require them.
        var service = new V2ProjectCandidateService(null, null, deliveries, null, null,
                null, null, null, null, null, new ObjectMapper());
        var response = service.read(7L, 8L, 9L, "pending");
        assertThat(response.status()).isEqualTo("RUNNING");
        assertThat(response.terminal()).isFalse();
        verify(deliveries).find(key);
        verifyNoMoreInteractions(deliveries);
    }

    @Test
    void pendingAnalysisReadReturnsStoredStatusWithoutResumingOrWriting() {
        var deliveries = mock(ProjectAnalysisDeliveryTransactions.class);
        var row = mock(ProjectAnalysisDeliveryEntity.class);
        var key = new ProjectAnalysisDeliveryKey(7L, 8L, 9L, "pending");
        when(row.id()).thenReturn(key);
        when(row.status()).thenReturn("RUNNING");
        when(deliveries.find(key)).thenReturn(row);
        var service = new V2ProjectAnalysisService(null, null, deliveries, null, null,
                null, null, null, null, null, null, null, null, null, new ObjectMapper());
        var response = service.read(7L, 8L, 9L, "pending");
        assertThat(response.status()).isEqualTo("RUNNING");
        assertThat(response.terminal()).isFalse();
        verify(deliveries).find(key);
        verifyNoMoreInteractions(deliveries);
    }

    @Test
    void candidateReadRemainsOwnerQualifiedAndDoesNotCreateMissingHistory() {
        var deliveries = mock(ProjectCandidateDeliveryTransactions.class);
        var key = new ProjectCandidateDeliveryKey(99L, 8L, 9L, "pending");
        when(deliveries.find(key)).thenThrow(new IllegalArgumentException("not found"));
        var service = new V2ProjectCandidateService(null, null, deliveries, null, null,
                null, null, null, null, null, new ObjectMapper());
        assertThatThrownBy(() -> service.read(99L, 8L, 9L, "pending"))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        verify(deliveries).find(key);
        verifyNoMoreInteractions(deliveries);
    }
}
