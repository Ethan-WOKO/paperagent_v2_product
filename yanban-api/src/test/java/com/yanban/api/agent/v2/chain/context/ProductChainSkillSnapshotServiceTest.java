package com.yanban.api.agent.v2.chain.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.skills.ResolvedSkill;
import com.yanban.api.skills.SkillsService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductChainSkillSnapshotServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");

    @Test
    void persistsNoneForATaskWithoutASelectedSkill() {
        MemoryRepository repository = new MemoryRepository();
        SkillsService skills = mock(SkillsService.class);
        ProductChainSkillSnapshotService service =
                new ProductChainSkillSnapshotService(repository, skills);

        ProductChainTaskSkillSnapshot snapshot = service.freezeNewTask(
                7, "task-1", "instruction-1", "  ", NOW);

        assertEquals(ProductChainTaskSkillSnapshot.SelectionKind.NONE,
                snapshot.selectionKind());
        assertEquals("[]", snapshot.allowedTools().json());
        verify(skills, never()).resolveEnabledSkill(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void resolvesSelectedSkillOnceAndStoresSortedToolsAndUniquePrompt() {
        MemoryRepository repository = new MemoryRepository();
        SkillsService skills = mock(SkillsService.class);
        when(skills.resolveEnabledSkill(7L, "review")).thenReturn(
                new ResolvedSkill("review", "Review carefully",
                        Set.of("literature.search", "project.read")));
        ProductChainSkillSnapshotService service =
                new ProductChainSkillSnapshotService(repository, skills);

        ProductChainTaskSkillSnapshot first = service.freezeNewTask(
                7, "task-1", "instruction-1", " review ", NOW);
        ProductChainTaskSkillSnapshot replay = service.freezeNewTask(
                7, "task-1", "instruction-1", "different", NOW.plusSeconds(1));

        assertSame(first, replay);
        assertEquals(ProductChainTaskSkillSnapshot.SelectionKind.SELECTED,
                first.selectionKind());
        assertEquals("review", first.skillId());
        assertEquals("Review carefully", first.promptBody());
        assertEquals("[\"literature.search\",\"project.read\"]",
                first.allowedTools().json());
        verify(skills).resolveEnabledSkill(7L, "review");
        verify(skills, never()).resolveEnabledSkill(7L, "different");
    }

    @Test
    void boundaryReplacementCopiesPersistedSnapshotWithoutResolvingAgain() {
        MemoryRepository repository = new MemoryRepository();
        ProductChainTaskSkillSnapshot predecessor =
                ProductChainTaskSkillSnapshot.selected(
                        "task-old", "instruction-old", "review", "prompt",
                        Set.of("project.read"), NOW);
        repository.append(predecessor);
        SkillsService skills = mock(SkillsService.class);
        ProductChainSkillSnapshotService service =
                new ProductChainSkillSnapshotService(repository, skills);

        ProductChainTaskSkillSnapshot replacement =
                service.copyForBoundaryReplacement(
                        "task-new", "instruction-trigger", "task-old",
                        NOW.plusSeconds(1));

        assertEquals("task-new", replacement.taskId());
        assertEquals("instruction-trigger", replacement.sourceInstructionId());
        assertEquals(predecessor.skillId(), replacement.skillId());
        assertEquals(predecessor.promptBody(), replacement.promptBody());
        assertEquals(predecessor.promptSha256(), replacement.promptSha256());
        assertEquals(predecessor.allowedTools(), replacement.allowedTools());
        verify(skills, never()).resolveEnabledSkill(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void supplementOrCorrectionCanOmitOrRepeatButCannotChangeSkill() {
        MemoryRepository repository = new MemoryRepository();
        repository.append(ProductChainTaskSkillSnapshot.selected(
                "task-1", "instruction-1", "review", "prompt",
                Set.of("project.read"), NOW));
        repository.append(ProductChainTaskSkillSnapshot.none(
                "task-2", "instruction-2", NOW));
        ProductChainSkillSnapshotService service =
                new ProductChainSkillSnapshotService(
                        repository, mock(SkillsService.class));

        assertTrue(service.preservesSelection("task-1", null));
        assertTrue(service.preservesSelection("task-1", " review "));
        assertFalse(service.preservesSelection("task-1", "another"));
        assertFalse(service.preservesSelection("task-2", "review"));
        assertThrows(IllegalStateException.class,
                () -> service.preservesSelection("task-missing", null));
    }

    private static final class MemoryRepository
            implements ProductChainSkillSnapshotRepository {
        private final Map<String, ProductChainTaskSkillSnapshot> values =
                new HashMap<>();

        @Override
        public Optional<ProductChainTaskSkillSnapshot> findByTaskId(
                String taskId) {
            return Optional.ofNullable(values.get(taskId));
        }

        @Override
        public ProductChainTaskSkillSnapshot append(
                ProductChainTaskSkillSnapshot snapshot) {
            ProductChainTaskSkillSnapshot existing = values.putIfAbsent(
                    snapshot.taskId(), snapshot);
            return existing == null ? snapshot : existing;
        }
    }
}
