package com.yanban.core.model;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.model.deepseek")
public class DeepSeekProperties {
    /** Full OpenAI-compatible chat completions URL. */
    private String apiUrl = "https://api.deepseek.com/v1/chat/completions";
    /** Full model discovery URL. */
    private String modelsUrl = "https://api.deepseek.com/models";
    private String apiKey;
    private String model = "deepseek-v4-flash";
    private Double temperature = 0.7;
    private Integer maxTokens = 4096;
    private Duration timeout = Duration.ofSeconds(60);

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getModelsUrl() {
        return modelsUrl;
    }

    public void setModelsUrl(String modelsUrl) {
        this.modelsUrl = modelsUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
