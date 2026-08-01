package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentPlanEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.util.StringUtils;

/** Pure, migration-free projection from existing runtime/Plan facts into the final-synthesis contract. */
final class FinalSynthesisInputProjector {
    private FinalSynthesisInputProjector() {
    }

    static FinalSynthesisInput fromPlan(ObjectMapper json,
                                        String lifecycleStatus,
                                        List<AgentPlanStepResponse> steps,
                                        String finalAnswer,
                                        List<AgentPlanEvent> events,
                                        String currentProjectVersion,
                                        Map<String, String> currentHashes) {
        List<AgentPlanStepResponse> safeSteps = steps == null ? List.of() : steps;
        List<AgentPlanEvent> safeEvents = events == null ? List.of() : events;
        Map<String, SynthesisEvidence> evidence = new LinkedHashMap<>();
        String executionOutcome = null;

        for (AgentPlanEvent event : safeEvents) {
            if (event == null || !StringUtils.hasText(event.getPayloadJson())) continue;
            try {
                JsonNode payload = json.readTree(event.getPayloadJson());
                if (isSandboxReceiptEvent(event, payload)) {
                    String executionId = payload.path("executionId").asText("unknown");
                    String receiptStatus = payload.path("status").asText("UNAVAILABLE");
                    executionOutcome = executionOutcome(receiptStatus, payload.path("exitCode"));
                    String receiptId = "execution:" + executionId;
                    CapturedOutput captured = capturedOutput(json, safeSteps, safeEvents,
                            event.getStepId(), executionId);
                    evidence.put(receiptId, new SynthesisEvidence(
                            receiptId, EvidenceCategory.EXECUTION_FACT, EvidenceStatus.VERIFIED,
                            "Provider receipt verifies this execution's status and captured output only.",
                            List.of(), null, null, null, null, null, "SANDBOX_RECEIPT",
                            ExternalSourceAccess.UNKNOWN,
                            new ExecutionFact(payload.path("provider").asText(null), receiptStatus,
                                    payload.path("exitCode").isInt() ? payload.path("exitCode").intValue() : null,
                                    payload.path("timedOut").asBoolean("TIMED_OUT".equals(receiptStatus)),
                                    strings(payload.path("command")),
                                    textOrNull(payload, "stdout") == null ? captured.stdout() : textOrNull(payload, "stdout"),
                                    textOrNull(payload, "stderr") == null ? captured.stderr() : textOrNull(payload, "stderr"),
                                    textOrNull(payload, "failurePhase"),
                                    textOrNull(payload, "failureType"),
                                    textOrNull(payload, "providerErrorType"),
                                    payload.path("providerCommandExitCode").isInt()
                                            ? payload.path("providerCommandExitCode").intValue() : null)));
                    addTypedEvidence(json, payload.path("evidence"), evidence, currentProjectVersion, currentHashes);
                    continue;
                }
                if ("step_project_evidence".equals(event.getEventType())) {
                    addTypedEvidence(json, payload.path("evidence"), evidence, currentProjectVersion, currentHashes);
                    continue;
                }
                if ("sandbox_output_analysis".equals(event.getEventType())) {
                    String executionId = payload.path("executionId").asText("unknown");
                    String id = "unverified-output-analysis:" + executionId;
                    evidence.put(id, new SynthesisEvidence(id, EvidenceCategory.UNVERIFIED_INPUT,
                            EvidenceStatus.UNVERIFIED,
                            payload.path("summary").asText("Read-only analysis of sandbox output."),
                            List.of("execution:" + executionId), null, null, null, null, null,
                            "READ_ONLY_OUTPUT_ANALYSIS", ExternalSourceAccess.UNKNOWN, null));
                }
            } catch (Exception ignored) {
                // Malformed legacy events remain unavailable; they cannot manufacture trusted evidence.
            }
        }

        if (executionOutcome == null) executionOutcome = legacyExecutionOutcome(lifecycleStatus, safeSteps);
        String taskOutcome = taskOutcome(lifecycleStatus, safeSteps, executionOutcome);
        if (StringUtils.hasText(finalAnswer)) {
            List<String> basis = evidence.values().stream()
                    .filter(item -> item.status() == EvidenceStatus.VERIFIED || item.status() == EvidenceStatus.SUPPORTED)
                    .map(SynthesisEvidence::id).toList();
            String id = "inference:final-answer:" + sha256(finalAnswer);
            evidence.put(id, new SynthesisEvidence(id, EvidenceCategory.INFERENCE, EvidenceStatus.INFERRED,
                    "Canonical answer is model/Plan synthesis; its authority is limited to the listed basis.",
                    basis, null, null, null, null, null, "CANONICAL_ANSWER",
                    ExternalSourceAccess.UNKNOWN, null));
        }
        EvidenceStatus answerStatus = answerStatus(taskOutcome, executionOutcome, evidence.values());
        return new FinalSynthesisInput(executionOutcome, taskOutcome, answerStatus,
                List.copyOf(evidence.values()), VerificationScope.standard());
    }

