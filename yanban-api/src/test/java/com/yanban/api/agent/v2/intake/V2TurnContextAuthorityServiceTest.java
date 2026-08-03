package com.yanban.api.agent.v2.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class V2TurnContextAuthorityServiceTest {
    @Test
    void resolvesOnlyThroughOwnerAndSessionQualifiedIntake() {
        var intakes = mock(V2TurnIntakeJpaRepository.class);
        var owned = new V2TurnIntakeEntity(
                7L, 9L, "request-1", "a".repeat(64), "question",
                false, null, null, 11L, 12L, Instant.EPOCH);
        when(intakes.findByUserIdAndSessionIdAndClientRequestId(
                7L, 9L, "request-1")).thenReturn(Optional.of(owned));
        when(intakes.findByUserIdAndSessionIdAndClientRequestId(
                8L, 9L, "request-1")).thenReturn(Optional.empty());
        var service = new V2TurnContextAuthorityService(intakes);

        assertThat(service.find(7L, 9L, "request-1"))
                .contains(new V2TurnContextAuthorityService.TurnAuthority(12L));
        assertThat(service.find(8L, 9L, "request-1")).isEmpty();

        verify(intakes).findByUserIdAndSessionIdAndClientRequestId(
                7L, 9L, "request-1");
        verify(intakes).findByUserIdAndSessionIdAndClientRequestId(
                8L, 9L, "request-1");
        verifyNoMoreInteractions(intakes);
    }
}
