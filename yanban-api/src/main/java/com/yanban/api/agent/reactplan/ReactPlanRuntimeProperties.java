package com.yanban.api.agent.reactplan;

import jakarta.validation.constraints.AssertTrue;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "yanban.agent.reactplan")
public class ReactPlanRuntimeProperties {
    private boolean enabled;
    private URI engineOrigin = URI.create("http://127.0.0.1:8092");
    private String engineServiceToken = "";
    private String defaultProvider = "deepseek";
    private String defaultModel = "deepseek-chat";

    @AssertTrue(message = "enabled ReAct runtime requires an engine service token of at least 32 characters")
    public boolean isServiceTokenSafe() {
        return !enabled || engineServiceToken != null && engineServiceToken.length() >= 32;
    }

    @AssertTrue(message = "ReAct Engine origin must use loopback HTTP(S) or the fixed Compose service")
    public boolean isEngineOriginSafe() {
        if (engineOrigin == null || engineOrigin.getHost() == null) return false;
        String host = engineOrigin.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
        boolean compose = "agent-engine-reactplan".equalsIgnoreCase(host);
        boolean safeScheme = loopback
                ? "http".equals(engineOrigin.getScheme()) || "https".equals(engineOrigin.getScheme())
                : compose && "http".equals(engineOrigin.getScheme());
        String path = engineOrigin.getPath();
        return safeScheme
                && engineOrigin.getPort() == 8092
                && engineOrigin.getUserInfo() == null
                && engineOrigin.getQuery() == null
                && engineOrigin.getFragment() == null
                && (path == null || path.isEmpty() || "/".equals(path));
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public URI getEngineOrigin() { return engineOrigin; }
    public void setEngineOrigin(URI engineOrigin) { this.engineOrigin = engineOrigin; }
    public String getEngineServiceToken() { return engineServiceToken; }
    public void setEngineServiceToken(String engineServiceToken) { this.engineServiceToken = engineServiceToken; }
    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
}