    static FinalSynthesisInput fromRuntime(AgentRuntimeResult result) {
        FinalSynthesisInput existing = result == null ? null : result.finalSynthesisInput();
        CompletionStatus completion = result == null || result.completionVerification() == null
                ? null : result.completionVerification().status();
        if (existing != null) {
            String task = existing.taskOutcome();
            EvidenceStatus answer = existing.answerStatus();
            if (completion == CompletionStatus.INSUFFICIENT_EVIDENCE) {
                if ("SUCCESS".equals(existing.executionOutcome())) task = "PARTIAL";
                answer = EvidenceStatus.UNVERIFIED;
            } else if (completion == CompletionStatus.PARTIAL) {
                if (!terminalExecutionFailure(existing.executionOutcome())) task = "PARTIAL";
                answer = existing.evidence().isEmpty() ? EvidenceStatus.UNVERIFIED : EvidenceStatus.INFERRED;
            } else if (completion == CompletionStatus.FAILED && !terminalExecutionFailure(existing.executionOutcome())) {
                if (!("SUCCESS".equals(existing.executionOutcome()) && "PARTIAL".equals(existing.taskOutcome()))) {
                    task = "FAILED";
                }
                answer = EvidenceStatus.UNVERIFIED;
            }
            return new FinalSynthesisInput(existing.executionOutcome(), task, answer,
                    existing.evidence(), existing.verificationScope());
        }
        if (result == null) return new FinalSynthesisInput("UNAVAILABLE", "FAILED", EvidenceStatus.UNVERIFIED,
                List.of(), VerificationScope.standard());
        String execution = switch (normalized(result.outcome())) {
            case "CANCELLED" -> "CANCELLED";
            case "TIMED_OUT" -> "TIMED_OUT";
            case "FAILED" -> "FAILED";
            default -> result.selectedStrategy() == AgentStrategy.DIRECT ? "NOT_APPLICABLE"
                    : result.success() ? "SUCCESS" : "UNAVAILABLE";
        };
        String task = switch (normalized(result.outcome())) {
            case "PARTIAL", "INSUFFICIENT_EVIDENCE", "BUDGET_STOP", "PAUSED", "WAITING" -> "PARTIAL";
            case "CANCELLED" -> "CANCELLED";
            case "TIMED_OUT" -> "TIMED_OUT";
            case "FAILED" -> "FAILED";
            default -> result.success() ? "SUCCESS" : "FAILED";
        };
        List<SynthesisEvidence> evidence = runtimeEvidence(result);
        EvidenceStatus answer = completion == CompletionStatus.VERIFIED
                ? EvidenceStatus.VERIFIED
                : completion == CompletionStatus.PARTIAL ? EvidenceStatus.INFERRED : EvidenceStatus.UNVERIFIED;
        return new FinalSynthesisInput(execution, task, answer, evidence, VerificationScope.standard());
    }

