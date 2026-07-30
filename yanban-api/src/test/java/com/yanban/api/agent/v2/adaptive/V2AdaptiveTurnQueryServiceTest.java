package com.yanban.api.agent.v2.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class V2AdaptiveTurnQueryServiceTest {
    @Test
    void ownerQualifiedReadReturnsOnlyBoundedProjectionAndDoesNotMutate() {
        var repository = mock(V2AdaptiveTurnRepository.class);
        var entity = new V2AdaptiveTurnEntity(
                7L, 2L, 3L, "request-1",
                "PERSISTENT_PLAN_EXECUTE", "plan-1", "version-1",
                "SUCCEEDED",
                "[{\"index\":1,\"title\":\"读取文件\","
                        + "\"status\":\"SUCCEEDED\",\"detail\":\"执行成功\"}]",
                "完成", null, "[\"result.txt\"]", null, Instant.EPOCH);
        when(repository.findByUserIdAndSessionIdAndClientRequestId(
                2L, 3L, "request-1")).thenReturn(Optional.of(entity));

        var result = new V2AdaptiveTurnQueryService(
                repository, new ObjectMapper()).get(2L, 3L, "request-1");

        assertEquals("SUCCEEDED", result.status());
        assertEquals("plan-1", result.planId());
        assertEquals("SUCCEEDED", result.steps().get(0).status());
        assertEquals(java.util.List.of("result.txt"), result.outputPaths());
        verify(repository).findByUserIdAndSessionIdAndClientRequestId(
                2L, 3L, "request-1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void anotherOwnerCannotReadTheRecord() {
        var repository = mock(V2AdaptiveTurnRepository.class);
        when(repository.findByUserIdAndSessionIdAndClientRequestId(
                9L, 3L, "request-1")).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () ->
                new V2AdaptiveTurnQueryService(
                        repository, new ObjectMapper())
                        .get(9L, 3L, "request-1"));
        verify(repository).findByUserIdAndSessionIdAndClientRequestId(
                9L, 3L, "request-1");
        verifyNoMoreInteractions(repository);
    }
}
