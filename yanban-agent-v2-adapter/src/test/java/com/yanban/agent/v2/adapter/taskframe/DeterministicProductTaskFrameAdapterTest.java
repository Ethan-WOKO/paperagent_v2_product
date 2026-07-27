package com.yanban.agent.v2.adapter.taskframe;

import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.TaskFrame;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicProductTaskFrameAdapterTest {
    private static final String EXPECTED_TASK_FRAME_ID =
            "product.afcb377a4876cc7039d410971edc80bf026a089804f5c98af9f964ff43a0f099";

    private final DeterministicProductTaskFrameAdapter adapter =
            new DeterministicProductTaskFrameAdapter();

    @Test
    void bindsWorkspaceRunWithoutProject() {
        AgentRunIdentity identity = ProductTaskFrameTestFixtures.identity(7L, null, null);

        ProductTaskFrameBinding binding = adapter.bind(
                ProductTaskFrameTestFixtures.request(identity, Optional.empty()));

        assertSame(identity, binding.identity());
        assertFalse(binding.taskFrame().sourceProjectVersion().isPresent());
        assertEquals(EXPECTED_TASK_FRAME_ID, binding.taskFrame().id().value());
    }

    @Test
    void bindsProjectRunToFrozenProjectVersion() {
        AgentRunIdentity identity = ProductTaskFrameTestFixtures.identity(7L, null, 42L);

        ProductTaskFrameBinding binding = adapter.bind(
                ProductTaskFrameTestFixtures.request(
                        identity,
                        Optional.of("version-9")));

        assertEquals(
                Optional.of(new ProjectVersionRef("42", "version-9")),
                binding.taskFrame().sourceProjectVersion());
    }

    @Test
    void preservesOptionalSessionIdentity() {
        AgentRunIdentity identity = ProductTaskFrameTestFixtures.identity(7L, 11L, null);

        ProductTaskFrameBinding binding = adapter.bind(
                ProductTaskFrameTestFixtures.request(identity, Optional.empty()));

        assertSame(identity, binding.identity());
        assertEquals(11L, binding.identity().sessionId());
    }

    @Test
    void equivalentInputsReplayToEqualBindingAndStableId() {
        ProductTaskFrameBinding first = adapter.bind(
                ProductTaskFrameTestFixtures.request(
                        ProductTaskFrameTestFixtures.identity(7L, 11L, 42L),
                        Optional.of("version-9")));
        ProductTaskFrameBinding replay = new DeterministicProductTaskFrameAdapter().bind(
                ProductTaskFrameTestFixtures.request(
                        ProductTaskFrameTestFixtures.identity(7L, 11L, 42L),
                        Optional.of("version-9")));

        assertEquals(first, replay);
        assertEquals(EXPECTED_TASK_FRAME_ID, first.taskFrame().id().value());
    }

    @Test
    void hashesUnsafeProductSourceInsteadOfUsingItAsV2Id() {
        AgentRunIdentity identity = new AgentRunIdentity(
                "turn/../../",
                "run with spaces/文献",
                7L,
                null,
                null);

        String taskFrameId = adapter.bind(ProductTaskFrameTestFixtures.request(
                identity,
                Optional.empty())).taskFrame().id().value();

        assertTrue(taskFrameId.matches("product\\.[0-9a-f]{64}"));
        assertFalse(taskFrameId.contains(identity.source()));
        assertFalse(taskFrameId.contains(identity.sourceId()));
    }

    @Test
    void usesCallerTimeWithoutAmbientClockOrState() {
        ProductTaskFrameIntakeRequest request = ProductTaskFrameTestFixtures.request(
                ProductTaskFrameTestFixtures.identity(7L, null, null),
                Optional.empty());

        TaskFrame first = adapter.bind(request).taskFrame();
        TaskFrame second = adapter.bind(request).taskFrame();

        assertEquals(ProductTaskFrameTestFixtures.CREATED_AT, first.createdAt());
        assertEquals(first, second);
    }

    @Test
    void outputCollectionsAreImmutableSnapshots() {
        TaskFrame taskFrame = adapter.bind(ProductTaskFrameTestFixtures.request(
                ProductTaskFrameTestFixtures.identity(7L, null, null),
                Optional.empty())).taskFrame();

        assertThrows(
                UnsupportedOperationException.class,
                () -> taskFrame.targets().add("ambient target"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> taskFrame.deliverables().clear());
        assertTrue(taskFrame.constraints().contains("preserve citations"));
    }
}
