package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ToolCall;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Maps model-visible portable provenance back to exactly one server-trusted ledger entry. */
@Component
final class CandidateProposalEvidenceSelector {
    record PortableEvidenceSelector(ProjectRelativePath relativePath, FileHash fileHash,
                                    int startLine, int endLine, String parserVersion) {
        PortableEvidenceSelector {
            if (relativePath == null || fileHash == null || startLine < 1 || endLine < startLine
                    || !StringUtils.hasText(parserVersion)) {
                throw new IllegalArgumentException("portable Evidence selector is incomplete");
            }
        }
    }

    record Selection(EvidenceLedger ledger, List<List<String>> evidenceIds) {
        Selection { evidenceIds = evidenceIds.stream().map(List::copyOf).toList(); }
    }

    private final ObjectMapper objectMapper;
    private final ProjectEvidenceValidator validator;

    CandidateProposalEvidenceSelector(ObjectMapper objectMapper, ProjectEvidenceValidator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    Selection select(CandidateProposalExecutionScope.Context scope, ProjectVersionRef currentVersion,
                     List<List<PortableEvidenceSelector>> requested) {
        EvidenceLedger trusted = currentTrustedLedger(scope);
        List<List<String>> idsByChange = new ArrayList<>();
        for (List<PortableEvidenceSelector> changeSelectors : requested) {
            List<String> ids = new ArrayList<>();
            for (PortableEvidenceSelector selector : changeSelectors) {
                List<EvidenceRef> matches = trusted.evidence().stream()
                        .filter(ref -> exact(ref, selector, currentVersion)).toList();
                if (matches.isEmpty()) {
                    throw new IllegalArgumentException(noExactMatchMessage(selector, trusted, currentVersion));
                }
                // Re-reading the same current file range creates a new trusted observation id, but not a
                // different portable fact. Prefer the latest exact observation deterministically. Hash,
                // ProjectVersion, range and parser mismatches are still excluded by exact().
                ids.add(matches.get(matches.size() - 1).id());
            }
            if (ids.stream().distinct().count() != ids.size()) {
                throw new IllegalArgumentException("a change contains duplicate Evidence selectors");
            }
            idsByChange.add(List.copyOf(ids));
        }
        return new Selection(trusted, List.copyOf(idsByChange));
    }

    private String noExactMatchMessage(PortableEvidenceSelector selector, EvidenceLedger trusted,
                                       ProjectVersionRef currentVersion) {
        List<String> observedRanges = trusted.evidence().stream()
                .filter(ref -> ref != null && ref.sourceType() == EvidenceSourceType.PROJECT
                        && ref.versionStatus() == EvidenceVersionStatus.VERIFIED
                        && ProjectEvidenceValidator.isTrusted(ref)
                        && currentVersion.value().equals(ref.projectVersion())
                        && selector.relativePath().value().equals(ref.file())
                        && selector.fileHash().sha256().equals(ref.fileHash())
                        && selector.parserVersion().equals(ref.parserVersion()))
                .map(ref -> ref.startLine() + "-" + ref.endLine())
                .distinct()
                .limit(8)
                .toList();
        String available = observedRanges.isEmpty() ? "none" : String.join(", ", observedRanges);
        return "Evidence selector has no exact current trusted match for "
                + selector.relativePath().value() + " lines " + selector.startLine() + "-" + selector.endLine()
                + ". A selector must exactly equal one completed project_read_file/project_search observation; "
                + "a subrange of a larger read is not accepted. Exact observed ranges for this file/hash/parser: "
                + available + ". Re-read exactly lines " + selector.startLine() + "-" + selector.endLine()
                + " and then retry project_propose_candidate with that same range.";
    }

    private EvidenceLedger currentTrustedLedger(CandidateProposalExecutionScope.Context scope) {
        Map<String, EvidenceRef> refs = new LinkedHashMap<>();
        scope.inheritedEvidence().evidence().forEach(ref -> putExact(refs, ref));
        Map<String, String> calls = currentToolCalls(scope);
        List<ChatMessage> messages = scope.transcript();
        for (int index = Math.max(0, scope.currentTurnStart()); index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message == null || !"tool".equals(message.role()) || !StringUtils.hasText(message.toolCallId())
                    || !calls.containsKey(message.toolCallId()) || !StringUtils.hasText(message.content())) continue;
            String toolName = calls.get(message.toolCallId());
            if ("project_read_file".equals(toolName) || "project_search".equals(toolName)) {
                collectProjectReadEvidence(scope, message, toolName, refs);
            }
        }
        ProjectRuntimeContext context = new ProjectRuntimeContext(scope.userId(), scope.projectId());
        Set<String> researchTools = ResearchProjectEvidenceAdapter.allowedResearchTools(
                scope.allowedTools().stream().toList());
        for (EvidenceRef ref : ResearchProjectEvidenceAdapter.extract(objectMapper, messages,
                scope.currentTurnStart(), context, researchTools).evidence()) putExact(refs, ref);
        return validator.current(scope.userId(), context, new EvidenceLedger(List.copyOf(refs.values())));
    }

