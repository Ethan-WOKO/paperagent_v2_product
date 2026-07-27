package io.paperagent.v2.sandbox;

import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.SecretRef;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class SandboxFixtures {
    static final Instant STARTED_AT = Instant.parse("2026-07-24T01:00:00Z");
    static final WorkspaceRef WORKSPACE = new WorkspaceRef(
            new WorkspaceId("workspace-1"),
            new ProjectVersionRef("project-1", "version-1"));
    static final SecretRef AUTHORIZED_SECRET = new SecretRef("service/api-token");

    private SandboxFixtures() {
    }

    static ExecutionProfile profile() {
        return profile(
                Set.of(Capability.EXECUTE_COMMAND),
                Set.of(),
                1_024);
    }

    static ExecutionProfile profile(
            Set<Capability> capabilities,
            Set<SecretRef> secretReferences,
            long outputBytes) {
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                capabilities,
                NetworkPolicy.DENY_ALL,
                List.of(),
                new ResourceLimits(
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(15),
                        128 * 1_024 * 1_024L,
                        outputBytes,
                        4),
                secretReferences);
    }

    static SandboxRequest request(String requestId) {
        return request(requestId, false);
    }

    static SandboxRequest request(String requestId, boolean cancelled) {
        return new SandboxRequest(
                new SandboxRequestId(requestId),
                new ToolCallId("tool-" + requestId),
                WORKSPACE,
                Optional.of(new ProjectPath("paper")),
                List.of("tool", "--request", requestId),
                Map.of("MODE", "test"),
                Map.of(),
                SandboxOperationIntent.COMMAND,
                profile(),
                cancelled);
    }

    static SandboxRequest request(
            String requestId,
            List<String> argv,
            Map<String, String> environment,
            Map<String, SecretRef> secretBindings,
            SandboxOperationIntent intent,
            ExecutionProfile profile,
            boolean cancelled) {
        return new SandboxRequest(
                new SandboxRequestId(requestId),
                new ToolCallId("tool-" + requestId),
                WORKSPACE,
                Optional.empty(),
                argv,
                environment,
                secretBindings,
                intent,
                profile,
                cancelled);
    }

    static ExecutedCommand executed(int exitCode, String stdout) {
        return new ExecutedCommand(
                exitCode,
                STARTED_AT,
                STARTED_AT.plusSeconds(1),
                stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new byte[0],
                false,
                false,
                Map.of("backend", "scripted"));
    }

    static SandboxFailure failure(SandboxFailureCode code) {
        return new SandboxFailure(
                code,
                "scripted " + code,
                Map.of("category", code.name()));
    }

    static SandboxValidationException violation(Runnable action) {
        try {
            action.run();
        } catch (SandboxValidationException exception) {
            return exception;
        }
        throw new AssertionError("expected SandboxValidationException");
    }

    static List<String> oversizedArguments() {
        List<String> arguments = new ArrayList<>();
        for (int index = 0; index <= SandboxLimits.MAX_ARGUMENT_COUNT; index++) {
            arguments.add("arg-" + index);
        }
        return arguments;
    }

    static Map<String, String> oversizedEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        for (int index = 0; index <= SandboxLimits.MAX_ENVIRONMENT_ENTRIES; index++) {
            environment.put("KEY_" + index, "value");
        }
        return environment;
    }
}
