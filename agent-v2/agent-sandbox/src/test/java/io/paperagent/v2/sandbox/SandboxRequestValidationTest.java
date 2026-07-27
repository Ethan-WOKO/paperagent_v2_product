package io.paperagent.v2.sandbox;

import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.SecretRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxRequestValidationTest {
    @Test
    void validRequestPreservesArgvWorkingDirectoryProfileAndDefensiveCopies() {
        List<String> argv = new ArrayList<>(List.of("python", "-m", "pytest"));
        Map<String, String> environment = new LinkedHashMap<>(Map.of("MODE", "test"));
        Map<String, SecretRef> secrets = new LinkedHashMap<>(
                Map.of("API_TOKEN", SandboxFixtures.AUTHORIZED_SECRET));
        ExecutionProfile profile = SandboxFixtures.profile(
                Set.of(
                        Capability.EXECUTE_COMMAND,
                        Capability.USE_SECRET_REFERENCE),
                Set.of(SandboxFixtures.AUTHORIZED_SECRET),
                1_024);

        SandboxRequest request = new SandboxRequest(
                new SandboxRequestId("request-valid"),
                new io.paperagent.v2.contracts.ToolCallId("tool-valid"),
                SandboxFixtures.WORKSPACE,
                Optional.of(new ProjectPath("paper/tests")),
                argv,
                environment,
                secrets,
                SandboxOperationIntent.COMMAND,
                profile,
                false);
        argv.set(0, "changed");
        environment.put("LATE", "mutation");
        secrets.clear();

        assertEquals(List.of("python", "-m", "pytest"), request.argv());
        assertEquals("paper/tests", request.workingDirectory().orElseThrow().value());
        assertEquals(profile, request.executionProfile());
        assertEquals(Map.of("MODE", "test"), request.environment());
        assertEquals(
                Map.of("API_TOKEN", SandboxFixtures.AUTHORIZED_SECRET),
                request.secretEnvironmentBindings());
        assertThrows(UnsupportedOperationException.class, () -> request.argv().add("late"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.environment().put("LATE", "mutation"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.secretEnvironmentBindings().clear());
    }

    @Test
    void argvMustHaveNonBlankExecutableWhileEmptyArgumentsArePreserved() {
        assertCode(
                SandboxValidationCode.REQUIRED_VALUE_MISSING,
                () -> requestWithArgv(null));
        assertCode(
                SandboxValidationCode.REQUIRED_COLLECTION_EMPTY,
                () -> requestWithArgv(List.of()));
        assertCode(
                SandboxValidationCode.REQUIRED_TEXT_BLANK,
                () -> requestWithArgv(List.of("")));
        assertEquals(
                List.of("tool", "", "  "),
                requestWithArgv(List.of("tool", "", "  ")).argv());
        List<String> withNull = new ArrayList<>();
        withNull.add("tool");
        withNull.add(null);
        assertCode(
                SandboxValidationCode.NULL_COLLECTION_ELEMENT,
                () -> requestWithArgv(withNull));
        assertCode(
                SandboxValidationCode.BOUND_EXCEEDED,
                () -> requestWithArgv(SandboxFixtures.oversizedArguments()));
        assertCode(
                SandboxValidationCode.BOUND_EXCEEDED,
                () -> requestWithArgv(
                        List.of("x".repeat(SandboxLimits.MAX_ARGUMENT_TEXT_LENGTH + 1))));
        assertCode(
                SandboxValidationCode.INVALID_TEXT_CHARACTER,
                () -> requestWithArgv(List.of("tool", "bad\0argument")));
    }

    @Test
    void environmentNamesAndValueLengthsAreBoundedWhileEmptyValuesArePreserved() {
        assertCode(
                SandboxValidationCode.REQUIRED_VALUE_MISSING,
                () -> requestWithEnvironment(null));
        assertCode(
                SandboxValidationCode.INVALID_ENVIRONMENT_NAME,
                () -> requestWithEnvironment(Map.of("NOT-VALID", "value")));
        assertCode(
                SandboxValidationCode.INVALID_TEXT_CHARACTER,
                () -> requestWithEnvironment(Map.of("BAD", "value\0suffix")));
        assertEquals(
                Map.of("EMPTY", "", "SPACES", "  "),
                requestWithEnvironment(Map.of("EMPTY", "", "SPACES", "  ")).environment());
        assertCode(
                SandboxValidationCode.BOUND_EXCEEDED,
                () -> requestWithEnvironment(Map.of(
                        "BIG",
                        "x".repeat(SandboxLimits.MAX_ENVIRONMENT_VALUE_LENGTH + 1))));
        assertCode(
                SandboxValidationCode.BOUND_EXCEEDED,
                () -> requestWithEnvironment(SandboxFixtures.oversizedEnvironment()));
        Map<String, String> withNull = new LinkedHashMap<>();
        withNull.put("NULL_VALUE", null);
        assertCode(
                SandboxValidationCode.NULL_COLLECTION_ELEMENT,
                () -> requestWithEnvironment(withNull));
    }

    @Test
    void missingCommandCapabilityRejectsBeforeAnAdapterCanReceiveRequest() {
        ExecutionProfile profile = SandboxFixtures.profile(Set.of(), Set.of(), 1_024);

        assertCode(
                SandboxValidationCode.CAPABILITY_NOT_GRANTED,
                () -> SandboxFixtures.request(
                        "request-no-command",
                        List.of("tool"),
                        Map.of(),
                        Map.of(),
                        SandboxOperationIntent.COMMAND,
                        profile,
                        false));
    }

    @Test
    void dependencyInstallRequiresBothExecutionAndInstallCapabilities() {
        ExecutionProfile commandOnly = SandboxFixtures.profile(
                Set.of(Capability.EXECUTE_COMMAND),
                Set.of(),
                1_024);
        ExecutionProfile install = SandboxFixtures.profile(
                Set.of(Capability.EXECUTE_COMMAND, Capability.INSTALL_DEPENDENCY),
                Set.of(),
                1_024);

        assertCode(
                SandboxValidationCode.CAPABILITY_NOT_GRANTED,
                () -> SandboxFixtures.request(
                        "request-install-denied",
                        List.of("package-manager", "install", "dependency"),
                        Map.of(),
                        Map.of(),
                        SandboxOperationIntent.DEPENDENCY_INSTALL,
                        commandOnly,
                        false));
        SandboxRequest accepted = SandboxFixtures.request(
                "request-install-allowed",
                List.of("package-manager", "install", "dependency"),
                Map.of(),
                Map.of(),
                SandboxOperationIntent.DEPENDENCY_INSTALL,
                install,
                false);
        assertEquals(SandboxOperationIntent.DEPENDENCY_INSTALL, accepted.intent());
    }

    @Test
    void secretBindingsRequireCapabilityAndProfileAuthorization() {
        SecretRef requested = new SecretRef("service/requested");
        ExecutionProfile noSecretCapability = SandboxFixtures.profile(
                Set.of(Capability.EXECUTE_COMMAND),
                Set.of(),
                1_024);
        ExecutionProfile authorizesDifferentSecret = SandboxFixtures.profile(
                Set.of(Capability.EXECUTE_COMMAND, Capability.USE_SECRET_REFERENCE),
                Set.of(SandboxFixtures.AUTHORIZED_SECRET),
                1_024);

        assertCode(
                SandboxValidationCode.CAPABILITY_NOT_GRANTED,
                () -> SandboxFixtures.request(
                        "request-secret-capability",
                        List.of("tool"),
                        Map.of(),
                        Map.of("TOKEN", requested),
                        SandboxOperationIntent.COMMAND,
                        noSecretCapability,
                        false));
        assertCode(
                SandboxValidationCode.SECRET_REFERENCE_NOT_AUTHORIZED,
                () -> SandboxFixtures.request(
                        "request-secret-authorization",
                        List.of("tool"),
                        Map.of(),
                        Map.of("TOKEN", requested),
                        SandboxOperationIntent.COMMAND,
                        authorizesDifferentSecret,
                        false));
    }

    @Test
    void plainAndSecretEnvironmentNamesCannotCollide() {
        ExecutionProfile profile = SandboxFixtures.profile(
                Set.of(Capability.EXECUTE_COMMAND, Capability.USE_SECRET_REFERENCE),
                Set.of(SandboxFixtures.AUTHORIZED_SECRET),
                1_024);

        assertCode(
                SandboxValidationCode.ENVIRONMENT_NAME_COLLISION,
                () -> SandboxFixtures.request(
                        "request-collision",
                        List.of("tool"),
                        Map.of("TOKEN", "plain"),
                        Map.of("TOKEN", SandboxFixtures.AUTHORIZED_SECRET),
                        SandboxOperationIntent.COMMAND,
                profile,
                false));
    }

    @Test
    void secretBindingNamesAndCountsAreBounded() {
        ExecutionProfile profile = SandboxFixtures.profile(
                Set.of(Capability.EXECUTE_COMMAND, Capability.USE_SECRET_REFERENCE),
                Set.of(SandboxFixtures.AUTHORIZED_SECRET),
                1_024);
        assertCode(
                SandboxValidationCode.INVALID_ENVIRONMENT_NAME,
                () -> SandboxFixtures.request(
                        "request-secret-name",
                        List.of("tool"),
                        Map.of(),
                        Map.of("NOT-VALID", SandboxFixtures.AUTHORIZED_SECRET),
                        SandboxOperationIntent.COMMAND,
                        profile,
                        false));

        Map<String, SecretRef> tooMany = new LinkedHashMap<>();
        for (int index = 0; index <= SandboxLimits.MAX_SECRET_ENVIRONMENT_BINDINGS; index++) {
            tooMany.put("SECRET_" + index, SandboxFixtures.AUTHORIZED_SECRET);
        }
        assertCode(
                SandboxValidationCode.BOUND_EXCEEDED,
                () -> SandboxFixtures.request(
                        "request-secret-count",
                        List.of("tool"),
                        Map.of(),
                        tooMany,
                        SandboxOperationIntent.COMMAND,
                        profile,
                        false));
    }

    @Test
    void optionalWorkingDirectoryContainerIsRequiredAndEmptyMeansRoot() {
        SandboxRequest root = SandboxFixtures.request(
                "request-root",
                List.of("tool"),
                Map.of(),
                Map.of(),
                SandboxOperationIntent.COMMAND,
                SandboxFixtures.profile(),
                false);
        assertEquals(Optional.empty(), root.workingDirectory());

        assertCode(
                SandboxValidationCode.REQUIRED_VALUE_MISSING,
                () -> new SandboxRequest(
                        new SandboxRequestId("request-null-optional"),
                        new io.paperagent.v2.contracts.ToolCallId("tool-null-optional"),
                        SandboxFixtures.WORKSPACE,
                        null,
                        List.of("tool"),
                        Map.of(),
                        Map.of(),
                        SandboxOperationIntent.COMMAND,
                        SandboxFixtures.profile(),
                        false));
    }

    private static SandboxRequest requestWithArgv(List<String> argv) {
        return SandboxFixtures.request(
                "request-argv",
                argv,
                Map.of(),
                Map.of(),
                SandboxOperationIntent.COMMAND,
                SandboxFixtures.profile(),
                false);
    }

    private static SandboxRequest requestWithEnvironment(Map<String, String> environment) {
        return SandboxFixtures.request(
                "request-environment",
                List.of("tool"),
                environment,
                Map.of(),
                SandboxOperationIntent.COMMAND,
                SandboxFixtures.profile(),
                false);
    }

    private static void assertCode(
            SandboxValidationCode expectedCode,
            Runnable action) {
        assertEquals(expectedCode, SandboxFixtures.violation(action).code());
    }
}
