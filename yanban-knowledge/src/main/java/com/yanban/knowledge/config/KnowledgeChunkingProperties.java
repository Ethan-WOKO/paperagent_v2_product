package com.yanban.knowledge.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "yanban.knowledge.chunking")
public class KnowledgeChunkingProperties {

    @Min(64)
    private int maxCharacters = 800;

    @Min(0)
    private int overlapCharacters = 120;

    public int getMaxCharacters() {
        return maxCharacters;
    }

    public void setMaxCharacters(int maxCharacters) {
        this.maxCharacters = maxCharacters;
    }

    public int getOverlapCharacters() {
        return overlapCharacters;
    }

    public void setOverlapCharacters(int overlapCharacters) {
        this.overlapCharacters = overlapCharacters;
    }

    @AssertTrue(message = "knowledge chunk overlap must not exceed half of max characters")
    public boolean isOverlapValid() {
        return overlapCharacters <= maxCharacters / 2;
    }
}
