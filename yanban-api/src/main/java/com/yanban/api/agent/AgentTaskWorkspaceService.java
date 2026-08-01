package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.agent.AgentTaskOutcome;
import com.yanban.core.agent.AgentTaskWorkspace;
import com.yanban.core.agent.AgentWorkspaceMemoryItem;
import com.yanban.core.agent.AgentWorkspaceMemoryType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Bounded Task Workspace assembly. Durable capability comes only from the canonical Project Plan. */
@Service
public class AgentTaskWorkspaceService {
    private final ObjectMapper objectMapper;
    private final int maxCharacters;
    private final int maxObservations;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentTaskWorkspaceService(ObjectMapper objectMapper) { this(objectMapper, 12_000, 24); }
    AgentTaskWorkspaceService(ObjectMapper objectMapper, int maxCharacters, int maxObservations) {
        this.objectMapper = objectMapper;
        this.maxCharacters = Math.max(128, maxCharacters);
        this.maxObservations = Math.max(1, maxObservations);
    }

    public AgentTaskWorkspace capture(AgentRuntimeRequest request, AgentRuntimeResult result, AgentRunProjection projection) {
        requireTrustedScope(request, projection.identity());
        Map<String, AgentWorkspaceMemoryItem> unique = new LinkedHashMap<>();
        int dropped = 0;
        for (EvidenceRef ref : result.trustedEvidenceLedger().evidence())
            dropped += add(unique, new AgentWorkspaceMemoryItem(AgentWorkspaceMemoryType.TRUSTED_EVIDENCE,
                    ref.id(), ref.sourceType() + ":" + ref.source() + (ref.file() == null ? "" : ":" + ref.file())));
        int observations = 0;
        for (String observation : result.toolTrace()) {
            if (!StringUtils.hasText(observation)) continue;
            if (observations++ >= maxObservations) { dropped++; continue; }
            dropped += add(unique, new AgentWorkspaceMemoryItem(AgentWorkspaceMemoryType.TOOL_OBSERVATION, null, observation));
        }
        if (!result.success() && StringUtils.hasText(result.errorMessage()))
            dropped += add(unique, new AgentWorkspaceMemoryItem(AgentWorkspaceMemoryType.FAILURE_RESULT,
                    result.stopReason() == null ? null : result.stopReason().name(), result.errorMessage()));
        CandidateChangeSet candidate = result.candidateChangeSet();
        if (candidate != null) {
            dropped += add(unique, new AgentWorkspaceMemoryItem(AgentWorkspaceMemoryType.CANDIDATE_REFERENCE,
                    candidate.relativePath(), candidate.summary()));
            if (candidate.artifactId() != null)
                dropped += add(unique, new AgentWorkspaceMemoryItem(AgentWorkspaceMemoryType.ARTIFACT_REFERENCE,
                        candidate.artifactId().toString(), candidate.relativePath()));
        }
        String summary = "status=" + projection.state().status() + ", phase=" + projection.state().phase()
                + ", outcome=" + projection.state().outcome() + ", steps=" + Math.max(0, result.steps());
        dropped += add(unique, new AgentWorkspaceMemoryItem(AgentWorkspaceMemoryType.AUDIT_SUMMARY,
                projection.identity().runId(), summary));
        List<AgentWorkspaceMemoryItem> bounded = new ArrayList<>();
        int used = length(request.userMessage()) + length(projection.canonicalAnswer()) + length(summary);
        for (AgentWorkspaceMemoryItem item : unique.values()) {
            int size = length(item.reference()) + length(item.content());
            if (used + size > maxCharacters) { dropped++; continue; }
            bounded.add(item); used += size;
        }
        List<String> remaining = switch (projection.state().status()) {
            case WAITING_INPUT -> List.of("Await trusted user input before continuing.");
            case PAUSED -> List.of("Resume the same canonical run before continuing.");
            case CANCELLED, STOPPED -> List.of();
            case FAILED -> List.of("Review the audited failure and retry through the canonical lifecycle.");
            case COMPLETED -> projection.state().outcome() == AgentTaskOutcome.PARTIAL
                    ? List.of("Complete work omitted by the controlled partial result.") : List.of();
            default -> List.of("Continue the current canonical run.");
        };
        String strategy = result.selectedStrategy() == null ? "runtime" : result.selectedStrategy().name();
        return new AgentTaskWorkspace(projection.identity(), projection.state(), request.userMessage(),
                List.of("Produce a canonical answer consistent with the run outcome."),
                List.of("Canonical run reference: " + projection.identity().runId(),
                        "Selected strategy: " + strategy),
                List.of("Runtime steps observed: " + Math.max(0, result.steps())), remaining, bounded, summary,
                projection.canonicalAnswer(), projection.persistenceLevel(), projection.checkpointAvailable(),
                projection.restartResumable(), dropped);
    }

