package com.yanban.api.agent.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolErrorCode;
import com.yanban.core.tool.ToolExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PastConversationToolExecutorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PastConversationHistoryService history = mock(PastConversationHistoryService.class);
    private final SearchPastConversationsToolExecutor search = new SearchPastConversationsToolExecutor(json, history);
    private final GetPastConversationToolExecutor detail = new GetPastConversationToolExecutor(json, history);

    @AfterEach void clear() { ToolExecutionContext.clear(); }

    @Test
    void descriptorsMatchBothProductProfilesAndDoNotRequireProjectAuthority() {
        for (var tool : new AbstractPastConversationToolExecutor[] {search, detail}) {
            ToolDescriptor descriptor = tool.descriptor();
            assertThat(descriptor.supportedProfiles()).containsExactly(
                    ToolDescriptor.CapabilityProfile.CHAT, ToolDescriptor.CapabilityProfile.PROJECT);
            assertThat(descriptor.requiredPermissions()).containsExactly("history:read");
            assertThat(descriptor.resourceScopes()).containsExactly(ToolDescriptor.ResourceScope.SESSION);
            assertThat(descriptor.sideEffectType()).isEqualTo(ToolDescriptor.SideEffectType.NONE);
            assertThat(descriptor.confirmationPolicy()).isEqualTo(ToolDescriptor.ConfirmationPolicy.NEVER);
            assertThat(descriptor.asyncMode()).isEqualTo(ToolDescriptor.AsyncMode.SYNC);
            assertThat(descriptor.modelVisible()).isTrue();
            assertThat(tool.definition().parameters().path("additionalProperties").asBoolean()).isFalse();
        }
    }

    @Test
    void missingAuthenticationFailsBeforeRepositoryOrService() {
        assertThat(search.execute(call(search, json.createObjectNode())).errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(detail.execute(call(detail, json.createObjectNode().put("sessionRef", "session.11"))).errorCode())
                .isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        verifyNoInteractions(history);
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId", "owner", "sql", "projectId", "_serverCurrentSessionId", "_serverCurrentTaskId"})
    void rejectsAllUnpublishedIdentityAndSqlArguments(String name) {
        ToolExecutionContext.setCurrentUserId(7L);
        ObjectNode args = json.createObjectNode().put(name, "secret");
        assertThat(search.execute(call(search, args)).errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
        assertThat(detail.execute(call(detail, args.put("sessionRef", "session.11"))).errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(history);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "11", "-1", "1.5", "4294967297", "9223372036854775808", "\"5\"", "null"})
    void rejectsInvalidOrOverflowingLimits(String value) throws Exception {
        ToolExecutionContext.setCurrentUserId(7L);
        ObjectNode args = json.createObjectNode();
        args.set("limit", json.readTree(value));
        assertThat(search.execute(call(search, args)).errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(history);
    }

    @Test
    void routesSearchFromChatAndProjectUsingOnlyTrustedOwner() {
        ToolExecutionContext.setCurrentUserId(7L);
        ObjectNode output = json.createObjectNode().put("resultCount", 0);
        when(history.search(7L, "query", null, null, 5)).thenReturn(output);
        assertThat(search.execute(call(search, json.createObjectNode().put("query", "query"))).success()).isTrue();
        ToolExecutionContext.setCurrentProjectId(999L);
        var result = search.execute(call(search, json.createObjectNode().put("query", "query")));
        assertThat(result.output()).isSameAs(output);
        assertThat(result.version()).isEqualTo(PastConversationToolContract.VERSION);
    }

    @Test
    void routesDetailAndSanitizesNotFoundAndDatabaseFailure() {
        ToolExecutionContext.setCurrentUserId(7L);
        ObjectNode args = json.createObjectNode().put("sessionRef", "session.11");
        doThrow(new PastConversationHistoryService.UnavailableException()).when(history).detail(7L, "session.11", null, 5);
        assertThat(detail.execute(call(detail, args)).errorCode()).isEqualTo(ToolErrorCode.NOT_FOUND);
        doThrow(new IllegalStateException("DB password=private sql data")).when(history).detail(7L, "session.11", null, 5);
        var result = detail.execute(call(detail, args));
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INTERNAL_ERROR);
        assertThat(result.errorMessage()).doesNotContain("private", "password", "sql data");
        assertThat(result.output()).isNull();
    }

    @Test
    void detailPassesPublishedContinuationAndNoIdentityArguments() {
        ToolExecutionContext.setCurrentUserId(7L);
        when(history.detail(7L, "session.11", "opaque", 2)).thenReturn(json.createObjectNode());
        ObjectNode args = json.createObjectNode().put("sessionRef", "session.11").put("cursor", "opaque").put("limit", 2);
        assertThat(detail.execute(call(detail, args)).success()).isTrue();
        verify(history).detail(7L, "session.11", "opaque", 2);
    }

    private ToolCall call(AbstractPastConversationToolExecutor tool, ObjectNode args) {
        return new ToolCall("call.history", tool.definition().name(), args);
    }
}