    static boolean hasVerifiedExecutionMaterial(FinalSynthesisInput input) {
        if (input == null || !"SUCCESS".equals(input.executionOutcome())) return false;
        boolean receipt = input.evidence().stream().anyMatch(item -> item.category() == EvidenceCategory.EXECUTION_FACT
                && item.status() == EvidenceStatus.VERIFIED && item.executionFact() != null
                && ("SUCCEEDED".equals(item.executionFact().status()) || "SUCCESS".equals(item.executionFact().status()))
                && Objects.equals(item.executionFact().exitCode(), 0));
        boolean snapshot = input.evidence().stream().anyMatch(item ->
                item.category() == EvidenceCategory.VERIFIED_PROJECT_EVIDENCE
                        && item.status() == EvidenceStatus.VERIFIED
                        && StringUtils.hasText(item.projectVersion()) && StringUtils.hasText(item.path())
                        && StringUtils.hasText(item.hash()) && item.startLine() != null && item.endLine() != null);
        return receipt && snapshot;
    }

    private static List<SynthesisEvidence> runtimeEvidence(AgentRuntimeResult result) {
        if (result.evidenceLedger() == null) return List.of();
        List<SynthesisEvidence> values = new ArrayList<>();
        for (EvidenceRef ref : result.evidenceLedger().evidence()) {
            if (ref == null) continue;
            values.add(fromEvidenceRef(ref, null, Map.of()));
        }
        return List.copyOf(values);
    }

    private static void addTypedEvidence(ObjectMapper json, JsonNode values,
                                         Map<String, SynthesisEvidence> target,
                                         String currentProjectVersion,
                                         Map<String, String> currentHashes) throws Exception {
        if (!values.isArray()) return;
        for (JsonNode value : values) {
            EvidenceRef ref = json.treeToValue(value, EvidenceRef.class);
            if (ref != null) target.putIfAbsent(ref.id(), fromEvidenceRef(ref, currentProjectVersion, currentHashes));
        }
    }

    private static SynthesisEvidence fromEvidenceRef(EvidenceRef ref, String currentProjectVersion,
                                                      Map<String, String> currentHashes) {
        EvidenceCategory category = switch (ref.sourceType()) {
            case PROJECT -> EvidenceCategory.VERIFIED_PROJECT_EVIDENCE;
            case WEB -> EvidenceCategory.EXTERNAL_SOURCE;
            case TOOL, LEGACY_UNVERSIONED -> EvidenceCategory.UNVERIFIED_INPUT;
            case RAG -> EvidenceCategory.UNVERIFIED_INPUT;
        };
        boolean stale = ref.versionStatus() == EvidenceVersionStatus.STALE;
        if (ref.sourceType() == EvidenceSourceType.PROJECT && currentHashes != null && !currentHashes.isEmpty()) {
            stale = !Objects.equals(currentProjectVersion, ref.projectVersion())
                    || !Objects.equals(currentHashes.get(ref.file()), ref.fileHash());
        }
        ExternalSourceAccess access = ref.sourceType() != EvidenceSourceType.WEB
                ? ExternalSourceAccess.UNKNOWN : externalAccess(ref);
        EvidenceStatus status = stale ? EvidenceStatus.STALE
                : ref.sourceType() == EvidenceSourceType.PROJECT
                && ref.versionStatus() == EvidenceVersionStatus.VERIFIED
                ? EvidenceStatus.VERIFIED
                : ref.sourceType() == EvidenceSourceType.WEB && access == ExternalSourceAccess.OPENED
                ? EvidenceStatus.SUPPORTED : EvidenceStatus.UNVERIFIED;
        return new SynthesisEvidence(ref.id(), category, status,
                ref.selectionReason(), List.of(), ref.projectVersion(), ref.file(), ref.fileHash(),
                ref.startLine(), ref.endLine(), ref.sourceType().name(), access, null);
    }

    private static ExternalSourceAccess externalAccess(EvidenceRef ref) {
        return switch (normalized(ref.source())) {
            case "OPENED" -> ExternalSourceAccess.OPENED;
            case "SEARCH_SUMMARY" -> ExternalSourceAccess.SEARCH_SUMMARY;
            default -> ExternalSourceAccess.UNKNOWN;
        };
    }