    /** Export boundary only; L2 persistence is owned by the canonical Plan row, never this JSON. */
    public String checkpoint(AgentTaskWorkspace workspace) {
        try { return objectMapper.writeValueAsString(workspace); }
        catch (Exception ex) { throw new IllegalStateException("workspace checkpoint serialization failed", ex); }
    }
    /**
     * Restores only bounded auditable memory. Identity, lifecycle, canonical answer and L0 capability
     * are rebuilt from server-owned request/projection and are never trusted from snapshot JSON.
     */
    public Optional<AgentTaskWorkspace> restore(String snapshot, AgentRuntimeRequest trustedRequest,
                                                AgentRunProjection trustedProjection) {
        if (!StringUtils.hasText(snapshot) || trustedRequest == null || trustedProjection == null) return Optional.empty();
        try {
            AgentTaskWorkspace untrusted = objectMapper.readValue(snapshot, AgentTaskWorkspace.class);
            requireTrustedScope(trustedRequest, trustedProjection.identity());
            if (!trustedProjection.identity().equals(untrusted.identity())) return Optional.empty();
            List<AgentWorkspaceMemoryItem> bounded = new ArrayList<>();
            Map<String, AgentWorkspaceMemoryItem> unique = new LinkedHashMap<>();
            int dropped = Math.max(0, untrusted.droppedItemCount());
            int observations = 0;
            int used = length(trustedRequest.userMessage()) + length(trustedProjection.canonicalAnswer());
            for (AgentWorkspaceMemoryItem item : untrusted.memory()) {
                if (item == null || item.type() == null || !StringUtils.hasText(item.content())) { dropped++; continue; }
                if (item.type() == AgentWorkspaceMemoryType.TOOL_OBSERVATION && observations++ >= maxObservations) {
                    dropped++; continue;
                }
                AgentWorkspaceMemoryItem recovered = new AgentWorkspaceMemoryItem(
                        AgentWorkspaceMemoryType.AUDIT_SUMMARY,
                        null,
                        "recovered/untrusted snapshot entry; originalType=" + item.type() + "; content=" + item.content());
                if (unique.putIfAbsent(recovered.deduplicationKey(), recovered) != null) { dropped++; continue; }
                int size = length(recovered.content());
                if (used + size > maxCharacters) { dropped++; continue; }
                bounded.add(recovered); used += size;
            }
            String summary = "status=" + trustedProjection.state().status() + ", phase="
                    + trustedProjection.state().phase() + ", outcome=" + trustedProjection.state().outcome()
                    + ", recovery=audited_memory_only";
            return Optional.of(new AgentTaskWorkspace(trustedProjection.identity(), trustedProjection.state(),
                    trustedRequest.userMessage(),
                    List.of("Produce a canonical answer consistent with the trusted run outcome."),
                    List.of("Canonical run reference: " + trustedProjection.identity().runId()),
                    List.of("Recovered audited memory only; runtime step details are unavailable."),
                    remainingWork(trustedProjection), bounded, summary, trustedProjection.canonicalAnswer(),
                    trustedProjection.persistenceLevel(), trustedProjection.checkpointAvailable(),
                    trustedProjection.restartResumable(), dropped));
        } catch (Exception ex) { return Optional.empty(); }
    }

    private List<String> remainingWork(AgentRunProjection projection) {
        return switch (projection.state().status()) {
            case WAITING_INPUT -> List.of("Await trusted user input before continuing.");
            case PAUSED -> List.of("Resume the same canonical run before continuing.");
            case CANCELLED, STOPPED -> List.of();
            case FAILED -> List.of("Review the audited failure and retry through the canonical lifecycle.");
            case COMPLETED -> projection.state().outcome() == AgentTaskOutcome.PARTIAL
                    ? List.of("Complete work omitted by the controlled partial result.") : List.of();
            default -> List.of("Continue the current canonical run.");
        };
    }
    private void requireTrustedScope(AgentRuntimeRequest request, AgentRunIdentity identity) {
        Long projectId = request.projectContext() == null ? null : request.projectContext().projectId();
        if (!request.userId().equals(identity.userId()) || !java.util.Objects.equals(request.sessionId(), identity.sessionId())
                || !java.util.Objects.equals(projectId, identity.projectId()))
            throw new IllegalArgumentException("workspace identity must come from the trusted runtime scope");
    }
    private int add(Map<String, AgentWorkspaceMemoryItem> items, AgentWorkspaceMemoryItem item) {
        return items.putIfAbsent(item.deduplicationKey(), item) == null ? 0 : 1;
    }
    private int length(String value) { return value == null ? 0 : value.length(); }
}
