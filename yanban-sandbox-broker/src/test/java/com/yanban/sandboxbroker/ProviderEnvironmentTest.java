package com.yanban.sandboxbroker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class ProviderEnvironmentTest {
    @Test void windowsE2bReceivesOnlyCaseInsensitiveSystemRootAndControlledValues() {
        BrokerProperties properties = new BrokerProperties();
        properties.setE2bApiKey("server-side-key-that-is-long-enough");
        ProcessBuilder builder = new ProcessBuilder("ignored");
        builder.environment().put("OPENAI_API_KEY", "must-not-leak");

        new ProviderEnvironment(properties, Map.of(
                "systemroot", "C:\\Windows",
                "PATH", "must-not-leak",
                "MYSQL_PASSWORD", "must-not-leak",
                "DATABASE_PASSWORD", "must-not-leak",
                "TEMP", "must-not-leak"), "Windows 11").apply(builder);

        assertThat(builder.environment()).containsOnly(
                org.assertj.core.data.MapEntry.entry("SystemRoot", "C:\\Windows"),
                org.assertj.core.data.MapEntry.entry("E2B_API_KEY", "server-side-key-that-is-long-enough"),
                org.assertj.core.data.MapEntry.entry("PYTHONUNBUFFERED", "1"),
                org.assertj.core.data.MapEntry.entry("PYTHONUTF8", "1"),
                org.assertj.core.data.MapEntry.entry("PYTHONIOENCODING", "utf-8"));
    }

    @Test void linuxE2bEnvironmentRemainsMinimal() {
        BrokerProperties properties = new BrokerProperties();
        properties.setE2bApiKey("server-side-key-that-is-long-enough");

        ProviderEnvironment environment = new ProviderEnvironment(properties,
                Map.of("SystemRoot", "must-not-cross-platform", "PATH", "must-not-leak"), "Linux");

        assertThat(environment.values()).containsOnly(
                org.assertj.core.data.MapEntry.entry("E2B_API_KEY", "server-side-key-that-is-long-enough"),
                org.assertj.core.data.MapEntry.entry("PYTHONUNBUFFERED", "1"),
                org.assertj.core.data.MapEntry.entry("PYTHONUTF8", "1"),
                org.assertj.core.data.MapEntry.entry("PYTHONIOENCODING", "utf-8"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void configuredPythonImportsWindowsRuntimeModulesWithAppliedEnvironment() throws Exception {
        String configured = System.getenv("YANBAN_E2B_PYTHON_EXECUTABLE");
        if (configured == null || configured.isBlank()) configured = System.getProperty("yanban.test.python");
        assumeTrue(configured != null && !configured.isBlank(), "configured E2B Python is unavailable");
        Path python = Path.of(configured).toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(python), "configured E2B Python is unavailable");

        BrokerProperties properties = new BrokerProperties();
        properties.setE2bApiKey("server-side-key-that-is-long-enough");
        ProcessBuilder builder = new ProcessBuilder(python.toString(), "-c",
                "import asyncio,_overlapped,socket; print('WORKER20_IMPORT_OK')");
        new ProviderEnvironment(properties).apply(builder);

        assertThat(builder.environment()).containsOnlyKeys("SystemRoot", "E2B_API_KEY", "PYTHONUNBUFFERED",
                "PYTHONUTF8", "PYTHONIOENCODING");
        Process process = builder.start();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.exitValue()).describedAs(stderr).isZero();
        assertThat(stdout).isEqualTo("WORKER20_IMPORT_OK");
    }
}