    private static boolean isSandboxReceiptEvent(AgentPlanEvent event, JsonNode payload) {
        return ("step_project_evidence".equals(event.getEventType())
                || "sandbox_execution_failed".equals(event.getEventType()))
                && payload.hasNonNull("executionId") && payload.hasNonNull("status")
                && payload.hasNonNull("provider");
    }

    private static String executionOutcome(String status, JsonNode exitCode) {
        return switch (normalized(status)) {
            case "SUCCEEDED", "SUCCESS", "COMPLETED" -> exitCode.isInt() && exitCode.intValue() != 0
                    ? "FAILED" : "SUCCESS";
            case "TIMED_OUT" -> "TIMED_OUT";
            case "CANCELLED" -> "CANCELLED";
            case "UNAVAILABLE", "SANDBOX_UNAVAILABLE" -> "UNAVAILABLE";
            default -> "FAILED";
        };
    }

    private static String legacyExecutionOutcome(String lifecycle, List<AgentPlanStepResponse> steps) {
        for (AgentPlanStepResponse step : steps) {
            if (step == null || !"SANDBOX_EXECUTE".equals(step.type())) continue;
            String error = normalized(step.errorMessage());
            if (error.contains("TIMED_OUT")) return "TIMED_OUT";
            if (error.contains("CANCELLED")) return "CANCELLED";
            if (error.contains("UNAVAILABLE")) return "UNAVAILABLE";
            if ("FAILED".equals(step.status())) return "FAILED";
            if ("COMPLETED".equals(step.status())) return "SUCCESS";
        }
        boolean partial = steps.stream().anyMatch(step -> step != null
                && ("DEGRADED".equals(step.status()) || "SKIPPED".equals(step.status())));
        if ("COMPLETED".equals(lifecycle)) return partial ? "PARTIAL" : "SUCCESS";
        if ("CANCELLED".equals(lifecycle)) return "CANCELLED";
        if ("FAILED".equals(lifecycle) && steps.stream().anyMatch(step -> step != null
                && normalized(step.errorMessage()).contains("TIMED_OUT"))) return "TIMED_OUT";
        if ("FAILED".equals(lifecycle)) return partial && hasUsefulResult(steps) ? "PARTIAL" : "FAILED";
        return lifecycle == null ? "UNAVAILABLE" : lifecycle;
    }

    private static String taskOutcome(String lifecycle, List<AgentPlanStepResponse> steps, String execution) {
        if ("CANCELLED".equals(execution) || "TIMED_OUT".equals(execution)) return execution;
        if ("FAILED".equals(execution) || "UNAVAILABLE".equals(execution)) return "FAILED";
        boolean partial = steps.stream().anyMatch(step -> step != null
                && ("DEGRADED".equals(step.status()) || "SKIPPED".equals(step.status())));
        if ("COMPLETED".equals(lifecycle)) return partial ? "PARTIAL" : "SUCCESS";
        if ("FAILED".equals(lifecycle) && "SUCCESS".equals(execution)) return "PARTIAL";
        if ("FAILED".equals(lifecycle)) return partial && hasUsefulResult(steps) ? "PARTIAL" : "FAILED";
        if ("CANCELLED".equals(lifecycle)) return "CANCELLED";
        return lifecycle == null ? "FAILED" : lifecycle;
    }

    private static boolean hasUsefulResult(List<AgentPlanStepResponse> steps) {
        return steps.stream().anyMatch(step -> step != null && StringUtils.hasText(step.result())
                && ("COMPLETED".equals(step.status()) || "DEGRADED".equals(step.status())));
    }

