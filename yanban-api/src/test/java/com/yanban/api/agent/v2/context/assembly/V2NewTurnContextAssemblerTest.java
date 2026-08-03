package com.yanban.api.agent.v2.context.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentLongTermMemoryContext;
import com.yanban.api.agent.AgentMemorySelectionRef;
import com.yanban.api.agent.AgentRagExperimentResult;
import com.yanban.api.agent.AgentRagSelectionRef;
import com.yanban.api.agent.AgentRetrievedChunkDebug;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.V2ContextSectionStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class V2NewTurnContextAssemblerTest {
    private final V2NewTurnContextAssembler assembler =
            new V2NewTurnContextAssembler(new ObjectMapper());

    @Test
    void rejectsCoverageAtUserHalfTurn() {
        V2CanonicalTurnProjection boundary = turn(1L, 10L, 11L, "old");
        V2ContextAssemblyRequest request = request(
                List.of(boundary), boundary,
                summary(10L, "summary"),
                10_000);

        assertThatThrownBy(() -> assembler.assemble(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("half-turn");
    }

    @Test
    void removesOldestWholeTurnsAndExcludesCurrentTurn() {
        List<V2CanonicalTurnProjection> turns = List.of(
                turn(3L, 30L, 31L, "newest"),
                turn(1L, 10L, 11L, "oldest"),
                turn(2L, 20L, 21L, "middle"),
                turn(4L, 40L, 41L, "current"));
        V2ContextAssemblyResult result = assembler.assemble(
                request(turns, null, null, 105, 4L));

        assertThat(result.recentTurnIds()).containsExactly(3L);
        assertThat(result.evictedTurnIds()).containsExactly(1L, 2L);
        var recent = result.sections().get(0);
        assertThat(recent.type()).isEqualTo(
                ContextSectionType.RECENT_CONVERSATION);
        assertThat(recent.status()).isEqualTo(
                V2ContextSectionStatus.COMPACTION_REQUIRED);
        assertThat(recent.projectionJson()).contains("newest")
                .doesNotContain("oldest", "middle", "current");
        assertThat(recent.sourceRefsJson())
                .contains("selected", "evicted", "\"turnId\":1")
                .doesNotContain("user-oldest");
    }

    @Test
    void outputIsByteStableAndKeepsInputMaterialsUnchanged() {
        AgentLongTermMemoryContext memory = new AgentLongTermMemoryContext(
                "safe memory", 1, 1, 0, "note",
                List.of(new AgentMemorySelectionRef(
                        "memory:1", "v1", 1, "a".repeat(64),
                        "- safe memory")));
        AgentRagExperimentResult rag = new AgentRagExperimentResult(
                "legacy display", List.of(new AgentRetrievedChunkDebug(
                        "kb", 5L, "renamed.md", 2, "cite", 1.0,
                        "evidence", 3, "ACTIVE")),
                List.of(new AgentRagSelectionRef(
                        "rag:5:2", "3", 1, "b".repeat(64))));
        V2ContextAssemblyRequest request = request(
                List.of(turn(2L, 20L, 21L, "two"),
                        turn(1L, 10L, 11L, "one")),
                null, null, 10_000, null, memory, rag);

        V2ContextAssemblyResult first = assembler.assemble(request);
        V2ContextAssemblyResult second = assembler.assemble(request);

        assertThat(second).isEqualTo(first);
        assertThat(first.sections()).extracting(section -> section.type())
                .containsExactly(
                        ContextSectionType.RECENT_CONVERSATION,
                        ContextSectionType.CONVERSATION_SUMMARY,
                        ContextSectionType.LONG_TERM_MEMORY,
                        ContextSectionType.RAG_EVIDENCE,
                        ContextSectionType.STEP_STATE);
        assertThat(memory.content()).isEqualTo("safe memory");
        assertThat(memory.selectedRefs()).hasSize(1);
        assertThat(rag.retrievedChunks().get(0).filename())
                .isEqualTo("renamed.md");
        assertThat(first.sections().get(3).projectionJson())
                .contains("evidence").doesNotContain("renamed.md");
    }

    @Test
    void rejectsForeignTurnAndUnboundedSourceInput() {
        V2CanonicalTurnProjection foreign = new V2CanonicalTurnProjection(
                1L, 99L, 9L, 10L, 11L, Instant.EPOCH, "COMPLETED",
                "user", "assistant", "u", "a");
        assertThatThrownBy(() -> assembler.assemble(
                request(List.of(foreign), null, null, 10_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical turn");
        assertThatThrownBy(() -> request(
                java.util.stream.IntStream.range(0, 257)
                        .mapToObj(index -> turn(
                                (long) index + 1,
                                (long) index * 2 + 10,
                                (long) index * 2 + 11,
                                "x"))
                        .toList(), null, null, 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boundedTurns");
    }

    @Test
    void historicalFactsAcceptStringPlanIdAndRejectMissingAuthorityRef() {
        V2HistoricalTerminalFact fact = new V2HistoricalTerminalFact(
                7L, 9L, 80L, "terminal:80", "v1", 1,
                "c".repeat(64), "WAITING_CONFIRMATION", null,
                " plan-2026-08-03-A ", null, null);
        V2ContextAssemblyRequest request = new V2ContextAssemblyRequest(
                7L, 9L, null, 64,
                10_000, 1_000, 1_000, 1_000, 1_000,
                List.of(), null, null,
                AgentLongTermMemoryContext.empty(),
                new AgentRagExperimentResult(null, null), List.of(fact));

        var historical = assembler.assemble(request).sections().get(4);

        assertThat(historical.projectionJson())
                .contains("\"planId\":\"plan-2026-08-03-A\"");
        assertThatThrownBy(() -> new V2HistoricalTerminalFact(
                7L, 9L, 81L, "terminal:81", "v1", 1,
                "d".repeat(64), "SUCCEEDED", null,
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("historical terminal fact");
    }

    private static V2ContextAssemblyRequest request(
            List<V2CanonicalTurnProjection> turns,
            V2CanonicalTurnProjection boundary,
            V2SummaryProjection summary,
            long recentLimit) {
        return request(turns, boundary, summary, recentLimit, null,
                AgentLongTermMemoryContext.empty(),
                new AgentRagExperimentResult(null, null));
    }

    private static V2ContextAssemblyRequest request(
            List<V2CanonicalTurnProjection> turns,
            V2CanonicalTurnProjection boundary,
            V2SummaryProjection summary,
            long recentLimit,
            Long currentTurnId) {
        return request(turns, boundary, summary, recentLimit,
                currentTurnId, AgentLongTermMemoryContext.empty(),
                new AgentRagExperimentResult(null, null));
    }

    private static V2ContextAssemblyRequest request(
            List<V2CanonicalTurnProjection> turns,
            V2CanonicalTurnProjection boundary,
            V2SummaryProjection summary,
            long recentLimit,
            Long currentTurnId,
            AgentLongTermMemoryContext memory,
            AgentRagExperimentResult rag) {
        return new V2ContextAssemblyRequest(
                7L, 9L, currentTurnId, 64,
                recentLimit, 1_000, 1_000, 1_000, 1_000,
                turns, boundary, summary, memory, rag, List.of());
    }

    private static V2CanonicalTurnProjection turn(
            Long turnId,
            Long userMessageId,
            Long assistantMessageId,
            String content) {
        return new V2CanonicalTurnProjection(
                turnId, 7L, 9L, userMessageId, assistantMessageId,
                Instant.EPOCH.plusSeconds(turnId),
                "COMPLETED", "user", "assistant",
                "user-" + content, "assistant-" + content);
    }

    private static V2SummaryProjection summary(
            Long coveredMessageId,
            String content) {
        return new V2SummaryProjection(1L, 7L, 9L,
                coveredMessageId, 1, content, "v1", sha256(content));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
