package com.yanban.api.agent.v2.context.assembly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.AgentLongTermMemoryContext;
import com.yanban.api.agent.AgentMemorySelectionRef;
import com.yanban.api.agent.AgentRagExperimentResult;
import com.yanban.api.agent.AgentRagSelectionRef;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.Utf8ByteTokenCounter;
import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import com.yanban.api.agent.v2.context.V2ContextSectionStatus;
import com.yanban.api.agent.v2.context.VersionedTokenCounter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class V2NewTurnContextAssembler {
    private final ObjectMapper json;
    private final VersionedTokenCounter tokens;

    @Autowired
    public V2NewTurnContextAssembler(ObjectMapper json) {
        this(json, new Utf8ByteTokenCounter());
    }

    V2NewTurnContextAssembler(
            ObjectMapper json,
            VersionedTokenCounter tokens) {
        this.json = json;
        this.tokens = tokens;
    }

    public V2ContextAssemblyResult assemble(V2ContextAssemblyRequest request) {
        validateSummary(request);
        Recent recent = recent(request);
        return new V2ContextAssemblyResult(List.of(
                recent.section(),
                summary(request),
                memory(request),
                rag(request),
                historical(request)), recent.turnIds(), recent.evictedTurnIds());
    }

    private Recent recent(V2ContextAssemblyRequest request) {
        List<V2CanonicalTurnProjection> eligible = request.boundedTurns().stream()
                .peek(turn -> validateTurn(turn, request.userId(), request.sessionId()))
                .filter(turn -> !turn.turnId().equals(request.currentTurnId()))
                .filter(turn -> afterCoverage(turn,
                        request.summary() == null ? null
                                : request.summary().coveredMessageId()))
                .sorted(turnOrder().reversed())
                .toList();
        boolean overflow = eligible.size() > request.maxRecentTurns();
        List<V2CanonicalTurnProjection> evicted = new ArrayList<>(
                eligible.stream().skip(request.maxRecentTurns()).toList());
        List<V2CanonicalTurnProjection> selected = new ArrayList<>(
                eligible.stream().limit(request.maxRecentTurns())
                        .sorted(turnOrder())
                        .toList());
        long before = tokens.count(turnProjection(eligible.stream()
                .sorted(turnOrder())
                .toList()));
        while (!selected.isEmpty()
                && tokens.count(turnProjection(selected))
                        > request.recentTokenLimit()) {
            evicted.add(selected.remove(0));
            overflow = true;
        }
        String projection = turnProjection(selected);
        List<V2CanonicalTurnProjection> orderedEvicted = evicted.stream()
                .distinct().sorted(turnOrder()).toList();
        ObjectNode refs = json.createObjectNode();
        refs.set("selected", turnRefs(selected));
        refs.set("evicted", turnRefs(orderedEvicted));
        return new Recent(section(ContextSectionType.RECENT_CONVERSATION,
                request.recentTokenLimit(), before, tokens.count(projection),
                write(refs), projection, overflow),
                selected.stream().map(V2CanonicalTurnProjection::turnId).toList(),
                orderedEvicted.stream().map(V2CanonicalTurnProjection::turnId).toList());
    }

    private void validateSummary(V2ContextAssemblyRequest request) {
        V2SummaryProjection summary = request.summary();
        if (summary == null) return;
        if (!request.userId().equals(summary.userId())
                || !request.sessionId().equals(summary.sessionId())) {
            throw new IllegalArgumentException("summary authority mismatch");
        }
        if (summary.sourceVersion() == null
                || summary.sourceVersion().isBlank()
                || summary.sourceVersion().length() > 128
                || summary.digest() == null
                || !summary.digest().equals(sha256(text(summary.content())))) {
            throw new IllegalArgumentException("summary source version or digest is invalid");
        }
        if (summary.coveredMessageId() == null) return;
        V2CanonicalTurnProjection boundary = request.coverageBoundary();
        validateTurn(boundary, request.userId(), request.sessionId());
        if (summary.coveredMessageId().equals(boundary.userMessageId())) {
            throw new IllegalStateException("summary coverage ends at half-turn");
        }
        if (!summary.coveredMessageId().equals(boundary.assistantMessageId())) {
            throw new IllegalStateException("summary coverage boundary is unknown");
        }
    }

    private void validateTurn(
            V2CanonicalTurnProjection turn,
            Long userId,
            Long sessionId) {
        if (turn == null || turn.turnId() == null
                || !userId.equals(turn.userId())
                || !sessionId.equals(turn.sessionId())
                || turn.userMessageId() == null
                || turn.assistantMessageId() == null
                || turn.startedAt() == null
                || !("COMPLETED".equals(turn.status())
                    || "FAILED".equals(turn.status()))
                || !"user".equalsIgnoreCase(turn.userRole())
                || !"assistant".equalsIgnoreCase(turn.assistantRole())) {
            throw new IllegalArgumentException(
                    "canonical turn projection is invalid");
        }
    }

    private boolean afterCoverage(
            V2CanonicalTurnProjection turn,
            Long coverage) {
        if (coverage == null) return true;
        if (turn.userMessageId() <= coverage
                && turn.assistantMessageId() > coverage) {
            throw new IllegalStateException("turn crosses summary coverage");
        }
        return turn.userMessageId() > coverage
                && turn.assistantMessageId() > coverage;
    }

    private V2ContextSectionDraft summary(V2ContextAssemblyRequest request) {
        ArrayNode refs = json.createArrayNode();
        ObjectNode projection = json.createObjectNode();
        if (request.summary() != null) {
            V2SummaryProjection value = request.summary();
            ObjectNode ref = refs.addObject();
            ref.put("summaryId", value.summaryId());
            if (value.coveredMessageId() == null) ref.putNull("coveredMessageId");
            else ref.put("coveredMessageId", value.coveredMessageId());
            ref.put("messageCount", value.messageCount());
            ref.put("version", value.sourceVersion());
            ref.put("digest", value.digest());
            projection.put("summary", text(value.content()));
        }
        return whole(ContextSectionType.CONVERSATION_SUMMARY,
                request.summaryTokenLimit(), write(refs), write(projection));
    }

    private V2ContextSectionDraft memory(V2ContextAssemblyRequest request) {
        AgentLongTermMemoryContext memory = request.memory();
        List<AgentMemorySelectionRef> ranked = memory.selectedRefs().stream()
                .filter(value -> value.projection() != null)
                .sorted(Comparator.comparingInt(value -> value.rank()))
                .toList();
        List<AgentMemorySelectionRef> selected = new ArrayList<>();
        for (AgentMemorySelectionRef item : ranked) {
            List<AgentMemorySelectionRef> candidate = new ArrayList<>(selected);
            candidate.add(item);
            if (tokens.count(memoryProjection(candidate))
                    > request.memoryTokenLimit()) break;
            selected.add(item);
        }
        String beforeProjection = memoryProjection(ranked);
        String projection = memoryProjection(selected);
        return section(ContextSectionType.LONG_TERM_MEMORY,
                request.memoryTokenLimit(), tokens.count(beforeProjection),
                tokens.count(projection), memoryRefs(selected), projection,
                selected.size() < memory.selectedRefs().size());
    }

    private V2ContextSectionDraft rag(V2ContextAssemblyRequest request) {
        AgentRagExperimentResult rag = request.rag();
        Map<Integer, AgentRagSelectionRef> refsByRank = rag.selectedRefs().stream()
                .collect(Collectors.toMap(AgentRagSelectionRef::rank,
                        Function.identity(), (left, right) -> left));
        List<RagItem> ranked = new ArrayList<>();
        for (int index = 0; index < rag.retrievedChunks().size(); index++) {
            AgentRagSelectionRef ref = refsByRank.get(index + 1);
            if (ref != null) {
                var chunk = rag.retrievedChunks().get(index);
                ranked.add(new RagItem(ref, chunk.documentId(),
                        chunk.chunkIndex(), chunk.versionNo(), text(chunk.content())));
            }
        }
        long before = tokens.count(ragProjection(ranked));
        List<RagItem> selected = new ArrayList<>();
        for (RagItem item : ranked) {
            List<RagItem> candidate = new ArrayList<>(selected);
            candidate.add(item);
            if (tokens.count(write(candidate.stream()
                    .map(RagItem::projection).toList()))
                    > request.ragTokenLimit()) break;
            selected.add(item);
        }
        boolean overflow = selected.size() < ranked.size();
        return section(ContextSectionType.RAG_EVIDENCE,
                request.ragTokenLimit(), before, tokens.count(ragProjection(selected)),
                write(selected.stream().map(RagItem::ref).toList()),
                ragProjection(selected),
                overflow);
    }

    private V2ContextSectionDraft historical(V2ContextAssemblyRequest request) {
        List<V2HistoricalTerminalFact> ordered = request.historicalFacts().stream()
                .peek(fact -> {
                    if (!request.userId().equals(fact.userId())
                            || !request.sessionId().equals(fact.sessionId())) {
                        throw new IllegalArgumentException(
                                "historical fact authority mismatch");
                    }
                })
                .filter(fact -> !fact.turnId().equals(request.currentTurnId()))
                .sorted(Comparator.comparingInt(V2HistoricalTerminalFact::rank))
                .toList();
        List<V2HistoricalTerminalFact> selected = new ArrayList<>();
        for (V2HistoricalTerminalFact fact : ordered) {
            List<V2HistoricalTerminalFact> candidate = new ArrayList<>(selected);
            candidate.add(fact);
            if (tokens.count(historicalProjection(candidate))
                    > request.historicalTokenLimit()) break;
            selected.add(fact);
        }
        return section(ContextSectionType.STEP_STATE,
                request.historicalTokenLimit(), tokens.count(historicalProjection(ordered)),
                tokens.count(historicalProjection(selected)), historicalRefs(selected),
                historicalProjection(selected),
                selected.size() < ordered.size());
    }

    private V2ContextSectionDraft whole(
            ContextSectionType type,
            long limit,
            String refs,
            String projection) {
        long count = tokens.count(projection);
        return section(type, limit, count, count, refs, projection, count > limit);
    }

    private V2ContextSectionDraft section(
            ContextSectionType type,
            long limit,
            long before,
            long after,
            String refs,
            String projection,
            boolean overflow) {
        return new V2ContextSectionDraft(type, type.percentage(), limit,
                before, after, overflow
                        ? V2ContextSectionStatus.COMPACTION_REQUIRED
                        : V2ContextSectionStatus.READY,
                refs, projection,
                overflow ? "SECTION_TOKEN_LIMIT_EXCEEDED" : null);
    }

    private String turnProjection(List<V2CanonicalTurnProjection> turns) {
        ArrayNode array = json.createArrayNode();
        turns.forEach(turn -> {
            ObjectNode item = array.addObject();
            item.put("turnId", turn.turnId());
            item.put("user", text(turn.userContent()));
            item.put("assistant", text(turn.assistantContent()));
        });
        return write(array);
    }

    private ArrayNode turnRefs(List<V2CanonicalTurnProjection> turns) {
        ArrayNode refs = json.createArrayNode();
        turns.forEach(turn -> {
            ObjectNode ref = refs.addObject();
            ref.put("turnId", turn.turnId());
            ref.put("userMessageId", turn.userMessageId());
            ref.put("assistantMessageId", turn.assistantMessageId());
            ref.put("startedAt", turn.startedAt().toString());
            ref.put("status", turn.status());
        });
        return refs;
    }

    private Comparator<V2CanonicalTurnProjection> turnOrder() {
        return Comparator.comparing(V2CanonicalTurnProjection::startedAt)
                .thenComparing(V2CanonicalTurnProjection::turnId);
    }

    private String memoryProjection(List<AgentMemorySelectionRef> refs) {
        ArrayNode projection = json.createArrayNode();
        refs.forEach(value -> {
            ObjectNode item = projection.addObject();
            item.put("rank", value.rank());
            item.put("content", value.projection());
        });
        return write(projection);
    }

    private String memoryRefs(List<AgentMemorySelectionRef> refs) {
        ArrayNode result = json.createArrayNode();
        refs.forEach(value -> {
            ObjectNode ref = result.addObject();
            ref.put("stableId", value.stableId());
            ref.put("version", value.version());
            ref.put("rank", value.rank());
            ref.put("digest", value.digest());
        });
        return write(result);
    }

    private String ragProjection(List<RagItem> items) {
        ArrayNode projection = json.createArrayNode();
        items.forEach(item -> projection.add(item.projection()));
        return write(projection);
    }

    private String historicalRefs(List<V2HistoricalTerminalFact> facts) {
        ArrayNode refs = json.createArrayNode();
        facts.forEach(value -> {
            ObjectNode ref = refs.addObject();
            ref.put("turnId", value.turnId());
            ref.put("stableId", value.stableId());
            ref.put("version", value.version());
            ref.put("rank", value.rank());
            ref.put("digest", value.digest());
        });
        return write(refs);
    }

    private String historicalProjection(List<V2HistoricalTerminalFact> facts) {
        ArrayNode projection = json.createArrayNode();
        facts.forEach(fact -> {
            ObjectNode item = projection.addObject();
            item.put("status", fact.finalStatus());
            item.put("errorCode", text(fact.errorCode()));
            if (fact.planId() == null) item.putNull("planId");
            else item.put("planId", fact.planId());
            if (fact.resultId() == null) item.putNull("resultId");
            else item.put("resultId", fact.resultId());
            if (fact.candidateArtifactId() == null) item.putNull("candidateArtifactId");
            else item.put("candidateArtifactId", fact.candidateArtifactId());
        });
        return write(projection);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception impossible) {
            throw new IllegalStateException("context assembly JSON failed", impossible);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record Recent(
            V2ContextSectionDraft section,
            List<Long> turnIds,
            List<Long> evictedTurnIds) { }

    private record RagItem(
            AgentRagSelectionRef ref,
            Long documentId,
            Integer chunkIndex,
            Integer version,
            String content) {
        private ObjectNode projection() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode result = mapper.createObjectNode();
            if (documentId == null) result.putNull("documentId");
            else result.put("documentId", documentId);
            if (chunkIndex == null) result.putNull("chunkIndex");
            else result.put("chunkIndex", chunkIndex);
            if (version == null) result.putNull("version");
            else result.put("version", version);
            result.put("content", content);
            return result;
        }
    }
}
