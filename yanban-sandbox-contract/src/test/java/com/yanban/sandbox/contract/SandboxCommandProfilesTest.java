package com.yanban.sandbox.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxCommandProfilesTest {
    @Test
    void allowsOnlyOneSafeJavaSourceInSourceFileMode() {
        assertDoesNotThrow(() -> SandboxCommandProfiles.requireAllowed(
                List.of("java", "src/main/java/xhs_1111.java")));
        assertThrows(IllegalArgumentException.class, () -> SandboxCommandProfiles.requireAllowed(
                List.of("java", "-cp", "src/main/java", "xhs_1111")));
        assertThrows(IllegalArgumentException.class, () -> SandboxCommandProfiles.requireAllowed(
                List.of("java", "../xhs_1111.java")));
    }

    @Test
    void allowsOnlyFixedMultiLanguageRunnerProfiles() {
        assertDoesNotThrow(() -> SandboxCommandProfiles.requireAllowed(
                List.of("yanban-runner", "python", "experiments/model.py")));
        assertDoesNotThrow(() -> SandboxCommandProfiles.requireAllowed(
                List.of("yanban-runner", "c", "src/model.c")));
        assertDoesNotThrow(() -> SandboxCommandProfiles.requireAllowed(
                List.of("yanban-runner", "cpp", "src/model.cpp")));
        assertThrows(IllegalArgumentException.class, () -> SandboxCommandProfiles.requireAllowed(
                List.of("yanban-runner", "matlab", "model.m")));
        assertThrows(IllegalArgumentException.class, () -> SandboxCommandProfiles.requireAllowed(
                List.of("yanban-runner", "python", "../secret.py")));
        assertThrows(IllegalArgumentException.class, () -> SandboxCommandProfiles.requireAllowed(
                List.of("python3", "model.py")));
    }

    @Test
    void derivesOnlyThePinnedServerOwnedMavenDependencyPreparation() {
        assertEquals(List.of(
                        List.of("mvn", "-B", "-ntp",
                                "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
                                "-Dartifact=org.codehaus.plexus:plexus-utils:1.1"),
                        List.of("mvn", "-B", "-ntp",
                                "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
                                "-Dartifact=org.apache.maven.surefire:surefire-junit-platform:3.5.2"),
                        List.of("mvn", "-B", "-ntp",
                                "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
                                "-Dartifact=org.junit.platform:junit-platform-launcher:1.9.3"),
                        List.of("mvn", "-B", "-ntp",
                                "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
                                "-Dartifact=org.junit.platform:junit-platform-launcher:1.11.4"),
                        List.of("mvn", "-B", "-ntp",
                                "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:go-offline")),
                SandboxCommandProfiles.dependencyPreparation(List.of("mvn", "-o", "test")).orElseThrow());
        assertTrue(SandboxCommandProfiles.dependencyPreparation(
                List.of("yanban-runner", "python", "study.py")).isEmpty());
        assertThrows(IllegalArgumentException.class, () ->
                SandboxCommandProfiles.dependencyPreparation(List.of("mvn", "dependency:go-offline")));
    }

    @Test
    void javaCoordinatesAreStrictBoundedAndProduceOnlyInternalPreparation() {
        var argv = List.of("yanban-runner", "java", "Sort.java",
                "--dependency=ch.qos.logback:logback-core:1.5.18");
        assertDoesNotThrow(() -> SandboxCommandProfiles.requireAllowed(argv));
        assertTrue(SandboxCommandProfiles.usesJavaDependencies(argv));
        assertEquals(List.of(List.of("yanban-java-dependencies",
                        "ch.qos.logback:logback-core:1.5.18")),
                SandboxCommandProfiles.dependencyPreparation(argv).orElseThrow());
        for (String bad : List.of(
                "--dependency=ch.qos.logback:logback-core",
                "--dependency=ch.qos.logback:logback-core:LATEST",
                "--dependency=g:a:1;touch-x",
                "--dependency=https://evil.invalid/a.jar")) {
            assertThrows(IllegalArgumentException.class, () ->
                    SandboxCommandProfiles.requireAllowed(
                            List.of("yanban-runner", "java", "Sort.java", bad)));
        }
        var tooMany = new java.util.ArrayList<>(
                List.of("yanban-runner", "java", "Sort.java"));
        for (int i = 0; i < 9; i++) tooMany.add("--dependency=g:a" + i + ":1.0");
        assertThrows(IllegalArgumentException.class, () ->
                SandboxCommandProfiles.requireAllowed(tooMany));
    }

    @Test
    void pythonRequirementsArePinnedBoundedAndProduceOnlyInternalPreparation() {
        var argv = List.of("yanban-runner", "python", "study.py",
                "--dependency=numpy==2.2.6",
                "--dependency=requests==2.32.3");
        assertDoesNotThrow(() -> SandboxCommandProfiles.requireAllowed(argv));
        assertTrue(SandboxCommandProfiles.usesPythonDependencies(argv));
        assertTrue(SandboxCommandProfiles.usesDeclaredDependencies(argv));
        assertEquals(List.of(List.of("yanban-python-dependencies",
                        "numpy==2.2.6", "requests==2.32.3")),
                SandboxCommandProfiles.dependencyPreparation(argv)
                        .orElseThrow());
        for (String bad : List.of(
                "--dependency=requests",
                "--dependency=requests>=2",
                "--dependency=requests==latest",
                "--dependency=requests[security]==2.32.3",
                "--dependency=requests==2.32.3;python_version>'3'",
                "--dependency=https://evil.invalid/package.whl")) {
            assertThrows(IllegalArgumentException.class, () ->
                    SandboxCommandProfiles.requireAllowed(List.of(
                            "yanban-runner", "python", "study.py", bad)));
        }
        assertThrows(IllegalArgumentException.class, () ->
                SandboxCommandProfiles.requireAllowed(List.of(
                        "yanban-runner", "python", "study.py",
                        "--dependency=my_package==1.0",
                        "--dependency=my-package==1.1")));
    }
}
