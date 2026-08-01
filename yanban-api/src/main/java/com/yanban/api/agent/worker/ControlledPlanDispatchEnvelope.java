package com.yanban.api.agent.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.AgentRuntimeRequest;
import com.yanban.api.agent.ResolvedToolPolicy;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.agent.worker.WorkerBudget;
import com.yanban.core.agent.worker.WorkerMaterialAssignment;
import com.yanban.core.agent.worker.WorkerMaterialType;
import com.yanban.core.agent.worker.WorkerServerAuthority;
import com.yanban.core.agent.worker.WorkerTaskAttestation;
import com.yanban.core.agent.worker.WorkerTaskAttestor;
import com.yanban.core.agent.worker.WorkerTaskPacket;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.ResearchRuntimeScope;
import com.yanban.core.research.ResearchToolContracts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Non-sensitive persisted constraints used to reissue, never deserialize, controlled Worker authority. */
public final class ControlledPlanDispatchEnvelope {

    public static final String SCHEMA = "controlled_plan_dispatch_envelope_v1";
    public static final String STEP_KEY = "controlled_cross_material_read";
    public static final String DIGEST_PREFIX = "[controlledEnvelopeSha256=";
    private static final String DIGEST_SUFFIX = "]";
    private static final Set<String> ROOT_FIELDS = Set.of("schema", "projectVersion", "parentMaxSteps",
            "parentMaxTokens", "parentMaxDuplicateToolCalls", "parentSynthesisMaxTokens",
            "parentAllowedReadTools", "parentWorkerBudget", "tasks");
    private static final Set<String> TASK_FIELDS = Set.of("role", "maxSteps", "maxTokens",
            "allowedReadTools", "budget", "materials");
    private static final Set<String> MATERIAL_FIELDS = Set.of("relativePath", "materialType",
            "sizeBytes", "sha256");
    private static final Set<String> BUDGET_FIELDS = Set.of("maxInputPaths", "maxToolCalls", "maxFindings",
            "maxEvidenceRefs", "maxBytesInspected", "maxSummaryUtf8Bytes");
    private static final Set<String> CONTRACT_READ_TOOLS = ResearchToolContracts.all().stream()
            .map(contract -> contract.definition().name())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private ControlledPlanDispatchEnvelope() {
    }

    public static ObjectNode capture(ObjectMapper json, ControlledWorkerDispatch dispatch) {
        if (json == null || dispatch == null) {
            throw new IllegalArgumentException("controlled Plan envelope inputs are required");
        }
        ObjectNode root = json.createObjectNode();
        root.put("schema", SCHEMA);
        root.put("projectVersion", dispatch.projectVersion().value());
        root.put("parentMaxSteps", dispatch.parentMaxSteps());
        root.put("parentMaxTokens", dispatch.parentMaxTokens());
        root.put("parentMaxDuplicateToolCalls", dispatch.parentMaxDuplicateToolCalls());
        root.put("parentSynthesisMaxTokens", dispatch.parentSynthesisMaxTokens());
        writeStrings(root.putArray("parentAllowedReadTools"), dispatch.authority().parentAllowedReadTools());
        writeBudget(root.putObject("parentWorkerBudget"), dispatch.authority().parentBudget());
        ArrayNode tasks = root.putArray("tasks");
        dispatch.tasks().stream().sorted(Comparator.comparing(ControlledPlanDispatchEnvelope::role)).forEach(task -> {
            WorkerTaskPacket packet = task.attestation().packet();
            ObjectNode value = tasks.addObject();
            value.put("role", role(task));
            value.put("maxSteps", task.maxSteps());
            value.put("maxTokens", task.maxTokens());
            writeStrings(value.putArray("allowedReadTools"), packet.allowedReadTools());
            writeBudget(value.putObject("budget"), packet.budget());
            ArrayNode materials = value.putArray("materials");
            packet.materialAssignments().stream()
                    .sorted(Comparator.comparing(item -> item.relativePath().value()))
                    .forEach(item -> {
                        ObjectNode material = materials.addObject();
                        ProjectRelativePath path = item.relativePath();
                        material.put("relativePath", path.value());
                        material.put("materialType", item.materialType().name());
                        material.put("sizeBytes", dispatch.fileSizes().get(path));
                        material.put("sha256", dispatch.fileHashes().get(path).sha256());
                    });
        });
        return root;
    }

