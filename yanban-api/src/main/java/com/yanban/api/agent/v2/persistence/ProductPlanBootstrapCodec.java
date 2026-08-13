package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.RequirementDeclarationMode;
import io.paperagent.v2.contracts.SecretRef;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TaskRequirements;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;
import io.paperagent.v2.contracts.DeliveryRequirement;
import io.paperagent.v2.contracts.PublishRequirement;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public final class ProductPlanBootstrapCodec {
    static final int FORMAT_VERSION = 2;
    static final int LEGACY_FORMAT_VERSION = 1;
    private static final String CORRUPT = "Stored V2 Plan bootstrap payload is invalid";

    private final ObjectMapper json;

    public ProductPlanBootstrapCodec(ObjectMapper json) {
        this.json = json.copy();
    }

    public EncodedPayload encode(PersistedPlanBootstrap bootstrap) {
        try {
            int formatVersion = formatVersion(bootstrap);
            byte[] bytes = json.writeValueAsBytes(toDocument(bootstrap, formatVersion));
            return new EncodedPayload(
                    formatVersion,
                    sha256(bytes),
                    new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode V2 Plan bootstrap", exception);
        }
    }

    public PersistedPlanBootstrap decode(int formatVersion, String expectedHash, String payload) {
        if (formatVersion != FORMAT_VERSION
                && formatVersion != LEGACY_FORMAT_VERSION) {
            throw corrupt();
        }
        try {
            byte[] bytes = required(payload).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(
                    sha256(bytes).getBytes(StandardCharsets.US_ASCII),
                    required(expectedHash).getBytes(StandardCharsets.US_ASCII))) {
                throw corrupt();
            }
            PersistedPlanBootstrap result = fromDocument(
                    json.readTree(bytes), formatVersion);
            EncodedPayload canonical = encode(result);
            if (!canonical.sha256().equals(expectedHash)
                    || !canonical.json().equals(payload)) {
                throw corrupt();
            }
            return result;
        } catch (Exception exception) {
            // Parser and canonical-constructor exceptions may carry excerpts from
            // the stored document. Never retain them on the externally visible
            // integrity failure.
            throw corrupt();
        }
    }

    private ObjectNode toDocument(
            PersistedPlanBootstrap bootstrap, int formatVersion) {
        ObjectNode root = json.createObjectNode();
        root.put("format", formatVersion);
        root.set("taskFrame", taskFrameNode(bootstrap.taskFrame(), formatVersion));
        root.set("plan", planNode(bootstrap.plan(), formatVersion));
        root.set("initialCheckpoint",
                checkpointNode(bootstrap.initialCheckpoint()));
        return root;
    }

    private ObjectNode taskFrameNode(TaskFrame value, int formatVersion) {
        ObjectNode node = json.createObjectNode();
        node.put("id", value.id().value());
        node.put("objective", value.objective());
        node.set("targets", strings(value.targets()));
        node.set("deliverables", strings(value.deliverables()));
        node.set("constraints", strings(value.constraints()));
        if (formatVersion >= FORMAT_VERSION) {
            node.set("requirements", requirementsNode(value.requirements()));
        }
        value.sourceProjectVersion().ifPresentOrElse(project -> {
            ObjectNode projectNode = json.createObjectNode();
            projectNode.put("projectId", project.projectId());
            projectNode.put("versionId", project.versionId());
            node.set("sourceProjectVersion", projectNode);
        }, () -> node.putNull("sourceProjectVersion"));
        node.set("executionProfile", executionProfileNode(value.executionProfile()));
        node.put("createdAt", value.createdAt().toString());
        return node;
    }

    private ObjectNode requirementsNode(TaskRequirements value) {
        ObjectNode node = json.createObjectNode();
        node.put("declarationMode", value.declarationMode().name());
        node.put("deliveryRequirement", value.deliveryRequirement().name());
        ArrayNode validation = json.createArrayNode();
        value.validationRequirements().forEach(requirement -> {
            ObjectNode item = json.createObjectNode();
            item.put("requirementId", requirement.requirementId());
            item.put("subject", requirement.subject().name());
            item.put("completionCondition", requirement.completionCondition());
            validation.add(item);
        });
        node.set("validationRequirements", validation);
        node.put("publishRequirement", value.publishRequirement().name());
        return node;
    }

    private ObjectNode executionProfileNode(ExecutionProfile value) {
        ObjectNode node = json.createObjectNode();
        node.put("tier", value.tier().name());
        node.set("capabilities", strings(value.capabilities().stream()
                .map(Enum::name).sorted().toList()));
        node.put("networkPolicy", value.networkPolicy().name());
        node.set("networkAllowlist", strings(value.networkAllowlist().stream().sorted().toList()));
        ObjectNode limits = json.createObjectNode();
        limits.put("wallTime", value.resourceLimits().wallTime().toString());
        limits.put("cpuTime", value.resourceLimits().cpuTime().toString());
        limits.put("memoryBytes", value.resourceLimits().memoryBytes());
        limits.put("outputBytes", value.resourceLimits().outputBytes());
        limits.put("processCount", value.resourceLimits().processCount());
        node.set("resourceLimits", limits);
        node.set("secretReferences", strings(value.secretReferences().stream()
                .map(SecretRef::name).sorted().toList()));
        return node;
    }

    private ObjectNode planNode(Plan value, int formatVersion) {
        ObjectNode node = json.createObjectNode();
        node.put("id", value.id().value());
        node.put("taskFrameId", value.taskFrameId().value());
        ArrayNode revisions = json.createArrayNode();
        value.revisions().forEach(revision -> revisions.add(
                revisionNode(revision, formatVersion)));
        node.set("revisions", revisions);
        return node;
    }

    private ObjectNode revisionNode(PlanRevision value, int formatVersion) {
        ObjectNode node = json.createObjectNode();
        node.put("id", value.id().value());
        node.put("taskFrameId", value.taskFrameId().value());
        node.put("number", value.number());
        value.parentRevisionId().ifPresentOrElse(
                parent -> node.put("parentRevisionId", parent.value()),
                () -> node.putNull("parentRevisionId"));
        node.put("reason", value.reason());
        node.put("createdAt", value.createdAt().toString());
        ArrayNode steps = json.createArrayNode();
        value.steps().forEach(step -> steps.add(stepNode(step, formatVersion)));
        node.set("steps", steps);
        ArrayNode facts = json.createArrayNode();
        value.completedFacts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(PlanStepId::value)))
                .forEach(entry -> facts.add(factNode(entry.getValue())));
        node.set("completedFacts", facts);
        return node;
    }

    private ObjectNode stepNode(PlanStep value, int formatVersion) {
        ObjectNode node = json.createObjectNode();
        node.put("id", value.id().value());
        node.put("intent", value.intent());
        node.put("expectedOutcome", value.expectedOutcome());
        node.set("dependencies", strings(value.dependencies().stream()
                .map(PlanStepId::value).sorted().toList()));
        node.set("completionCriteria", strings(value.completionCriteria()));
        if (!value.constraints().isEmpty()) {
            node.set("constraints", strings(value.constraints()));
        }
        if (value.mayChangeCandidate()) {
            node.put("mayChangeCandidate", true);
        }
        if (value.candidateValidationCompletionCondition() != null) {
            node.put("candidateValidationCompletionCondition",
                    value.candidateValidationCompletionCondition());
        }
        if (formatVersion >= FORMAT_VERSION) {
            node.set("validationRequirementIds",
                    strings(value.validationRequirementIds()));
        }
        ObjectNode hints = json.createObjectNode();
        hints.put("maxAttempts", value.executionHints().maxAttempts());
        hints.put("maxDuration", value.executionHints().maxDuration().toString());
        node.set("executionHints", hints);
        return node;
    }

    private ObjectNode factNode(CompletionFact value) {
        ObjectNode node = json.createObjectNode();
        node.put("stepId", value.stepId().value());
        node.put("outcomeHash", value.outcomeHash());
        node.put("completedAt", value.completedAt().toString());
        node.set("receiptReferences", strings(value.receiptReferences().stream()
                .map(ReceiptId::value).toList()));
        return node;
    }

    private ObjectNode checkpointNode(VersionedCheckpoint value) {
        Checkpoint checkpoint = value.checkpoint();
        ObjectNode node = json.createObjectNode();
        node.put("version", value.version());
        node.put("taskFrameId", checkpoint.taskFrameId().value());
        node.put("planId", checkpoint.planId().value());
        node.put("revisionId", checkpoint.revisionId().value());
        node.put("revisionNumber", checkpoint.revisionNumber());
        node.put("lastEventSequence", checkpoint.lastEventSequence());
        node.put("planState", checkpoint.planState().name());
        ArrayNode states = json.createArrayNode();
        checkpoint.stepStates().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(PlanStepId::value)))
                .forEach(entry -> {
                    ObjectNode state = json.createObjectNode();
                    state.put("stepId", entry.getKey().value());
                    state.put("state", entry.getValue().name());
                    states.add(state);
                });
        node.set("stepStates", states);
        node.set("receiptReferences", strings(checkpoint.receiptReferences().stream()
                .map(ReceiptId::value).toList()));
        node.put("createdAt", checkpoint.createdAt().toString());
        return node;
    }

    private PersistedPlanBootstrap fromDocument(
            JsonNode root, int formatVersion) {
        if (integer(root, "format") != formatVersion) {
            throw corrupt();
        }
        TaskFrame taskFrame = taskFrame(
                requiredNode(root, "taskFrame"), formatVersion);
        Plan plan = plan(requiredNode(root, "plan"), formatVersion);
        VersionedCheckpoint checkpoint =
                checkpoint(requiredNode(root, "initialCheckpoint"));
        return new PersistedPlanBootstrap(taskFrame, plan, checkpoint);
    }

    private TaskFrame taskFrame(JsonNode node, int formatVersion) {
        JsonNode project = requiredNode(node, "sourceProjectVersion");
        Optional<ProjectVersionRef> projectVersion = project.isNull()
                ? Optional.empty()
                : Optional.of(new ProjectVersionRef(
                        text(project, "projectId"), text(project, "versionId")));
        return new TaskFrame(
                new TaskFrameId(text(node, "id")),
                text(node, "objective"),
                stringList(node, "targets"),
                stringList(node, "deliverables"),
                stringList(node, "constraints"),
                formatVersion == LEGACY_FORMAT_VERSION
                        ? TaskRequirements.legacyUnspecified()
                        : requirements(requiredNode(node, "requirements")),
                projectVersion,
                executionProfile(requiredNode(node, "executionProfile")),
                Instant.parse(text(node, "createdAt")));
    }

    private TaskRequirements requirements(JsonNode node) {
        List<ValidationRequirement> validationRequirements = new ArrayList<>();
        requiredArray(node, "validationRequirements").forEach(value ->
                validationRequirements.add(new ValidationRequirement(
                        text(value, "requirementId"),
                        ValidationSubject.valueOf(text(value, "subject")),
                        text(value, "completionCondition"))));
        return new TaskRequirements(
                RequirementDeclarationMode.valueOf(text(node, "declarationMode")),
                DeliveryRequirement.valueOf(text(node, "deliveryRequirement")),
                validationRequirements,
                PublishRequirement.valueOf(text(node, "publishRequirement")));
    }

    private ExecutionProfile executionProfile(JsonNode node) {
        JsonNode limits = requiredNode(node, "resourceLimits");
        return new ExecutionProfile(
                ExecutionTier.valueOf(text(node, "tier")),
                new LinkedHashSet<>(stringList(node, "capabilities").stream()
                        .map(Capability::valueOf).toList()),
                NetworkPolicy.valueOf(text(node, "networkPolicy")),
                stringList(node, "networkAllowlist"),
                new ResourceLimits(
                        Duration.parse(text(limits, "wallTime")),
                        Duration.parse(text(limits, "cpuTime")),
                        number(limits, "memoryBytes"),
                        number(limits, "outputBytes"),
                        integer(limits, "processCount")),
                new LinkedHashSet<>(stringList(node, "secretReferences").stream()
                        .map(SecretRef::new).toList()));
    }

    private Plan plan(JsonNode node, int formatVersion) {
        List<PlanRevision> revisions = new ArrayList<>();
        requiredArray(node, "revisions").forEach(value -> revisions.add(
                revision(value, formatVersion)));
        return new Plan(
                new PlanId(text(node, "id")),
                new TaskFrameId(text(node, "taskFrameId")),
                revisions);
    }

    private PlanRevision revision(JsonNode node, int formatVersion) {
        List<PlanStep> steps = new ArrayList<>();
        requiredArray(node, "steps").forEach(value -> steps.add(
                step(value, formatVersion)));
        Map<PlanStepId, CompletionFact> facts = new LinkedHashMap<>();
        requiredArray(node, "completedFacts").forEach(value -> {
            CompletionFact fact = fact(value);
            facts.put(fact.stepId(), fact);
        });
        JsonNode parent = requiredNode(node, "parentRevisionId");
        return new PlanRevision(
                new PlanRevisionId(text(node, "id")),
                new TaskFrameId(text(node, "taskFrameId")),
                number(node, "number"),
                parent.isNull()
                        ? Optional.empty()
                        : Optional.of(new PlanRevisionId(parent.textValue())),
                text(node, "reason"),
                Instant.parse(text(node, "createdAt")),
                steps,
                facts);
    }

    private PlanStep step(JsonNode node, int formatVersion) {
        return new PlanStep(
                new PlanStepId(text(node, "id")),
                text(node, "intent"),
                text(node, "expectedOutcome"),
                new LinkedHashSet<>(stringList(node, "dependencies").stream()
                        .map(PlanStepId::new).toList()),
                stringList(node, "completionCriteria"),
                new BoundedExecutionHints(
                        integer(requiredNode(node, "executionHints"), "maxAttempts"),
                        Duration.parse(text(requiredNode(node, "executionHints"), "maxDuration"))),
                node.has("constraints") ? stringList(node, "constraints") : List.of(),
                optionalBoolean(node, "mayChangeCandidate"),
                optionalText(node, "candidateValidationCompletionCondition"),
                formatVersion == LEGACY_FORMAT_VERSION
                        ? List.of()
                        : stringList(node, "validationRequirementIds"));
    }

    private static int formatVersion(PersistedPlanBootstrap bootstrap) {
        if (bootstrap.taskFrame().requirements().declarationMode()
                == RequirementDeclarationMode.LEGACY_UNSPECIFIED) {
            boolean hasFormalBindings = bootstrap.plan().revisions().stream()
                    .flatMap(revision -> revision.steps().stream())
                    .anyMatch(step -> !step.validationRequirementIds().isEmpty());
            if (hasFormalBindings) {
                throw new IllegalStateException(
                        "Legacy TaskFrame cannot carry formal validation bindings");
            }
            return LEGACY_FORMAT_VERSION;
        }
        return FORMAT_VERSION;
    }

    private static boolean optionalBoolean(JsonNode node, String field) {
        if (!node.has(field)) return false;
        if (!node.get(field).isBoolean()) throw corrupt();
        return node.get(field).booleanValue();
    }

    private static String optionalText(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return null;
        if (!node.get(field).isTextual()) throw corrupt();
        return node.get(field).textValue();
    }

    private CompletionFact fact(JsonNode node) {
        return new CompletionFact(
                new PlanStepId(text(node, "stepId")),
                text(node, "outcomeHash"),
                Instant.parse(text(node, "completedAt")),
                stringList(node, "receiptReferences").stream()
                        .map(ReceiptId::new).toList());
    }

    private VersionedCheckpoint checkpoint(JsonNode node) {
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        requiredArray(node, "stepStates").forEach(value -> states.put(
                new PlanStepId(text(value, "stepId")),
                StepExecutionState.valueOf(text(value, "state"))));
        Checkpoint checkpoint = new Checkpoint(
                new TaskFrameId(text(node, "taskFrameId")),
                new PlanId(text(node, "planId")),
                new PlanRevisionId(text(node, "revisionId")),
                number(node, "revisionNumber"),
                number(node, "lastEventSequence"),
                PlanExecutionState.valueOf(text(node, "planState")),
                states,
                stringList(node, "receiptReferences").stream()
                        .map(ReceiptId::new).toList(),
                Instant.parse(text(node, "createdAt")));
        return new VersionedCheckpoint(number(node, "version"), checkpoint);
    }

    private ArrayNode strings(List<String> values) {
        ArrayNode array = json.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private static List<String> stringList(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        requiredArray(node, field).forEach(value -> {
            if (!value.isTextual()) {
                throw corrupt();
            }
            result.add(value.textValue());
        });
        return List.copyOf(result);
    }

    private static JsonNode requiredNode(JsonNode node, String field) {
        if (node == null || !node.isObject() || !node.has(field)) {
            throw corrupt();
        }
        return node.get(field);
    }

    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = requiredNode(node, field);
        if (!value.isArray()) {
            throw corrupt();
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = requiredNode(node, field);
        if (!value.isTextual()) {
            throw corrupt();
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = requiredNode(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw corrupt();
        }
        return value.intValue();
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = requiredNode(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw corrupt();
        }
        return value.longValue();
    }

    private static String required(String value) {
        if (value == null) {
            throw corrupt();
        }
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static IllegalStateException corrupt() {
        return new IllegalStateException(CORRUPT);
    }

    public record EncodedPayload(int formatVersion, String sha256, String json) {
    }
}
