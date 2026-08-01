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
    void registersWorkspaceChatRouteWithoutRestoringProjectV1Route() {
        ChatWebSocketHandler chat = mock(ChatWebSocketHandler.class);
        WebSocketAuthHandshakeInterceptor authentication = mock(WebSocketAuthHandshakeInterceptor.class);
        WebSocketConfig config = new WebSocketConfig(chat, authentication);
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration chatRegistration = mock(
                WebSocketHandlerRegistration.class, Answers.RETURNS_SELF);
        when(registry.addHandler(chat, "/api/v1/ws/chat")).thenReturn(chatRegistration);

        config.registerWebSocketHandlers(registry);

        verify(chatRegistration).addInterceptors(authentication);
        verify(chatRegistration).setAllowedOrigins("*");
    }
}
