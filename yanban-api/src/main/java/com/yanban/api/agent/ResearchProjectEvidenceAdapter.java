package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ToolCall;
import com.yanban.core.research.ResearchEvidenceRef;
import com.yanban.core.research.ResearchToolContracts;
import com.yanban.core.research.TrustLabel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * Converts only a governed research-tool envelope into the legacy trusted evidence ledger.
 * It deliberately ignores item prose and never accepts a Project id from tool JSON.
 */
final class ResearchProjectEvidenceAdapter {
    private static final Set<String> RESEARCH_TOOLS = ResearchToolContracts.all().stream()
            .map(contract -> contract.definition().name()).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private ResearchProjectEvidenceAdapter() { }

    static boolean isResearchTool(String name) { return RESEARCH_TOOLS.contains(name); }

    static Set<String> allResearchTools() { return RESEARCH_TOOLS; }

    static Set<String> allowedResearchTools(List<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) return Set.of();
        return allowedTools.stream().filter(RESEARCH_TOOLS::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static EvidenceLedger extract(ObjectMapper mapper, List<ChatMessage> messages, int start,
                                  ProjectRuntimeContext context, Set<String> allowedResearchTools) {
        if (mapper == null || messages == null || context == null || allowedResearchTools == null
                || allowedResearchTools.isEmpty()) return EvidenceLedger.empty();
        Map<String, String> callNames = new LinkedHashMap<>();
        for (int index = Math.max(0, start); index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message == null || message.toolCalls() == null) continue;
            for (ToolCall call : message.toolCalls()) if (call != null && call.function() != null
                    && StringUtils.hasText(call.id()) && RESEARCH_TOOLS.contains(call.function().name())
                    && allowedResearchTools.contains(call.function().name())) {
                callNames.put(call.id(), call.function().name());
            }
        }
        Map<String, EvidenceRef> refs = new LinkedHashMap<>();
        for (int i = Math.max(0, start); i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message == null || !"tool".equals(message.role()) || !StringUtils.hasText(message.toolCallId())
                    || !callNames.containsKey(message.toolCallId()) || !StringUtils.hasText(message.content())) continue;
            try {
                JsonNode result = mapper.readTree(message.content());
                String status = result.path("status").asText("");
                if (!("COMPLETE".equals(status) || "PARTIAL".equals(status) || "TRUNCATED".equals(status))) continue;
                JsonNode envelope = result.path("evidenceRefs");
                if (!envelope.isArray()) continue;
                for (JsonNode node : envelope) {
                    ResearchEvidenceRef evidence = mapper.treeToValue(node, ResearchEvidenceRef.class);
                    if (!valid(evidence)) continue;
                    String hash = evidence.fileHash().sha256();
                    String callId = message.toolCallId();
                    EvidenceRef ref = new EvidenceRef("trusted-tool:" + context.projectId() + ":" + evidence.relativePath().value()
                            + ":" + hash + ":" + callId, EvidenceSourceType.PROJECT, "PROJECT", evidence.relativePath().value(),
                            "tool:" + callId, null, hash, "governed research tool evidence",
                            evidence.projectVersion().value(), hash, evidence.range().startLine(),
                            evidence.range().endLine(), evidence.parserVersion().value(), EvidenceVersionStatus.VERIFIED);
                    EvidenceRef existing = refs.putIfAbsent(ref.id(), ref);
                    if (existing != null && !existing.equals(ref)) throw new IllegalArgumentException("conflicting research evidence id");
                }
            } catch (Exception ignored) {
                // Result text is untrusted unless it is a complete, typed research envelope.
            }
        }
        return new EvidenceLedger(List.copyOf(refs.values()));
    }

    private static boolean valid(ResearchEvidenceRef evidence) {
        return evidence != null && evidence.projectVersion() != null && evidence.relativePath() != null
                && evidence.fileHash() != null && evidence.range() != null && evidence.parserVersion() != null
                && evidence.trustLabel() == TrustLabel.SERVER_ATTESTED_METADATA;
    }
}