    public static String digest(ObjectMapper json, JsonNode envelope) {
        try {
            byte[] canonical = json.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception exception) {
            throw new IllegalStateException("controlled Plan envelope digest could not be computed", exception);
        }
    }

    public static String digestMarker(ObjectMapper json, JsonNode envelope) {
        return DIGEST_PREFIX + digest(json, envelope) + DIGEST_SUFFIX;
    }

    public static Recovery recover(ObjectMapper json, JsonNode envelope, AgentRuntimeRequest currentRequest,
                                   ProjectManifestResponse currentManifest) {
        if (json == null || currentRequest == null || currentRequest.planId() == null
                || currentRequest.projectContext() == null || currentManifest == null) {
            throw new IllegalStateException("controlled Plan recovery boundary is incomplete");
        }
        requireObject(envelope, ROOT_FIELDS, "controlled Plan envelope");
        if (!SCHEMA.equals(text(envelope, "schema"))) {
            throw new IllegalStateException("unsupported controlled Plan envelope schema");
        }
        ProjectVersionRef version = new ProjectVersionRef(text(envelope, "projectVersion"));
        int parentMaxSteps = positiveInt(envelope, "parentMaxSteps");
        int parentMaxTokens = positiveInt(envelope, "parentMaxTokens");
        int parentMaxDuplicateToolCalls = nonNegativeInt(envelope, "parentMaxDuplicateToolCalls");
        int parentSynthesisMaxTokens = positiveInt(envelope, "parentSynthesisMaxTokens");
        WorkerBudget parentBudget = budget(envelope.path("parentWorkerBudget"));
        List<String> parentTools = strings(envelope.path("parentAllowedReadTools"), "parentAllowedReadTools");
        if (parentTools.isEmpty() || parentTools.stream().anyMatch(tool -> !CONTRACT_READ_TOOLS.contains(tool))) {
            throw new IllegalStateException("controlled Plan envelope contains a non-contract tool");
        }
        Set<String> currentTools = Set.copyOf(currentRequest.toolPolicy().allowedTools());
        if (!currentTools.containsAll(parentTools)
                || currentRequest.toolPolicy().maxToolCalls() < parentBudget.maxToolCalls()
                || currentRequest.toolPolicy().maxDuplicateToolCalls() < parentMaxDuplicateToolCalls) {
            throw new IllegalStateException("controlled Plan authority was revoked by the current tool policy");
        }
        if (!currentRequest.projectContext().projectId().equals(currentManifest.projectId())
                || !version.value().equals(currentManifest.version())) {
            throw new IllegalStateException("controlled Plan Project version is stale");
        }

        Map<ProjectRelativePath, ProjectFileEntry> currentFiles = new LinkedHashMap<>();
        for (ProjectFileEntry file : currentManifest.files()) {
            ProjectRelativePath path = ProjectRelativePath.of(file.path());
            if (currentFiles.putIfAbsent(path, file) != null) {
                throw new IllegalStateException("current Project manifest contains duplicate paths");
            }
        }

        JsonNode tasksNode = envelope.path("tasks");
        if (!tasksNode.isArray() || tasksNode.size() != 2) {
            throw new IllegalStateException("controlled Plan envelope must contain exactly two tasks");
        }
        List<TaskSpec> taskSpecs = new ArrayList<>();
        for (JsonNode task : tasksNode) taskSpecs.add(task(task, currentFiles));
        taskSpecs.sort(Comparator.comparing(TaskSpec::role));
        if (!List.of("implementation", "paper").equals(taskSpecs.stream().map(TaskSpec::role).toList())) {
            throw new IllegalStateException("controlled Plan envelope task roles are invalid");
        }
        validateRoleTools(taskSpecs);
        Set<ProjectRelativePath> assigned = new LinkedHashSet<>();
        Map<ProjectRelativePath, Long> sizes = new LinkedHashMap<>();
        Map<ProjectRelativePath, FileHash> hashes = new LinkedHashMap<>();
        for (TaskSpec task : taskSpecs) {
            for (Material material : task.materials()) {
                if (!assigned.add(material.assignment().relativePath())) {
                    throw new IllegalStateException("controlled Plan envelope contains overlapping paths");
                }
                sizes.put(material.assignment().relativePath(), material.sizeBytes());
                hashes.put(material.assignment().relativePath(), material.fileHash());
            }
        }
        if (assigned.size() != parentBudget.maxInputPaths()) {
            throw new IllegalStateException("controlled Plan input budget does not match its material scope");
        }
        int allocatedToolCalls = taskSpecs.stream().mapToInt(task -> task.budget().maxToolCalls()).sum();
        int allocatedSteps = taskSpecs.stream().mapToInt(TaskSpec::maxSteps).sum() + 1;
        int allocatedTokens = taskSpecs.stream().mapToInt(TaskSpec::maxTokens).sum() + parentSynthesisMaxTokens;
        if (allocatedToolCalls != parentBudget.maxToolCalls() || allocatedSteps > parentMaxSteps
                || allocatedTokens != parentMaxTokens) {
            throw new IllegalStateException("controlled Plan envelope does not conserve its parent budget");
        }

        String parentRunId = "AGENT_PLAN:" + currentRequest.planId();
        AgentRunIdentity identity = new AgentRunIdentity("AGENT_PLAN", currentRequest.planId().toString(),
                currentRequest.userId(), currentRequest.sessionId(), currentManifest.projectId());
        ResearchRuntimeScope runtimeScope = new ResearchRuntimeScope(currentManifest.projectId(),
                currentRequest.userId(), Set.of(WorkerServerAuthority.REQUIRED_READ_CAPABILITY), version);
        WorkerServerAuthority authority = WorkerServerAuthority.serverResolved(
                identity, runtimeScope, parentTools, parentBudget);
        List<ControlledWorkerDispatch.Task> recoveredTasks = new ArrayList<>();
        for (TaskSpec task : taskSpecs) {
            List<WorkerMaterialAssignment> assignments = task.materials().stream()
                    .map(Material::assignment).toList();
            WorkerTaskPacket packet = new WorkerTaskPacket(parentRunId + ":" + task.role(), parentRunId, version,
                    assignments, objective(task.role()), successCriteria(task.role()), task.allowedReadTools(),
                    List.of(ControlledWorkerDispatchPlanner.FINDING_KEY), task.budget(), List.of());
            WorkerTaskAttestation attestation = WorkerTaskAttestor.attestServerResolved(authority, packet);
            recoveredTasks.add(new ControlledWorkerDispatch.Task(attestation, task.maxSteps(), task.maxTokens()));
        }
        ControlledWorkerDispatch dispatch = new ControlledWorkerDispatch(authority, currentRequest.userId(),
                currentManifest.projectId(), currentRequest.sessionId(), parentRunId, version, recoveredTasks,
                sizes, hashes, parentMaxSteps, parentMaxTokens, parentMaxDuplicateToolCalls,
                parentSynthesisMaxTokens);
        ResolvedToolPolicy effectivePolicy = new ResolvedToolPolicy(parentTools, parentBudget.maxToolCalls(),
                parentMaxDuplicateToolCalls, currentRequest.toolPolicy().reason() + "+controlled_plan_recovery");
        return new Recovery(dispatch, effectivePolicy, parentMaxSteps, parentMaxTokens);
    }

