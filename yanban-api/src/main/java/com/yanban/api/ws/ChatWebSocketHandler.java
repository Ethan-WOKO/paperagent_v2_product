package com.yanban.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentService;
import com.yanban.api.agent.SendMessageRequest;
import com.yanban.api.agent.SendMessageResponse;
import com.yanban.api.security.JwtUser;
import com.yanban.api.user.SysUserRepository;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String DEFAULT_WS_ERROR = "对话处理失败";

    private final ObjectMapper objectMapper;
    private final AgentService agentService;
    private final SysUserRepository users;

    @Autowired
    public ChatWebSocketHandler(ObjectMapper objectMapper,
                                AgentService agentService,
                                SysUserRepository users) {
        this.objectMapper = objectMapper;
        this.agentService = agentService;
        this.users = users;
    }

    /** Compatibility constructor for focused websocket tests. */
    public ChatWebSocketHandler(ObjectMapper objectMapper, AgentService agentService) {
        this(objectMapper, agentService, null);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        JwtUser currentUser = (JwtUser) session.getAttributes().get(WebSocketAuthHandshakeInterceptor.ATTR_JWT_USER);
        log.debug("WebSocket connected wsSessionId={} userId={} username={} remote={}",
                session.getId(),
                currentUser == null ? null : currentUser.id(),
                currentUser == null ? null : currentUser.username(),
                session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JwtUser currentUser = (JwtUser) session.getAttributes().get(WebSocketAuthHandshakeInterceptor.ATTR_JWT_USER);
        if (currentUser == null) {
            log.warn("WebSocket message rejected: missing authenticated user wsSessionId={} remote={}",
                    session.getId(),
                    session.getRemoteAddress());
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Unauthorized"));
            return;
        }
        if (!isCurrentLogin(currentUser)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Session replaced"));
            return;
        }

        WsChatRequest request = objectMapper.readValue(message.getPayload(), WsChatRequest.class);
        log.debug("WebSocket inbound message wsSessionId={} userId={} chatSessionId={} contentLength={} ragDisabled={} skillId={} clientRequestId={}",
                session.getId(),
                currentUser.id(),
                request.sessionId(),
                request.content() == null ? 0 : request.content().length(),
                request.ragDisabled(),
                request.skillId(),
                request.clientRequestId());
        if (request.sessionId() == null || !StringUtils.hasText(request.content())) {
            sendSafe(session, WsChatEvent.error(request.sessionId(), "sessionId and content are required", request.clientRequestId()));
            return;
        }

        try {
            sendSafe(session, WsChatEvent.ack(request.sessionId(), request.clientRequestId()));
            SendMessageResponse response = agentService.sendMessageStreaming(
                    currentUser.id(),
                    request.sessionId(),
                    new SendMessageRequest(request.content(), request.ragDisabled(), request.skillId(), request.clientRequestId(), null),
                    token -> {
                        if (token != null && !token.isEmpty()) {
                            sendSafe(session, WsChatEvent.chunk(request.sessionId(), token, request.clientRequestId()));
                        }
                    },
                    process -> {
                        if (process != null && !process.isBlank()) {
                            sendSafe(session, WsChatEvent.process(request.sessionId(), process, request.clientRequestId()));
                        }
                    }
            );

            if (!response.success()) {
                if (response.debug() != null) {
                    sendSafe(session, WsChatEvent.debug(request.sessionId(), response.debug(), request.clientRequestId()));
                }
                log.warn("WebSocket agent response failed wsSessionId={} userId={} chatSessionId={} clientRequestId={} error={}",
                        session.getId(),
                        currentUser.id(),
                        request.sessionId(),
                        request.clientRequestId(),
                        response.errorMessage());
                sendSafe(session, WsChatEvent.error(
                        request.sessionId(),
                        StringUtils.hasText(response.errorMessage()) ? response.errorMessage() : DEFAULT_WS_ERROR,
                        request.clientRequestId()
                ));
                return;
            }

            if (response.debug() != null) {
                sendSafe(session, WsChatEvent.debug(request.sessionId(), response.debug(), request.clientRequestId()));
            }
            log.debug("WebSocket agent response completed wsSessionId={} userId={} chatSessionId={} clientRequestId={} steps={} navigationUrl={} messageCount={}",
                    session.getId(),
                    currentUser.id(),
                    request.sessionId(),
                    request.clientRequestId(),
                    response.steps(),
                    response.navigationUrl(),
                    response.messages() == null ? 0 : response.messages().size());
            if (StringUtils.hasText(response.navigationUrl())) {
                sendSafe(session, WsChatEvent.doneWithNavigation(
                        request.sessionId(),
                        "intent_redirect",
                        response.navigationUrl(),
                        request.clientRequestId()
                ));
                return;
            }

            sendSafe(session, WsChatEvent.done(
                    request.sessionId(),
                    StringUtils.hasText(request.skillId()) ? "skill_langchain4j" : "langchain4j",
                    request.clientRequestId()
            ));
        } catch (Exception ex) {
            log.warn("WebSocket agent execution threw exception wsSessionId={} userId={} chatSessionId={} clientRequestId={} error={}",
                    session.getId(),
                    currentUser.id(),
                    request.sessionId(),
                    request.clientRequestId(),
                    extractErrorMessage(ex),
                    ex);
            sendSafe(session, WsChatEvent.error(request.sessionId(), extractErrorMessage(ex), request.clientRequestId()));
        }
    }

    private boolean isCurrentLogin(JwtUser user) {
        return users == null || users.findById(user.id())
                .map(value -> value.getLoginVersion() == user.loginVersion())
                .orElse(false);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        JwtUser currentUser = (JwtUser) session.getAttributes().get(WebSocketAuthHandshakeInterceptor.ATTR_JWT_USER);
        log.warn("WebSocket transport error wsSessionId={} userId={} remote={} error={}",
                session.getId(),
                currentUser == null ? null : currentUser.id(),
                session.getRemoteAddress(),
                exception == null ? null : exception.getMessage(),
                exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        JwtUser currentUser = (JwtUser) session.getAttributes().get(WebSocketAuthHandshakeInterceptor.ATTR_JWT_USER);
        log.debug("WebSocket closed wsSessionId={} userId={} remote={} code={} reason={}",
                session.getId(),
                currentUser == null ? null : currentUser.id(),
                session.getRemoteAddress(),
                status == null ? null : status.getCode(),
                status == null ? null : status.getReason());
    }

    private String extractErrorMessage(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return DEFAULT_WS_ERROR;
    }

    private void send(WebSocketSession session, WsChatEvent event) throws IOException {
        if (session == null || !session.isOpen()) {
            return;
        }
        String payload = objectMapper.writeValueAsString(event);
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        }
    }

    private void sendSafe(WebSocketSession session, WsChatEvent event) {
        try {
            send(session, event);
        } catch (IOException | RuntimeException ex) {
            log.warn("Ignoring WebSocket send failure sessionId={} eventType={} error={}",
                    session == null ? null : session.getId(),
                    event == null ? null : event.type(),
                    ex.getMessage(),
                    ex);
        }
    }
}