    private Map<String, String> currentToolCalls(CandidateProposalExecutionScope.Context scope) {
        Map<String, String> calls = new LinkedHashMap<>();
        List<ChatMessage> messages = scope.transcript();
        for (int index = Math.max(0, scope.currentTurnStart()); index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message == null || message.toolCalls() == null) continue;
            for (ToolCall call : message.toolCalls()) {
                if (call != null && call.function() != null && StringUtils.hasText(call.id())
                        && scope.allowedTools().contains(call.function().name())) {
                    calls.put(call.id(), call.function().name());
                }
            }
        }
        return calls;
    }

    private void collectProjectReadEvidence(CandidateProposalExecutionScope.Context scope, ChatMessage message,
                                            String toolName, Map<String, EvidenceRef> refs) {
        try {
            JsonNode node = objectMapper.readTree(message.content());
            if (node.path("projectId").asLong(-1) != scope.projectId()) return;
            if ("project_search".equals(toolName) && node.path("hits").isArray()) {
                int hitIndex = 0;
                for (JsonNode hit : node.path("hits")) {
                    EvidenceRef ref = attest(scope, hit.path("relativePath").asText(""),
                            hit.path("hash").asText(hit.path("version").asText("")),
                            hit.path("lineNumber").asInt(0), hit.path("lineNumber").asInt(0),
                            hit.path("parserVersion").asText(""),
                            message.toolCallId() + ":hit:" + hitIndex++);
                    if (ref != null) putExact(refs, ref);
                }
                return;
            }
            EvidenceRef ref = attest(scope, node.path("relativePath").asText(""),
                    node.path("hash").asText(node.path("version").asText("")),
                    node.path("startLine").asInt(0), node.path("endLine").asInt(0),
                    node.path("parserVersion").asText(""), message.toolCallId());
            if (ref != null) putExact(refs, ref);
        } catch (Exception ignored) {
            // Tool output cannot become Evidence unless every field is re-attested by the server.
        }
    }

    private EvidenceRef attest(CandidateProposalExecutionScope.Context scope, String path, String hash,
                               int startLine, int endLine, String parserVersion, String callId) {
        if (!StringUtils.hasText(path) || !StringUtils.hasText(hash) || !StringUtils.hasText(parserVersion)
                || startLine < 1 || endLine < startLine) return null;
        return validator.attestCurrentFile(scope.userId(), new ProjectRuntimeContext(scope.userId(), scope.projectId()),
                "trusted-tool:" + scope.projectId() + ":" + path + ":" + hash + ":" + callId,
                path, hash, startLine, endLine, parserVersion, "tool:" + callId,
                "current governed Project tool observation");
    }

    private boolean exact(EvidenceRef ref, PortableEvidenceSelector selector, ProjectVersionRef currentVersion) {
        return ref != null && ref.sourceType() == EvidenceSourceType.PROJECT
                && ref.versionStatus() == EvidenceVersionStatus.VERIFIED && ProjectEvidenceValidator.isTrusted(ref)
                && currentVersion.value().equals(ref.projectVersion())
                && selector.relativePath().value().equals(ref.file())
                && selector.fileHash().sha256().equals(ref.fileHash())
                && selector.startLine() == ref.startLine() && selector.endLine() == ref.endLine()
                && selector.parserVersion().equals(ref.parserVersion());
    }

    private void putExact(Map<String, EvidenceRef> values, EvidenceRef ref) {
        if (ref == null) return;
        EvidenceRef existing = values.putIfAbsent(ref.id(), ref);
        if (existing != null && !existing.equals(ref)) {
            throw new IllegalArgumentException("conflicting trusted Evidence id");
        }
    }
}
