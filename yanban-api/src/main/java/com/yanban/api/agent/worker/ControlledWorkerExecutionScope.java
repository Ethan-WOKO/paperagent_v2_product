package com.yanban.api.agent.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.worker.WorkerMaterialType;
import com.yanban.core.agent.worker.WorkerTaskAttestation;
import com.yanban.core.agent.worker.WorkerTaskPacket;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ResearchToolContracts;
import com.yanban.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Thread-confined execution boundary for a server-attested Worker task.
 * It narrows model arguments to the exact assigned paths and records only calls that reached the tool executor.
 */
public final class ControlledWorkerExecutionScope implements AutoCloseable {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private final State state;
    private boolean closed;

    private ControlledWorkerExecutionScope(State state) {
        this.state = state;
    }

    public static ControlledWorkerExecutionScope open(WorkerTaskAttestation attestation) {
        if (attestation == null) {
            throw new IllegalArgumentException("worker task attestation is required");
        }
        if (CURRENT.get() != null) {
            throw new IllegalStateException("recursive controlled Worker execution is not allowed");
        }
        State state = new State(attestation.packet());
        CURRENT.set(state);
        return new ControlledWorkerExecutionScope(state);
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    public static void validateInvocation(String toolName, JsonNode arguments) {
        State state = CURRENT.get();
        if (state == null) return;
        try {
            state.validate(toolName, arguments);
        } catch (RuntimeException exception) {
            state.reject(exception.getMessage());
            throw exception;
        }
    }

    public static void recordResult(String toolName, JsonNode arguments, ToolResult result) {
        State state = CURRENT.get();
        if (state == null) return;
        state.record(toolName, arguments, result);
    }

    public static void recordFailure(String toolName, JsonNode arguments, RuntimeException exception) {
        State state = CURRENT.get();
        if (state == null) return;
        state.recordFailure(toolName, arguments,
                exception == null ? "tool execution failed" : exception.getClass().getSimpleName());
    }

    public static void recordSerializedResult(String toolName, JsonNode arguments, String serialized,
                                              ObjectMapper objectMapper) {
        State state = CURRENT.get();
        if (state == null) return;
        state.recordSerialized(toolName, arguments, serialized, objectMapper);
    }

    Snapshot snapshot() {
        ensureOpen();
        return state.snapshot();
    }

    @Override
    public void close() {
        if (closed) return;
        if (CURRENT.get() != state) {
            throw new IllegalStateException("controlled Worker execution scope closed out of order");
        }
        CURRENT.remove();
        closed = true;
    }

    private void ensureOpen() {
        if (closed || CURRENT.get() != state) {
            throw new IllegalStateException("controlled Worker execution scope is not active");
        }
    }

    record Snapshot(List<ControlledWorkerToolExecution> executions, String rejection) {
        Snapshot {
            executions = executions == null ? List.of() : List.copyOf(executions);
        }
    }

    private static final class State {
        private final WorkerTaskPacket packet;
        private final Set<ProjectRelativePath> assignedPaths;
        private final Set<ProjectRelativePath> successfullyInspected = new LinkedHashSet<>();
        private final List<ControlledWorkerToolExecution> executions = new ArrayList<>();
        private String rejection;

        private State(WorkerTaskPacket packet) {
            this.packet = packet;
            this.assignedPaths = Set.copyOf(packet.materialScope());
        }

        private void validate(String toolName, JsonNode arguments) {
            if (toolName == null || !packet.allowedReadTools().contains(toolName)) {
                throw new IllegalArgumentException("controlled Worker tool is outside its task allowlist");
            }
            List<ProjectRelativePath> paths = paths(arguments);
            if (paths.isEmpty()) {
                throw new IllegalArgumentException("controlled Worker tools require explicit assigned relativePaths");
            }
            if (!assignedPaths.containsAll(paths)) {
                throw new IllegalArgumentException("controlled Worker tool requested a path outside its assignment");
            }
            if (paths.stream().anyMatch(successfullyInspected::contains)) {
                throw new IllegalArgumentException("controlled Worker path was already inspected by this task");
            }
            for (ProjectRelativePath path : paths) {
                WorkerMaterialType type = packet.materialTypeOf(path);
                if (!compatible(toolName, type)) {
                    throw new IllegalArgumentException("controlled Worker tool does not match the assigned material type");
                }
            }
        }

        private boolean compatible(String toolName, WorkerMaterialType type) {
            return switch (toolName) {
                case ResearchToolContracts.PROJECT_LATEX_OUTLINE -> type == WorkerMaterialType.PAPER;
                case ResearchToolContracts.PROJECT_CODE_SYMBOLS -> type == WorkerMaterialType.CODE;
                case ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY ->
                        type == WorkerMaterialType.CONFIGURATION || type == WorkerMaterialType.EXPERIMENT;
                default -> false;
            };
        }

        private void record(String toolName, JsonNode arguments, ToolResult result) {
            List<ProjectRelativePath> paths = paths(arguments);
            executions.add(new ControlledWorkerToolExecution(toolName, paths,
                    result != null && result.success(), result == null ? null : result.output(),
                    result == null || result.errorCode() == null ? null : result.errorCode().name(),
                    result == null ? "tool execution returned no result" : result.errorMessage(),
                    result != null && result.retryable()));
            if (result != null && result.success()) successfullyInspected.addAll(paths);
        }

        private void recordFailure(String toolName, JsonNode arguments, String message) {
            executions.add(new ControlledWorkerToolExecution(toolName, paths(arguments), false,
                    null, "INTERNAL_ERROR", message, false));
        }

        private void recordSerialized(String toolName, JsonNode arguments, String serialized,
                                      ObjectMapper objectMapper) {
            JsonNode output = null;
            boolean success = false;
            String error = null;
            try {
                output = objectMapper.readTree(serialized == null ? "{}" : serialized);
                success = !output.has("success") || output.path("success").asBoolean(true);
                if (!success) error = output.path("errorMessage").asText("annotated tool failed");
            } catch (Exception exception) {
                error = "annotated tool result was not valid JSON";
            }
            executions.add(new ControlledWorkerToolExecution(toolName, paths(arguments), success,
                    output, success ? null : "INTERNAL_ERROR", error, false));
            if (success) successfullyInspected.addAll(paths(arguments));
        }

        private void reject(String reason) {
            if (rejection == null) rejection = reason;
        }

        private Snapshot snapshot() {
            return new Snapshot(List.copyOf(executions), rejection);
        }

        private List<ProjectRelativePath> paths(JsonNode arguments) {
            if (arguments == null || !arguments.isObject()) return List.of();
            JsonNode values = arguments.get("relativePaths");
            LinkedHashSet<ProjectRelativePath> paths = new LinkedHashSet<>();
            if (values != null && values.isArray()) {
                values.forEach(value -> {
                    ProjectRelativePath path = ProjectRelativePath.of(value.asText());
                    if (!paths.add(path)) {
                        throw new IllegalArgumentException("controlled Worker relativePaths contain duplicates");
                    }
                });
            } else if (values != null && values.isTextual()) {
                paths.add(ProjectRelativePath.of(values.asText()));
            }
            if (paths.size() > packet.materialAssignments().size()) {
                throw new IllegalArgumentException("controlled Worker relativePaths are invalid");
            }
            return List.copyOf(paths);
        }
    }
}
