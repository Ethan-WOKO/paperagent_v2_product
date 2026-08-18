package com.yanban.api.agent.sandbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DataSizeUnit;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "yanban.sandbox")
public class SandboxExecutionProperties {

    private boolean enabled = false;
    private boolean requiredAtStartup = false;
    @NotBlank
    private String provider = "e2b";
    @NotNull
    private URI brokerUrl = URI.create("http://127.0.0.1:8091");
    @Min(1)
    @Max(64)
    private int maxConcurrentRuns = 18;
    @Min(1)
    @Max(2)
    private int cpus = 2;
    @NotNull
    @DataSizeUnit(DataUnit.GIGABYTES)
    private DataSize memoryLimit = DataSize.ofGigabytes(4);
    @NotNull
    private Duration executionTimeout = Duration.ofMinutes(15);
    @NotNull
    @DataSizeUnit(DataUnit.MEGABYTES)
    private DataSize maxOutputSize = DataSize.ofMegabytes(20);
    private boolean networkEnabled = false;
    private String brokerToken = "";

    @AssertTrue(message = "required-at-startup requires the sandbox capability to be enabled")
    public boolean isStartupRequirementConsistent() {
        return !requiredAtStartup || enabled;
    }

    @AssertTrue(message = "sandbox provider must be a supported governed provider")
    public boolean isProviderSupported() {
        return "e2b".equals(provider);
    }

    @AssertTrue(message = "the first sandbox release is fail-closed with networking disabled")
    public boolean isNetworkPolicySafe() {
        return !networkEnabled;
    }

    @AssertTrue(message = "sandbox memory cannot exceed 4 GiB")
    public boolean isMemoryLimitSafe() { return memoryLimit != null && memoryLimit.toBytes() <= DataSize.ofGigabytes(4).toBytes(); }

    @AssertTrue(message = "sandbox timeout cannot exceed 15 minutes")
    public boolean isExecutionTimeoutSafe() { return executionTimeout != null && !executionTimeout.isZero()
            && !executionTimeout.isNegative() && executionTimeout.compareTo(Duration.ofMinutes(15)) <= 0; }

    @AssertTrue(message = "sandbox output cannot exceed 20 MiB")
    public boolean isOutputLimitSafe() { return maxOutputSize != null && maxOutputSize.toBytes() > 0
            && maxOutputSize.toBytes() <= DataSize.ofMegabytes(20).toBytes(); }

    @AssertTrue(message = "sandbox broker URL must be HTTP(S) and must not contain user info")
    public boolean isBrokerUrlSafe() {
        if (brokerUrl == null || brokerUrl.getUserInfo() != null) return false;
        if ("https".equalsIgnoreCase(brokerUrl.getScheme())) return true;
        String host = brokerUrl.getHost();
        return "http".equalsIgnoreCase(brokerUrl.getScheme())
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host)
                    || ("e2b".equals(provider) && "sandbox-broker".equals(host) && brokerUrl.getPort() == 8091));
    }

    @AssertTrue(message = "enabled sandbox requires deployment-provided broker authentication")
    public boolean isBrokerAuthenticationSafe() { return !enabled || (brokerToken != null && brokerToken.length() >= 32); }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequiredAtStartup() {
        return requiredAtStartup;
    }

    public void setRequiredAtStartup(boolean requiredAtStartup) {
        this.requiredAtStartup = requiredAtStartup;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public URI getBrokerUrl() {
        return brokerUrl;
    }

    public void setBrokerUrl(URI brokerUrl) {
        this.brokerUrl = brokerUrl;
    }

    public int getMaxConcurrentRuns() {
        return maxConcurrentRuns;
    }

    public void setMaxConcurrentRuns(int maxConcurrentRuns) {
        this.maxConcurrentRuns = maxConcurrentRuns;
    }

    public int getCpus() {
        return cpus;
    }

    public void setCpus(int cpus) {
        this.cpus = cpus;
    }

    public DataSize getMemoryLimit() {
        return memoryLimit;
    }

    public void setMemoryLimit(DataSize memoryLimit) {
        this.memoryLimit = memoryLimit;
    }

    public Duration getExecutionTimeout() {
        return executionTimeout;
    }

    public void setExecutionTimeout(Duration executionTimeout) {
        this.executionTimeout = executionTimeout;
    }

    public DataSize getMaxOutputSize() {
        return maxOutputSize;
    }

    public void setMaxOutputSize(DataSize maxOutputSize) {
        this.maxOutputSize = maxOutputSize;
    }

    public boolean isNetworkEnabled() {
        return networkEnabled;
    }

    public String getBrokerToken() { return brokerToken; }
    public void setBrokerToken(String brokerToken) { this.brokerToken = brokerToken; }

    public void setNetworkEnabled(boolean networkEnabled) {
        this.networkEnabled = networkEnabled;
    }
}
