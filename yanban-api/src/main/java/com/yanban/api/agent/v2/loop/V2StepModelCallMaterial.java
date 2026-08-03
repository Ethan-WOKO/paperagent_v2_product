package com.yanban.api.agent.v2.loop;

import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.MessageRole;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NullValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** The exact safe projection shared by the Step prompt and context revision. */
public record V2StepModelCallMaterial(
        ModelRequest request,
        String safeModelRequestDigest,
        Step step,
        List<HistoryItem> history,
        String safeHistoryCanonicalDigest,
        long decisionOrdinal) {
    public V2StepModelCallMaterial {
        if (request == null || safeModelRequestDigest == null
                || !safeModelRequestDigest.matches("[a-f0-9]{64}")
                || step == null
                || safeHistoryCanonicalDigest == null
                || !safeHistoryCanonicalDigest.matches("[a-f0-9]{64}")
                || decisionOrdinal < 1
                || !safeModelRequestDigest.equals(requestDigest(request))) {
            throw new IllegalArgumentException("step model material is invalid");
        }
        history = List.copyOf(history);
    }

    public record Step(
            String taskFrameId,
            String objective,
            List<String> targets,
            List<String> deliverables,
            List<String> constraints,
            String planId,
            String planRevisionId,
            long planRevisionNumber,
            String stepId,
            String intent,
            String expectedOutcome,
            List<String> completionCriteria,
            List<CompletedFact> completedFacts,
            long checkpointVersion,
            long checkpointLastEventSequence) {
        public Step {
            targets = List.copyOf(targets);
            deliverables = List.copyOf(deliverables);
            constraints = List.copyOf(constraints);
            completionCriteria = List.copyOf(completionCriteria);
            completedFacts = List.copyOf(completedFacts);
        }
    }

    public record CompletedFact(
            String stepId, String intent, List<String> acceptedRefs) {
        public CompletedFact {
            acceptedRefs = List.copyOf(acceptedRefs);
        }
    }

    public record HistoryItem(
            int ordinal,
            String toolCallId,
            String toolKind,
            List<String> allowlistedArgumentFields,
            String argumentsDigest,
            boolean completed,
            boolean successful,
            String receiptId,
            String receiptStatus,
            String resultCode,
            Integer exitCode,
            String outputDigest,
            boolean outputTruncated,
            List<String> artifactRefs,
            String boundedSafeText) {
        public HistoryItem {
            allowlistedArgumentFields = List.copyOf(allowlistedArgumentFields);
            artifactRefs = List.copyOf(artifactRefs);
        }
    }

    public V2StepModelCallMaterial withHistory(List<HistoryItem> keptHistory) {
        List<HistoryItem> kept = List.copyOf(keptHistory);
        ModelRequest rebuilt = new ModelRequest(
                request.requestId(), request.correlationId(),
                List.of(
                        request.messages().stream()
                                .filter(value -> value.role() == MessageRole.SYSTEM)
                                .findFirst().orElseThrow(),
                        new ModelMessage(MessageRole.USER,
                                userMessage(step, kept))),
                request.availableTools(), request.generationOptions(),
                request.taskFrameId(), request.planId(),
                request.planRevisionId(), request.stepId(),
                request.cancellationRequested());
        return new V2StepModelCallMaterial(
                rebuilt, requestDigest(rebuilt), step, kept,
                safeHistoryCanonicalDigest,
                decisionOrdinal);
    }

    public static String userMessage(Step step, List<HistoryItem> history) {
        StringBuilder value = new StringBuilder();
        value.append("Objective: ").append(step.objective())
                .append("\nTargets: ")
                .append(String.join("; ", step.targets()))
                .append("\nDeliverables: ")
                .append(String.join("; ", step.deliverables()))
                .append("\nConstraints: ")
                .append(String.join("; ", step.constraints()))
                .append("\nCompleted Plan Steps:\n");
        if (step.completedFacts().isEmpty()) {
            value.append("- none");
        } else {
            step.completedFacts().forEach(fact -> value.append("- ")
                    .append(fact.stepId()).append(": ")
                    .append(fact.intent()).append("; acceptedRefs=")
                    .append(String.join(",", fact.acceptedRefs())).append('\n'));
        }
        value.append("\nCurrent goal: ").append(step.intent())
                .append("\nExpected outcome: ").append(step.expectedOutcome())
                .append("\nCompletion criteria: ")
                .append(String.join("; ", step.completionCriteria()))
                .append("\nDurable effect history:\n");
        if (history.isEmpty()) {
            value.append("- none\n");
        } else {
            for (HistoryItem entry : history) {
                value.append("- tool=").append(entry.toolKind())
                        .append("; argumentFields=")
                        .append(String.join(",", entry.allowlistedArgumentFields()))
                        .append("; argumentsDigest=").append(entry.argumentsDigest());
                if (!entry.completed()) {
                    value.append("; status=PENDING\n");
                    continue;
                }
                value.append("; status=").append(entry.receiptStatus())
                        .append("; resultCode=")
                        .append(entry.resultCode() == null ? "" : entry.resultCode())
                        .append("; exitCode=")
                        .append(entry.exitCode() == null ? "" : entry.exitCode())
                        .append("; outputDigest=").append(entry.outputDigest())
                        .append("; truncated=").append(entry.outputTruncated())
                        .append("; artifacts=")
                        .append(String.join(",", entry.artifactRefs()))
                        .append("; safeText=")
                        .append(entry.boundedSafeText() == null
                                ? "" : entry.boundedSafeText())
                        .append('\n');
            }
        }
        return value.append("Choose the next useful tool, or return a short message "
                + "when these facts already satisfy the current goal.").toString();
    }

    public static String requestDigest(ModelRequest request) {
        StringBuilder canonical = new StringBuilder("step-request-v1")
                .append('\u001f').append(request.requestId().value())
                .append('\u001f').append(request.correlationId().value());
        request.messages().forEach(message -> canonical
                .append('\u001e').append(message.role().name())
                .append('\u001f').append(message.content().length())
                .append(':').append(message.content()));
        request.availableTools().forEach(tool -> {
            canonical.append('\u001e').append(tool.id().value())
                    .append('\u001f').append(tool.description().length())
                    .append(':').append(tool.description());
            tool.requiredCapabilities().stream().map(Enum::name).sorted()
                    .forEach(capability -> canonical.append('\u001f')
                            .append(capability));
            canonical.append('\u001f');
            appendContractValue(canonical, tool.parameterSchema());
        });
        canonical.append('\u001f').append(
                request.generationOptions().maxOutputTokens())
                .append('\u001f').append(
                        request.generationOptions().maxProposedToolCalls())
                .append('\u001f').append(
                        request.generationOptions().temperature())
                .append('\u001f').append(
                        request.generationOptions().seed().isPresent()
                                ? request.generationOptions().seed().getAsLong()
                                : "none")
                .append('\u001f').append(new java.util.TreeMap<>(
                        request.generationOptions().deterministicOptions()))
                .append('\u001f').append(request.taskFrameId()
                        .map(value -> value.value()).orElse(""))
                .append('\u001f').append(request.planId()
                        .map(value -> value.value()).orElse(""))
                .append('\u001f').append(request.planRevisionId()
                        .map(value -> value.value()).orElse(""))
                .append('\u001f').append(request.stepId()
                        .map(value -> value.value()).orElse(""))
                .append('\u001f').append(request.cancellationRequested());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void appendContractValue(
            StringBuilder target, ContractValue value) {
        if (value instanceof TextValue text) {
            target.append('S').append(text.value().length())
                    .append(':').append(text.value());
        } else if (value instanceof NumberValue number) {
            target.append('N').append(number.value().toPlainString());
        } else if (value instanceof BooleanValue bool) {
            target.append('B').append(bool.value());
        } else if (value == NullValue.INSTANCE) {
            target.append('0');
        } else if (value instanceof ListValue list) {
            target.append('[');
            list.values().forEach(item -> appendContractValue(target, item));
            target.append(']');
        } else if (value instanceof ObjectValue object) {
            target.append('{');
            new java.util.TreeMap<>(object.values()).forEach((key, item) -> {
                target.append(key.length()).append(':').append(key);
                appendContractValue(target, item);
            });
            target.append('}');
        } else {
            throw new IllegalArgumentException("unsupported contract value");
        }
    }
}
