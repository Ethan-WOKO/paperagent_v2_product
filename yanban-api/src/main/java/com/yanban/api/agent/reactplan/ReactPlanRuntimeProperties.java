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
    private int maxConcurrentTasks = 20;
    private int maxConcurrentTasksPerUser = 3;
    private int maxQueuedTasksPerUser = 10;
    private int taskLeaseSeconds = 30;

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
    public int getMaxConcurrentTasks() { return maxConcurrentTasks; }
    public void setMaxConcurrentTasks(int value) { maxConcurrentTasks = positive(value, "maxConcurrentTasks"); }
    public int getMaxConcurrentTasksPerUser() { return maxConcurrentTasksPerUser; }
    public void setMaxConcurrentTasksPerUser(int value) { maxConcurrentTasksPerUser = positive(value, "maxConcurrentTasksPerUser"); }
    public int getMaxQueuedTasksPerUser() { return maxQueuedTasksPerUser; }
    public void setMaxQueuedTasksPerUser(int value) { maxQueuedTasksPerUser = positive(value, "maxQueuedTasksPerUser"); }
    public int getTaskLeaseSeconds() { return taskLeaseSeconds; }
    public void setTaskLeaseSeconds(int value) { taskLeaseSeconds = positive(value, "taskLeaseSeconds"); }

    private static int positive(int value, String name) {
        if (value < 1 || value > 10_000) throw new IllegalArgumentException(name + " must be between 1 and 10000");
        return value;
    }
}
