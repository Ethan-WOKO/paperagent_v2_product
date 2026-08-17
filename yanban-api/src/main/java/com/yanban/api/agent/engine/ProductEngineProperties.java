package com.yanban.api.agent.engine;

import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "yanban.agent.engine.product")
public class ProductEngineProperties {
    private String mode = "legacy";
    private String dshBaseUrl = "http://127.0.0.1:8095";
    private String codexBaseUrl = "http://127.0.0.1:8094";
    private String serviceToken = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private int maxContextCharacters = 12_000;
    private int maxRecentMessages = 24;

    public ProductEngineMode selectedMode() {
        return ProductEngineMode.parse(mode);
    }

    public String selectedBaseUrl() {
        return baseUrl(selectedMode());
    }

    String baseUrl(ProductEngineMode selected) {
        return switch (selected) {
            case DSH -> dshBaseUrl;
            case CODEX -> codexBaseUrl;
            case LEGACY -> "";
        };
    }

    @AssertTrue(message = "external product Engine mode requires a fixed Engine base URL and service token")
    public boolean isExternalConfigurationSafe() {
        ProductEngineMode selected = selectedMode();
        if (!selected.external()) {
            return true;
        }
        return safeFixedOrigin(selectedBaseUrl())
                && serviceToken != null && serviceToken.length() >= 24;
    }

    @AssertTrue(message = "product Engine timeouts and context bounds are unsafe")
    public boolean isBoundsSafe() {
        return connectTimeout != null && !connectTimeout.isNegative()
                && connectTimeout.compareTo(Duration.ofSeconds(30)) <= 0
                && requestTimeout != null
                && requestTimeout.compareTo(Duration.ofSeconds(1)) >= 0
                && requestTimeout.compareTo(Duration.ofMinutes(2)) <= 0
                && maxContextCharacters >= 1_024 && maxContextCharacters <= 14_000
                && maxRecentMessages >= 1 && maxRecentMessages <= 100;
    }

    private boolean safeFixedOrigin(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank()
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                    && uri.getPort() <= 65535;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getDshBaseUrl() { return dshBaseUrl; }
    public void setDshBaseUrl(String dshBaseUrl) { this.dshBaseUrl = dshBaseUrl; }
    public String getCodexBaseUrl() { return codexBaseUrl; }
    public void setCodexBaseUrl(String codexBaseUrl) { this.codexBaseUrl = codexBaseUrl; }
    public String getServiceToken() { return serviceToken; }
    public void setServiceToken(String serviceToken) { this.serviceToken = serviceToken; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getMaxContextCharacters() { return maxContextCharacters; }
    public void setMaxContextCharacters(int maxContextCharacters) { this.maxContextCharacters = maxContextCharacters; }
    public int getMaxRecentMessages() { return maxRecentMessages; }
    public void setMaxRecentMessages(int maxRecentMessages) { this.maxRecentMessages = maxRecentMessages; }
}