    private static EvidenceStatus answerStatus(String task, String execution,
                                               java.util.Collection<SynthesisEvidence> evidence) {
        if (evidence.stream().anyMatch(item -> item.status() == EvidenceStatus.CONFLICTING)) return EvidenceStatus.CONFLICTING;
        if (evidence.stream().anyMatch(item -> item.status() == EvidenceStatus.STALE)) return EvidenceStatus.STALE;
        if (terminalExecutionFailure(execution) || "FAILED".equals(task)) return EvidenceStatus.UNVERIFIED;
        if (evidence.stream().anyMatch(item -> item.category() == EvidenceCategory.EXECUTION_FACT
                || item.category() == EvidenceCategory.VERIFIED_PROJECT_EVIDENCE)) return EvidenceStatus.SUPPORTED;
        return "SUCCESS".equals(task) ? EvidenceStatus.INFERRED : EvidenceStatus.UNVERIFIED;
    }

    private static boolean terminalExecutionFailure(String execution) {
        return "FAILED".equals(execution) || "TIMED_OUT".equals(execution)
                || "CANCELLED".equals(execution) || "UNAVAILABLE".equals(execution);
    }

    private static List<String> strings(JsonNode values) {
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> { if (value.isTextual()) result.add(value.asText()); });
        return List.copyOf(result);
    }

    private static String textOrNull(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static CapturedOutput capturedOutput(ObjectMapper json,
                                                 List<AgentPlanStepResponse> steps,
                                                 List<AgentPlanEvent> events,
                                                 Long stepId,
                                                 String executionId) {
        AgentPlanStepResponse step = steps.stream().filter(item -> item != null && Objects.equals(item.id(), stepId))
                .findFirst().orElse(null);
        if (step == null || !StringUtils.hasText(step.result())) return new CapturedOutput(null, null);
        String marker = "\nstdout:\n";
        String stderrMarker = "\nstderr:\n";
        int stdoutStart = step.result().indexOf(marker);
        int stderrStart = step.result().lastIndexOf(stderrMarker);
        if (stdoutStart < 0 || stderrStart < stdoutStart) return new CapturedOutput(null, null);
        String stdout = step.result().substring(stdoutStart + marker.length(), stderrStart);
        String stderr = step.result().substring(stderrStart + stderrMarker.length());
        String stdoutHash = receiptHash(step.result(), "stdoutSha256=");
        String stderrHash = receiptHash(step.result(), "stderrSha256=");
        if (stdoutHash != null && !stdoutHash.equals(sha256(stdout))) stdout = null;
        if (stderrHash != null && !stderrHash.equals(sha256(stderr))) {
            stderr = stripReadOnlyAnalysis(json, events, stepId, executionId, stderr);
        }
        if (stderrHash != null && (stderr == null || !stderrHash.equals(sha256(stderr)))) stderr = null;
        return new CapturedOutput(stdout, stderr);
    }

    private static String stripReadOnlyAnalysis(ObjectMapper json,
                                                List<AgentPlanEvent> events,
                                                Long stepId,
                                                String executionId,
                                                String captured) {
        for (AgentPlanEvent event : events) {
            if (event == null || !Objects.equals(stepId, event.getStepId())
                    || !"sandbox_output_analysis".equals(event.getEventType())
                    || !StringUtils.hasText(event.getPayloadJson())) continue;
            try {
                JsonNode payload = json.readTree(event.getPayloadJson());
                if (!Objects.equals(executionId, payload.path("executionId").asText())) continue;
                String disclaimer = textOrNull(payload, "disclaimer");
                String summary = textOrNull(payload, "summary");
                if (disclaimer == null || summary == null) continue;
                String appendix = "\n\n" + disclaimer + "\n" + summary;
                if (captured.endsWith(appendix)) return captured.substring(0, captured.length() - appendix.length());
            } catch (Exception ignored) {
                // Optional read-only analysis can never be reclassified as raw provider output.
            }
        }
        return captured;
    }

    private static String receiptHash(String result, String field) {
        int start = result.indexOf(field);
        if (start < 0) return null;
        start += field.length();
        int end = start;
        while (end < result.length() && Character.digit(result.charAt(end), 16) >= 0) end++;
        String value = result.substring(start, end);
        return value.length() == 64 ? value : null;
    }

    private record CapturedOutput(String stdout, String stderr) {
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
