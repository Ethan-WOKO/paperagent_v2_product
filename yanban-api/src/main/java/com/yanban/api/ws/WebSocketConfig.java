package com.yanban.api.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final ProjectChatWebSocketHandler projectChatWebSocketHandler;
    private final WebSocketAuthHandshakeInterceptor authHandshakeInterceptor;
    private final ProjectWebSocketHandshakeInterceptor projectHandshakeInterceptor;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler,
                           ProjectChatWebSocketHandler projectChatWebSocketHandler,
                           WebSocketAuthHandshakeInterceptor authHandshakeInterceptor,
                           ProjectWebSocketHandshakeInterceptor projectHandshakeInterceptor) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.projectChatWebSocketHandler = projectChatWebSocketHandler;
        this.authHandshakeInterceptor = authHandshakeInterceptor;
        this.projectHandshakeInterceptor = projectHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/api/v1/ws/chat")
                .addInterceptors(authHandshakeInterceptor)
                .setAllowedOrigins("*");
        registry.addHandler(projectChatWebSocketHandler, "/api/v1/ws/projects/{projectId}/chat")
                .addInterceptors(authHandshakeInterceptor, projectHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
