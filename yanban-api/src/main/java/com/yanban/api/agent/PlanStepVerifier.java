package com.yanban.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanStep;
import com.yanban.core.agent.AgentPlanStepStatus;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PlanStepVerifier {

    private static final Logger log = LoggerFactory.getLogger(PlanStepVerifier.class);

    private final ChatModelProvider modelProvider;
    private final ObjectMapper objectMapper;

    public PlanStepVerifier(@Qualifier("chatModelProvider") ChatModelProvider modelProvider,
                            ObjectMapper objectMapper) {
        this.modelProvider = modelProvider;
        this.objectMapper = objectMapper;
    }

    public VerificationResult verify(VerificationRequest request) {
        VerificationResult deterministicFailure = deterministicFailure(request);
        if (deterministicFailure != null) {
            logDecision(request, deterministicFailure);
            return deterministicFailure;
        }
        if (!StringUtils.hasText(request.successCriteria())) {
            return VerificationResult.passed("No explicit success criteria were provided.");
        }
        try {
            ChatResponse response = callVerifier(request, List.of(
                    ChatMessage.system(buildVerifierSystemPrompt()),
                    ChatMessage.user(buildVerifierUserMessage(request))), 256);
            String content = response == null || response.message() == null ? null : response.message().content();
            logVerifierResponse(request, response, content, 1);
            if (!StringUtils.hasText(content)) {
                return VerificationResult.inconclusive("Verifier returned an empty response.");
            }
            try {
                VerificationResult decision = parseVerification(content, request);
                logDecision(request, decision);
                return decision;
            } catch (Exception ex) {
                log.warn("Plan step verifier returned invalid JSON stepKey={} attempt=1 finishReason={} error={}",
                        request.step().getStepKey(), response == null ? null : response.finishReason(), abbreviate(ex.getMessage(), 300));
                ChatResponse repaired = callVerifier(request, List.of(
                        ChatMessage.system(buildRepairSystemPrompt()),
                        ChatMessage.user(buildRepairUserMessage(request, content))), 160);
                String repairedContent = repaired == null || repaired.message() == null ? null : repaired.message().content();
                logVerifierResponse(request, repaired, repairedContent, 2);
                try {
                    VerificationResult decision = parseVerification(repairedContent, request);
                    logDecision(request, decision);
                    return decision;
                } catch (Exception repairError) {
                    log.warn("Plan step verifier repair returned invalid JSON stepKey={} attempt=2 finishReason={} error={}",
                            request.step().getStepKey(), repaired == null ? null : repaired.finishReason(),
                            abbreviate(repairError.getMessage(), 300));
                    return VerificationResult.inconclusive("Verifier returned invalid JSON or an incomplete structured decision after one bounded repair: "
                            + abbreviate(repairError.getMessage(), 140) + finishReasonSuffix(response, repaired));
                }
            }
        } catch (Exception ex) {
            log.warn("Plan step verification failed stepKey={}", request.step().getStepKey(), ex);
            return VerificationResult.inconclusive("Verifier error: " + abbreviate(ex.getMessage(), 300));
        }
    }

    private VerificationResult deterministicFailure(VerificationRequest request) {
        if (request == null || request.step() == null || request.plan() == null) {
            return VerificationResult.failed("The step verification request is incomplete.");
        }
        String candidate = request.candidateResult();
        if (!StringUtils.hasText(candidate)) {
            return VerificationResult.failed("The candidate result is empty.");
        }
        String trimmed = candidate.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.endsWith("...") || trimmed.endsWith("…")
                || lower.contains("[truncated]") || lower.contains("output truncated")) {
            return VerificationResult.failed("The candidate result has a deterministic truncation marker.");
        }

        String goal = blankToDefault(request.plan().getGoal(), "").toLowerCase(Locale.ROOT);
        String stepSemantics = String.join(" ",
                blankToDefault(request.step().getTitle(), ""),
                blankToDefault(request.step().getDescription(), ""),
                blankToDefault(request.step().getType(), "")).toLowerCase(Locale.ROOT);
        boolean dependencySynthesis = !readStringList(request.step().getDependenciesJson()).isEmpty()
                && containsAny(stepSemantics, "synth", "final", "conclusion", "cross", "综合", "结论", "交叉", "核对");
        if (!dependencySynthesis) return null;

        List<String> missing = new ArrayList<>();
        if (containsAny(goal, "一致点", "consistent points", "consistencies")
                && !containsAny(lower, "一致", "consistent", "alignment")) missing.add("一致点");
        if (containsAny(goal, "差异点", "difference", "discrepanc")
                && !containsAny(lower, "差异", "difference", "discrepanc")) missing.add("差异点");
        if (containsAny(goal, "证据位置", "evidence location", "evidence positions")
                && !containsAny(lower, "证据", "evidence", "位置", "location", "path", "line")) missing.add("证据位置");
        if (containsAny(goal, "待确认", "to confirm", "open question")
                && !containsAny(lower, "待确认", "待核实", "confirm", "unresolved", "limitation", "open question")) {
            missing.add("待确认事项");
        }
        return missing.isEmpty() ? null : VerificationResult.failed(
                "The final synthesis is missing required section(s): " + String.join(", ", missing) + ".");
    }

    private boolean containsAny(String value, String... candidates) {
        if (!StringUtils.hasText(value)) return false;
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate) && value.contains(candidate.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String buildVerifierSystemPrompt() {
        return """
                You are an independent verifier for a Plan-and-Execute agent.
                Judge only whether the candidate result satisfies the current step success criteria.
                Do not require future steps to be completed.
                Be strict about missing evidence, missing requested artifacts, and vague placeholder answers.
                For search, audit, discovery, or lookup criteria, a documented zero-match result satisfies the
                criterion when the candidate identifies the searched term/scope and cites governed tool evidence.
                Never require a positive finding when the criterion only requires performing and reporting a search.
                A zero-match result has no matching file path or line number. Do not require nonexistent locations;
                require paths and line numbers only for actual matches. Server-observed tool facts take precedence.
                A bare unsupported claim of "no matches" does not satisfy the criterion.
                Evaluate every numbered criterion supplied by the server. Return one compact JSON object only.
                Keep each reason under 100 characters. Use exactly this shape:
                {"criteria":[{"id":"c1","satisfied":true,"reason":"brief basis"}],"reason":"brief overall reason"}
                """;
    }

    private String buildRepairSystemPrompt() {
        return """
                Repair a verifier decision into one compact JSON object only.
                Evaluate every supplied criterion and do not repeat the candidate result. Use exactly this shape:
                {"criteria":[{"id":"c1","satisfied":true,"reason":"brief basis"}],"reason":"brief overall reason"}
                """;
    }

    private String buildRepairUserMessage(VerificationRequest request, String invalidOutput) {
        return "Criteria to evaluate:\n" + formatCriteria(criteria(request.successCriteria()))
                + "\n\nCandidate summary:\n" + abbreviate(request.candidateResult(), 1400)
                + "\n\nInvalid verifier output to repair:\n" + abbreviate(invalidOutput, 400);
    }

    private ChatResponse callVerifier(VerificationRequest request, List<ChatMessage> messages, int maxTokens) {
        return modelProvider.chat(new ChatRequest(
                request.session().getModelProviderSnapshot(), request.session().getModelSnapshot(), messages,
                0.0, maxTokens, null, request.apiKey(), request.apiUrl(), ChatRequest.ResponseFormat.jsonObject(),
                ChatRequest.Thinking.disabled(), request.traceId()));
    }

    private void logVerifierResponse(VerificationRequest request, ChatResponse response, String content, int attempt) {
        ChatResponse.Usage usage = response == null ? null : response.usage();
        log.info("Plan step verifier response stepKey={} attempt={} finishReason={} promptTokens={} completionTokens={} contentLength={}",
                request.step().getStepKey(), attempt, response == null ? null : response.finishReason(),
                usage == null ? null : usage.promptTokens(), usage == null ? null : usage.completionTokens(),
                content == null ? 0 : content.length());
    }

    private void logDecision(VerificationRequest request, VerificationResult decision) {
        log.info("Plan step verifier decision stepKey={} passed={} conclusive={} reason={}",
                request.step().getStepKey(), decision.passed(), decision.conclusive(),
                abbreviate(decision.reason(), 240));
    }

    private String finishReasonSuffix(ChatResponse first, ChatResponse second) {
        String firstReason = first == null ? null : first.finishReason();
        String secondReason = second == null ? null : second.finishReason();
        return " [firstFinishReason=" + blankToDefault(firstReason, "unknown")
                + ", repairFinishReason=" + blankToDefault(secondReason, "unknown") + "]";
    }

    private String buildVerifierUserMessage(VerificationRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Overall goal:\n")
                .append(request.plan().getGoal())
                .append("\n\nCurrent step:\n")
                .append("- id: ").append(request.step().getStepKey()).append("\n")
                .append("- title: ").append(blankToDefault(request.step().getTitle(), "")).append("\n")
                .append("- type: ").append(blankToDefault(request.step().getType(), "")).append("\n")
                .append("- description: ").append(request.step().getDescription()).append("\n")
                .append("- success criteria to evaluate:\n").append(formatCriteria(criteria(request.successCriteria()))).append("\n")
                .append("Completed dependency results:\n");
        boolean hasDependencyResult = false;
        for (AgentPlanStep dependency : completedDependencyResults(request)) {
            String trustedResult = SandboxTrustedResultBoundary.trusted(dependency);
            if (StringUtils.hasText(trustedResult)) {
                hasDependencyResult = true;
                sb.append("## ").append(dependency.getStepKey()).append(" ")
                        .append(blankToDefault(dependency.getTitle(), ""))
                        .append(" [").append(dependency.getStatus()).append("]\n");
                if (AgentPlanStepStatus.DEGRADED.name().equals(dependency.getStatus())
                        && StringUtils.hasText(dependency.getErrorMessage())) {
                    sb.append("Dependency limitation: ")
                            .append(abbreviate(dependency.getErrorMessage(), 800)).append("\n");
                }
                sb.append(abbreviate(trustedResult, 1200))
                        .append("\n\n");
            }
        }
        if (!hasDependencyResult) {
            sb.append("None.\n\n");
        }
        sb.append("Candidate result to verify:\n")
                .append(abbreviate(request.candidateResult(), 3000));
        if (StringUtils.hasText(request.executionFacts())) {
            sb.append("\n\nServer-observed tool facts (authoritative; not model claims):\n")
                    .append(abbreviate(request.executionFacts(), 4000));
        }
        return sb.toString();
    }

    private VerificationResult parseVerification(String raw, VerificationRequest request) throws Exception {
        JsonNode root = objectMapper.readTree(stripCodeFence(raw));
        List<Criterion> expected = criteria(request.successCriteria());
        JsonNode criterionResults = root.path("criteria");
        if (criterionResults.isArray()) {
            List<CriterionDecision> decisions = new ArrayList<>();
            for (Criterion criterion : expected) {
                JsonNode matched = null;
                for (JsonNode candidate : criterionResults) {
                    if (criterion.id().equals(candidate.path("id").asText())) {
                        matched = candidate;
                        break;
                    }
                }
                if (matched == null || !matched.has("satisfied") || !matched.path("satisfied").isBoolean()) {
                    throw new IllegalArgumentException(
                            "Verifier omitted a decision for criterion " + criterion.id() + ".");
                }
                decisions.add(new CriterionDecision(
                        criterion.id(),
                        matched.path("satisfied").asBoolean(),
                        abbreviate(matched.path("reason").asText("No basis supplied."), 180)
                ));
            }
            boolean passed = decisions.stream().allMatch(CriterionDecision::satisfied);
            String reason = abbreviate(firstText(root, "reason", "rationale", "explanation"), 240);
            if (!StringUtils.hasText(reason)) {
                reason = passed ? "Every success criterion is satisfied."
                        : "One or more success criteria are not satisfied.";
            }
            return new VerificationResult(passed, true, reason, null, decisions);
        }
        Boolean passed = readBoolean(root, "passed", "success", "satisfied");
        if (passed == null) {
            return VerificationResult.inconclusive("Verifier response did not include a boolean passed field.");
        }
        String reason = firstText(root, "reason", "rationale", "explanation");
        String evidence = abbreviate(firstText(root, "evidence", "supportingEvidence"), 240);
        String missingItems = readMissingItems(root.path("missing"));
        if (!StringUtils.hasText(missingItems)) {
            missingItems = readMissingItems(root.path("missingItems"));
        }
        if (!StringUtils.hasText(missingItems)) {
            missingItems = readMissingItems(root.path("missing_items"));
        }
        if (!passed && StringUtils.hasText(missingItems)) {
            reason = appendSentence(reason, "Missing: " + missingItems);
        }
        if (!StringUtils.hasText(reason)) {
            reason = passed ? "Candidate result satisfies the success criteria."
                    : "Candidate result does not satisfy the success criteria.";
        }
        return new VerificationResult(passed, true, abbreviate(reason, 240), evidence, List.of());
    }

    private List<Criterion> criteria(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String normalized = raw.replace("\r", "\n");
        String[] parts = normalized.split("(?:\\n+|;|；)+");
        List<Criterion> result = new ArrayList<>();
        for (String part : parts) {
            String value = part == null ? "" : part.trim()
                    .replaceFirst("^(?:[-*]|\\d+[.)、])\\s*", "");
            if (StringUtils.hasText(value)) {
                result.add(new Criterion("c" + (result.size() + 1), abbreviate(value, 240)));
            }
        }
        if (result.isEmpty()) {
            result.add(new Criterion("c1", abbreviate(raw.trim(), 240)));
        }
        return List.copyOf(result);
    }

    private String formatCriteria(List<Criterion> criteria) {
        StringBuilder result = new StringBuilder();
        for (Criterion criterion : criteria) {
            result.append("- ").append(criterion.id()).append(": ")
                    .append(criterion.description()).append("\n");
        }
        return result.toString().trim();
    }

    private List<AgentPlanStep> completedDependencyResults(VerificationRequest request) {
        if (request == null || request.step() == null || request.allSteps() == null) {
            return List.of();
        }
        Map<String, AgentPlanStep> byKey = request.allSteps().stream()
                .filter(java.util.Objects::nonNull)
                .filter(item -> StringUtils.hasText(item.getStepKey()))
                .collect(java.util.stream.Collectors.toMap(
                        AgentPlanStep::getStepKey, item -> item, (left, right) -> left,
                        LinkedHashMap::new));
        LinkedHashSet<String> pending = new LinkedHashSet<>(
                readStringList(request.step().getDependenciesJson()));
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        List<AgentPlanStep> resolved = new ArrayList<>();
        while (!pending.isEmpty()) {
            String key = pending.iterator().next();
            pending.remove(key);
            if (!visited.add(key) || java.util.Objects.equals(request.step().getStepKey(), key)) {
                continue;
            }
            AgentPlanStep dependency = byKey.get(key);
            if (dependency == null || !isUsableDependency(dependency)) {
                continue;
            }
            if (StringUtils.hasText(dependency.getResult())) {
                resolved.add(dependency);
            }
            readStringList(dependency.getDependenciesJson()).stream()
                    .filter(ancestor -> !visited.contains(ancestor))
                    .forEach(pending::add);
        }
        return resolved.stream()
                .sorted(Comparator.comparing(AgentPlanStep::getSortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private boolean isUsableDependency(AgentPlanStep step) {
        return AgentPlanStepStatus.COMPLETED.name().equals(step.getStatus())
                || AgentPlanStepStatus.DEGRADED.name().equals(step.getStatus());
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String stripCodeFence(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        cleaned = cleaned.replaceAll("(?s)^```json\\s*", "")
                .replaceAll("(?s)^```\\s*", "")
                .replaceAll("(?s)```$", "")
                .trim();
        int first = cleaned.indexOf('{');
        int last = cleaned.lastIndexOf('}');
        if (first >= 0 && last >= first) {
            return cleaned.substring(first, last + 1).trim();
        }
        return cleaned;
    }

    private Boolean readBoolean(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode node = root.path(field);
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            if (node.isTextual()) {
                String value = node.asText("").trim().toLowerCase();
                if ("true".equals(value) || "yes".equals(value) || "pass".equals(value) || "passed".equals(value)) {
                    return true;
                }
                if ("false".equals(value) || "no".equals(value) || "fail".equals(value) || "failed".equals(value)) {
                    return false;
                }
            }
        }
        return null;
    }

    private String firstText(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode node = root.path(field);
            if (node.isTextual() && StringUtils.hasText(node.asText())) {
                return node.asText().trim();
            }
        }
        return "";
    }

    private String readMissingItems(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("").trim();
        }
        if (!node.isArray()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && StringUtils.hasText(item.asText())) {
                values.add(item.asText().trim());
            }
        }
        return String.join("; ", values);
    }

    private String appendSentence(String value, String sentence) {
        if (!StringUtils.hasText(value)) {
            return sentence;
        }
        return value.endsWith(".") ? value + " " + sentence : value + ". " + sentence;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String blankToDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    public record VerificationRequest(
            AgentPlan plan,
            AgentSession session,
            AgentPlanStep step,
            List<AgentPlanStep> allSteps,
            String candidateResult,
            String executionFacts,
            String apiKey,
            String apiUrl,
            String traceId
    ) {
        public VerificationRequest(AgentPlan plan,
                                   AgentSession session,
                                   AgentPlanStep step,
                                   List<AgentPlanStep> allSteps,
                                   String candidateResult,
                                   String apiKey) {
            this(plan, session, step, allSteps, candidateResult, null, apiKey, null, null);
        }

        public String successCriteria() {
            return step == null ? null : step.getSuccessCriteria();
        }

    }

    private record Criterion(String id, String description) {
    }

    public record CriterionDecision(String id, boolean satisfied, String reason) {
    }

    public record VerificationResult(boolean passed, boolean conclusive, String reason, String evidence,
                                     List<CriterionDecision> criteria) {
        public VerificationResult(boolean passed, boolean conclusive, String reason, String evidence) {
            this(passed, conclusive, reason, evidence, List.of());
        }

        public VerificationResult {
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
        }

        public static VerificationResult passed(String reason) {
            return new VerificationResult(true, true, reason, null, List.of());
        }

        public static VerificationResult failed(String reason) {
            return new VerificationResult(false, true, reason, null, List.of());
        }

        public static VerificationResult inconclusive(String reason) {
            return new VerificationResult(true, false, reason, null, List.of());
        }
    }
}
