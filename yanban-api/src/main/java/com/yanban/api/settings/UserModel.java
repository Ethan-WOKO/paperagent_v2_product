package com.yanban.api.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "user_models")
public class UserModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "provider_key", nullable = false, length = 64)
    private String providerKey;

    @Column(name = "provider_label", nullable = false, length = 128)
    private String providerLabel;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "api_url", length = 512)
    private String apiUrl;

    @Column(name = "api_key_encrypted")
    private String apiKeyEncrypted;

    @Column(name = "is_builtin", nullable = false)
    private Boolean builtin;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "supports_vision")
    private Boolean supportsVision;

    public Boolean getSupportsVision() { return supportsVision; }
    public void setSupportsVision(Boolean value) { supportsVision = value; }

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserModel() {
    }

    public UserModel(Long userId, String providerKey, String providerLabel, String modelName,
                    String apiUrl, String apiKeyEncrypted, Boolean builtin, Integer sortOrder) {
        this.userId = userId;
        this.providerKey = providerKey;
        this.providerLabel = providerLabel;
        this.modelName = modelName;
        this.apiUrl = apiUrl;
        this.apiKeyEncrypted = apiKeyEncrypted;
        this.builtin = builtin;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public String getProviderLabel() {
        return providerLabel;
    }

    public String getModelName() {
        return modelName;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getApiKeyEncrypted() {
        return apiKeyEncrypted;
    }

    public Boolean getBuiltin() {
        return builtin;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(String providerLabel, String modelName, String apiUrl, String apiKeyEncrypted) {
        this.providerLabel = providerLabel;
        this.modelName = modelName;
        this.apiUrl = apiUrl;
        this.apiKeyEncrypted = apiKeyEncrypted;
    }
}