    private static TaskSpec task(JsonNode node, Map<ProjectRelativePath, ProjectFileEntry> currentFiles) {
        requireObject(node, TASK_FIELDS, "controlled Plan task");
        String role = text(node, "role");
        int maxSteps = positiveInt(node, "maxSteps");
        int maxTokens = positiveInt(node, "maxTokens");
        List<String> tools = strings(node.path("allowedReadTools"), "allowedReadTools");
        if (tools.isEmpty() || tools.stream().anyMatch(tool -> !CONTRACT_READ_TOOLS.contains(tool))) {
            throw new IllegalStateException("controlled Plan task contains an invalid tool");
        }
        WorkerBudget budget = budget(node.path("budget"));
        JsonNode materialsNode = node.path("materials");
        if (!materialsNode.isArray() || materialsNode.isEmpty()) {
            throw new IllegalStateException("controlled Plan task material scope is empty");
        }
        List<Material> materials = new ArrayList<>();
        for (JsonNode materialNode : materialsNode) {
            requireObject(materialNode, MATERIAL_FIELDS, "controlled Plan material");
            ProjectRelativePath path = ProjectRelativePath.of(text(materialNode, "relativePath"));
            WorkerMaterialType type;
            try {
                type = WorkerMaterialType.valueOf(text(materialNode, "materialType"));
            } catch (Exception exception) {
                throw new IllegalStateException("controlled Plan material type is invalid", exception);
            }
            long size = nonNegativeLong(materialNode, "sizeBytes");
            FileHash hash = new FileHash(text(materialNode, "sha256"));
            ProjectFileEntry current = currentFiles.get(path);
            if (current == null || current.sizeBytes() != size || !hash.sha256().equalsIgnoreCase(current.sha256())) {
                throw new IllegalStateException("controlled Plan material no longer matches the current manifest");
            }
            materials.add(new Material(new WorkerMaterialAssignment(path, type), size, hash));
        }
        materials.sort(Comparator.comparing(item -> item.assignment().relativePath().value()));
        if (materials.size() != budget.maxInputPaths()) {
            throw new IllegalStateException("controlled Plan task budget does not match its material scope");
        }
        return new TaskSpec(role, maxSteps, maxTokens, tools, budget, List.copyOf(materials));
    }

