package com.yanban.api.agent.v2.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.context.*;
import com.yanban.api.agent.v2.intake.V2TurnContextAuthorityService;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class V2AdaptiveTurnQueryServiceTest {
    @Test
    void runningStepExposesLatestDurableContextPhaseAndCompactedSection() {
        var repository = mock(V2AdaptiveTurnRepository.class);
        var authority = mock(V2TurnContextAuthorityService.class);
        var revisions = mock(V2ContextRevisionService.class);
        var entity = new V2AdaptiveTurnEntity(
                7L, 2L, 3L, "request-1",
                "PERSISTENT_PLAN_EXECUTE", "plan-1", "version-1",
                "RUNNING",
                "[{\"index\":1,\"title\":\"读取文件\","
                        + "\"status\":\"RUNNING\",\"detail\":null}]",
                null, null, "[]", null, Instant.EPOCH);
        when(repository.findByUserIdAndSessionIdAndClientRequestId(
                2L, 3L, "request-1")).thenReturn(Optional.of(entity));
        when(authority.find(2L, 3L, "request-1")).thenReturn(Optional.of(
                new V2TurnContextAuthorityService.TurnAuthority(11L)));
        when(revisions.findLatest(2L, 3L, 11L)).thenReturn(Optional.of(
                contextRevision(V2ContextRevisionStatus.COMPACTING)));

        var result = new V2AdaptiveTurnQueryService(
                repository, new ObjectMapper(), authority, revisions)
                .get(2L, 3L, "request-1");

        assertNotNull(result.context());
        assertEquals("COMPACTING", result.context().phase());
        assertEquals("step-1", result.context().stepId());
        assertEquals(List.of("TOOL_RESULTS"),
                result.context().compactedSections());
    }

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

    private static V2ContextRevisionSnapshot contextRevision(
            V2ContextRevisionStatus status) {
        List<V2ContextSectionDraft> sections = Arrays.stream(
                        ContextSectionType.values())
                .map(type -> new V2ContextSectionDraft(
                        type, type.percentage(), 1_000,
                        type == ContextSectionType.TOOL_RESULTS ? 100 : 10,
                        type == ContextSectionType.TOOL_RESULTS ? 60 : 10,
                        V2ContextSectionStatus.READY,
                        type == ContextSectionType.STEP_STATE
                                ? "{\"stepId\":\"step-1\"}" : "{}",
                        "{}", null))
                .toList();
        V2ContextRevisionDraft draft = new V2ContextRevisionDraft(
                2L, 3L, 11L, 2, 1L, "a".repeat(64),
                V2ContextStage.STEP_DECISION, "step-key", status,
                "provider", "model", 1_000_000, 4_096,
                "counter", "profile", 140, 50, sections);
        return new V2ContextRevisionSnapshot(
                2L, V2ContextRevisionOutcome.REPLAYED, draft,
                "{}", "b".repeat(64), List.of());
    }
}
