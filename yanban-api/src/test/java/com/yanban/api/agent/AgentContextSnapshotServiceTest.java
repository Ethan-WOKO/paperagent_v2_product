package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentContextSnapshot;
import com.yanban.core.agent.AgentContextSnapshotRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.model.ChatMessage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AgentContextSnapshotServiceTest {

    @Mock
    private AgentContextSnapshotRepository snapshots;

    @Mock
    private AgentSessionRepository sessions;

    private AgentContextSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new AgentContextSnapshotService(snapshots, sessions, new ObjectMapper());
    }

    @Test
    void saveSnapshotSerializesSafeContextProjectionWithoutUnprojectedMessageBodies() {
        when(snapshots.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AgentContextPackage contextPackage = new AgentContextPackage(
                List.of(ChatMessage.user("secret draft text must not be persisted")),
                List.of(new AgentContextSection("recent_messages", 1, 42, "Recent messages")),
                List.of(new AgentContextDroppedItem("message", 3, "Dropped by window")),
                10,
                8,
                256,
                EvidenceLedger.empty(),
                new AgentContextDebugView(
                        512, 512, 256,
                        new AgentContextDebugView.DebugText("safe current text", true, false, "active_request"),
                        List.of(), null, null, null, List.of(),
                        List.of(new AgentContextSection("current_user_message", 1, 17, "current")),
                        List.of())
        );

        service.saveSnapshot(11L, 22L, 33L, "trace-save", contextPackage);

        ArgumentCaptor<AgentContextSnapshot> captor = ArgumentCaptor.forClass(AgentContextSnapshot.class);
        verify(snapshots).saveAndFlush(captor.capture());
        AgentContextSnapshot saved = captor.getValue();
        assertThat(saved.getSectionsJson()).contains("recent_messages");
        assertThat(saved.getSectionsJson()).contains("safe current text", "active_request");
        assertThat(saved.getDroppedItemsJson()).contains("Dropped by window");
        assertThat(saved.getSectionsJson()).doesNotContain("secret draft text");
        assertThat(saved.getDroppedItemsJson()).doesNotContain("secret draft text");
        assertThat(saved.getContextMessageCount()).isEqualTo(1);
        assertThat(saved.getEstimatedCharacters()).isEqualTo(256);
    }

    @Test
    void getTurnSnapshotRequiresOwnedSession() {
        when(sessions.findByIdAndUserId(22L, 33L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTurnSnapshot(33L, 22L, 11L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void getTurnSnapshotParsesStructuredMetadata() {
        when(sessions.findByIdAndUserId(22L, 33L)).thenReturn(Optional.of(new AgentSession(
                33L,
                "session",
                "deepseek",
                "deepseek-chat",
                20,
                false
        )));
        when(snapshots.findByTurnIdAndSessionIdAndUserId(11L, 22L, 33L)).thenReturn(Optional.of(new AgentContextSnapshot(
                11L,
                22L,
                33L,
                "trace-read",
                "[{\"type\":\"runtime_identity_guard\",\"itemCount\":1,\"estimatedCharacters\":100,\"note\":\"guard\"}]",
                "[]",
                2,
                2,
                3,
                512
        )));

        AgentContextSnapshotResponse response = service.getTurnSnapshot(33L, 22L, 11L);

        assertThat(response.traceId()).isEqualTo("trace-read");
        assertThat(response.sections()).extracting(AgentContextSection::type)
                .containsExactly("runtime_identity_guard");
        assertThat(response.droppedItems()).isEmpty();
        assertThat(response.estimatedCharacters()).isEqualTo(512);
    }

    @Test
    void listSessionSnapshotsUsesOnlyLatestRevisionPerTurn() {
        when(sessions.findByIdAndUserId(22L, 33L)).thenReturn(Optional.of(
                new AgentSession(33L, "session", "deepseek",
                        "deepseek-chat", 20, false)));
        AgentContextSnapshot revisionTwo = new AgentContextSnapshot(
                11L, 22L, 33L, 2, 91L, "STEP_DECISION", "step:1",
                "READY", "deepseek", "deepseek-v4-flash",
                1_000_000L, 384_000L, "utf8-byte-v1", "layered-v1",
                20L, 50_000L, "a".repeat(64), "b".repeat(64));
        when(snapshots.findLatestRevisionPerTurn(
                org.mockito.ArgumentMatchers.eq(22L),
                org.mockito.ArgumentMatchers.eq(33L), any()))
                .thenReturn(List.of(revisionTwo));

        List<AgentContextSnapshotResponse> result =
                service.listSessionSnapshots(33L, 22L, 10);

        assertThat(result).hasSize(1);
        verify(snapshots).findLatestRevisionPerTurn(
                org.mockito.ArgumentMatchers.eq(22L),
                org.mockito.ArgumentMatchers.eq(33L), any());
    }
}
