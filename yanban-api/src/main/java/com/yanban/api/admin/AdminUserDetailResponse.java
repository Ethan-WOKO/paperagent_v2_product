package com.yanban.api.admin;

import java.time.Instant;
import java.util.List;

public record AdminUserDetailResponse(AdminUserSummaryResponse user,
                                      List<ChatSession> chats,
                                      List<PaperTask> papers,
                                      List<Project> projects,
                                      List<AiUsage> usage) {

    public record ChatSession(Long id,
                              String title,
                              String scope,
                              Long projectId,
                              String modelProvider,
                              String model,
                              Instant createdAt,
                              Instant updatedAt,
                              List<ChatMessage> messages) {
    }

    public record ChatMessage(Long id, String role, String content, Instant createdAt) {
    }

    public record PaperTask(Long id,
                            String title,
                            String sourceFilename,
                            String status,
                            String currentStage,
                            String errorMessage,
                            Instant createdAt,
                            Instant updatedAt) {
    }

    public record Project(Long id,
                          String name,
                          String rootType,
                          String indexVersion,
                          Instant createdAt,
                          Instant updatedAt) {
    }

    public record AiUsage(Long id,
                          String feature,
                          long promptTokens,
                          long completionTokens,
                          long totalTokens,
                          Instant createdAt) {
    }
}
