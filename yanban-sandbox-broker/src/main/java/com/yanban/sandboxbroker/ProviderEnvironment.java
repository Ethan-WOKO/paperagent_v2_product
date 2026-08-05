package com.yanban.sandboxbroker;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Complete provider-process environment. Provider secrets are never forwarded into a user sandbox. */
@Component
final class ProviderEnvironment {
    private final BrokerProperties properties;
    private final boolean windows;
    private final String windowsSystemRoot;

    @Autowired
    ProviderEnvironment(BrokerProperties properties) {
        this(properties, System.getenv(), System.getProperty("os.name", ""));
    }

    ProviderEnvironment(BrokerProperties properties, Map<String, String> hostEnvironment, String osName) {
        this.properties = properties;
        this.windows = osName != null && osName.toLowerCase(java.util.Locale.ROOT).contains("windows");
        this.windowsSystemRoot = windows ? hostValue(hostEnvironment, "SystemRoot") : null;
    }

    void apply(ProcessBuilder builder) {
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(values());
    }

    Map<String, String> values() {
        Map<String, String> values = new LinkedHashMap<>();
        copyWindowsRuntime(values, "SystemRoot");
        values.put("E2B_API_KEY", properties.getE2bApiKey());
        values.put("PYTHONUNBUFFERED", "1");
        values.put("PYTHONUTF8", "1");
        values.put("PYTHONIOENCODING", "utf-8");
        return Map.copyOf(values);
    }

    private void copyWindowsRuntime(Map<String, String> target, String canonicalName) {
        if (windows && windowsSystemRoot != null) target.put(canonicalName, windowsSystemRoot);
    }

    private static String hostValue(Map<String, String> hostEnvironment, String name) {
        if (hostEnvironment == null) return null;
        return hostEnvironment.entrySet().stream()
                .filter(entry -> entry.getKey() != null && name.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