    private static void validateRoleTools(List<TaskSpec> tasks) {
        TaskSpec implementation = tasks.get(0);
        TaskSpec paper = tasks.get(1);
        if (!paper.materials().stream().allMatch(item -> item.assignment().materialType() == WorkerMaterialType.PAPER)
                || !paper.allowedReadTools().equals(List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE))) {
            throw new IllegalStateException("controlled Plan paper task contract is invalid");
        }
        Set<WorkerMaterialType> implementationTypes = implementation.materials().stream()
                .map(item -> item.assignment().materialType()).collect(java.util.stream.Collectors.toSet());
        if (implementationTypes.stream().anyMatch(type -> type != WorkerMaterialType.CODE
                && type != WorkerMaterialType.CONFIGURATION)) {
            throw new IllegalStateException("controlled Plan implementation material type is invalid");
        }
        List<String> expectedTools = new ArrayList<>();
        if (implementationTypes.contains(WorkerMaterialType.CODE)) {
            expectedTools.add(ResearchToolContracts.PROJECT_CODE_SYMBOLS);
        }
        if (implementationTypes.contains(WorkerMaterialType.CONFIGURATION)) {
            expectedTools.add(ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY);
        }
        expectedTools.sort(String::compareTo);
        if (!implementation.allowedReadTools().equals(expectedTools)) {
            throw new IllegalStateException("controlled Plan implementation task contract is invalid");
        }
    }

    private static WorkerBudget budget(JsonNode node) {
        requireObject(node, BUDGET_FIELDS, "controlled Plan budget");
        return new WorkerBudget(positiveInt(node, "maxInputPaths"), positiveInt(node, "maxToolCalls"),
                positiveInt(node, "maxFindings"), positiveInt(node, "maxEvidenceRefs"),
                positiveLong(node, "maxBytesInspected"), positiveLong(node, "maxSummaryUtf8Bytes"));
    }

    private static void writeBudget(ObjectNode node, WorkerBudget budget) {
        node.put("maxInputPaths", budget.maxInputPaths());
        node.put("maxToolCalls", budget.maxToolCalls());
        node.put("maxFindings", budget.maxFindings());
        node.put("maxEvidenceRefs", budget.maxEvidenceRefs());
        node.put("maxBytesInspected", budget.maxBytesInspected());
        node.put("maxSummaryUtf8Bytes", budget.maxSummaryUtf8Bytes());
    }

    private static void writeStrings(ArrayNode node, List<String> values) {
        values.stream().sorted().forEach(node::add);
    }

    private static List<String> strings(JsonNode node, String field) {
        if (!node.isArray() || node.isEmpty()) throw new IllegalStateException(field + " must be a non-empty array");
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.textValue().isBlank() || !values.add(value.textValue())) {
                throw new IllegalStateException(field + " contains an invalid or duplicate value");
            }
        }
        List<String> sorted = values.stream().sorted().toList();
        if (!new ArrayList<>(values).equals(sorted)) {
            throw new IllegalStateException(field + " must use stable sorted order");
        }
        return sorted;
    }

    private static void requireObject(JsonNode node, Set<String> fields, String label) {
        if (node == null || !node.isObject()) throw new IllegalStateException(label + " must be an object");
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) throw new IllegalStateException(label + " fields are invalid");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException(field + " must be non-blank text");
        }
        return value.textValue();
    }

    private static int positiveInt(JsonNode node, String field) {
        int value = integer(node, field);
        if (value < 1) throw new IllegalStateException(field + " must be positive");
        return value;
    }

    private static int nonNegativeInt(JsonNode node, String field) {
        int value = integer(node, field);
        if (value < 0) throw new IllegalStateException(field + " must be non-negative");
        return value;
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalStateException(field + " must be an integer");
        }
        return value.intValue();
    }

    private static long positiveLong(JsonNode node, String field) {
        long value = nonNegativeLong(node, field);
        if (value < 1) throw new IllegalStateException(field + " must be positive");
        return value;
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalStateException(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static String role(ControlledWorkerDispatch.Task task) {
        String id = task.attestation().packet().workerTaskId();
        return id.substring(id.lastIndexOf(':') + 1);
    }

    private static String objective(String role) {
        return "paper".equals(role)
                ? "Analyze the assigned LaTeX paper material for evidence relevant to a later cross-material comparison."
                : "Analyze the assigned code and configuration material for evidence relevant to a later cross-material comparison.";
    }

    private static List<String> successCriteria(String role) {
        return "paper".equals(role)
                ? List.of("Inspect every assigned paper path", "Return only evidence-bound observations")
                : List.of("Inspect every assigned implementation path", "Return only evidence-bound observations");
    }

    public record Recovery(ControlledWorkerDispatch dispatch, ResolvedToolPolicy effectivePolicy,
                           int maxSteps, int maxTokens) {
        public AgentRuntimeRequest attach(AgentRuntimeRequest request) {
            AgentRuntimeRequest bounded = new AgentRuntimeRequest(request.strategy(), request.sessionId(),
                    request.history(), request.userId(), request.userMessage(), request.provider(), request.model(),
                    request.temperature(), maxTokens, maxSteps, request.ragDisabled(), request.skillId(),
                    request.apiKey(), request.apiUrl(), request.skillPrompt(), request.runtimeMode(),
                    request.toolCallingMode(), effectivePolicy, effectivePolicy.maxToolCalls(),
                    effectivePolicy.maxDuplicateToolCalls(), request.traceId(), request.tokenConsumer(),
                    request.processConsumer(), request.planId(), request.projectContext(),
                    request.inheritedTrustedEvidence(), request.orchestrationRequirements(),
                    request.persistPlanConversationSummary(), null);
            return bounded.withControlledWorkerDispatch(dispatch);
        }
    }

    private record TaskSpec(String role, int maxSteps, int maxTokens, List<String> allowedReadTools,
                            WorkerBudget budget, List<Material> materials) {
    }

    private record Material(WorkerMaterialAssignment assignment, long sizeBytes, FileHash fileHash) {
    }
}
