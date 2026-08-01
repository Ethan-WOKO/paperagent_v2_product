package com.yanban.api.ws;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

class WebSocketConfigTest {

    @Test
    void registersProjectRouteWithAuthenticationThenOwnershipBinding() {
        ChatWebSocketHandler chat = mock(ChatWebSocketHandler.class);
        ProjectChatWebSocketHandler projectChat = mock(ProjectChatWebSocketHandler.class);
        WebSocketAuthHandshakeInterceptor authentication = mock(WebSocketAuthHandshakeInterceptor.class);
        ProjectWebSocketHandshakeInterceptor projectBinding = mock(ProjectWebSocketHandshakeInterceptor.class);
        WebSocketConfig config = new WebSocketConfig(chat, projectChat, authentication, projectBinding);
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration chatRegistration = mock(
                WebSocketHandlerRegistration.class, Answers.RETURNS_SELF);
        WebSocketHandlerRegistration projectRegistration = mock(
                WebSocketHandlerRegistration.class, Answers.RETURNS_SELF);
        when(registry.addHandler(chat, "/api/v1/ws/chat")).thenReturn(chatRegistration);
        when(registry.addHandler(projectChat, "/api/v1/ws/projects/{projectId}/chat"))
                .thenReturn(projectRegistration);

        config.registerWebSocketHandlers(registry);

        verify(projectRegistration).addInterceptors(authentication, projectBinding);
        verify(projectRegistration).setAllowedOrigins("*");
    }
}
