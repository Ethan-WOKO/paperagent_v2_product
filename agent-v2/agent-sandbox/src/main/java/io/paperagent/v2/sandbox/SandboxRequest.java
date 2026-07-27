package io.paperagent.v2.sandbox;

import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.SecretRef;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.WorkspaceRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One provider-neutral execution attempt. It contains argv tokens and logical
 * secret references, never a shell command, host path, or secret value.
 */
public record SandboxRequest(
        SandboxRequestId requestId,
        ToolCallId toolCallId,
        WorkspaceRef workspace,
        Optional<ProjectPath> workingDirectory,
        List<String> argv,
        Map<String, String> environment,
        Map<String, SecretRef> secretEnvironmentBindings,
        SandboxOperationIntent intent,
        ExecutionProfile executionProfile,
        boolean cancellationRequested) {

    public SandboxRequest {
        SandboxValues.required(requestId, "sandboxRequest.requestId");
        SandboxValues.required(toolCallId, "sandboxRequest.toolCallId");
        SandboxValues.required(workspace, "sandboxRequest.workspace");
        workingDirectory = SandboxValues.required(
                workingDirectory,
                "sandboxRequest.workingDirectory");
        argv = validateArguments(argv);
        environment = validateEnvironment(environment);
        secretEnvironmentBindings = validateSecretBindings(secretEnvironmentBindings);
        SandboxValues.required(intent, "sandboxRequest.intent");
        SandboxValues.required(executionProfile, "sandboxRequest.executionProfile");
        validateCapabilities(intent, executionProfile, secretEnvironmentBindings);
        rejectEnvironmentCollisions(environment, secretEnvironmentBindings);
    }

    private static List<String> validateArguments(List<String> values) {
        List<String> copy = SandboxValues.list(values, "sandboxRequest.argv");
        if (copy.isEmpty()) {
            SandboxValues.fail(
                    SandboxValidationCode.REQUIRED_COLLECTION_EMPTY,
                    "sandboxRequest.argv",
                    "at least one argv token is required");
        }
        if (copy.size() > SandboxLimits.MAX_ARGUMENT_COUNT) {
            SandboxValues.fail(
                    SandboxValidationCode.BOUND_EXCEEDED,
                    "sandboxRequest.argv",
                    "argv exceeds maximum token count " + SandboxLimits.MAX_ARGUMENT_COUNT);
        }
        SandboxValues.boundedText(
                copy.get(0),
                "sandboxRequest.argv[0]",
                SandboxLimits.MAX_ARGUMENT_TEXT_LENGTH);
        for (int index = 1; index < copy.size(); index++) {
            SandboxValues.boundedValue(
                    copy.get(index),
                    "sandboxRequest.argv[" + index + "]",
                    SandboxLimits.MAX_ARGUMENT_TEXT_LENGTH);
        }
        return copy;
    }

    private static Map<String, String> validateEnvironment(Map<String, String> values) {
        Map<String, String> copy = SandboxValues.map(values, "sandboxRequest.environment");
        if (copy.size() > SandboxLimits.MAX_ENVIRONMENT_ENTRIES) {
            SandboxValues.fail(
                    SandboxValidationCode.BOUND_EXCEEDED,
                    "sandboxRequest.environment",
                    "environment exceeds maximum entry count "
                            + SandboxLimits.MAX_ENVIRONMENT_ENTRIES);
        }
        copy.forEach((name, value) -> {
            SandboxValues.environmentName(name, "sandboxRequest.environment.name");
            SandboxValues.boundedValue(
                    value,
                    "sandboxRequest.environment.value",
                    SandboxLimits.MAX_ENVIRONMENT_VALUE_LENGTH);
        });
        return copy;
    }

    private static Map<String, SecretRef> validateSecretBindings(
            Map<String, SecretRef> values) {
        Map<String, SecretRef> copy = SandboxValues.map(
                values,
                "sandboxRequest.secretEnvironmentBindings");
        if (copy.size() > SandboxLimits.MAX_SECRET_ENVIRONMENT_BINDINGS) {
            SandboxValues.fail(
                    SandboxValidationCode.BOUND_EXCEEDED,
                    "sandboxRequest.secretEnvironmentBindings",
                    "secret bindings exceed maximum entry count "
                            + SandboxLimits.MAX_SECRET_ENVIRONMENT_BINDINGS);
        }
        copy.keySet().forEach(name -> SandboxValues.environmentName(
                name,
                "sandboxRequest.secretEnvironmentBindings.name"));
        return copy;
    }

    private static void validateCapabilities(
            SandboxOperationIntent intent,
            ExecutionProfile profile,
            Map<String, SecretRef> secretBindings) {
        requireCapability(profile, Capability.EXECUTE_COMMAND, "command execution");
        if (intent == SandboxOperationIntent.DEPENDENCY_INSTALL) {
            requireCapability(profile, Capability.INSTALL_DEPENDENCY, "dependency installation");
        }
        if (!secretBindings.isEmpty()) {
            requireCapability(profile, Capability.USE_SECRET_REFERENCE, "secret references");
        }
        for (Map.Entry<String, SecretRef> binding : secretBindings.entrySet()) {
            if (!profile.secretReferences().contains(binding.getValue())) {
                SandboxValues.fail(
                        SandboxValidationCode.SECRET_REFERENCE_NOT_AUTHORIZED,
                        "sandboxRequest.secretEnvironmentBindings." + binding.getKey(),
                        "secret reference is not authorized by the execution profile");
            }
        }
    }

    private static void requireCapability(
            ExecutionProfile profile,
            Capability capability,
            String operation) {
        if (!profile.allows(capability)) {
            SandboxValues.fail(
                    SandboxValidationCode.CAPABILITY_NOT_GRANTED,
                    "sandboxRequest.executionProfile.capabilities",
                    operation + " requires " + capability);
        }
    }

    private static void rejectEnvironmentCollisions(
            Map<String, String> environment,
            Map<String, SecretRef> secretBindings) {
        for (String name : secretBindings.keySet()) {
            if (environment.containsKey(name)) {
                SandboxValues.fail(
                        SandboxValidationCode.ENVIRONMENT_NAME_COLLISION,
                        "sandboxRequest.secretEnvironmentBindings." + name,
                        "an environment name cannot have both a plain value and a secret binding");
            }
        }
    }
}
