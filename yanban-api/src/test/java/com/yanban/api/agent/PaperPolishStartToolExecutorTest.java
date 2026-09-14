package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.tool.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PaperPolishStartToolExecutorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PaperPolishStartService service = mock(PaperPolishStartService.class);
    private final PaperPolishStartToolExecutor executor = new PaperPolishStartToolExecutor(service, json);
    @AfterEach void cleanup() { ToolExecutionContext.clear(); }

    @Test void schemaDoesNotLetModelChooseIdentityOrServerReplayFields() {
        var properties = executor.definition().parameters().path("properties");
        for (String name : new String[]{"userId", "projectId", "clientRequestId", "expectedProjectVersion", "invocationScope"}) {
            assertThat(properties.has(name)).isFalse();
        }
        assertThat(executor.descriptor().requiredPermissions()).containsExactly("paper:polish");
        assertThat(executor.descriptor().idempotencyPolicy()).isEqualTo(ToolDescriptor.IdempotencyPolicy.REQUIRED_KEY);
        assertThat(executor.descriptor().asyncMode()).isEqualTo(ToolDescriptor.AsyncMode.EXTERNAL_TASK);
    }

    @Test void requiresAuthenticatedOwnerBeforeDelegation() {
        var result = executor.execute(call());
        assertThat(result.success()).isFalse();
        verifyNoInteractions(service);
    }

    @Test void projectsUseServerContextAndReturnTaskLinkWithoutQueueCompletionClaim() {
        ToolExecutionContext.setCurrentUserId(7L);
        ToolExecutionContext.setCurrentProjectId(9L);
        when(service.start(eq(7L), eq(9L), any())).thenReturn(new PaperPolishStartService.StartResult(42L, "PENDING", "UPLOAD_RECEIVED", false, "a".repeat(64)));
        var result = executor.execute(call());
        assertThat(result.success()).isTrue();
        assertThat(result.output().path("completed").asBoolean()).isFalse();
        assertThat(result.output().path("taskUrl").asText()).isEqualTo("/paper?taskId=42");
        assertThat(result.output().path("projectFilesModified").asBoolean()).isFalse();
        assertThat(result.output().has("objectKey")).isFalse();
        verify(service).start(eq(7L), eq(9L), any());
    }

    @Test void workspaceFailuresRemainActionableAndUnexpectedExceptionsAreSanitized() {
        ToolExecutionContext.setCurrentUserId(7L);
        when(service.start(eq(7L), isNull(), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "上传文档不存在或不可访问。"));
        assertThat(executor.execute(call()).errorMessage()).contains("不可访问");
        when(service.start(eq(7L), isNull(), any())).thenThrow(new IllegalStateException("secret-host-path"));
        assertThat(executor.execute(call()).errorMessage()).doesNotContain("secret-host-path").contains("相同请求");
    }

    private ToolCall call() { return new ToolCall("call", "paper_polish_start", json.createObjectNode().put("targetLanguage", "en").put("latexText", "source")); }
}
