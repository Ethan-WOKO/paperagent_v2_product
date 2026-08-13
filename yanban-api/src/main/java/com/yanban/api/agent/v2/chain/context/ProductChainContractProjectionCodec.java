package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskRequirements;
import io.paperagent.v2.contracts.ValidationRequirement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Canonical Context values for the three frozen V2 planning contracts. */
public final class ProductChainContractProjectionCodec {
    public static final String SCHEMA_VERSION = "product-chain-contract-v2";

    private ProductChainContractProjectionCodec() {
    }

    public static Projection taskFrame(TaskFrame frame) {
        Objects.requireNonNull(frame, "frame");
        ChainContextValue value = object(Map.of(
                "schemaVersion", text(SCHEMA_VERSION),
                "id", text(frame.id().value()),
                "objective", text(frame.objective()),
                "targets", strings(frame.targets()),
                "deliverables", strings(frame.deliverables()),
                "constraints", strings(frame.constraints()),
                "requirements", taskRequirements(frame.requirements()),
                "sourceProjectVersion", frame.sourceProjectVersion()
                        .<ChainContextValue>map(
                                ProductChainContractProjectionCodec::projectVersion)
                        .orElseGet(ChainContextValue::nil),
                "executionProfile", executionProfile(frame.executionProfile()),
                "createdAt", text(frame.createdAt().toString())));
        return projection(value);
    }

    public static Projection planRevision(PlanRevision revision) {
        Objects.requireNonNull(revision, "revision");
        TreeMap<String, ChainContextValue> facts = new TreeMap<>();
        revision.completedFacts().forEach((stepId, fact) ->
                facts.put(stepId.value(), completionFact(fact)));
        ChainContextValue value = object(Map.of(
                "schemaVersion", text(SCHEMA_VERSION),
                "id", text(revision.id().value()),
                "taskFrameId", text(revision.taskFrameId().value()),
                "number", ChainContextValue.number(revision.number()),
                "parentRevisionId", revision.parentRevisionId()
                        .<ChainContextValue>map(id -> text(id.value()))
                        .orElseGet(ChainContextValue::nil),
                "reason", text(revision.reason()),
                "createdAt", text(revision.createdAt().toString()),
                "steps", ChainContextValue.array(revision.steps().stream()
                        .map(ProductChainContractProjectionCodec::planStepValue)
                        .toList()),
                "completedFacts", object(facts)));
        return projection(value);
    }

    public static Projection planStep(PlanStep step) {
        Objects.requireNonNull(step, "step");
        return projection(planStepValue(step));
    }

    private static ChainContextValue planStepValue(PlanStep step) {
        return object(Map.ofEntries(
                Map.entry("schemaVersion", text(SCHEMA_VERSION)),
                Map.entry("id", text(step.id().value())),
                Map.entry("intent", text(step.intent())),
                Map.entry("expectedOutcome", text(step.expectedOutcome())),
                Map.entry("dependencies", strings(step.dependencies().stream()
                        .map(value -> value.value()).sorted().toList())),
                Map.entry("completionCriteria", strings(
                        step.completionCriteria())),
                Map.entry("executionHints", executionHints(
                        step.executionHints())),
                Map.entry("constraints", strings(step.constraints())),
                Map.entry("mayChangeCandidate", ChainContextValue.bool(
                        step.mayChangeCandidate())),
                Map.entry("candidateValidationCompletionCondition",
                        step.candidateValidationCompletionCondition() == null
                        ? ChainContextValue.nil()
                        : text(step.candidateValidationCompletionCondition())),
                Map.entry("validationRequirementIds", strings(
                        step.validationRequirementIds()))));
    }

    static ChainContextValue taskRequirements(TaskRequirements value) {
        Objects.requireNonNull(value, "value");
        return object(Map.of(
                "declarationMode", text(value.declarationMode().name()),
                "deliveryRequirement", text(
                        value.deliveryRequirement().name()),
                "validationRequirements", ChainContextValue.array(
                        value.validationRequirements().stream()
                                .map(ProductChainContractProjectionCodec
                                        ::validationRequirement)
                                .toList()),
                "publishRequirement", text(
                        value.publishRequirement().name())));
    }

    private static ChainContextValue validationRequirement(
            ValidationRequirement value) {
        return object(Map.of(
                "requirementId", text(value.requirementId()),
                "subject", text(value.subject().name()),
                "completionCondition", text(value.completionCondition())));
    }

