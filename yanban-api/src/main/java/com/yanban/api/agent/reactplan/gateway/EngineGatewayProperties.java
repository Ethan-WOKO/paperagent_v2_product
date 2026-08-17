package com.yanban.api.agent.reactplan.gateway;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "yanban.agent.engine.gateway")
public class EngineGatewayProperties {
    private boolean enabled;
    private String taskGrantSecret = "";
    private Duration taskGrantTtl = Duration.ofMinutes(10);
    private String workspaceRoot = "data/agent-engine-workspaces";
    @Min(1)
    @Max(10 * 1024 * 1024)
    private int maxReadBytes = 10 * 1024 * 1024;

    @AssertTrue(message = "enabled engine gateway requires a deployment task-grant secret of at least 32 characters")
    public boolean isTaskGrantConfigurationSafe() {
        return !enabled || taskGrantSecret != null && taskGrantSecret.length() >= 32;
    }

    @AssertTrue(message = "engine task grants must live between 30 seconds and 30 minutes")
    public boolean isTaskGrantTtlSafe() {
        return taskGrantTtl != null
                && taskGrantTtl.compareTo(Duration.ofSeconds(30)) >= 0
                && taskGrantTtl.compareTo(Duration.ofMinutes(30)) <= 0;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTaskGrantSecret() { return taskGrantSecret; }
    public void setTaskGrantSecret(String taskGrantSecret) { this.taskGrantSecret = taskGrantSecret; }
    public Duration getTaskGrantTtl() { return taskGrantTtl; }
    public void setTaskGrantTtl(Duration taskGrantTtl) { this.taskGrantTtl = taskGrantTtl; }
    public int getMaxReadBytes() { return maxReadBytes; }
    public void setMaxReadBytes(int maxReadBytes) { this.maxReadBytes = maxReadBytes; }
    public String getWorkspaceRoot() { return workspaceRoot; }
    public void setWorkspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; }
}
