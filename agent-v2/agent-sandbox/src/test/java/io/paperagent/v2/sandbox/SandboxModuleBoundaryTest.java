package io.paperagent.v2.sandbox;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ToolCall;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxModuleBoundaryTest {
    private static final List<String> FORBIDDEN_SOURCE_MARKERS = List.of(
            "ProcessBuilder",
            "Runtime.getRuntime",
            "java.net.",
            "java.net.http",
            "java.nio.file",
            "System.getenv",
            "System.getProperty",
            "System.currentTimeMillis",
            "Instant.now",
            "Clock.",
            "UUID.randomUUID",
            "Thread.sleep",
            "com.fasterxml.",
            "org.springframework.",
            "okhttp",
            "retrofit",
            "e2b",
            "paperagent.v1");

    private static final Set<Class<?>> FORBIDDEN_PUBLIC_TYPES = Set.of(
            Path.class,
            Process.class,
            Thread.class,
            Future.class,
            ToolCall.class,
            ExecutionReceipt.class);

    private static final List<Class<?>> PUBLIC_API_TYPES = List.of(
            SandboxLimits.class,
            SandboxValidationCode.class,
            SandboxValidationException.class,
            SandboxRequestId.class,
            SandboxOperationIntent.class,
            SandboxRequest.class,
            SandboxFailureCode.class,
            SandboxResult.class,
            SandboxFailure.class,
            ExecutedCommand.class,
            SandboxProtocolValidator.class,
            SandboxPort.class,
            ScriptObservation.class,
            ScriptedSandboxAssertionException.class,
            ScriptedSandboxPort.class,
            ScriptedSandboxPort.ScriptStep.class);

    @Test
    void productionSourceHasNoExecutionSdkNetworkEnvironmentOrV1Access() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(sourceRoot));
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(value -> value.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path).toLowerCase();
                for (String marker : FORBIDDEN_SOURCE_MARKERS) {
                    assertFalse(
                            source.contains(marker.toLowerCase()),
                            () -> path + " contains forbidden marker " + marker);
                }
            }
        }
    }

    @Test
    void publicApiDoesNotExposeHostProcessConcurrencyOrExecutedContractTypes() {
        for (Class<?> apiType : PUBLIC_API_TYPES) {
            for (Field field : apiType.getFields()) {
                assertAllowed(field.getType(), apiType + " field " + field.getName());
            }
            for (Constructor<?> constructor : apiType.getConstructors()) {
                Arrays.stream(constructor.getParameterTypes())
                        .forEach(type -> assertAllowed(type, constructor.toString()));
            }
            for (Method method : apiType.getMethods()) {
                if (method.getDeclaringClass() == Object.class
                        || !Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                assertAllowed(method.getReturnType(), method.toString());
                Arrays.stream(method.getParameterTypes())
                        .forEach(type -> assertAllowed(type, method.toString()));
            }
        }
    }

    private static void assertAllowed(Class<?> type, String location) {
        Class<?> component = type;
        while (component.isArray()) {
            component = component.getComponentType();
        }
        Class<?> exposedType = component;
        assertFalse(
                FORBIDDEN_PUBLIC_TYPES.contains(exposedType),
                () -> location + " exposes forbidden type " + exposedType.getName());
    }
}