    private static ChainContextValue completionFact(CompletionFact fact) {
        return object(Map.of(
                "stepId", text(fact.stepId().value()),
                "outcomeHash", text(fact.outcomeHash()),
                "completedAt", text(fact.completedAt().toString()),
                "receiptReferences", strings(fact.receiptReferences().stream()
                        .map(value -> value.value()).toList())));
    }

    private static ChainContextValue projectVersion(ProjectVersionRef value) {
        return object(Map.of(
                "projectId", text(value.projectId()),
                "versionId", text(value.versionId())));
    }

    private static ChainContextValue executionProfile(ExecutionProfile value) {
        return object(Map.of(
                "tier", text(value.tier().name()),
                "capabilities", strings(value.capabilities().stream()
                        .map(Enum::name).sorted().toList()),
                "networkPolicy", text(value.networkPolicy().name()),
                "networkAllowlist", strings(value.networkAllowlist().stream()
                        .sorted().toList()),
                "resourceLimits", resourceLimits(value.resourceLimits()),
                "secretReferences", strings(value.secretReferences().stream()
                        .map(ref -> ref.name()).sorted().toList())));
    }

    private static ChainContextValue resourceLimits(ResourceLimits value) {
        return object(Map.of(
                "wallTime", duration(value.wallTime()),
                "cpuTime", duration(value.cpuTime()),
                "memoryBytes", ChainContextValue.number(value.memoryBytes()),
                "outputBytes", ChainContextValue.number(value.outputBytes()),
                "processCount", ChainContextValue.number(value.processCount())));
    }

    private static ChainContextValue executionHints(BoundedExecutionHints value) {
        return object(Map.of(
                "maxAttempts", ChainContextValue.number(value.maxAttempts()),
                "maxDuration", duration(value.maxDuration())));
    }

    private static ChainContextValue duration(Duration value) {
        return object(Map.of(
                "seconds", ChainContextValue.number(value.getSeconds()),
                "nanos", ChainContextValue.number(value.getNano())));
    }

    private static ChainContextValue.ArrayValue strings(List<String> values) {
        return ChainContextValue.array(values.stream()
                .map(ProductChainContractProjectionCodec::text).toList());
    }

    private static ChainContextValue.Text text(String value) {
        return ChainContextValue.text(value);
    }

    private static ChainContextValue.ObjectValue object(
            Map<String, ? extends ChainContextValue> values) {
        return ChainContextValue.object(values);
    }

    private static Projection projection(ChainContextValue value) {
        String canonicalJson = canonicalJson(value);
        return new Projection(value, sha256(canonicalJson), canonicalJson);
    }

    static String canonicalJson(ChainContextValue value) {
        StringBuilder result = new StringBuilder();
        append(result, Objects.requireNonNull(value, "value"));
        return result.toString();
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void append(StringBuilder out, ChainContextValue value) {
        if (value instanceof ChainContextValue.Text item) {
            quote(out, item.value());
        } else if (value instanceof ChainContextValue.NumberValue item) {
            out.append(item.value());
        } else if (value instanceof ChainContextValue.BooleanValue item) {
            out.append(item.value());
        } else if (value instanceof ChainContextValue.NullValue) {
            out.append("null");
        } else if (value instanceof ChainContextValue.ArrayValue item) {
            out.append('[');
            for (int index = 0; index < item.values().size(); index++) {
                if (index > 0) out.append(',');
                append(out, item.values().get(index));
            }
            out.append(']');
        } else if (value instanceof ChainContextValue.ObjectValue item) {
            out.append('{');
            List<Map.Entry<String, ChainContextValue>> entries = item.values()
                    .entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .toList();
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) out.append(',');
                quote(out, entries.get(index).getKey());
                out.append(':');
                append(out, entries.get(index).getValue());
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException("unsupported context value");
        }
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) out.append(String.format(
                            "\\u%04x", (int) character));
                    else out.append(character);
                }
            }
        }
        out.append('"');
    }

    public record Projection(
            ChainContextValue value, String sha256, String canonicalJson) {
        public Projection {
            Objects.requireNonNull(value, "value");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be canonical");
            }
            Objects.requireNonNull(canonicalJson, "canonicalJson");
        }
    }
}
