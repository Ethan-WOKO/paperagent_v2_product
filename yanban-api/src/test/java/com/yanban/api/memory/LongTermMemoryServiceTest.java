package com.yanban.api.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentLongTermMemory;
import com.yanban.core.agent.AgentLongTermMemoryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryServiceTest {

    @Mock
    AgentLongTermMemoryRepository memories;

    @Mock
    ProjectService projectService;

    LongTermMemoryService service;
    AtomicLong ids;

    @BeforeEach
    void setUp() {
        service = new LongTermMemoryService(memories, new ObjectMapper(), projectService);
        ids = new AtomicLong(100);
        lenient().when(memories.saveAndFlush(any(AgentLongTermMemory.class))).thenAnswer(invocation -> {
            AgentLongTermMemory memory = invocation.getArgument(0);
            if (memory.getId() == null) {
                ReflectionTestUtils.setField(memory, "id", ids.incrementAndGet());
            }
            return memory;
        });
    }

    @Test
    void projectCreateUsesServerManifestAndIgnoresClientSourceIdentity() {
        String version = "a".repeat(64);
        when(projectService.manifest(42L, 7L)).thenReturn(new ProjectManifestResponse(7L, version, List.of()));

        LongTermMemoryResponse response = service.createMemory(42L, new CreateLongTermMemoryRequest(
                7L, "PROJECT", "FACT", "The objective is evaluated by SINR.", List.of("paper"),
                "MODEL_INFERRED", "C:\\Users\\other\\secret.txt", BigDecimal.valueOf(0.9)));

        assertThat(response.userId()).isEqualTo(42L);
        assertThat(response.projectId()).isEqualTo(7L);
        assertThat(response.projectVersion()).isEqualTo(version);
        assertThat(response.confirmationStatus()).isEqualTo(AgentLongTermMemory.CONFIRMATION_UNCONFIRMED);
        assertThat(response.sourceType()).isEqualTo(AgentLongTermMemory.SOURCE_USER_CONFIRMED);
        assertThat(response.sourceRefId()).isNull();
        assertThat(response.confirmedAt()).isNull();
    }

    @Test
    void distilledCandidateIsVisibleButUnconfirmedUntilUserReview() {
        when(memories.findByUserIdAndSourceTypeAndSourceRefId(
                eq(42L), eq(AgentLongTermMemory.SOURCE_LLM_DISTILLED), any()))
                .thenReturn(Optional.empty());

        LongTermMemoryService.DistilledMemoryWriteResult result = service.createDistilledMemory(
                42L,
                new MemoryDistillationCandidate(
                        "USER", null, "PREFERENCE", "用户偏好简洁的中文回答。",
                        List.of("语言", "风格"), new BigDecimal("0.91"), List.of(11L)));

        assertThat(result.created()).isTrue();
        assertThat(result.memory().getStatus()).isEqualTo(AgentLongTermMemory.STATUS_ACTIVE);
        assertThat(result.memory().getConfirmationStatus())
                .isEqualTo(AgentLongTermMemory.CONFIRMATION_UNCONFIRMED);
        assertThat(result.memory().getSourceType()).isEqualTo(AgentLongTermMemory.SOURCE_LLM_DISTILLED);
        assertThat(result.memory().getSourceRefId()).startsWith("distilled:");
        assertThat(result.memory().getConfirmedAt()).isNull();
        assertThat(result.memory().getProvenanceType()).isNull();
    }

    @Test
    void distilledProjectCandidateBindsTheCurrentServerProjectVersion() {
        String version = "c".repeat(64);
        when(projectService.manifest(42L, 7L)).thenReturn(new ProjectManifestResponse(7L, version, List.of()));
        when(memories.findByUserIdAndSourceTypeAndSourceRefId(
                eq(42L), eq(AgentLongTermMemory.SOURCE_LLM_DISTILLED), any()))
                .thenReturn(Optional.empty());

        LongTermMemoryService.DistilledMemoryWriteResult result = service.createDistilledMemory(
                42L,
                new MemoryDistillationCandidate(
                        "PROJECT", 7L, "DECISION", "项目构建固定使用 Java 17。",
                        List.of("构建"), new BigDecimal("0.88"), List.of(21L)));

        assertThat(result.memory().getProjectId()).isEqualTo(7L);
        assertThat(result.memory().getProjectVersion()).isEqualTo(version);
        assertThat(result.memory().getConfirmationStatus())
                .isEqualTo(AgentLongTermMemory.CONFIRMATION_UNCONFIRMED);
    }

    @Test
    void activeListUsesTimeAwareGovernanceQuery() {
        AgentLongTermMemory active = userMemory(42L);
        when(memories.findActiveForGovernance(eq(42L), any(Instant.class), any()))
                .thenReturn(List.of(active));

        List<LongTermMemoryResponse> response = service.listMemories(42L, "ACTIVE", 50);

        assertThat(response).extracting(LongTermMemoryResponse::id).containsExactly(11L);
    }

    @Test
    void staleProjectMemoryCannotBeConfirmedOrCorrected() {
        AgentLongTermMemory memory = projectMemory(42L, 7L, "a".repeat(64));
        when(memories.findOwnedForUpdate(11L, 42L)).thenReturn(Optional.of(memory));
        when(projectService.manifest(42L, 7L))
                .thenReturn(new ProjectManifestResponse(7L, "b".repeat(64), List.of()));

        assertThatThrownBy(() -> service.confirmMemory(42L, 11L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("stale");
        assertThatThrownBy(() -> service.correctMemory(42L, 11L,
                new UpdateLongTermMemoryRequest(7L, "PROJECT", "FACT", "Updated result.", List.of(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("stale");
        assertThat(memory.getConfirmationStatus()).isEqualTo(AgentLongTermMemory.CONFIRMATION_UNCONFIRMED);
        assertThat(memory.getStatus()).isEqualTo(AgentLongTermMemory.STATUS_ACTIVE);
    }

    @Test
    void correctionIsConfirmedAndSupersedesOldMemoryAtomically() {
        AgentLongTermMemory current = userMemory(42L);
        when(memories.findOwnedForUpdate(11L, 42L)).thenReturn(Optional.of(current));

        LongTermMemoryResponse replacement = service.correctMemory(42L, 11L,
                new UpdateLongTermMemoryRequest(null, "USER", "PREFERENCE",
                        "User prefers explicit caveats.", List.of("style"), BigDecimal.valueOf(0.95)));

        assertThat(replacement.confirmationStatus()).isEqualTo(AgentLongTermMemory.CONFIRMATION_CONFIRMED);
        assertThat(replacement.confirmedSource()).isEqualTo(AgentLongTermMemory.CONFIRMED_SOURCE_USER_ACTION);
        assertThat(replacement.provenanceType()).isEqualTo(AgentLongTermMemory.PROVENANCE_USER_SETTINGS_ACTION);
        assertThat(replacement.provenanceRef()).isEqualTo("memory-settings:" + replacement.id() + ":correct");
        assertThat(replacement.supersedesMemoryId()).isEqualTo(11L);
        assertThat(current.getStatus()).isEqualTo(AgentLongTermMemory.STATUS_SUPERSEDED);
        assertThat(current.getSupersededByMemoryId()).isEqualTo(replacement.id());
    }

    @Test
    void correctionCannotMoveMemoryAcrossScopeOrProject() {
        AgentLongTermMemory current = userMemory(42L);
        when(memories.findOwnedForUpdate(11L, 42L)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.correctMemory(42L, 11L,
                new UpdateLongTermMemoryRequest(7L, "PROJECT", "FACT", "Moved memory.", List.of(), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("scope cannot be changed");
    }

    @Test
    void confirmRejectAndExpiryFailClosedForExpiredOrInvalidatedRows() {
        AgentLongTermMemory expired = userMemory(42L);
        ReflectionTestUtils.setField(expired, "expiresAt", Instant.now().minusSeconds(1));
        when(memories.findOwnedForUpdate(11L, 42L)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.confirmMemory(42L, 11L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("expired");
        assertThatThrownBy(() -> service.rejectMemory(42L, 11L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("expired");

        AgentLongTermMemory invalidated = userMemory(42L);
        ReflectionTestUtils.setField(invalidated, "invalidatedAt", Instant.now().minusSeconds(1));
        when(memories.findOwnedForUpdate(12L, 42L)).thenReturn(Optional.of(invalidated));
        assertThatThrownBy(() -> service.updateExpiry(42L, 12L,
                new UpdateLongTermMemoryExpiryRequest(null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("invalidated");
    }

    @Test
    void staleProjectMemoryCanBeRejectedAfterFreshOwnershipCheck() {
        AgentLongTermMemory stale = projectMemory(42L, 7L, "a".repeat(64));
        when(memories.findOwnedForUpdate(11L, 42L)).thenReturn(Optional.of(stale));
        when(projectService.manifest(42L, 7L))
                .thenReturn(new ProjectManifestResponse(7L, "b".repeat(64), List.of()));

        LongTermMemoryResponse response = service.rejectMemory(42L, 11L);

        assertThat(response.confirmationStatus()).isEqualTo(AgentLongTermMemory.CONFIRMATION_REJECTED);
        assertThat(response.projectVersion()).isEqualTo("a".repeat(64));
    }

    private AgentLongTermMemory userMemory(Long userId) {
        AgentLongTermMemory memory = new AgentLongTermMemory(userId, null, AgentLongTermMemory.SCOPE_USER,
                "FACT", "Original memory.", null, AgentLongTermMemory.SOURCE_USER_CONFIRMED,
                null, BigDecimal.valueOf(0.8), null);
        ReflectionTestUtils.setField(memory, "id", 11L);
        return memory;
    }

    private AgentLongTermMemory projectMemory(Long userId, Long projectId, String version) {
        AgentLongTermMemory memory = new AgentLongTermMemory(userId, projectId, AgentLongTermMemory.SCOPE_PROJECT,
                "FACT", "Original project memory.", null, AgentLongTermMemory.SOURCE_USER_CONFIRMED,
                null, BigDecimal.valueOf(0.8), null);
        ReflectionTestUtils.setField(memory, "id", 11L);
        memory.bindProjectVersion(version);
        return memory;
    }
}
