package com.yanban.api.skills;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_installed_skills")
public class UserSkillEntity {
    @Id @Column(length = 48) private String id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "name_key", nullable = false, length = 100) private String nameKey;
    @Column(nullable = false, length = 1024) private String description;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String prompt;
    @Column(columnDefinition = "LONGTEXT") private String metadata;
    @Column(name = "allowed_tools_json", nullable = false, columnDefinition = "TEXT") private String allowedToolsJson;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected UserSkillEntity() {}

    UserSkillEntity(String id, Long userId, SkillUploadParser.Parsed skill, String toolsJson) {
        this.id = id;
        this.userId = userId;
        this.name = skill.name();
        this.nameKey = SkillUploadParser.nameKey(skill.name());
        this.description = skill.description();
        this.prompt = skill.prompt();
        this.metadata = skill.metadata();
        this.allowedToolsJson = toolsJson;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getPrompt() { return prompt; }
    public String getMetadata() { return metadata; }
    public String getAllowedToolsJson() { return allowedToolsJson; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
